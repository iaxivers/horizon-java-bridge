package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Mods' entities (a bear, a butterfly): Horizon's world has no such types, so they live here,
 * in the mirrored levels, as vanilla's levels keep theirs: added by {@code addFreshEntity} (a
 * spawn egg, {@code /summon}, a mod's own code), ticked with the server (ServerLevel.tick's
 * entity loop: {@code tickNonPassenger}), found by mods through the level's entity getter, and
 * saved beside the world ({@code horizon-java/entities/}). One whose chunk Horizon unloaded is
 * put away and comes back with it. Each tick their looks are captured by their own renderers
 * ({@link HorizonEntityRender}) for Horizon to draw.
 */
public final class HorizonEntities {
    private HorizonEntities() {}

    /** Every 10 s, the entities are saved (when one changed level or came or went). */
    private static final int SAVE_TICKS = 200;

    /** Per level: its mods' entities, by UUID, in the order they came (vanilla's tick order). */
    private static final Map<ServerLevel, Map<UUID, Entity>> LIVE = new IdentityHashMap<>();
    /**
     * Each level's entities as a list, made again after one comes or goes (getEntities runs
     * this many times a tick: every mob's pushes, collisions, searches).
     */
    private static final Map<ServerLevel, List<Entity>> VIEWS = new IdentityHashMap<>();
    /** Per dimension: entities put away (their chunks unloaded), as saved. */
    private static final Map<String, List<CompoundTag>> SAVED = new HashMap<>();
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static Path dir;
    private static int ticks;

    /** A mod's entity type (Horizon simulates vanilla's itself). */
    public static boolean modded(EntityType<?> type) {
        return !BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace().equals("minecraft");
    }

    /** Every mod entity type's id, for Horizon's commands (/summon). */
    public static String[] types() {
        List<String> out = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (modded(type)) out.add(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        }
        return out.toArray(String[]::new);
    }

    /** The world changed (a new folder): what was held belongs to the last one. */
    static synchronized void world(Path worldDir) {
        Path now = worldDir == null ? null : worldDir.resolve("horizon-java").resolve("entities");
        if (now == null || now.equals(dir)) return;
        if (dir != null) save();
        for (Map<UUID, Entity> live : LIVE.values()) {
            for (Entity e : live.values()) e.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        }
        LIVE.clear();
        VIEWS.clear();
        GRIDS.clear();
        SAVED.clear();
        dir = now;
        // The new world's saved entities wait for their chunks (restore, each second).
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static boolean loaded(ServerLevel level, double x, double y, double z) {
        return HorizonServer.loaded(level, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /**
     * ServerLevel.addFreshEntity of a mod's entity on a mirrored level: it is the level's now
     * (PersistentEntitySectionManager.addNewEntity): its callback set, Fabric's load event.
     * False when one with its UUID is already there.
     */
    public static synchronized boolean add(ServerLevel level, Entity entity) {
        Map<UUID, Entity> live = LIVE.computeIfAbsent(level, l -> new LinkedHashMap<>());
        for (Map<UUID, Entity> other : LIVE.values()) {
            if (other.containsKey(entity.getUUID())) return false;
        }
        // Vanilla's entities count their ids from 1 (Entity.ENTITY_COUNTER), as Horizon's do:
        // a mirrored level's are the bridge's own, apart from Horizon's.
        if (entity.getId() < 0x7fff0000) entity.setId(HorizonServer.nextEntityId());
        live.put(entity.getUUID(), entity);
        VIEWS.remove(level);
        entity.setLevelCallback(new Callback(level, entity));
        fire(entity, "ServerEntityEvents.ENTITY_LOAD", () ->
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(entity, level));
        return true;
    }

    /** The level's mods' entities, for its entity getter (mods' searches, getEntity). */
    public static synchronized List<Entity> of(ServerLevel level) {
        Map<UUID, Entity> live = LIVE.get(level);
        if (live == null) return List.of();
        return VIEWS.computeIfAbsent(level, l -> List.copyOf(live.values()));
    }

    /** Every mirrored level's mods' entities. */
    static synchronized Map<ServerLevel, List<Entity>> all() {
        Map<ServerLevel, List<Entity>> out = new IdentityHashMap<>();
        for (var e : LIVE.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        return out;
    }

    /**
     * A level's entities by the chunk column they stand in, for box searches (near), and the
     * widest half of their boxes.
     */
    private record Grid(long tick, List<Entity> of, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<Entity>> cells, double reach) {}

    private static final Map<ServerLevel, Grid> GRIDS = new IdentityHashMap<>();
    /** How far an entity may move in a tick and still be found from the column it was put in. */
    private static final double GRID_MARGIN = 8.0;

    /**
     * The entities whose boxes may meet {@code box}: those put (once a tick) in the chunk
     * columns the box covers, grown by how far an entity's box reaches from where it stands
     * and how far it may have moved since. Vanilla searches its entity sections the same way;
     * a scan of every entity for each of the many searches a tick (pushes, collisions, goals)
     * was a fifth of the mobs' time.
     */
    static synchronized void near(ServerLevel level, net.minecraft.world.phys.AABB box, java.util.function.Consumer<Entity> out) {
        List<Entity> of = of(level);
        if (of.size() < 16) {
            for (Entity e : of) out.accept(e);
            return;
        }
        long tick = HorizonServer.tickCount();
        Grid grid = GRIDS.get(level);
        if (grid == null || grid.tick() != tick || grid.of() != of) {
            var cells = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<Entity>>();
            double reach = 0.0;
            for (Entity e : of) {
                var b = e.getBoundingBox();
                reach = Math.max(reach, Math.max(b.getXsize(), b.getZsize()) / 2.0);
                cells.computeIfAbsent(net.minecraft.world.level.ChunkPos.pack(e.getBlockX() >> 4, e.getBlockZ() >> 4),
                        k -> new ArrayList<>()).add(e);
            }
            grid = new Grid(tick, of, cells, reach + GRID_MARGIN);
            GRIDS.put(level, grid);
        }
        double m = grid.reach();
        int x0 = (int) Math.floor(box.minX - m) >> 4, x1 = (int) Math.floor(box.maxX + m) >> 4;
        int z0 = (int) Math.floor(box.minZ - m) >> 4, z1 = (int) Math.floor(box.maxZ + m) >> 4;
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > grid.cells().size()) {
            for (List<Entity> cell : grid.cells().values()) for (Entity e : cell) out.accept(e);
            return;
        }
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                List<Entity> cell = grid.cells().get(net.minecraft.world.level.ChunkPos.pack(cx, cz));
                if (cell != null) for (Entity e : cell) out.accept(e);
            }
        }
    }

    /** One of the levels' mods' entities by its id (Horizon's players hit it by id). */
    static synchronized Entity byId(int id) {
        for (Map<UUID, Entity> live : LIVE.values()) {
            for (Entity e : live.values()) if (e.getId() == id) return e;
        }
        return null;
    }

    /**
     * An entity's EntityInLevelCallback, as the section manager's: removed (killed, discarded,
     * changed level), it leaves the level.
     */
    private record Callback(ServerLevel level, Entity entity) implements EntityInLevelCallback {
        @Override
        public void onMove() {}

        @Override
        public void onRemove(Entity.RemovalReason reason) {
            RESTING.remove(entity);
            synchronized (HorizonEntities.class) {
                Map<UUID, Entity> live = LIVE.get(level);
                if (live != null && live.get(entity.getUUID()) == entity) {
                    live.remove(entity.getUUID());
                    VIEWS.remove(level);
                }
            }
            fire(entity, "ServerEntityEvents.ENTITY_UNLOAD", () ->
                    net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.invoker().onUnload(entity, level));
            entity.setLevelCallback(EntityInLevelCallback.NULL);
        }
    }

    /**
     * Each server tick, ServerLevel.tick's entities: one whose chunk is gone is put away; the
     * rest check their despawning, then tick (tickNonPassenger: old position kept, the count
     * up, {@code tick}; riders through their vehicle).
     */
    public static void tick() {
        Map<ServerLevel, List<Entity>> all = all();
        for (var e : all.entrySet()) {
            ServerLevel level = e.getKey();
            for (Entity entity : e.getValue()) {
                if (entity.isRemoved()) continue;
                if (!loaded(level, entity.getX(), entity.getY(), entity.getZ())) {
                    putAway(level, entity);
                    continue;
                }
                if (entity.isPassenger()) continue;
                fire(entity, "Entity.checkDespawn", entity::checkDespawn);
                if (entity.isRemoved()) continue;
                if (!active(level, entity) && (ticks + entity.getId()) % FAR_EVERY != 0) {
                    // Far from every player and idle: it ticks one tick in FAR_EVERY, and isn't
                    // drawn again until it does (HorizonEntityRender keeps its last look).
                    RESTING.add(entity);
                    continue;
                }
                RESTING.remove(entity);
                long started = HorizonProfile.start();
                fire(entity, "Entity.tick", () -> tickNonPassenger(entity));
                if (HorizonProfile.ON) HorizonProfile.entity(
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), System.nanoTime() - started);
            }
        }
        if (++ticks % SAVE_TICKS == 0) save();
        if (ticks % 20 == 0) restore();
    }

    /**
     * Mods' entities farther than this from every player tick one tick in FAR_EVERY while idle
     * (an entity activation range, as servers such as Paper's run theirs): a big pack's mobs
     * spread around the players and most of them stand far away doing nothing.
     * HORIZON_JAVA_ENTITY_RANGE sets it (0: every entity ticks every tick).
     */
    static final int ACTIVE_RANGE = Integer.getInteger("horizon.entityRange",
            parseInt(System.getenv("HORIZON_JAVA_ENTITY_RANGE"), 32));
    static final int FAR_EVERY = 4;
    /** The entities that didn't tick this tick (far and idle): their last look stands. */
    private static final java.util.Set<Entity> RESTING = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private static int parseInt(String text, int otherwise) {
        try {
            return text == null ? otherwise : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    /** Whether the entity didn't tick this tick (HorizonEntityRender keeps its last look). */
    static boolean resting(Entity entity) {
        return RESTING.contains(entity);
    }

    /**
     * Whether an entity ticks every tick: a player within ACTIVE_RANGE, or busy (hurt, dying,
     * with a target, carrying or carried, moving faster than walking, in a fluid).
     */
    private static boolean active(ServerLevel level, Entity entity) {
        if (ACTIVE_RANGE <= 0) return true;
        double range = (double) ACTIVE_RANGE * ACTIVE_RANGE;
        for (var player : level.players()) {
            if (player.distanceToSqr(entity) < range) return true;
        }
        if (entity.isVehicle() || entity.isPassenger() || entity.isInWater() || entity.isInLava()) return true;
        if (entity.getDeltaMovement().horizontalDistanceSqr() > 0.04 || entity.getDeltaMovement().y > 0.1) return true;
        if (entity instanceof net.minecraft.world.entity.LivingEntity living
                && (living.hurtTime > 0 || living.isDeadOrDying())) return true;
        return entity instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() != null;
    }

    private static void tickNonPassenger(Entity entity) {
        entity.setOldPosAndRot();
        entity.tickCount++;
        entity.tick();
        walkAnimation(entity);
        HorizonClientShadows.tick(entity);
        for (Entity passenger : entity.getPassengers()) tickPassenger(entity, passenger);
    }

    /**
     * Steps the walk animation (limb swing) as the client does. LivingEntity.aiStep calls
     * calculateEntityAnimation only on a client level; mods' entities live on the mirror's server level
     * and are drawn from there, so without this their walk animation never moved and every walking
     * mod mob slid in its standing pose (UMP anim test: 47 of 66 mod mobs). Players are Horizon's.
     */
    private static void walkAnimation(Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living) || entity instanceof net.minecraft.world.entity.player.Player) return;
        if (living.isRemoved() || living.isPassenger()) return;
        try {
            living.calculateEntityAnimation(false);
        } catch (RuntimeException | Error e) {
            // a mod's own animation code; its pose stays as it was
        }
    }

    private static void tickPassenger(Entity vehicle, Entity passenger) {
        if (passenger.isRemoved() || passenger.getVehicle() != vehicle) {
            passenger.stopRiding();
            return;
        }
        // A Horizon player riding a mod's mob: Horizon ticks the player, not the mirror
        // (ServerPlayer.rideTick would run its whole tick). Player.rideTick's part: the sneak
        // key gets it off (wantsToStopRiding; stopRiding's dismountTo moves Horizon's player),
        // else it sits where the mob's positionRider puts it.
        if (passenger instanceof net.minecraft.server.level.ServerPlayer player && HorizonServer.isMirror(player)) {
            if (player.isShiftKeyDown()) {
                fire(vehicle, "Player.stopRiding", player::stopRiding);
                player.setShiftKeyDown(false);
            } else {
                player.setOldPosAndRot();
                vehicle.positionRider(player);
                player.resetFallDistance();
            }
            return;
        }
        passenger.setOldPosAndRot();
        passenger.tickCount++;
        passenger.rideTick();
        for (Entity rider : passenger.getPassengers()) tickPassenger(passenger, rider);
    }

    /** Per mirrored player riding a jumping mob: LocalPlayer's jumpRidingTicks. */
    private static final Map<UUID, int[]> RIDING_JUMPS = new HashMap<>();

    /**
     * A horse-style jump (PlayerRideableJumping), which vanilla charges on the rider's client
     * (LocalPlayer.aiStep): the jump key held charges it, its release jumps (onPlayerJump, as
     * that client does, then handleStartJump, as START_RIDING_JUMP does on the server), and
     * ten ticks of cooldown follow. Called each tick with the jump key.
     */
    static void ridingJump(net.minecraft.server.level.ServerPlayer player, boolean jump) {
        if (!(player.getVehicle() instanceof net.minecraft.world.entity.PlayerRideableJumping jumping)
                || !modded(player.getVehicle().getType())) {
            RIDING_JUMPS.remove(player.getUUID());
            return;
        }
        // {ticks, was jumping, scale * 1000}
        int[] st = RIDING_JUMPS.computeIfAbsent(player.getUUID(), k -> new int[3]);
        if (jumping.getJumpCooldown() != 0) {
            st[1] = jump ? 1 : 0;
            return;
        }
        // The jump key charges it rather than jumping (as vanilla's horse ignores `jumping`).
        player.setJumping(false);
        if (st[0] < 0 && ++st[0] == 0) st[2] = 0;
        boolean was = st[1] != 0;
        if (was && !jump) {
            st[0] = -10;
            int power = net.minecraft.util.Mth.floor(st[2] / 1000.0F * 100.0F);
            if (jumping.canJump() && power > 0) {
                Entity vehicle = player.getVehicle();
                fire(vehicle, "PlayerRideableJumping.onPlayerJump", () -> jumping.onPlayerJump(power));
                fire(vehicle, "PlayerRideableJumping.handleStartJump", () -> jumping.handleStartJump(power));
            }
        } else if (!was && jump) {
            st[0] = 0;
            st[2] = 0;
        } else if (was) {
            st[0]++;
            float scale = st[0] < 10 ? st[0] * 0.1F : 0.8F + 2.0F / (st[0] - 9) * 0.1F;
            st[2] = Math.round(scale * 1000.0F);
        }
        st[1] = jump ? 1 : 0;
    }

    /** An entity whose chunk Horizon unloaded: saved, and out of the level until it loads. */
    private static void putAway(ServerLevel level, Entity entity) {
        CompoundTag tag = saveOne(level, entity);
        if (tag != null) {
            synchronized (HorizonEntities.class) {
                saved(dimension(level)).add(tag);
            }
        }
        entity.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
    }

    /** Saved entities whose chunks Horizon has now are made again (a chunk's entities load). */
    private static void restore() {
        for (ServerLevel level : HorizonServer.levels()) {
            List<CompoundTag> saved;
            synchronized (HorizonEntities.class) {
                saved = saved(dimension(level));
            }
            for (CompoundTag tag : new ArrayList<>(saved)) {
                var pos = tag.getListOrEmpty("Pos");
                if (pos.size() < 3) {
                    saved.remove(tag);
                    continue;
                }
                if (!loaded(level, pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0))) continue;
                saved.remove(tag);
                try {
                    Entity entity = EntityType.loadEntityRecursive(
                            TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
                            level, EntitySpawnReason.LOAD, e -> e);
                    if (entity != null && !add(level, entity)) entity.setRemoved(Entity.RemovalReason.DISCARDED);
                } catch (Throwable t) {
                    Natives.log("Java mods: an entity could not be loaded: " + t);
                }
            }
        }
    }

    private static List<CompoundTag> saved(String dimension) {
        return SAVED.computeIfAbsent(dimension, HorizonEntities::read);
    }

    private static Path file(String dimension) {
        return dir == null ? null : dir.resolve(dimension.replace(':', '_').replace('/', '_') + ".nbt");
    }

    private static List<CompoundTag> read(String dimension) {
        List<CompoundTag> out = new ArrayList<>();
        Path file = file(dimension);
        if (file == null || !Files.exists(file)) return out;
        try {
            ListTag list = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getListOrEmpty("entities");
            for (int i = 0; i < list.size(); i++) out.add(list.getCompoundOrEmpty(i));
        } catch (Throwable t) {
            Natives.log("Java mods: " + file + " could not be read: " + t);
        }
        return out;
    }

    /** An entity as a chunk saves it (Entity.save: its id, then its data); null if it won't. */
    private static CompoundTag saveOne(ServerLevel level, Entity entity) {
        try {
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            return entity.save(out) ? out.buildResult() : null;
        } catch (Throwable t) {
            Natives.log("Java mods: " + entity + " could not be saved: " + t);
            return null;
        }
    }

    /** Writes every dimension's entities: the live ones and those put away. */
    public static synchronized void save() {
        if (dir == null) return;
        Map<String, ListTag> lists = new HashMap<>();
        for (var e : SAVED.entrySet()) {
            ListTag list = lists.computeIfAbsent(e.getKey(), d -> new ListTag());
            for (CompoundTag tag : e.getValue()) list.add(tag);
        }
        for (var e : LIVE.entrySet()) {
            ListTag list = lists.computeIfAbsent(dimension(e.getKey()), d -> new ListTag());
            for (Entity entity : e.getValue().values()) {
                // Riders are saved with their vehicle (Entity.save writes its passengers).
                if (entity.isPassenger() || entity.isRemoved()) continue;
                CompoundTag tag = saveOne(e.getKey(), entity);
                if (tag != null) list.add(tag);
            }
        }
        try {
            Files.createDirectories(dir);
            for (var e : lists.entrySet()) {
                CompoundTag root = new CompoundTag();
                root.put("entities", (Tag) e.getValue());
                NbtIo.writeCompressed(root, file(e.getKey()));
            }
        } catch (Throwable t) {
            Natives.log("Java mods: entities could not be saved in " + dir + ": " + t);
        }
    }

    // ---- Players and mods' entities ---------------------------------------------------------

    /**
     * A player hit a mod's entity (ServerGamePacketListenerImpl.handleInteract's attack):
     * Player.attack, as vanilla's server runs it. False when there is no such entity near.
     */
    public static boolean attack(String uuid, int id) {
        Thread.currentThread().setContextClassLoader(HorizonEntities.class.getClassLoader());
        var player = HorizonServer.player(UUID.fromString(uuid));
        Entity target = byId(id);
        if (player == null || target == null || target.level() != player.level()) return false;
        // The mirror's player doesn't tick its attack strength: Horizon's player, whose
        // attack this is, swung with its own; the hit counts as charged.
        try {
            java.lang.reflect.Field ticker = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("attackStrengthTicker");
            ticker.setAccessible(true);
            ticker.setInt(player, 1000);
        } catch (ReflectiveOperationException e) {
            // Uncharged, then.
        }
        equipment(player);
        // ServerGamePacketListenerImpl's attack, as Fabric hooks it: AttackEntityCallback first
        // (a mod may take the hit), then Player.attack.
        boolean[] taken = {false};
        fire(target, "AttackEntityCallback", () -> {
            var r = net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker().interact(player, player.level(),
                    net.minecraft.world.InteractionHand.MAIN_HAND, target, null);
            taken[0] = r != net.minecraft.world.InteractionResult.PASS;
        });
        if (!taken[0]) fire(target, "Player.attack", () -> player.attack(target));
        return true;
    }

    /**
     * A player used a mod's entity (handleInteract's interact: Player.interactOn, the entity's
     * interact then the item's): the result as useBlock's (0 nothing, 1 taken, 2 with a swing).
     */
    public static int interact(String uuid, int id, int hand, double x, double y, double z, int selected) {
        Thread.currentThread().setContextClassLoader(HorizonEntities.class.getClassLoader());
        var player = HorizonServer.player(UUID.fromString(uuid));
        Entity target = byId(id);
        if (player == null || target == null || target.level() != player.level()) return 0;
        if (selected >= 0 && selected < 9) player.getInventory().setSelectedSlot(selected);
        equipment(player);
        var interaction = hand == 1 ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        net.minecraft.world.InteractionResult[] result = {net.minecraft.world.InteractionResult.PASS};
        var at = new net.minecraft.world.phys.Vec3(x - target.getX(), y - target.getY(), z - target.getZ());
        // ServerGamePacketListenerImpl's interaction, as Fabric hooks it: UseEntityCallback first
        // (Carry On picks a mob up there), then Player.interactOn.
        fire(target, "UseEntityCallback", () -> result[0] = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
                .interact(player, player.level(), interaction, target,
                        new net.minecraft.world.phys.EntityHitResult(target, new net.minecraft.world.phys.Vec3(x, y, z))));
        if (result[0] == net.minecraft.world.InteractionResult.PASS) {
            fire(target, "Player.interactOn", () -> result[0] = player.interactOn(target, interaction, at));
        }
        HorizonServer.afterCall();
        if (!result[0].consumesAction()) return 0;
        return result[0] instanceof net.minecraft.world.InteractionResult.Success success && success.shouldSwing() ? 2 : 1;
    }

    /**
     * Level.broadcastEntityEvent of a mirrored level: the particles vanilla's client makes for
     * a mod entity's event (Horizon's game doesn't run the entity's handleEntityEvent), sent
     * to the players within 64 blocks as level particles in its box. Only events of the
     * classes that define them (a mod may use the same byte for its own event).
     */
    public static void entityEvent(ServerLevel level, Entity entity, byte event) {
        if (!modded(entity.getType())) return;
        float w = entity.getBbWidth(), h = entity.getBbHeight();
        net.minecraft.core.particles.ParticleOptions type = null;
        int count = 7;
        double y = entity.getY() + h / 2.0 + 0.5;
        float speed = 0.02F;
        switch (event) {
            // LivingEntity.makePoofParticles; Mob.spawnAnim.
            case net.minecraft.world.entity.EntityEvent.POOF, net.minecraft.world.entity.EntityEvent.SILVERFISH_MERGE_ANIM -> {
                if (!(entity instanceof net.minecraft.world.entity.LivingEntity)) return;
                type = net.minecraft.core.particles.ParticleTypes.POOF;
                count = 20;
                y = entity.getY() + h / 2.0;
            }
            // TamableAnimal.spawnTamingParticles.
            case net.minecraft.world.entity.EntityEvent.TAMING_SUCCEEDED, net.minecraft.world.entity.EntityEvent.TAMING_FAILED -> {
                if (!(entity instanceof net.minecraft.world.entity.TamableAnimal)) return;
                type = event == net.minecraft.world.entity.EntityEvent.TAMING_SUCCEEDED
                        ? net.minecraft.core.particles.ParticleTypes.HEART : net.minecraft.core.particles.ParticleTypes.SMOKE;
            }
            // Animal.handleEntityEvent: bred.
            case net.minecraft.world.entity.EntityEvent.IN_LOVE_HEARTS -> {
                if (!(entity instanceof net.minecraft.world.entity.animal.Animal)) return;
                type = net.minecraft.core.particles.ParticleTypes.HEART;
            }
            // Villager.handleEntityEvent.
            case net.minecraft.world.entity.EntityEvent.LOVE_HEARTS, net.minecraft.world.entity.EntityEvent.VILLAGER_ANGRY,
                 net.minecraft.world.entity.EntityEvent.VILLAGER_HAPPY, net.minecraft.world.entity.EntityEvent.VILLAGER_SWEAT -> {
                if (!(entity instanceof net.minecraft.world.entity.npc.villager.AbstractVillager)) return;
                type = switch (event) {
                    case net.minecraft.world.entity.EntityEvent.LOVE_HEARTS -> net.minecraft.core.particles.ParticleTypes.HEART;
                    case net.minecraft.world.entity.EntityEvent.VILLAGER_ANGRY -> net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER;
                    case net.minecraft.world.entity.EntityEvent.VILLAGER_HAPPY -> net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER;
                    default -> net.minecraft.core.particles.ParticleTypes.SPLASH;
                };
                count = 5;
                y = entity.getY() + h + 0.5;
            }
            // LivingEntity.handleEntityEvent: teleported (a chorus fruit, an enderman's kin).
            case net.minecraft.world.entity.EntityEvent.TELEPORT -> {
                if (!(entity instanceof net.minecraft.world.entity.LivingEntity)) return;
                type = net.minecraft.core.particles.ParticleTypes.PORTAL;
                count = 128;
                speed = 0.1F;
                y = entity.getY() + h / 2.0;
            }
            default -> {
                return;
            }
        }
        HorizonPackets.near(level, entity.getX(), y, entity.getZ(), 64.0,
                new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(
                        type, false, false, entity.getX(), y, entity.getZ(), w / 2.0F, h / 2.0F, w / 2.0F, speed, count));
    }

    /**
     * LivingEntity.detectEquipmentUpdates, which a mirrored player (not ticked) misses: the held
     * item's attribute modifiers (a sword's damage) apply before it hits.
     */
    static void equipment(net.minecraft.server.level.ServerPlayer player) {
        try {
            java.lang.reflect.Method detect = net.minecraft.world.entity.LivingEntity.class.getDeclaredMethod("detectEquipmentUpdates");
            detect.setAccessible(true);
            detect.invoke(player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Java mods: a player's equipment could not be read: " + e);
        }
    }

    static void fire(Entity entity, String what, Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            String key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " " + what + " " + t;
            if (FAILURES.merge(key, 1, Integer::sum) == 1) {
                Natives.log("Java mods: " + key);
                StackTraceElement[] frames = t.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
            }
        }
    }
}
