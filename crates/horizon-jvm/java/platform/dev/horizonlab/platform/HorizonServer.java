package dev.horizonlab.platform;

import com.mojang.authlib.GameProfile;
import dev.horizonlab.bridge.Natives;
import dev.horizonlab.platform.mixin.EntityAccess;
import dev.horizonlab.platform.mixin.LevelAccess;
import dev.horizonlab.platform.mixin.MinecraftServerAccess;
import dev.horizonlab.platform.mixin.PlayerListAccess;
import dev.horizonlab.platform.mixin.ServerCommonListenerAccess;
import dev.horizonlab.platform.mixin.ServerLevelAccess;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Horizon's server as vanilla objects, for Fabric mods. Horizon simulates the world in Rust;
 * once per tick it calls {@link #tick} with its players and what they did, and this class
 * mirrors them into a {@code MinecraftServer}, {@code ServerLevel}s and {@code ServerPlayer}s
 * made without their constructors ({@link Natives#allocate}), then fires Fabric API's events
 * with them. A mirror holds what Horizon gave it: a player's UUID, name, position, rotation,
 * level, game mode and health. What a mod asks of a mirror goes back to Horizon through the
 * natives (see the mixins: system messages, broadcasts).
 *
 * <p>Every event is fired on its own: a mod that throws is logged and the rest go on.
 */
public final class HorizonServer {
    private HorizonServer() {}

    private static MinecraftServer server;
    private static PlayerList playerList;
    private static final Map<ResourceKey<Level>, ServerLevel> LEVELS = new LinkedHashMap<>();
    private static final List<ServerPlayer> PLAYERS = new ArrayList<>();
    private static final Map<ServerLevel, List<ServerPlayer>> LEVEL_PLAYERS = new IdentityHashMap<>();
    private static final Map<UUID, ServerPlayer> BY_UUID = new HashMap<>();
    /** Every player ever mirrored, by UUID: a player who leaves and comes back is the same object. */
    private static final Map<UUID, ServerPlayer> KNOWN = new HashMap<>();
    /**
     * Every mirror: the server, its player list, the levels, players and their connections.
     * An identity set: an entity's equals and hashCode read its id, which a mirror has only
     * once its constructor ran.
     */
    private static final Set<Object> MIRRORS = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    /** MIRRORS as read: a copy, made again when one is added (rarely: a level, a player). */
    private static volatile Set<Object> MIRROR_VIEW = Set.of();

    static void mirror(Object object) {
        synchronized (MIRRORS) {
            MIRRORS.add(object);
            Set<Object> view = Collections.newSetFromMap(new IdentityHashMap<>());
            view.addAll(MIRRORS);
            MIRROR_VIEW = view;
        }
    }
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static boolean started;
    /** Horizon's world folder, where mods keep their world data (getWorldPath). */
    private static java.nio.file.Path worldDir;

    /** The world folder Horizon plays, for MinecraftServer.getWorldPath. */
    public static java.nio.file.Path worldDir() {
        return worldDir;
    }

    /** Whether this object is one of Horizon's mirrors (not a vanilla object a mod made). */
    public static boolean isMirror(Object object) {
        // Asked on every block read of a mirrored level (LevelMixin): a lock-free copy of the set.
        return object != null && MIRROR_VIEW.contains(object);
    }

    /** Each mirrored player's packet context (Fabric's, which its connection would hold). */
    private static final java.util.Map<Object, net.fabricmc.fabric.api.networking.v1.context.PacketContext> CONTEXTS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * A mirrored listener's packet context (MirrorPacketContextMixin): no connection, the
     * server, its registries and the player's profile.
     */
    public static net.fabricmc.fabric.api.networking.v1.context.PacketContext packetContext(
            net.minecraft.server.network.ServerCommonPacketListenerImpl listener) {
        return CONTEXTS.computeIfAbsent(listener, l -> {
            var context = new net.fabricmc.fabric.impl.networking.context.PacketContextImpl(null);
            var server = server();
            if (server != null) {
                context.set(net.fabricmc.fabric.impl.networking.context.PacketContextImpl.SERVER_INSTANCE, server);
                context.set(net.fabricmc.fabric.impl.networking.context.PacketContextImpl.REGISTRY_ACCESS, server.registryAccess());
            }
            if (l instanceof net.minecraft.server.network.ServerGamePacketListenerImpl game && game.player != null) {
                context.set(net.fabricmc.fabric.impl.networking.context.PacketContextImpl.GAME_PROFILE, game.player.getGameProfile());
            }
            return context;
        });
    }

    /** Ids for mirrored entities, apart from the ids Horizon gives its own (counting down). */
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID = new java.util.concurrent.atomic.AtomicInteger(0x7fff0000);

    /** An entity id for a mirror. */
    public static int nextEntityId() {
        return NEXT_ID.getAndIncrement();
    }

    private static final Map<UUID, Integer> MODES = new ConcurrentHashMap<>();
    private static final Map<UUID, net.minecraft.stats.ServerStatsCounter> STATS = new ConcurrentHashMap<>();
    private static final Map<UUID, net.minecraft.server.PlayerAdvancements> ADVANCEMENTS = new ConcurrentHashMap<>();

    /** Permission levels by player, as Horizon gives them each tick (0 everyone to 4 owner). */
    private static final Map<UUID, Integer> PERMISSIONS = new ConcurrentHashMap<>();

    /** A player's permissions on Horizon's server: the level Horizon gave for them. */
    public static net.minecraft.server.permissions.LevelBasedPermissionSet permissions(net.minecraft.server.players.NameAndId player) {
        int level = player == null ? 0 : PERMISSIONS.getOrDefault(player.id(), 0);
        return net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(
                net.minecraft.server.permissions.PermissionLevel.byId(level));
    }

    /**
     * The server's operators (PlayerList.getOps, which the mirror's skipped constructor never
     * made; Nature's Compass reads it when used): Horizon's players with a permission level
     * above 0, as Horizon gave them. Never saved: Horizon keeps ops.json.
     */
    public static synchronized net.minecraft.server.players.ServerOpList ops() {
        if (OPS == null) {
            OPS = new net.minecraft.server.players.ServerOpList(
                    new java.io.File(System.getProperty("java.io.tmpdir"), "horizon-mirror-ops.json"),
                    new net.minecraft.server.notifications.NotificationManager()) {
                @Override
                public void save() {
                    // Horizon's ops.json is the real one.
                }
            };
        }
        OPS.clear();
        for (ServerPlayer player : KNOWN.values()) {
            int level = PERMISSIONS.getOrDefault(player.getUUID(), 0);
            if (level <= 0) continue;
            var who = new net.minecraft.server.players.NameAndId(player.getGameProfile());
            OPS.add(new net.minecraft.server.players.ServerOpListEntry(who, permissions(who), false));
        }
        return OPS;
    }

    private static net.minecraft.server.players.ServerOpList OPS;

    /** PlayerList.isOp: whether Horizon gave the player a permission level above 0. */
    public static boolean isOp(net.minecraft.server.players.NameAndId player) {
        return player != null && PERMISSIONS.getOrDefault(player.id(), 0) > 0;
    }

    /** A player's stats: none kept yet (Horizon keeps its own). */
    public static net.minecraft.stats.ServerStatsCounter stats(net.minecraft.world.entity.player.Player player) {
        // The real counter (mods award stats), on a file that isn't there: Horizon keeps the
        // player's statistics, this one is never read or saved.
        return STATS.computeIfAbsent(player.getUUID(), u -> new net.minecraft.stats.ServerStatsCounter(server,
                java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "horizon-mirror", u + ".json")));
    }

    /** A player's advancements: none kept yet (Horizon keeps its own). */
    public static net.minecraft.server.PlayerAdvancements advancements(ServerPlayer player) {
        return ADVANCEMENTS.computeIfAbsent(player.getUUID(), u -> {
            var advancements = allocate(net.minecraft.server.PlayerAdvancements.class);
            // No trigger listens (Horizon awards the real advancements): triggers find none.
            ((dev.horizonlab.platform.mixin.PlayerAdvancementsAccess) advancements).horizon$setActiveTriggers(new java.util.HashMap<>());
            // Its progress, empty (mods read it: Modonomicon replays the advancements a player
            // has as it joins).
            emptyCollections(advancements, net.minecraft.server.PlayerAdvancements.class);
            try {
                java.lang.reflect.Field owner = net.minecraft.server.PlayerAdvancements.class.getDeclaredField("player");
                owner.setAccessible(true);
                owner.set(advancements, player);
            } catch (ReflectiveOperationException e) {
                // A player-less one still answers progress.
            }
            // The list a grant's announcement goes to (a mod awarding its own advancement: The
            // Sift's rift; a null list failed the mod's whole teleport). Horizon announces
            // advancements itself: PlayerListMixin drops the mirror's. Progress changed here
            // isn't Horizon's (the routed award, HorizonVanillaActions.award, is).
            try {
                java.lang.reflect.Field list = net.minecraft.server.PlayerAdvancements.class.getDeclaredField("playerList");
                list.setAccessible(true);
                if (list.get(advancements) == null) list.set(advancements, playerList);
                // And the tree its grants mark for a visibility update (the server's).
                java.lang.reflect.Field tree = net.minecraft.server.PlayerAdvancements.class.getDeclaredField("tree");
                tree.setAccessible(true);
                if (tree.get(advancements) == null && HorizonData.loaded != null) {
                    tree.set(advancements, HorizonData.loaded.reloadable().getAdvancements().tree());
                }
            } catch (ReflectiveOperationException e) {
                // Announcements fail as before.
            }
            return advancements;
        });
    }

    /** The mirrored server, once Horizon's first tick made it. */
    public static MinecraftServer server() {
        return server;
    }

    /**
     * A mirrored level's server level: itself, or for a headless client's level
     * (HorizonClient.clientLevel) its dimension's, whose block entities and blocks it shows.
     */
    public static Level serverSide(Level level) {
        return level instanceof ServerLevel ? level : level(level.dimension().identifier().toString());
    }

    /** The mirrored players (the headless client's, one tick of HUD each). */
    public static List<ServerPlayer> players() {
        synchronized (PLAYERS) {
            return new ArrayList<>(PLAYERS);
        }
    }

    /** An object made without its constructor that counts as the mirror's (isMirror). */
    static <T> T mirror(Class<T> type) {
        return allocate(type);
    }

    private static net.minecraft.network.Connection clientConnection;

    /** The headless client's network connection (its packet listener's): a mirror one. */
    static synchronized net.minecraft.network.Connection clientConnection() {
        if (clientConnection == null) clientConnection = HorizonClient.memoryConnection(allocate(net.minecraft.network.Connection.class));
        return clientConnection;
    }

    /** Whether {@code connection} is the headless client's (connected, never sending: MirrorConnectionMixin). */
    public static boolean isClientConnection(Object connection) {
        return connection != null && connection == clientConnection;
    }

    private static <T> T allocate(Class<T> type) {
        T object = type.cast(Natives.allocate(type));
        mirror(object);
        return object;
    }

    private static void setUp(boolean dedicated) throws ClassNotFoundException {
        String serverClass = dedicated
                ? "net.minecraft.server.dedicated.DedicatedServer"
                : "net.minecraft.client.server.IntegratedServer";
        String listClass = dedicated
                ? "net.minecraft.server.dedicated.DedicatedPlayerList"
                : "net.minecraft.client.server.IntegratedPlayerList";
        ClassLoader loader = HorizonServer.class.getClassLoader();
        server = (MinecraftServer) allocate(Class.forName(serverClass, true, loader));
        playerList = (PlayerList) allocate(Class.forName(listClass, true, loader));
        ((PlayerListAccess) playerList).horizon$setPlayers(PLAYERS);
        ((PlayerListAccess) playerList).horizon$setPlayersByUUID(BY_UUID);
        ((PlayerListAccess) playerList).horizon$setServer(server);
        ((MinecraftServerAccess) server).horizon$setPlayerList(playerList);
        ((MinecraftServerAccess) server).horizon$setLevels(LEVELS);
        // Its tick rate (Level.tickRateManager: mods' tooltips read it) and the collections
        // mods' mixins add to it.
        try {
            java.lang.reflect.Field rate = MinecraftServer.class.getDeclaredField("tickRateManager");
            rate.setAccessible(true);
            rate.set(server, new net.minecraft.server.ServerTickRateManager(server));
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's server mirror has no tick rate: " + e);
        }
        emptyCollections(server, server.getClass());
        // Its data packs (One Block's generator reads the selected ones for its void options).
        try {
            java.lang.reflect.Field packs = MinecraftServer.class.getDeclaredField("packRepository");
            packs.setAccessible(true);
            if (packs.get(server) == null && HorizonData.repository != null) packs.set(server, HorizonData.repository);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Horizon's server mirror has no pack repository: " + e);
        }
        // Its debug subscribers (entities ask whether anyone watches their debug data): none.
        try {
            java.lang.reflect.Field debug = MinecraftServer.class.getDeclaredField("debugSubscribers");
            debug.setAccessible(true);
            debug.set(server, new net.minecraft.util.debug.ServerDebugSubscribers(server));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Horizon's server mirror has no debug subscribers: " + e);
        }
        // Its notifications (MinecraftServer.onGameRuleChanged tells them): a game rule a mod
        // or a data pack sets (Simply OneBlock's load function) goes to Horizon's rules.
        try {
            var notifications = new net.minecraft.server.notifications.NotificationManager();
            notifications.registerService((net.minecraft.server.notifications.NotificationService) java.lang.reflect.Proxy.newProxyInstance(
                    loader, new Class<?>[] {net.minecraft.server.notifications.NotificationService.class}, (proxy, method, args) -> {
                        if (method.getName().equals("onGameRuleChanged") && args != null && args.length == 2
                                && args[0] instanceof net.minecraft.world.level.gamerules.GameRule<?> rule) {
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            String value = ((net.minecraft.world.level.gamerules.GameRule) rule).serialize(args[1]);
                            Natives.gameRule(rule.id(), value);
                        }
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> "Horizon's game rules";
                            };
                        }
                        return null;
                    }));
            java.lang.reflect.Field field = MinecraftServer.class.getDeclaredField("notificationManager");
            field.setAccessible(true);
            field.set(server, notifications);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Horizon's server mirror has no notifications: " + e);
        }
        // Its weather (ServerLevel.getWeatherData reads the server's: Serene Seasons): clear.
        try {
            java.lang.reflect.Field weather = MinecraftServer.class.getDeclaredField("weatherData");
            weather.setAccessible(true);
            weather.set(server, new net.minecraft.world.level.saveddata.WeatherData());
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Horizon's server mirror has no weather: " + e);
        }
                // The registries and resources HorizonData loaded: vanilla's data pack and the mods'.
        HorizonData.Loaded data = HorizonData.loaded;
        if (data != null) {
            ((MinecraftServerAccess) server).horizon$setRegistries(data.registries());
            ((MinecraftServerAccess) server).horizon$setWorldData(data.worldData());
            // Vanilla's defaults; Horizon's own rules are mirrored over them later.
            ((MinecraftServerAccess) server).horizon$setGameRules(MinecraftServer.DEFAULT_GAME_RULES.get());
            // Its world clocks (Level.clockManager: structures placing mobs read the difficulty,
            // which reads the overworld's clock), from the registries just set.
            try {
                var make = net.minecraft.world.clock.ServerClockManager.class
                        .getDeclaredConstructor(net.minecraft.world.clock.PackedClockStates.class);
                make.setAccessible(true);
                var clocks = make.newInstance(net.minecraft.world.clock.PackedClockStates.EMPTY);
                clocks.init(server);
                java.lang.reflect.Field field = MinecraftServer.class.getDeclaredField("clockManager");
                field.setAccessible(true);
                field.set(server, clocks);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Natives.log("Horizon's server mirror has no world clocks: " + e);
            }
            // Teams and objectives: an empty one (Horizon's own come later); display names read it.
            ((MinecraftServerAccess) server).horizon$setScoreboard(new net.minecraft.server.ServerScoreboard(server));
            try {
                var constructor = Class.forName("net.minecraft.server.MinecraftServer$ReloadableResources", true, loader)
                        .getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                var field = MinecraftServer.class.getDeclaredField("resources");
                field.setAccessible(true);
                field.set(server, constructor.newInstance(data.resources(), data.reloadable()));
            } catch (ReflectiveOperationException e) {
                Natives.log("Horizon's server mirror has no resources: " + e);
            }
        }
        for (String dimension : levelIds()) {
            level(dimension);
        }
    }

    /** The mirrored server, made now if no tick made it yet (a single player's, until one does). */
    static synchronized void ensureServer() {
        if (server != null) return;
        try {
            setUp(false);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        HorizonHooks.scan();
    }

    /**
     * Fills the null collection fields of {@code object} (made without its constructor), up its
     * classes from {@code from}: what the constructor (and mods' mixins into it) would have set.
     */
    static void emptyCollections(Object object, Class<?> from) {
        for (Class<?> c = from; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                Object empty;
                if (type == Map.class) empty = new java.util.concurrent.ConcurrentHashMap<>();
                else if (type == java.util.Set.class) empty = java.util.concurrent.ConcurrentHashMap.newKeySet();
                else if (type == List.class || type == java.util.Collection.class) empty = new java.util.concurrent.CopyOnWriteArrayList<>();
                else if (type == java.util.Queue.class || type == java.util.Deque.class) empty = new java.util.concurrent.ConcurrentLinkedDeque<>();
                // A mod's atomic flag or counter (its mixin's field, made in the constructor the
                // mirror skips: Chunky's housekeeping flag on MinecraftServer), at its default.
                else if (type == java.util.concurrent.atomic.AtomicBoolean.class) empty = new java.util.concurrent.atomic.AtomicBoolean();
                else if (type == java.util.concurrent.atomic.AtomicInteger.class) empty = new java.util.concurrent.atomic.AtomicInteger();
                else if (type == java.util.concurrent.atomic.AtomicLong.class) empty = new java.util.concurrent.atomic.AtomicLong();
                else if (type == java.util.concurrent.atomic.AtomicReference.class) empty = new java.util.concurrent.atomic.AtomicReference<>();
                else if ((java.util.Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type))
                        && !type.isInterface() && !java.lang.reflect.Modifier.isAbstract(type.getModifiers())
                        && (type.getName().startsWith("java.util.") || type.getName().startsWith("it.unimi.dsi.fastutil."))) {
                    // A concrete JDK collection a mod's mixin declares (Spell Engine's ArrayList
                    // on ServerLevel), or vanilla's fastutil maps (ChunkMap.updatingChunkMap,
                    // which Fabric's block entity attachments read: McJtyLib's machines): a new
                    // empty one of that class.
                    try {
                        empty = type.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        continue;
                    }
                }
                else continue;
                try {
                    field.setAccessible(true);
                    if (field.get(object) == null) field.set(object, empty);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // A field it can't reach stays as it is.
                }
            }
        }
    }

    static ServerLevel level(String dimension) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
        ServerLevel level = makeLevel(key);
        // What mods add at the end of a level's constructor (which the mirror skips), now that
        // it is set up: once, outside the map's update (their code may ask for levels).
        // A level made before a world opened (the content export's) waits for its saved data.
        if (HorizonSavedData.ready() && CONSTRUCTED.add(level)) {
            chunkMap(level);
            HorizonHooks.constructed(level);
        }
        if (started) levelLoaded(level);
        return level;
    }

    /** The mirrored levels' chunk maps (ChunkMapMixin answers for them). */
    private static final java.util.Set<Object> CHUNK_MAPS =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    public static boolean isMirrorChunkMap(Object map) {
        return CHUNK_MAPS.contains(map);
    }

    /**
     * The world's storage as mods see it (MinecraftServer.storageSource: getWorldPath, a
     * dimension's folder): rooted in the world's horizon-java folder, so what mods write stays
     * beside the rest of the Java side's files. Made without its constructor (no session lock:
     * Horizon holds the world).
     */
    static synchronized net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess storage() {
        java.nio.file.Path root = HorizonSavedData.javaDir();
        if (root == null) return null;
        if (storage != null && root.equals(storageRoot)) return storage;
        try {
            var access = (net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess)
                    allocate(net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess.class);
            Class<?> owner = net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess.class;
            for (var entry : new Object[][] {
                    {"levelDirectory", new net.minecraft.world.level.storage.LevelStorageSource.LevelDirectory(root)},
                    {"levelId", root.getParent() == null ? "world" : root.getParent().getFileName().toString()},
                    {"resources", new java.util.concurrent.ConcurrentHashMap<>()}}) {
                java.lang.reflect.Field f = owner.getDeclaredField((String) entry[0]);
                f.setAccessible(true);
                f.set(access, entry[1]);
            }
            java.lang.reflect.Field source = MinecraftServer.class.getDeclaredField("storageSource");
            source.setAccessible(true);
            source.set(server, access);
            storage = access;
            storageRoot = root;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Java mods: no world storage for the mods: " + e);
        }
        return storage;
    }

    private static net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess storage;
    private static java.nio.file.Path storageRoot;

    /**
     * A mirrored level's chunk map (ServerChunkCache.chunkMap), made without its constructor:
     * mods reach their per-level state through it (GraphLib's graph storage, which its mixin
     * makes at the end of ChunkMap's constructor: run here, with the world's storage).
     * Horizon holds the chunks; ChunkMapMixin answers what mods ask of it.
     */
    private static void chunkMap(ServerLevel level) {
        try {
            var map = allocate(net.minecraft.server.level.ChunkMap.class);
            java.lang.reflect.Field owner = net.minecraft.server.level.ChunkMap.class.getDeclaredField("level");
            owner.setAccessible(true);
            owner.set(map, level);
            emptyCollections(map, map.getClass());
            // Its chunk holders' maps (fastutil's), empty: Horizon holds the chunks, and mods
            // that walk them find none (JourneyMap's loaded-chunk overlay, each server tick).
            for (java.lang.reflect.Field f : net.minecraft.server.level.ChunkMap.class.getDeclaredFields()) {
                Class<?> type = f.getType();
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !type.getName().startsWith("it.unimi.dsi.fastutil.")
                        || type.isInterface() || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) continue;
                f.setAccessible(true);
                if (f.get(map) == null) f.set(map, type.getDeclaredConstructor().newInstance());
            }
            // Its main thread is the server's (what is queued on it runs between Horizon's
            // ticks: EventLoopMixin), as ServerChunkCache gives it its own.
            java.lang.reflect.Field executor = net.minecraft.server.level.ChunkMap.class.getDeclaredField("mainThreadExecutor");
            executor.setAccessible(true);
            if (executor.get(map) == null) executor.set(map, server);
            java.lang.reflect.Field field = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("chunkMap");
            field.setAccessible(true);
            field.set(level.getChunkSource(), map);
            CHUNK_MAPS.add(map);
            HorizonHooks.constructed(map, storage(), generator(level));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Java mods: a level has no chunk map: " + e);
        }
    }

    private static final java.util.Set<ServerLevel> CONSTRUCTED =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private static final java.util.Set<ServerLevel> LOADED_LEVELS =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** Fabric's ServerLevelEvents.LOAD for a level, once (mods set up per level: chunk loaders). */
    private static void levelLoaded(ServerLevel level) {
        if (!LOADED_LEVELS.add(level)) return;
        fireEach("ServerLevelEvents.LOAD", net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.LOAD,
                l -> l.onLevelLoad(server, level));
    }

    private static ServerLevel makeLevel(ResourceKey<Level> key) {
        return LEVELS.computeIfAbsent(key, k -> {
            ServerLevel level = allocate(ServerLevel.class);
            LevelAccess access = (LevelAccess) level;
            access.horizon$setDimension(k);
            access.horizon$setClientSide(false);
            access.horizon$setRandom(net.minecraft.util.RandomSource.create());
            ((ServerLevelAccess) level).horizon$setServer(server);
            ((ServerLevelAccess) level).horizon$setSleepStatus(new net.minecraft.server.players.SleepStatus());
            // What the skipped constructor makes that mods' code reaches (a placement's
            // entity check, sounds, block events).
            ServerLevelAccess sl = (ServerLevelAccess) level;
            sl.horizon$setDragonParts(new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>());
            sl.horizon$setNavigatingMobs(new java.util.HashSet<>());
            sl.horizon$setBlockEvents(new it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<>());
            sl.horizon$setBlockEventsToReschedule(new ArrayList<>());
            sl.horizon$setCustomSpawners(new ArrayList<>());
            sl.horizon$setBlockTicks(new HorizonTicks<>());
            sl.horizon$setFluidTicks(new HorizonTicks<>());
            // A chunk source holding no chunks (ServerChunkCacheMixin): what entities send their
            // trackers goes nowhere, their paths read the level.
            var chunks = allocate(net.minecraft.server.level.ServerChunkCache.class);
            try {
                java.lang.reflect.Field owner = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("level");
                owner.setAccessible(true);
                owner.set(chunks, level);
            } catch (ReflectiveOperationException e) {
                Natives.log("Java mods: a level's chunk source has no level: " + e);
            }
            sl.horizon$setChunkSource(chunks);
            // Its waypoint manager (living entities leave it when removed; Horizon has the
            // locator bar's waypoints).
            try {
                java.lang.reflect.Field waypoints = ServerLevel.class.getDeclaredField("waypointManager");
                waypoints.setAccessible(true);
                waypoints.set(level, new net.minecraft.server.waypoints.ServerWaypointManager());
            } catch (ReflectiveOperationException e) {
                Natives.log("Java mods: a level has no waypoint manager: " + e);
            }
            access.horizon$setSoundSeedGenerator(net.minecraft.util.RandomSource.create());
            access.horizon$setBlockEntityTickers(new ArrayList<>());
            access.horizon$setPendingBlockEntityTickers(new ArrayList<>());
            // Its players' list from the start: vanilla's code (setGameMode's sleeping check)
            // may read it before this tick's lists are made.
            List<ServerPlayer> players = new ArrayList<>();
            LEVEL_PLAYERS.put(level, players);
            // Collections other mods' mixins add to the level and fill in its constructor
            // (Fabric's Transfer API's lookup caches): empty ones of their own.
            emptyCollections(level, ServerLevel.class);
            ((ServerLevelAccess) level).horizon$setPlayers(players);
            HorizonData.Loaded data = HorizonData.loaded;
            if (data != null) {
                net.minecraft.core.RegistryAccess.Frozen registries = data.registries().compositeAccess();
                access.horizon$setRegistryAccess(registries);
                access.horizon$setDamageSources(new net.minecraft.world.damagesource.DamageSources(registries));
                // Its chunks' paletted containers (HorizonChunks' copies, HorizonWorldgen's protos).
                try {
                    java.lang.reflect.Field containers = Level.class.getDeclaredField("palettedContainerFactory");
                    containers.setAccessible(true);
                    containers.set(level, net.minecraft.world.level.chunk.PalettedContainerFactory.create(registries));
                } catch (ReflectiveOperationException e) {
                    Natives.log("Java mods: a level has no paletted containers: " + e);
                }
                access.horizon$setLevelData(data.worldData());
                // ServerLevel's own copy (its name, its spawn, what mods read of the world).
                try {
                    java.lang.reflect.Field own = ServerLevel.class.getDeclaredField("serverLevelData");
                    own.setAccessible(true);
                    own.set(level, data.worldData());
                } catch (ReflectiveOperationException e) {
                    Natives.log("Java mods: a level's data could not be set: " + e);
                }
                // The dimension's type: the level stem's, as vanilla's server binds it.
                var stem = registries.lookupOrThrow(Registries.LEVEL_STEM)
                        .get(ResourceKey.create(Registries.LEVEL_STEM, k.identifier()));
                stem.ifPresent(s -> access.horizon$setDimensionType(s.value().type()));
                // Its environment attributes (what mobs read of the sky and the moon:
                // EnvironmentAttributeSystem.addDefaultLayers): the dimension's own values. The
                // mirror has no world clocks for the time-based layers, nor biomes for theirs.
                stem.ifPresent(s -> {
                    try {
                        var builder = net.minecraft.world.attribute.EnvironmentAttributeSystem.builder();
                        java.lang.reflect.Method layer = net.minecraft.world.attribute.EnvironmentAttributeSystem.class
                                .getDeclaredMethod("addDimensionLayer", builder.getClass(),
                                        net.minecraft.world.level.dimension.DimensionType.class);
                        layer.setAccessible(true);
                        layer.invoke(null, builder, s.value().type().value());
                        level.setEnvironmentAttributes(builder.build());
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        Natives.log("Java mods: a level has no environment attributes: " + e);
                    }
                });
            }
            return level;
        });
    }

    /** Horizon's world game time, which the mirrored levels read (Level.getGameTime). */
    public static void gameTime(long time, long seed, boolean running) {
        if (server == null) return;
        server.getWorldData().overworldData().setGameTime(time);
        HorizonServer.seed = seed;
        // `/tick freeze` in Horizon: the mirror's levels stand too (tickBody), and mods that
        // ask the tick rate manager (runsNormally, isEntityFrozen) hear it.
        HorizonServer.running = running;
        try {
            var rate = server.tickRateManager();
            HorizonClient.set(net.minecraft.world.TickRateManager.class, rate, "isFrozen", !running);
            HorizonClient.set(net.minecraft.world.TickRateManager.class, rate, "runGameElements", running);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A mirror without its manager: the levels still stand (tickBody).
        }
    }

    /** Whether Horizon's world runs this tick (not frozen by `/tick freeze`, or stepping). */
    static boolean running = true;

    /** Horizon's mob `attacker` hit the mods' entity `id` (HorizonNeighbours.hurtByHorizon). */
    public static boolean hurtByHorizon(int id, int attacker, float amount) {
        boolean hurt = HorizonNeighbours.hurtByHorizon(id, attacker, amount);
        runTasks();
        afterCall();
        return hurt;
    }

    /** Horizon's projectile hit the mods' entity `id` (HorizonNeighbours.hurtByProjectile). */
    public static boolean hurtByProjectile(int id, String kind, String player, int mob, float amount,
            double x, double y, double z, double vx, double vy, double vz, boolean burning) {
        boolean hurt = HorizonNeighbours.hurtByProjectile(id, kind, player, mob, amount, x, y, z, vx, vy, vz, burning);
        runTasks();
        afterCall();
        return hurt;
    }

    /** Horizon's mobs near its players this tick, for the mods' mobs (HorizonNeighbours.update). */
    public static void horizonMobs(String json) {
        HorizonNeighbours.update(json);
    }

    /** Horizon's item entities near its players, for the mods' blocks (HorizonItems). */
    public static void horizonItems(String json) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        HorizonItems.update(json);
    }

    /** Whether the mods' client reads its connection's packets (HorizonPacketTap.wanted). */
    public static boolean wantsPackets() {
        return HorizonPacketTap.wanted();
    }

    /** What the server sent the window's own player (Rust's packet tap), for its client connection. */
    public static void clientPackets(String uuid, byte[] framed) {
        HorizonPacketTap.feed(uuid, framed);
    }

    /** The integrated world closes: the client connection ends, what mods do then is waited for. */
    public static void clientClosed() {
        HorizonPacketTap.close(true);
    }

    /** Mods Horizon left out of Fabric's (Sodium, Iris), one line each: told at the players' join. */
    public static void notNeeded(String lines) {
        if (lines == null) return;
        for (String line : lines.split("\n")) {
            if (!line.isBlank()) HorizonFailures.notNeeded(line);
        }
    }

    /**
     * Horizon's game rules into the mirror's (the server's, which every mirrored level reads)
     * and its difficulty: "id=value;...;difficulty=n" as Horizon has them. Set without the server (no
     * onGameRuleChanged: that would send them back to Horizon).
     */
    public static void gameRules(String rules) {
        if (server == null || rules == null) return;
        var gameRules = server.getGameRules();
        for (String pair : rules.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                if (pair.startsWith("difficulty=")) {
                    var difficulty = net.minecraft.world.Difficulty.byId(Integer.parseInt(pair.substring(eq + 1)));
                    if (server.getWorldData().getDifficulty() != difficulty) server.getWorldData().setDifficulty(difficulty);
                    continue;
                }
                var rule = net.minecraft.core.registries.BuiltInRegistries.GAME_RULE
                        .getValue(Identifier.parse(pair.substring(0, eq)));
                if (rule != null) setRule(gameRules, rule, pair.substring(eq + 1));
            } catch (Throwable t) {
                Natives.log("Java mods: game rule " + pair + " not set: " + t);
            }
        }
        RULES_SEEN = true;
    }

    /** Whether Horizon's rules were passed once (the first pass sets them all, quietly). */
    private static boolean RULES_SEEN;

    private static <T> void setRule(net.minecraft.world.level.gamerules.GameRules rules,
                                    net.minecraft.world.level.gamerules.GameRule<T> rule, String value) {
        rule.deserialize(value).result().ifPresent(v -> {
            if (v.equals(rules.get(rule))) return;
            boolean first = !RULES_SEEN;
            rules.set(rule, v, null);
            if (!first) Natives.log("Java mods: game rule " + rule.id() + " is " + value);
        });
    }

    /** Horizon's world seed (ServerLevel.getSeed on a mirrored level), from its first tick. */
    private static volatile long seed;

    public static long seed() {
        return seed;
    }

    /** Horizon's world seed before any tick (world generation asks first: HorizonWorldgen). */
    public static void worldSeed(long seed) {
        HorizonServer.seed = seed;
    }

    /**
     * A mirrored level's chunk generator (ServerChunkCache.getGenerator): its level stem's,
     * from the data packs. Horizon generates the terrain; mods ask the generator about it
     * (Nature's Compass searches its biome source), and Horizon's generation is vanilla's.
     */
    public static net.minecraft.world.level.chunk.ChunkGenerator generator(ServerLevel level) {
        // The world's preset's stem (a mod's world type) or the registries'.
        var stem = HorizonData.stem(level.dimension().identifier());
        return stem == null ? null : stem.generator();
    }

    private static final Map<ServerLevel, net.minecraft.world.level.levelgen.RandomState> RANDOM_STATES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<ServerLevel, net.minecraft.world.level.chunk.ChunkGeneratorStructureState> STRUCTURE_STATES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long statesSeed;
    private static volatile String statesPreset = "";

    /** Whether the states made are for this seed and world preset. */
    private static boolean statesCurrent() {
        return statesSeed == seed && statesPreset.equals(HorizonData.preset());
    }

    /** The states made for another seed or preset go. */
    private static synchronized void refreshStates() {
        if (statesCurrent()) return;
        RANDOM_STATES.clear();
        STRUCTURE_STATES.clear();
        statesPreset = HorizonData.preset();
        statesSeed = seed;
    }

    /**
     * ServerChunkCache.randomState: the generator's noises with Horizon's seed (ChunkMap's
     * constructor). Every generation step asks, from every worker: lock-free once made.
     */
    public static net.minecraft.world.level.levelgen.RandomState randomState(ServerLevel level) {
        if (!statesCurrent()) refreshStates();
        var made = RANDOM_STATES.get(level);
        return made != null ? made : makeRandomState(level);
    }

    private static synchronized net.minecraft.world.level.levelgen.RandomState makeRandomState(ServerLevel level) {
        return RANDOM_STATES.computeIfAbsent(level, l -> {
            var registries = HorizonData.loaded.registries().compositeAccess();
            var noises = registries.lookupOrThrow(Registries.NOISE);
            var settings = generator(l) instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise
                    ? noise.generatorSettings().value()
                    : registries.lookupOrThrow(Registries.NOISE_SETTINGS)
                            .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD).value();
            var state = net.minecraft.world.level.levelgen.RandomState.create(noises, seed, settings);
            // The mods' wraps of this call in ChunkMap's constructor (Lithostitched's noise
            // router modifiers), with the level and registries it captures there.
            net.minecraft.server.level.ChunkMap map = null;
            try {
                map = l.getChunkSource().chunkMap;
            } catch (RuntimeException e) {
                // No chunk source yet.
            }
            if (map == null) map = (net.minecraft.server.level.ChunkMap) Natives.allocate(net.minecraft.server.level.ChunkMap.class);
            return (net.minecraft.world.level.levelgen.RandomState) HorizonHooks.wrapConstructorCall(
                    net.minecraft.server.level.ChunkMap.class, map, HorizonHooks.RANDOM_STATE_CREATE, state,
                    noises, seed, settings, l, registries);
        });
    }

    /** ServerChunkCache.getGeneratorState: where structures may be (a locator's search). Lock-free once made. */
    public static net.minecraft.world.level.chunk.ChunkGeneratorStructureState generatorState(ServerLevel level) {
        if (!statesCurrent()) refreshStates();
        var made = STRUCTURE_STATES.get(level);
        return made != null ? made : makeGeneratorState(level);
    }

    private static synchronized net.minecraft.world.level.chunk.ChunkGeneratorStructureState makeGeneratorState(ServerLevel level) {
        var random = randomState(level);
        return STRUCTURE_STATES.computeIfAbsent(level, l -> {
            var state = generator(l).createState(
                    HorizonData.loaded.registries().compositeAccess().lookupOrThrow(Registries.STRUCTURE_SET), random, seed);
            // Vanilla's ChunkMap does this once, on the server thread, before any chunk
            // generates; left to the first chunks, the worldgen workers each start it and fill
            // its unsynchronized ring-position map together (AIOOBE in fastutil's rehash).
            state.ensureStructuresGenerated();
            return state;
        });
    }

    // The mods' world generation (HorizonWorldgen), for horizon-jvm's events.rs.
    /**
     * The world Horizon has open (its folder): where the mods' block entities, saved data and
     * entities are kept. Before anything runs in it: its tick, or its worldgen's start (whose
     * mods' hooks, Fabric's data attachments among them, read the server's saved data).
     */
    static void world(String world) {
        if (world != null && !world.isEmpty()) {
            java.nio.file.Path dir = java.nio.file.Paths.get(world).toAbsolutePath().normalize();
            // A world made with a mod's world type: its dimensions are that preset's.
            if (!dir.equals(worldDir)) {
                String preset = worldPreset(dir);
                if (preset != null) HorizonData.usePreset(preset);
            }
            worldDir = dir;
        }
        HorizonBlockEntities.world(worldDir);
        HorizonSavedData.world(server, worldDir);
        HorizonEntities.world(worldDir);
    }

    /**
     * The mod world preset Horizon's world creation wrote ({@code horizon:preset}) in the world
     * at {@code dir} (or a folder above it: a dimension's own), "" for none; null when no
     * world's settings are there.
     */
    private static String worldPreset(java.nio.file.Path dir) {
        java.nio.file.Path file = null;
        for (java.nio.file.Path at = dir; at != null && file == null; at = at.getParent()) {
            java.nio.file.Path settings = at.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
            if (java.nio.file.Files.isRegularFile(settings)) file = settings;
            if (at.getFileName() != null && at.getFileName().toString().equals("saves")) break;
        }
        if (file == null) return null;
        try {
            net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return root.getCompoundOrEmpty("data").getStringOr("horizon:preset", "");
        } catch (java.io.IOException | RuntimeException e) {
            Natives.log("Java mods: the world's settings can't be read: " + e);
            return "";
        }
    }

    /** Whether the mods' load functions have run (Horizon places a new player after them). */
    public static boolean functionsReady() {
        return HorizonFunctions.ready();
    }

    public static boolean worldgenStart(String dimension, long seed, boolean structures, String world,
            String[] horizonCannot) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ensureServer();
        // Once a world: the Nether's and the End's starts come while the server ticks, and
        // world() makes the stores the tick reads (a second time mid-tick: a
        // ConcurrentModificationException in the tick). The tick names the folder of the level
        // in place (dimensions/<ns>/<path>), inside the world's: a world opened in a mod's
        // dimension starts its generation only when a chunk there isn't saved, ticks later.
        if (world != null && !world.isEmpty()) {
            java.nio.file.Path dir = java.nio.file.Paths.get(world).toAbsolutePath().normalize();
            java.nio.file.Path current = worldDir;
            if (current == null || !current.startsWith(dir)) world(world);
        }
        return HorizonWorldgen.start(dimension, seed, structures, horizonCannot);
    }

    public static String[] worldgenLodTops(String dimension, int[] xyz, int[] waterHeights) {
        return HorizonWorldgen.lodTops(dimension, xyz, waterHeights);
    }

    public static String[] worldgenLodMaterials(String dimension) {
        return HorizonWorldgen.lodMaterials(dimension);
    }

    public static String worldgenLodNoise(String dimension, String dir) {
        return HorizonWorldgen.lodNoise(dimension, dir);
    }

    public static String[] worldgenLodColumns(String dimension, int[] xz) {
        return HorizonWorldgen.lodColumns(dimension, xz);
    }

    /** The level stems of the data packs beyond vanilla's three, sorted (mods' dimensions). */
    static List<Identifier> extraStems() {
        HorizonData.Loaded data = HorizonData.loaded;
        java.util.TreeSet<Identifier> ids = new java.util.TreeSet<>();
        if (data == null) return new ArrayList<>(ids);
        try {
            data.registries().compositeAccess().lookupOrThrow(Registries.LEVEL_STEM).listElementIds()
                    .forEach(k -> ids.add(k.identifier()));
        } catch (RuntimeException e) {
            Natives.log("Java mods: the level stems can't be listed: " + e);
        }
        ids.removeIf(HorizonServer::vanillaLevel);
        return new ArrayList<>(ids);
    }

    /** The Overworld, the Nether or the End. */
    static boolean vanillaLevel(Identifier id) {
        return id.getNamespace().equals("minecraft")
                && (id.getPath().equals("overworld") || id.getPath().equals("the_nether") || id.getPath().equals("the_end"));
    }

    /** Every level a server of these data packs has: vanilla's three, then the mods' (createLevels' order). */
    static List<String> levelIds() {
        List<String> ids = new ArrayList<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
        for (Identifier id : extraStems()) ids.add(id.toString());
        return ids;
    }

    /**
     * The level stems beyond the Overworld, the Nether and the End (a mod's or a data pack's
     * dimension: The Sift's, a mining dimension), as a JSON list of what Horizon needs to hold
     * them as levels of its own: id, dimension type, build range, skylight, ceiling, coordinate
     * scale. Their mirrored levels are made with vanilla's ({@link #levelIds}), as
     * MinecraftServer.createLevels makes one per stem: mods' code asking {@code
     * server.getLevel(key)} (a horn, a teleporter block) finds it.
     */
    public static String extraLevels() {
        StringBuilder json = new StringBuilder("[");
        for (Identifier id : extraStems()) {
            // The world's preset's stem when it has one of that id.
            var stem = HorizonData.stem(id);
            if (stem == null) continue;
            try {
                var type = stem.type().value();
                String typeId = stem.type().unwrapKey().map(k -> k.identifier().toString()).orElse(id.toString());
                if (json.length() > 1) json.append(',');
                json.append("{\"id\":\"").append(id).append("\",\"type\":\"").append(typeId)
                        .append("\",\"min_y\":").append(type.minY())
                        .append(",\"height\":").append(type.height())
                        .append(",\"logical_height\":").append(type.logicalHeight())
                        .append(",\"has_skylight\":").append(type.hasSkyLight())
                        .append(",\"has_ceiling\":").append(type.hasCeiling())
                        .append(",\"coordinate_scale\":").append(type.coordinateScale())
                        .append('}');
            } catch (RuntimeException e) {
                Natives.log("Java mods: the dimension " + id + " can't be a level: " + e);
            }
        }
        return json.append(']').toString();
    }

    /** The world spawn's first chunk in a dimension the mods generate (HorizonWorldgen.origin): [x, z] or null. */
    public static int[] worldgenOrigin(String dimension) {
        return HorizonWorldgen.origin(dimension);
    }

    public static long worldgenProto(String dimension, int x, int z) {
        return HorizonWorldgen.proto(dimension, x, z);
    }

    public static void worldgenDecorate(String dimension, int x, int z) {
        HorizonWorldgen.decorate(dimension, x, z);
    }

    public static byte[] worldgenFinish(String dimension, int x, int z, long token) {
        return HorizonWorldgen.finish(dimension, x, z, token);
    }

    public static long worldgenProtoFilled(String dimension, int x, int z, String[] palette, int[] blocks, int[] post) {
        return HorizonWorldgen.proto(dimension, x, z, palette, blocks, post);
    }

    public static byte[] worldgenSnapshot(String dimension, int x, int z, long token) {
        return HorizonWorldgen.snapshot(dimension, x, z, token);
    }

    public static void worldgenRestore(String dimension, int x, int z, long token, String[] palette, int fromY,
                                       int[] blocks, String[] blockEntities, String[] entities, boolean decorated) {
        HorizonWorldgen.restore(dimension, x, z, token, palette, fromY, blocks, blockEntities, entities, decorated);
    }

    public static String[] worldgenLocateStructure(String dimension, String selector, int x, int y, int z, int radius) {
        return HorizonWorldgen.locateStructure(dimension, selector, x, y, z, radius);
    }

    public static boolean worldgenNoiseFromHorizon(String dimension) {
        return HorizonWorldgen.noiseFromHorizon(dimension);
    }

    public static String[] worldgenBiomes(String dimension, int[] quarts) {
        return HorizonWorldgen.biomes(dimension, quarts);
    }

    public static String[] worldgenPossibleBiomes(String dimension) {
        return HorizonWorldgen.possibleBiomes(dimension);
    }

    public static String[] worldgenFindBiome(String dimension, String[] names, int x, int y, int z, int radius,
                                             int horizontal, int vertical) {
        return HorizonWorldgen.findBiome(dimension, names, x, y, z, radius, horizontal, vertical);
    }

    public static void worldgenForget(String dimension, int x, int z, long token) {
        HorizonWorldgen.forget(dimension, x, z, token);
    }

    /** Each mirrored player's last position Horizon gave (its motion, a tick to the next) and the motion set before the block entities. */
    private static final Map<UUID, Vec3> LAST_POSITION = new java.util.HashMap<>();
    private static final Map<UUID, Vec3> MOTION_BEFORE = new java.util.HashMap<>();

    /**
     * Before the block entities tick: each player's motion as Horizon moved it (its position
     * since the last tick; Horizon's own velocity doesn't come over), so a mod's block entity
     * that adds to it (Create's fan: {@code motion + (wind - motion) / 8}) works from it.
     */
    private static void playerMotionsBefore() {
        MOTION_BEFORE.clear();
        for (ServerPlayer player : PLAYERS) {
            Vec3 at = player.position();
            Vec3 last = LAST_POSITION.put(player.getUUID(), at);
            Vec3 motion = last == null || last.distanceToSqr(at) > 100 ? Vec3.ZERO : at.subtract(last);
            player.setDeltaMovement(motion);
            MOTION_BEFORE.put(player.getUUID(), player.getDeltaMovement());
        }
    }

    /**
     * After: a player whose motion a mod's block entity set (Create's fan blowing at it, its
     * belt carrying it) is told it (Natives.playerMotion: ClientboundSetEntityMotionPacket to
     * its client). Vanilla's clients run the same block entities and move their own player;
     * here the server's half stands in for the client's (the client level holds no player of
     * the player's own). Entities' pushes and hurts are left to Horizon.
     */
    private static void playerMotionsAfter() {
        for (ServerPlayer player : PLAYERS) {
            Vec3 before = MOTION_BEFORE.get(player.getUUID());
            Vec3 now = player.getDeltaMovement();
            if (before == null || now == before || now.distanceToSqr(before) < 1.0E-8) continue;
            Natives.playerMotion(player.getUUID().toString(), now.x, now.y, now.z);
        }
    }

    /** The mirrored levels (one a dimension Horizon has). */
    public static java.util.List<ServerLevel> levels() {
        return new ArrayList<>(LEVELS.values());
    }

    /** Player {@code i} of a tick's arrays, mirrored as Horizon gives it (made the first time). */
    private static ServerPlayer mirrorPlayer(int i, String[] ids, String[] names, String[] dimensions, double[] motion,
                                             int[] modes, float[] health, float[] food, int[] permissions,
                                             String[] inventories, long tick) {
        UUID uuid = UUID.fromString(ids[i]);
        ServerLevel level = level(dimensions[i]);
        ServerPlayer player = player(uuid, names[i], level);
        HorizonServices.known(uuid, names[i]);
        if (player.level() != level) ((EntityAccess) player).horizon$setLevel(level);
        player.setPosRaw(motion[i * 5], motion[i * 5 + 1], motion[i * 5 + 2]);
        // Its box follows (setPosRaw leaves it): mods find players by it (a cabinet's viewers).
        player.setBoundingBox(player.getDimensions(player.getPose()).makeBoundingBox(player.position()));
        player.setYRot((float) motion[i * 5 + 3]);
        player.setXRot((float) motion[i * 5 + 4]);
        // A player's head and body face where Horizon's player looks.
        player.setYHeadRot((float) motion[i * 5 + 3]);
        player.setYBodyRot((float) motion[i * 5 + 3]);
        player.setHealth(health[i]);
        if (food != null && food.length >= (i + 1) * 2) {
            var data = player.getFoodData();
            data.setFoodLevel((int) food[i * 2]);
            data.setSaturation(food[i * 2 + 1]);
            FOOD_SEEN.put(uuid, new float[] {food[i * 2], food[i * 2 + 1]});
        }
        MODES.put(uuid, modes[i]);
        // Horizon's game mode, so mods see it (ServerPlayer.setGameMode: abilities
        // follow, and a mod that grants flight hears of the change).
        if (player.gameMode().getId() != modes[i]) {
            // What vanilla tells clients of the change, Horizon already did.
            syncing = true;
            try {
                player.setGameMode(net.minecraft.world.level.GameType.byId(modes[i]));
            } finally {
                syncing = false;
            }
        }
        // The permission level (low 8 bits) and the sneak key (bit 8).
        PERMISSIONS.put(uuid, permissions[i] & 0xff);
        // tickCount moves with Horizon's ticks (the mirror isn't ticked): Carry On
        // refuses a second action on the tick of the last one, which was every tick.
        player.tickCount = (int) tick;
        boolean sneaking = ((permissions[i] >> 8) & 1) != 0;
        player.setShiftKeyDown(sneaking);
        // isCrouching is the pose (the mirror isn't ticked: updatePlayerPose doesn't
        // run): Veinminer's "sneak to mine a vein" reads it.
        var pose = player.getPose();
        if (sneaking && pose == net.minecraft.world.entity.Pose.STANDING) player.setPose(net.minecraft.world.entity.Pose.CROUCHING);
        else if (!sneaking && pose == net.minecraft.world.entity.Pose.CROUCHING) player.setPose(net.minecraft.world.entity.Pose.STANDING);
        // The movement keys (bits 16-22, the PlayerInput packet's flags): a mod's mob
        // it rides steers by them (getRiddenInput reads xxa and zza, as the rider's
        // client would set them; RiderAuthorityMixin lets the mirror move the mob).
        int keys = (permissions[i] >> 16) & 0x7f;
        player.setLastClientInput(new net.minecraft.world.entity.player.Input((keys & 1) != 0, (keys & 2) != 0,
                (keys & 4) != 0, (keys & 8) != 0, (keys & 16) != 0, (keys & 32) != 0, (keys & 64) != 0));
        if (player.getVehicle() != null) {
            player.zza = ((keys & 1) != 0 ? 1.0F : 0.0F) - ((keys & 2) != 0 ? 1.0F : 0.0F);
            player.xxa = ((keys & 4) != 0 ? 1.0F : 0.0F) - ((keys & 8) != 0 ? 1.0F : 0.0F);
            player.setJumping((keys & 16) != 0);
            HorizonEntities.ridingJump(player, (keys & 16) != 0);
        } else {
            player.xxa = 0.0F;
            player.zza = 0.0F;
            player.setJumping(false);
        }
        mirrorInventory(player, inventories, i);
        return player;
    }

    /**
     * The mirror of a game connected to another's server whose own JVM runs its mods' client
     * half (HorizonDeviceMods.OWN_CLIENT): its player alone, where its game sees it, and its
     * levels over the game's own world (the chunks it was sent). Nothing of the server runs
     * here (no server events, ticks or block entities: the server is the other's); the mirror
     * only gives the headless client what it runs on, so the client is this player's alone, as
     * a vanilla client is, and its tick, frame, HUD and screens are the host's own code.
     */
    /** The world spawn the server told this game, for its own client's mirror (ownClientTick). */
    static volatile net.minecraft.core.BlockPos ownSpawn = net.minecraft.core.BlockPos.ZERO;

    static void ownClientTick(long tick, String id, String name, String dimension, double[] motion, int mode,
                              float health, float[] food, int permission, String[] inventory) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        serverThread = Thread.currentThread();
        try {
            if (server == null) {
                // A server's mirror, not single player's: the client is connected to another's
                // (mods name their per-server data by it, not by a world folder of this game's).
                setUp(true);
                HorizonHooks.scan();
                Natives.log("Java mods: this game's own client runs on a mirror of what it sees");
            }
        } catch (Throwable t) {
            Natives.log("Java mods: this game's own client has no mirror: " + t);
            return;
        }
        ((MinecraftServerAccess) server).horizon$setTickCount((int) tick);
        // The world spawn the server told this game (handleSetSpawn's; Xaero's minimap reads it
        // at the join): the mirror has no world data of its own.
        try {
            java.lang.reflect.Field respawn = MinecraftServer.class.getDeclaredField("effectiveRespawnData");
            respawn.setAccessible(true);
            var spawn = net.minecraft.world.level.storage.LevelData.RespawnData.of(
                    net.minecraft.world.level.Level.OVERWORLD, ownSpawn, 0f, 0f);
            if (!spawn.equals(respawn.get(server))) respawn.set(server, spawn);
        } catch (ReflectiveOperationException e) {
            if (FAILURES.merge("this game's spawn", 1, Integer::sum) <= 1) Natives.log("Java mods: no spawn for this game's own client: " + e);
        }
        try {
            ServerPlayer player = mirrorPlayer(0, new String[] {id}, new String[] {name}, new String[] {dimension}, motion,
                    new int[] {mode}, new float[] {health}, food, new int[] {permission}, inventory, tick);
            if (!BY_UUID.containsKey(player.getUUID())) {
                PLAYERS.add(player);
                BY_UUID.put(player.getUUID(), player);
            }
            for (ServerLevel level : LEVELS.values()) LEVEL_PLAYERS.computeIfAbsent(level, l -> {
                List<ServerPlayer> list = new ArrayList<>();
                ((ServerLevelAccess) l).horizon$setPlayers(list);
                return list;
            }).clear();
            if (player.level() instanceof ServerLevel level) {
                List<ServerPlayer> list = LEVEL_PLAYERS.get(level);
                if (list != null) list.add(player);
            }
        } catch (Throwable t) {
            if (FAILURES.merge("mirroring this game's player", 1, Integer::sum) <= 10) {
                Natives.log("Java mods: this game's player couldn't be mirrored: " + t);
            }
            return;
        }
        if (client) HorizonClient.clientTick();
        if (client) HorizonClient.sendFrames();
        HorizonLoop.flush();
    }

    /** The game left the server: its player's client goes (ownClientTick's). */
    static void ownClientLeft(UUID id) {
        ServerPlayer player = BY_UUID.remove(id);
        if (player != null) PLAYERS.remove(player);
        if (client) HorizonClient.left(id);
    }

    /** A mirrored player by id; null when Horizon doesn't list them. */
    public static ServerPlayer player(UUID uuid) {
        return BY_UUID.get(uuid);
    }

    private static ServerPlayer player(UUID uuid, String name, ServerLevel level) {
        ServerPlayer player = KNOWN.get(uuid);
        if (player == null) {
            // The real constructor, so a mirror has what vanilla's code expects of a player:
            // attributes, synced data, inventory, food, abilities. The mixins answer what it
            // asks of the server that only a running world would know.
            player = new ServerPlayer(server, level, HorizonSkins.profile(uuid, name),
                    net.minecraft.server.level.ClientInformation.createDefault());
            mirror(player);
            ServerGamePacketListenerImpl connection = allocate(ServerGamePacketListenerImpl.class);
            connection.player = player;
            ((ServerCommonListenerAccess) connection).horizon$setServer(server);
            // Its network connection, which mods read of a player (Forge Config API Port's
            // isMemoryConnection each tick): the mirror's (Horizon has the real one).
            ((ServerCommonListenerAccess) connection).horizon$setConnection(mirrorConnection(player));
            player.connection = connection;
            KNOWN.put(uuid, player);
        }
        return player;
    }

    // ---- Damage --------------------------------------------------------------------------

    /**
     * The head of a player's hurtServer, called by Horizon before it hurts the player:
     * Fabric's ALLOW_DAMAGE, then the mods' injections at the heads of ServerPlayer, Player and
     * LivingEntity.hurtServer (vanilla's calls, in that order). The amount, or -1 when a mod
     * stops the hit.
     */
    public static float hurt(String uuid, String damageType, float amount) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return amount;
        ServerLevel level = (ServerLevel) player.level();
        net.minecraft.world.damagesource.DamageSource source = damageSource(level, damageType);
        boolean[] allowed = {true};
        fire("ServerLivingEntityEvents.ALLOW_DAMAGE", () -> allowed[0] =
                net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.invoker()
                        .allowDamage(player, source, amount));
        if (!allowed[0]) return -1;
        Object[] args = {level, source, amount};
        String desc = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z";
        for (Class<?> owner : new Class<?>[] {ServerPlayer.class, net.minecraft.world.entity.player.Player.class,
                net.minecraft.world.entity.LivingEntity.class}) {
            if (!HorizonHooks.hooked(owner.getName().replace('.', '/'), "hurtServer")) continue;
            HorizonHooks.Result r = HorizonHooks.fire(player, owner, "hurtServer", desc, "HEAD", args, true, false);
            if (r.cancelled() && Boolean.FALSE.equals(r.returnValue())) return -1;
            if (r.cancelled()) return amount;
        }
        syncInventories();
        return amount;
    }

    /** A damage type by id as a DamageSource (generic if it isn't one). */
    private static net.minecraft.world.damagesource.DamageSource damageSource(ServerLevel level, String type) {
        try {
            var key = net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(type));
            return new net.minecraft.world.damagesource.DamageSource(
                    level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key));
        } catch (Throwable t) {
            return level.damageSources().generic();
        }
    }

    // ---- Death and respawn ----------------------------------------------------------------

    /**
     * Horizon's player died of {@code cause} (a damage type id): vanilla's LivingEntity.die as
     * mods hook it (ServerPlayer.die and LivingEntity.die's head, dropAllDeathLoot's head and end,
     * Fabric's AFTER_DEATH, then the ends of die). Horizon has dropped the items already: the
     * mirrored inventory is what is left (so a mod can't keep a second copy of them).
     */
    private static void died(ServerPlayer player, String cause) {
        ServerLevel level = (ServerLevel) player.level();
        net.minecraft.world.damagesource.DamageSource source;
        try {
            var key = net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(cause));
            source = new net.minecraft.world.damagesource.DamageSource(
                    level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key));
        } catch (Throwable t) {
            source = level.damageSources().generic();
        }
        net.minecraft.world.damagesource.DamageSource from = source;
        Object[] dying = {from};
        String die = "(Lnet/minecraft/world/damagesource/DamageSource;)V";
        hook(player, ServerPlayer.class, "die", die, "HEAD", dying);
        hook(player, net.minecraft.world.entity.LivingEntity.class, "die", die, "HEAD", dying);
        Object[] loot = {level, from};
        String dropLoot = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V";
        hook(player, net.minecraft.world.entity.LivingEntity.class, "dropAllDeathLoot", dropLoot, "HEAD", loot);
        hook(player, net.minecraft.world.entity.LivingEntity.class, "dropAllDeathLoot", dropLoot, "RETURN", loot);
        hook(player, net.minecraft.world.entity.LivingEntity.class, "dropAllDeathLoot", dropLoot, "TAIL", loot);
        fire("ServerLivingEntityEvents.AFTER_DEATH", () ->
                net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.invoker().afterDeath(player, from));
        hook(player, net.minecraft.world.entity.LivingEntity.class, "die", die, "RETURN", dying);
        hook(player, net.minecraft.world.entity.LivingEntity.class, "die", die, "TAIL", dying);
        hook(player, ServerPlayer.class, "die", die, "RETURN", dying);
        hook(player, ServerPlayer.class, "die", die, "TAIL", dying);
    }

    /**
     * Horizon's dead player respawned: vanilla makes a new ServerPlayer and copies the old one in
     * (ServerPlayer.restoreFrom); the mirror keeps one, so the copy is from itself (mods' copies
     * of their own data keep it), then Fabric's COPY_FROM and AFTER_RESPAWN.
     */
    private static void respawned(ServerPlayer player) {
        Object[] restoring = {player, false};
        String restoreFrom = "(Lnet/minecraft/server/level/ServerPlayer;Z)V";
        hook(player, ServerPlayer.class, "restoreFrom", restoreFrom, "HEAD", restoring);
        hook(player, ServerPlayer.class, "restoreFrom", restoreFrom, "RETURN", restoring);
        hook(player, ServerPlayer.class, "restoreFrom", restoreFrom, "TAIL", restoring);
        fire("ServerPlayerEvents.COPY_FROM", () ->
                net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.COPY_FROM.invoker().copyFromPlayer(player, player, false));
        fire("ServerPlayerEvents.AFTER_RESPAWN", () ->
                net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(player, player, false));
    }

    // ---- The mods' event-like Mixins (HorizonHooks) ---------------------------------------

    private static final String PLACE_NEW_PLAYER =
            "(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V";
    private static final String REMOVE = "(Lnet/minecraft/server/level/ServerPlayer;)V";
    private static final String TICK_SERVER = "(Ljava/util/function/BooleanSupplier;)V";
    private static final Object[] NO_ARGUMENTS = {};
    private static final Map<UUID, net.minecraft.network.Connection> CONNECTIONS = new ConcurrentHashMap<>();

    /** Fires the mods' injections (not Fabric API's: Horizon fires its events) at a point. */
    private static HorizonHooks.Result hook(Object self, Class<?> owner, String method, String desc, String at, Object[] args) {
        if (!HorizonHooks.hooked(owner.getName().replace('.', '/'), method)) return HorizonHooks.Result.NONE;
        return HorizonHooks.fire(self, owner, method, desc, at, args, null, true);
    }

    /** A player's network connection, as mods are handed it (a mirror: Horizon has the real one). */
    private static net.minecraft.network.Connection mirrorConnection(ServerPlayer player) {
        return CONNECTIONS.computeIfAbsent(player.getUUID(), u -> allocate(net.minecraft.network.Connection.class));
    }

    /**
     * The players who would see a point of a mirrored level (Fabric's PlayerLookup.tracking):
     * the level's players within the server's view distance of it, but {@code self} (a player
     * doesn't track itself).
     */
    public static java.util.Collection<ServerPlayer> tracking(ServerLevel level, double x, double z, Object self) {
        List<ServerPlayer> players = LEVEL_PLAYERS.get(level);
        if (players == null || players.isEmpty()) return List.of();
        int view = server.getPlayerList().getViewDistance();
        double reach = (view > 0 ? view : 12) * 16.0 + 8;
        List<ServerPlayer> seen = new ArrayList<>();
        for (ServerPlayer player : new ArrayList<>(players)) {
            if (player == self) continue;
            if (Math.abs(player.getX() - x) <= reach && Math.abs(player.getZ() - z) <= reach) seen.add(player);
        }
        return seen;
    }

    /** ServerChunkCache.blockChanged on a mirrored level: its block entity is saved with the others. */
    public static void blockChanged(Level level, net.minecraft.core.BlockPos pos) {
        if (level == null) return;
        HorizonBlockEntities.changed(level, pos);
        // Vanilla's chunk holder sends the block's update and its block entity's update packet
        // (Create's sendData goes this way, not through sendBlockUpdated): the client's copy loads it.
        if (level instanceof ServerLevel server && clientHalf != Thread.currentThread()) {
            HorizonClientBlockEntities.updated(server, pos);
        }
    }

    /** The mirrored server's services (HorizonServices). */
    public static net.minecraft.server.Services services() {
        return HorizonServices.get();
    }

    /** A packet sender that sends nothing (Fabric networking to a client with no mod channels). */
    public static PacketSender noPackets() {
        return NO_PACKETS;
    }

    private static final PacketSender NO_PACKETS = (PacketSender) Proxy.newProxyInstance(
            PacketSender.class.getClassLoader(),
            new Class<?>[] {PacketSender.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return "Horizon's packet sender (none yet)";
                    default: return null;
                }
            });

    /**
     * Runs each listener of a Fabric event on its own (in the event's phase order), so a mod
     * that throws does not stop the others. Vanilla's server would crash on that throw; the
     * mirror goes on, and through the invoker one mod failing each tick (Carry On, Forge Config
     * API Port in a 40-mod pack) silently cut off every listener registered after it. Only for
     * events with no result: those with one keep their invoker (it combines the results).
     */
    @SuppressWarnings("unchecked")
    static <T> void fireEach(String event, net.fabricmc.fabric.api.event.Event<T> source, java.util.function.Consumer<T> call) {
        Object[] handlers = listeners(source);
        if (handlers == null) {
            fire(event, () -> call.accept(source.invoker()));
            return;
        }
        // Client events: a device mod's listeners run only on its player's own machine.
        boolean client = event.startsWith("Client");
        for (Object handler : handlers) {
            if (client && !HorizonDeviceMods.runs(handler.getClass())) continue;
            String name = handler.getClass().getName();
            int lambda = name.indexOf("$$");
            fire(event + " [" + (lambda > 0 ? name.substring(0, lambda) : name) + "]", () -> call.accept((T) handler));
        }
    }

    private static java.lang.reflect.Field HANDLERS;

    /** An array-backed Fabric event's listeners, or null if it is some other kind. */
    static Object[] listeners(net.fabricmc.fabric.api.event.Event<?> source) {
        try {
            if (HANDLERS == null) {
                Class<?> backed = Class.forName("net.fabricmc.fabric.impl.base.event.ArrayBackedEvent", false, source.getClass().getClassLoader());
                java.lang.reflect.Field field = backed.getDeclaredField("handlers");
                field.setAccessible(true);
                HANDLERS = field;
            }
            if (!HANDLERS.getDeclaringClass().isInstance(source)) return null;
            return (Object[]) HANDLERS.get(source);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Runs a mod event, logging (not throwing) what a mod throws, ten times per event at most. */
    /** True the first time {@code what} fails (a failure logged once). */
    static boolean failedOnce(String what) {
        return FAILURES.merge(what, 1, Integer::sum) == 1;
    }

    static void fire(String event, Runnable run) {
        try {
            run.run();
        } catch (Throwable t) {
            int count = FAILURES.merge(event, 1, Integer::sum);
            if (count <= 3) {
                // The innermost cause: mods wrap their failures.
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                Natives.log("A mod failed in " + event + ": " + root);
                HorizonFailures.report(event, root);
                StackTraceElement[] frames = root.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 12); f++) {
                    if (frames[f].getClassName().startsWith("dev.horizonlab.platform")) break;
                    Natives.log("    at " + frames[f]);
                }
            }
        }
    }

    /**
     * One server tick, called by Horizon (horizon-jvm's events.rs) at the tick's end.
     *
     * @param world Horizon's world folder
     * @param motion five values per player: x, y, z, yaw, pitch
     * @param permissions per player: vanilla's permission level, 0 to 4, in the low 8 bits; bit 8 the sneak key;
     *     bits 16-22 the movement keys (PlayerInput's flags)
     * @param kinds per event: 0 joined, 1 left, 2 chat, 3 broke a block, 4 placed a block, 5 died
     *     (texts: the damage type), 6 respawned
     * @param who per event: the player's UUID
     * @param texts per event: the chat line, or the block state ("minecraft:oak_log[axis=y]")
     * @param positions three per event: the block's x, y, z
     * @param inventories 41 per player, vanilla's Inventory slots as SNBT ("" empty), all null
     *     for a player whose inventory didn't change
     */
    public static void tick(long tick, boolean dedicated, String world, String[] ids, String[] names, String[] dimensions,
                            double[] motion, int[] modes, float[] health, float[] food, int[] permissions,
                            int[] kinds, String[] who, String[] texts, int[] positions,
                            String[] inventories) {
        long started = System.nanoTime();
        try {
            tickBody(tick, dedicated, world, ids, names, dimensions, motion, modes, health, food, permissions, kinds, who,
                    texts, positions, inventories);
        } catch (RuntimeException | Error e) {
            // Rust logs only the message ("a tick failed: …"): where it failed, once a kind.
            if (TICK_FAILURES.add(e.getClass().getName())) {
                java.io.StringWriter trace = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(trace));
                Natives.log("Java mods: a tick failed: " + trace);
            }
            throw e;
        } finally {
            tickTime(tick, System.nanoTime() - started);
        }
    }

    /** The kinds of exceptions a tick failed with, each traced once (tick). */
    private static final java.util.Set<String> TICK_FAILURES = ConcurrentHashMap.newKeySet();

    /**
     * MinecraftServer.tickServer's record of its ticks' times (tickTimesNanos, the average and
     * the smoothed time), which the mirror's server never ran: this tick's Java work. Mods read
     * them (TACZ's shots look back by the average tick time; its array was null).
     */
    private static void tickTime(long tick, long nanos) {
        MinecraftServer server = HorizonServer.server;
        if (server == null) return;
        try {
            long[] times = server.getTickTimesNanos();
            if (times == null) {
                times = new long[100];
                java.util.Arrays.fill(times, 50_000_000L);
                HorizonClient.set(MinecraftServer.class, server, "tickTimesNanos", times);
                HorizonClient.set(MinecraftServer.class, server, "aggregatedTickTimesNanos", 50_000_000L * times.length);
                HorizonClient.set(MinecraftServer.class, server, "smoothedTickTimeMillis", 50.0F);
            }
            int i = (int) Math.floorMod(tick, (long) times.length);
            long sum = (long) HorizonClient.field(MinecraftServer.class, server, "aggregatedTickTimesNanos") - times[i] + nanos;
            times[i] = nanos;
            HorizonClient.set(MinecraftServer.class, server, "aggregatedTickTimesNanos", sum);
            float smoothed = (float) HorizonClient.field(MinecraftServer.class, server, "smoothedTickTimeMillis");
            HorizonClient.set(MinecraftServer.class, server, "smoothedTickTimeMillis", smoothed * 0.8F + nanos / 1_000_000.0F * 0.19999999F);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Left as it was.
        }
    }

    private static void tickBody(long tick, boolean dedicated, String world, String[] ids, String[] names, String[] dimensions,
                                 double[] motion, int[] modes, float[] health, float[] food, int[] permissions,
                                 int[] kinds, String[] who, String[] texts, int[] positions,
                                 String[] inventories) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        serverThread = Thread.currentThread();
        try {
            if (server == null) {
                setUp(dedicated);
                HorizonHooks.scan();
            }
        } catch (Throwable t) {
            Natives.log("Horizon's server mirror could not be set up: " + t);
            for (StackTraceElement frame : t.getStackTrace()) Natives.log("    at " + frame);
            return;
        }
        MinecraftServer server = HorizonServer.server;
        long phase = HorizonProfile.start();
        world(world);
        // The server's tick count follows Horizon's (mods time their tasks with it).
        ((MinecraftServerAccess) server).horizon$setTickCount((int) tick);
        // The headless client's tick (its frame, then its game tick), before the server's.
        if (client) HorizonClient.clientTick();
        phase = HorizonProfile.add("client tick", phase);
        if (!started) {
            started = true;
            // The constructor's hooks (biome modifications) come before the server starts, as
            // in vanilla, even when no dimension's generation has asked for them yet.
            HorizonWorldgen.readyBeforeTicks(seed);
            fireEach("ServerLifecycleEvents.SERVER_STARTING", ServerLifecycleEvents.SERVER_STARTING, l -> l.onServerStarting(server));
            // MinecraftServer.loadLevel (runServer's initServer): the levels exist at its end.
            hook(server, MinecraftServer.class, "loadLevel", "()V", "HEAD", NO_ARGUMENTS);
            for (String dimension : levelIds()) {
                level(dimension);
            }
            // MinecraftServer.createLevels: Fabric's LOAD for each (a level made before
            // this first tick too); later levels hear it as they are made (level()).
            for (ServerLevel made : new ArrayList<>(LEVELS.values())) levelLoaded(made);
            hook(server, MinecraftServer.class, "loadLevel", "()V", "RETURN", NO_ARGUMENTS);
            hook(server, MinecraftServer.class, "loadLevel", "()V", "TAIL", NO_ARGUMENTS);
            fireEach("ServerLifecycleEvents.SERVER_STARTED", ServerLifecycleEvents.SERVER_STARTED, l -> l.onServerStarted(server));
        }
        // MinecraftServer.tickServer's head (Fabric's START_SERVER_TICK is its own injection there).
        Object[] haveTime = {(java.util.function.BooleanSupplier) () -> true};
        hook(server, MinecraftServer.class, "tickServer", TICK_SERVER, "HEAD", haveTime);
        // Players' age in ticks (Entity.tick's tickCount++, which mirrored players don't run):
        // mods time their actions by it (Carry On won't pick up on the tick it last acted).
        for (ServerPlayer p : BY_UUID.values()) p.tickCount++;
        // Their item cooldowns (Player.tick's): a mod item's use that starts one (Create's belt
        // connector: 5 ticks after its first shaft) was otherwise never usable again.
        for (ServerPlayer p : BY_UUID.values()) fire("ItemCooldowns.tick", () -> p.getCooldowns().tick());
        // Their held uses of mods' items (LivingEntity.tick's updatingUsingItem: sandpaper).
        for (ServerPlayer p : BY_UUID.values()) tickUse(p);
        fireEach("ServerTickEvents.START_SERVER_TICK", ServerTickEvents.START_SERVER_TICK, l -> l.onStartTick(server));
        for (ServerLevel level : new ArrayList<>(LEVELS.values())) {
            fireEach("ServerTickEvents.START_LEVEL_TICK", ServerTickEvents.START_LEVEL_TICK, l -> l.onStartTick(level));
            hook(level, ServerLevel.class, "tick", TICK_SERVER, "HEAD", haveTime);
        }

        closeForwarded();
        currentTick = tick;
        // Horizon made the writes it has applied: its world answers again there. A write made
        // between ticks (a block placed by a player's use) is applied after this tick, so it
        // stays until Horizon's world shows it (a few ticks at most: one Horizon refused goes).
        synchronized (WRITTEN) {
            for (var levelWrites : WRITTEN.entrySet()) {
                String dimension = dimensionOf(levelWrites.getKey());
                levelWrites.getValue().entrySet().removeIf(w -> {
                    BlockPos pos = BlockPos.of(w.getKey());
                    String name = Natives.blockState(dimension, pos.getX(), pos.getY(), pos.getZ());
                    // Not loaded in Horizon yet: the write waits for its chunk there (as
                    // vanilla's setBlock loads it), so mods keep reading it (~20 s at most);
                    // once loaded, Horizon gets its few ticks to make it from then.
                    if (name == null) {
                        WAITING_CHUNK.add(w.getKey());
                        return WRITTEN_AT.getOrDefault(w.getKey(), 0L) < tick - 400;
                    }
                    if (WAITING_CHUNK.remove(w.getKey())) WRITTEN_AT.put(w.getKey(), tick);
                    return parseState(name) == w.getValue() || WRITTEN_AT.getOrDefault(w.getKey(), 0L) < tick - 3;
                });
            }
            WRITTEN.values().removeIf(Map::isEmpty);
            WRITTEN_AT.keySet().removeIf(k -> WRITTEN.values().stream().noneMatch(m -> m.containsKey(k)));
            WAITING_CHUNK.retainAll(WRITTEN_AT.keySet());
            anyWritten = !WRITTEN.isEmpty();
        }

        // Mirror the players: those still on, as Horizon gives them.
        Map<UUID, ServerPlayer> now = new LinkedHashMap<>();
        for (int i = 0; i < ids.length; i++) {
            try {
                ServerPlayer player = mirrorPlayer(i, ids, names, dimensions, motion, modes, health, food, permissions, inventories, tick);
                now.put(player.getUUID(), player);
            } catch (Throwable t) {
                if (FAILURES.merge("mirroring players", 1, Integer::sum) <= 10) {
                    Natives.log("Mirroring player " + names[i] + " failed: " + t);
                    StackTraceElement[] frames = t.getStackTrace();
                    for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
                }
            }
        }

        for (int e = 0; e < kinds.length; e++) {
            UUID uuid = UUID.fromString(who[e]);
            ServerPlayer player = KNOWN.get(uuid);
            if (player == null) continue;
            switch (kinds[e]) {
                case 0 -> {
                    PLAYERS.add(player);
                    BY_UUID.put(uuid, player);
                    ServerGamePacketListenerImpl connection = player.connection;
                    ServerLevel level = (ServerLevel) player.level();
                    // PlayerList.placeNewPlayer: the mods' injections at its head, Fabric's
                    // events, then those at its end (Fabric's own injections are its events).
                    Object[] joining = {mirrorConnection(player), player,
                            net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)};
                    // The configuration phase before play: the mods' data their client needs first.
                    HorizonConfiguration.configure(server, player, (net.minecraft.network.Connection) joining[0]);
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "placeNewPlayer", PLACE_NEW_PLAYER, "HEAD", joining);
                    fireEach("ServerPlayConnectionEvents.INIT", ServerPlayConnectionEvents.INIT, l -> l.onPlayInit(connection, server));
                    fireEach("ServerEntityEvents.ENTITY_LOAD", ServerEntityEvents.ENTITY_LOAD, l -> l.onLoad(player, level));
                    fireEach("ServerPlayConnectionEvents.JOIN", ServerPlayConnectionEvents.JOIN, l -> l.onPlayReady(connection, NO_PACKETS, server));
                    // A game joining has none of the mods' dynamic textures yet.
                    if (client) HorizonClient.imagesSent(player.getUUID()).clear();
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "placeNewPlayer", PLACE_NEW_PLAYER, "RETURN", joining);
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "placeNewPlayer", PLACE_NEW_PLAYER, "TAIL", joining);
                }
                case 1 -> {
                    ServerGamePacketListenerImpl connection = player.connection;
                    ServerLevel level = (ServerLevel) player.level();
                    Object[] leaving = {player};
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "remove", REMOVE, "HEAD", leaving);
                    fireEach("ServerPlayConnectionEvents.DISCONNECT", ServerPlayConnectionEvents.DISCONNECT, l -> l.onPlayDisconnect(connection, server));
                    fireEach("ServerEntityEvents.ENTITY_UNLOAD", ServerEntityEvents.ENTITY_UNLOAD, l -> l.onUnload(player, level));
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "remove", REMOVE, "RETURN", leaving);
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "remove", REMOVE, "TAIL", leaving);
                    PLAYERS.remove(player);
                    BY_UUID.remove(uuid);
                    if (client) HorizonClient.left(uuid);
                }
                case 2 -> {
                    PlayerChatMessage message = PlayerChatMessage.unsigned(uuid, texts[e]);
                    fire("ServerMessageEvents.CHAT_MESSAGE", () -> ServerMessageEvents.CHAT_MESSAGE.invoker().onChatMessage(message, player, null));
                }
                case 3 -> {
                    BlockPos pos = new BlockPos(positions[e * 3], positions[e * 3 + 1], positions[e * 3 + 2]);
                    BlockState state = state(texts[e]);
                    Level level = player.level();
                    fire("PlayerBlockBreakEvents.AFTER", () -> PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, player, pos, state, null));
                }
                case 5 -> died(player, texts[e]);
                case 6 -> respawned(player);
                default -> { }
            }
        }
        // A player Horizon lists but never announced (the bridge started late) is on all the same.
        for (Map.Entry<UUID, ServerPlayer> entry : now.entrySet()) {
            if (!BY_UUID.containsKey(entry.getKey())) {
                PLAYERS.add(entry.getValue());
                BY_UUID.put(entry.getKey(), entry.getValue());
            }
        }
        // Each level's players, as its own list (vanilla's code reads level.players()).
        for (ServerLevel level : LEVELS.values()) LEVEL_PLAYERS.computeIfAbsent(level, l -> {
            List<ServerPlayer> list = new ArrayList<>();
            ((ServerLevelAccess) l).horizon$setPlayers(list);
            return list;
        }).clear();
        for (ServerPlayer player : PLAYERS) {
            if (player.level() instanceof ServerLevel level) {
                List<ServerPlayer> list = LEVEL_PLAYERS.get(level);
                if (list != null) list.add(player);
            }
        }

        phase = HorizonProfile.add("players and events", phase);
        for (ServerLevel level : new ArrayList<>(LEVELS.values())) {
            hook(level, ServerLevel.class, "tick", TICK_SERVER, "RETURN", haveTime);
            hook(level, ServerLevel.class, "tick", TICK_SERVER, "TAIL", haveTime);
            fireEach("ServerTickEvents.END_LEVEL_TICK", ServerTickEvents.END_LEVEL_TICK, l -> l.onEndTick(level));
        }
        phase = HorizonProfile.add("level tick hooks", phase);
        HorizonFunctions.tick(server, server.getWorldData().overworldData().getGameTime());
        phase = HorizonProfile.add("functions", phase);
        // Each player's tick (ServerPlayer.tick, Player.tick from doTick), as vanilla's
        // levels and connections run them within the server's tick, with the ticks Player.tick
        // runs inside it (super.tick(): LivingEntity.tick, then Entity.tick) nested as they are:
        // TACZ ticks a player's guns (drawing, cool-downs, reloads) at LivingEntity.tick's end.
        for (ServerPlayer player : new ArrayList<>(PLAYERS)) {
            hook(player, ServerPlayer.class, "tick", "()V", "HEAD", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.player.Player.class, "tick", "()V", "HEAD", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.LivingEntity.class, "tick", "()V", "HEAD", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.Entity.class, "tick", "()V", "HEAD", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.Entity.class, "tick", "()V", "RETURN", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.Entity.class, "tick", "()V", "TAIL", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.LivingEntity.class, "tick", "()V", "RETURN", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.LivingEntity.class, "tick", "()V", "TAIL", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.player.Player.class, "tick", "()V", "RETURN", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.player.Player.class, "tick", "()V", "TAIL", NO_ARGUMENTS);
            hook(player, ServerPlayer.class, "tick", "()V", "RETURN", NO_ARGUMENTS);
            hook(player, ServerPlayer.class, "tick", "()V", "TAIL", NO_ARGUMENTS);
        }
        phase = HorizonProfile.add("player tick hooks", phase);
        // Mod blocks' scheduled ticks and block entities, as their levels' ticks run them.
        // A frozen world (`/tick freeze`) runs none of these, as vanilla's levels don't
        // (ServerLevel.tick and Level.tickBlockEntities ask runsNormally()).
        for (ServerLevel level : running ? new ArrayList<>(LEVELS.values()) : List.<ServerLevel>of()) {
            if (level.getBlockTicks() instanceof HorizonTicks<?> ticks) ticks.run(level);
            // The mods' fluids flow here (HorizonTicks: their scheduled fluid ticks).
            if (level.getFluidTicks() instanceof HorizonTicks<?> ticks) ticks.run(level);
        }
        phase = HorizonProfile.add("scheduled ticks", phase);
        // Horizon's items on and in the mods' blocks (HorizonItems: a depot catching one).
        HorizonItems.touchBlocks();
        playerMotionsBefore();
        if (running) HorizonBlockEntities.tick();
        playerMotionsAfter();
        phase = HorizonProfile.add("block entities", phase);
        HorizonChunkLoads.tick();
        HorizonTickets.tick(new ArrayList<>(LEVELS.values()));
        HorizonSavedData.tick();
        HorizonDataFixer.check();
        phase = HorizonProfile.add("tickets, saved data", phase);
        // Mods' entities, as ServerLevel.tick's entity loop runs them.
        if (running) HorizonEntities.tick();
        phase = HorizonProfile.add("entities", phase);
        if (running) HorizonSpawner.tick();
        phase = HorizonProfile.add("spawner", phase);
        // What the mods did to Horizon's items this tick, to Horizon.
        HorizonItems.report();
        // ServerPlayer.tick and doTick: a menu no longer valid closes, and the open menu sends
        // its changes (a mod's menu keeps its block working while it is watched, and syncs its
        // own data there: Tom's Storage's terminal its item list).
        for (ServerPlayer player : new ArrayList<>(PLAYERS)) {
            var menu = player.containerMenu;
            if (menu == null || menu == player.inventoryMenu || !SHOWN.containsKey(player.getUUID())) continue;
            fire("AbstractContainerMenu.stillValid", () -> {
                if (!menu.stillValid(player)) player.closeContainer();
            });
            if (player.containerMenu == menu) fire("AbstractContainerMenu.broadcastChanges", menu::broadcastChanges);
        }
        phase = HorizonProfile.add("menus", phase);
        // Mods' own screens: each changed frame to its player.
        if (client) HorizonClient.sendFrames();
        phase = HorizonProfile.add("screens", phase);
        fireEach("ServerTickEvents.END_SERVER_TICK", ServerTickEvents.END_SERVER_TICK, l -> l.onEndTick(server));
        hook(server, MinecraftServer.class, "tickServer", TICK_SERVER, "RETURN", haveTime);
        hook(server, MinecraftServer.class, "tickServer", TICK_SERVER, "TAIL", haveTime);
        phase = HorizonProfile.add("end tick hooks", phase);
        runTasks();
        phase = HorizonProfile.add("tasks", phase);
        syncInventories();
        HorizonProfile.add("sync", phase);
        HorizonProfile.tick();
    }

    /**
     * A mod moved a mirrored player: the mirror is there now, and Horizon is told to move the
     * real player (its own /tp, across levels too).
     */
    public static void teleported(ServerPlayer player, ServerLevel level, net.minecraft.world.entity.PositionMoveRotation to) {
        if (player.level() != level) ((EntityAccess) player).horizon$setLevel(level);
        player.setPosRaw(to.position().x, to.position().y, to.position().z);
        player.setYRot(to.yRot());
        player.setXRot(to.xRot());
        player.setYHeadRot(to.yRot());
        player.setYBodyRot(to.yRot());
        Natives.teleport(player.getStringUUID(), level.dimension().identifier().toString(),
                to.position().x, to.position().y, to.position().z, to.yRot(), to.xRot());
    }

    private static final Map<String, Integer> DROPPED = new ConcurrentHashMap<>();

    /** A packet a mod sent a mirrored player that Horizon doesn't take yet: logged once per type. */
    /** Per player: the window of a menu a mod opened that Horizon now shows instead. */
    private static final Map<UUID, Integer> FORWARDED = new ConcurrentHashMap<>();

    /** A mod's menu for {@code player} (window {@code containerId}) went to Horizon. */
    public static void forwarded(ServerPlayer player, int containerId) {
        FORWARDED.put(player.getUUID(), containerId);
    }

    public static boolean isForwarded(ServerPlayer player, int containerId) {
        Integer window = FORWARDED.get(player.getUUID());
        return window != null && containerId >= 0 && window == containerId;
    }

    /**
     * Closes the mirror's copies of the menus Horizon shows (AbstractContainerMenu.removed),
     * so the next menu a mod opens starts from the player's inventory, as vanilla's do.
     */
    private static void closeForwarded() {
        for (var entry : new ArrayList<>(FORWARDED.entrySet())) {
            ServerPlayer player = KNOWN.get(entry.getKey());
            FORWARDED.remove(entry.getKey());
            if (player == null || player.containerMenu == player.inventoryMenu) continue;
            if (player.containerMenu.containerId != entry.getValue()) continue;
            syncing = true;
            try {
                player.doCloseContainer();
            } catch (Throwable t) {
                Natives.log("Closing a mod's menu failed: " + t);
            } finally {
                syncing = false;
            }
        }
    }

    // ---- Mods' menus Horizon shows ---------------------------------------------------------

    /** Menus Horizon runs itself when a mod opens one (the rest are shown, run here). */
    public static boolean horizonMenu(String type, String title) {
        return switch (type) {
            case "minecraft:crafting", "minecraft:stonecutter", "minecraft:grindstone", "minecraft:loom",
                 "minecraft:cartography_table", "minecraft:smithing" -> true;
            case "minecraft:generic_9x3" -> title != null && title.contains("container.enderchest");
            default -> false;
        };
    }

    /** Menu types a guest's game draws, with their container slots. */
    private static final Map<String, Integer> DRAWN = Map.of(
            "minecraft:generic_9x1", 9, "minecraft:generic_9x2", 18, "minecraft:generic_9x3", 27,
            "minecraft:generic_9x4", 36, "minecraft:generic_9x5", 45, "minecraft:generic_9x6", 54,
            "minecraft:generic_3x3", 9, "minecraft:hopper", 5, "minecraft:shulker_box", 27);

    /**
     * A mod's menu a player has open, as Horizon shows it: the menu type the player's game
     * draws, and for each of its slots the menu's slot (-1: none), the container's first, then
     * the player's 36 as vanilla's menus have them (inventory rows, then the hotbar).
     */
    private record Shown(int containerId, String type, String title, int[] slots) {}

    private record Opening(int containerId, String type, String title, net.minecraft.network.chat.Component text) {}

    private static final Map<UUID, Opening> OPENING = new ConcurrentHashMap<>();
    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SHOWN_CONTENT = new ConcurrentHashMap<>();
    /** Players whose window is sent again whole, changed or not (a click the menu refused). */
    private static final Set<UUID> RESEND = ConcurrentHashMap.newKeySet();

    /** ClientboundOpenScreenPacket of a mod's menu: shown once the menu is the player's. */
    public static void menuOpened(ServerPlayer player, int containerId, String type, String title) {
        menuOpened(player, containerId, type, title, net.minecraft.network.chat.Component.empty());
    }

    public static void menuOpened(ServerPlayer player, int containerId, String type, String title,
                                  net.minecraft.network.chat.Component text) {
        OPENING.put(player.getUUID(), new Opening(containerId, type, title, text));
    }

    public static boolean isShown(ServerPlayer player, int containerId) {
        Shown shown = SHOWN.get(player.getUUID());
        Opening opening = OPENING.get(player.getUUID());
        return containerId >= 0 && ((shown != null && shown.containerId == containerId)
                || (opening != null && opening.containerId == containerId));
    }

    private static Shown layout(net.minecraft.world.inventory.AbstractContainerMenu menu, Opening opening) {
        List<Integer> own = new ArrayList<>();
        int[] player = new int[36];
        java.util.Arrays.fill(player, -1);
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                int k = slot.getContainerSlot();
                // The shown order: inventory 9-35, then the hotbar 0-8.
                if (k >= 9 && k < 36) player[k - 9] = i;
                else if (k >= 0 && k < 9) player[27 + k] = i;
            } else {
                own.add(i);
            }
        }
        String type = opening.type;
        Integer drawn = DRAWN.get(type);
        if (drawn == null || drawn != own.size()) {
            // A menu the game can't draw (a cooking pot's): its slots in a chest's rows.
            int rows = Math.max(1, Math.min(6, (own.size() + 8) / 9));
            type = "minecraft:generic_9x" + rows;
            drawn = rows * 9;
        }
        int[] slots = new int[drawn + 36];
        java.util.Arrays.fill(slots, -1);
        for (int i = 0; i < Math.min(drawn, own.size()); i++) slots[i] = own.get(i);
        System.arraycopy(player, 0, slots, drawn, 36);
        return new Shown(opening.containerId, type, opening.title, slots);
    }

    /**
     * Each mirrored player's menu, after anything that may change it: a mod's menu just opened
     * is shown, one closed (or replaced) is closed, and a shown one's slots go to Horizon when
     * they changed (a cooking pot's progress, a click).
     */
    private static void syncMenus() {
        for (ServerPlayer player : new ArrayList<>(BY_UUID.values())) {
            UUID id = player.getUUID();
            var menu = player.containerMenu;
            Opening opening = OPENING.get(id);
            if (opening != null && menu != null && menu.containerId == opening.containerId) {
                OPENING.remove(id);
                Shown shown = layout(menu, opening);
                SHOWN.put(id, shown);
                // The mod's own screen, in Horizon's headless client (its frames: sendFrames).
                if (client) HorizonClient.openScreen(player, menu, opening.text());
                SHOWN_CONTENT.remove(id);
            }
            Shown shown = SHOWN.get(id);
            if (shown == null) continue;
            if (menu == null || menu == player.inventoryMenu || menu.containerId != shown.containerId) {
                SHOWN.remove(id);
                SHOWN_CONTENT.remove(id);
                if (client) HorizonClient.closeScreen(id);
                Natives.menuContent(player.getStringUUID(), null, null, null, null);
                continue;
            }
            String[] items = new String[shown.slots.length];
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < items.length; i++) {
                int at = shown.slots[i];
                items[i] = at >= 0 && at < menu.slots.size() ? snbt(menu.slots.get(at).getItem()) : null;
                all.append(items[i]).append('\n');
            }
            String carried = snbt(menu.getCarried());
            all.append(carried);
            String now = all.toString();
            if (now.equals(SHOWN_CONTENT.get(id)) && !RESEND.remove(id)) continue;
            boolean first = !SHOWN_CONTENT.containsKey(id);
            SHOWN_CONTENT.put(id, now);
            Natives.menuContent(player.getStringUUID(), first ? shown.type : null, first ? shown.title : null,
                    items, carried);
        }
    }

    /**
     * A click in a shown menu's window (ServerboundContainerClickPacket): the menu's own
     * AbstractContainerMenu.clicked, on the slot behind the shown one.
     */
    public static void menuClick(String uuid, int slot, int button, int input) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return;
        Shown shown = SHOWN.get(player.getUUID());
        var menu = player.containerMenu;
        if (shown == null || menu == null || menu.containerId != shown.containerId) return;
        int at = slot == -999 || slot == -1 ? slot : slot >= 0 && slot < shown.slots.length ? shown.slots[slot] : -2;
        if (at == -2 || (at == -1 && slot != -1)) {
            SHOWN_CONTENT.remove(player.getUUID()); // the window has it wrong: send it again
        } else if (input >= 0 && input < net.minecraft.world.inventory.ContainerInput.values().length) {
            var type = net.minecraft.world.inventory.ContainerInput.values()[input];
            // The player's game guessed the click's result: whatever the menu made of it (a
            // slot it refuses, a cooking pot's meal), the window is sent again as it is.
            RESEND.add(player.getUUID());
            fire("AbstractContainerMenu.clicked", () -> {
                if (menu.stillValid(player)) {
                    menu.clicked(at, button, type, player);
                    menu.broadcastChanges();
                } else {
                    player.closeContainer();
                }
            });
        }
        syncInventories();
    }

    /** After input to a mod's screen: what its clicks changed reaches Horizon. */
    static void afterScreenInput() {
        syncInventories();
    }

    /** The player closed a shown menu's window (ServerboundContainerClosePacket). */
    public static void menuClose(String uuid) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return;
        Shown shown = SHOWN.remove(player.getUUID());
        SHOWN_CONTENT.remove(player.getUUID());
        if (client) HorizonClient.closeScreen(player.getUUID());
        if (shown != null && player.containerMenu != null && player.containerMenu.containerId == shown.containerId) {
            fire("ServerPlayer.doCloseContainer", player::doCloseContainer);
        }
        syncInventories();
    }

    /**
     * Whether this JVM runs the headless client (the game's own, not a dedicated server's): set
     * by HorizonClient as it boots. Without it no client class is touched (a server has none).
     */
    static volatile boolean client;

    /**
     * The thread running the client half of a player's action now (HorizonClient.useItem), for
     * which mirrored levels answer isClientSide() true (LevelMixin); null otherwise.
     */
    public static volatile Thread clientHalf;

    /** Whether the mods' messages loop between their two sides here (HorizonLoop). */
    public static boolean loop() {
        return client;
    }

    /** After a mod's client message ran its server code: its changes go out as a click's do. */
    static void afterLoop() {
        syncInventories();
    }

    /** True while Horizon's own state is copied in: the packets that sends are Horizon's already. */
    private static boolean syncing;

    /**
     * A mod's payload for a mirrored player: to the mod's own client code when it receives it
     * (HorizonLoop); else Fabric API's menu opening (ExtendedMenuProvider's) is shown as
     * vanilla's are; the rest is dropped.
     */
    public static void payload(ServerPlayer player,
                               net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        Class<?> c = payload.getClass();
        // Fabric API's menu opening is Horizon's to show (its own screens, below), never the
        // client's Fabric code's: that would make the client's menu from a client's level.
        boolean opening = c.getName().equals("net.fabricmc.fabric.impl.menu.Networking$OpenScreenPayload");
        // The mod's own client code takes its messages (the headless client's receivers).
        if (!opening && client && HorizonLoop.toClient(player, payload)) return;
        if (opening) {
            try {
                Object type = c.getMethod("identifier").invoke(payload);
                int containerId = (int) c.getMethod("containerId").invoke(payload);
                Object title = c.getMethod("title").invoke(payload);
                // Its opening data, for the client's own menu (HorizonClient.clientMenu).
                HorizonClient.openingData(player, containerId, c.getMethod("innerCodec").invoke(payload), c.getMethod("data").invoke(payload));
                menuOpened(player, containerId, String.valueOf(type),
                        json((net.minecraft.network.chat.Component) title), (net.minecraft.network.chat.Component) title);
                return;
            } catch (ReflectiveOperationException e) {
                Natives.log("Java mods: a menu's opening could not be read: " + e);
            }
        }
        dropped(payload);
    }

    public static void dropped(Object packet) {
        if (syncing) return;
        // Horizon sends its own blocks: a mod's resync (a cancelled break) is already done.
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket) return;
        String type = packet.getClass().getSimpleName();
        if (DROPPED.merge(type, 1, Integer::sum) == 1) {
            Natives.log("A mod sent a player a " + type + "; Horizon doesn't send those yet");
        }
    }

    // ---- Commands -------------------------------------------------------------------------

    private static com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher() {
        HorizonData.Loaded data = HorizonData.loaded;
        return data == null ? null : data.reloadable().getCommands().getDispatcher();
    }

    /**
     * Every command the server's dispatcher has, vanilla's and the mods' (Fabric API's command
     * callback registers the mods' when the server's data loads). Horizon takes the names that
     * aren't vanilla's.
     */
    public static String[] commandNames() {
        var dispatcher = dispatcher();
        if (dispatcher == null) return new String[0];
        var names = new java.util.TreeSet<String>();
        dispatcher.getRoot().getChildren().forEach(n -> names.add(n.getName()));
        // The mods' client commands too: the players' games are Horizon's, which send every
        // command here (HorizonClientCommands).
        var client = HorizonClientCommands.dispatcher();
        if (client != null) client.getRoot().getChildren().forEach(n -> names.add(n.getName()));
        return names.toArray(String[]::new);
    }

    /**
     * These commands as a root of the data generator's commands report, the format Horizon's
     * command tree is in: {@code {"type": "root", "children": {...}}}. An argument of a type a
     * vanilla client doesn't know (a mod's own) is sent as a greedy string: the client then
     * still parses the line and Horizon's server checks it.
     */
    public static String commandTree(String[] names) {
        var dispatcher = dispatcher();
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("type", "root");
        com.google.gson.JsonObject children = new com.google.gson.JsonObject();
        if (dispatcher != null) {
            var client = HorizonClientCommands.dispatcher();
            for (String name : names) {
                var node = dispatcher.getRoot().getChild(name);
                if (node == null && client != null && client.getRoot().getChild(name) != null) {
                    try {
                        com.google.gson.JsonObject json = net.minecraft.commands.synchronization.ArgumentUtils.serializeNodeToJson(
                                client, client.getRoot().getChild(name));
                        vanillaParsers(json);
                        children.add(name, json);
                    } catch (Throwable t) {
                        // An argument type of the mod's own (Flashback's colour): the command
                        // with one greedy argument, the line checked when it runs.
                        children.add(name, anyArguments());
                    }
                    continue;
                }
                if (node == null) continue;
                try {
                    com.google.gson.JsonObject json = net.minecraft.commands.synchronization.ArgumentUtils.serializeNodeToJson(dispatcher, node);
                    vanillaParsers(json);
                    children.add(name, json);
                } catch (Throwable t) {
                    Natives.log("Command /" + name + " can't be sent to players: " + t);
                }
            }
        }
        root.add("children", children);
        return root.toString();
    }

    /** A literal that runs alone or with anything after it (a greedy string). */
    private static com.google.gson.JsonObject anyArguments() {
        var literal = new com.google.gson.JsonObject();
        literal.addProperty("type", "literal");
        literal.addProperty("executable", true);
        var argument = new com.google.gson.JsonObject();
        argument.addProperty("type", "argument");
        argument.addProperty("parser", "brigadier:string");
        var properties = new com.google.gson.JsonObject();
        properties.addProperty("type", "greedy");
        argument.add("properties", properties);
        argument.addProperty("executable", true);
        var children = new com.google.gson.JsonObject();
        children.add("arguments", argument);
        literal.add("children", children);
        return literal;
    }

    private static void vanillaParsers(com.google.gson.JsonObject node) {
        if (node.has("parser")) {
            String parser = node.get("parser").getAsString();
            if (!parser.startsWith("minecraft:") && !parser.startsWith("brigadier:")) {
                node.addProperty("parser", "brigadier:string");
                com.google.gson.JsonObject properties = new com.google.gson.JsonObject();
                properties.addProperty("type", "greedy");
                node.add("properties", properties);
            }
        }
        if (node.has("children")) {
            for (var entry : node.getAsJsonObject("children").entrySet()) {
                vanillaParsers(entry.getValue().getAsJsonObject());
            }
        }
    }

    /**
     * While a command runs for the mods' entities only (runForEntities): selectors find no
     * players (EntitySelectorMixin), Horizon's run of the command has them.
     */
    public static final ThreadLocal<Boolean> ENTITIES_ONLY = ThreadLocal.withInitial(() -> false);

    /**
     * A command with {@code @e} or {@code @n} that Horizon runs for its own entities: run here
     * too, quietly, for the mods' entities (a {@code /kill @e} kills theirs as well).
     */
    public static void runForEntities(String uuid, String line) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = KNOWN.get(UUID.fromString(uuid));
        HorizonData.Loaded data = HorizonData.loaded;
        if (player == null || data == null || !BY_UUID.containsKey(player.getUUID())) return;
        ENTITIES_ONLY.set(true);
        try {
            data.reloadable().getCommands().performPrefixedCommand(player.createCommandSourceStack().withSuppressedOutput(), line);
        } catch (Throwable t) {
            // Horizon's run of it says what went wrong.
        } finally {
            ENTITIES_ONLY.set(false);
        }
        syncInventories();
    }

    /**
     * Runs a command line (no slash) as a player, as vanilla's server runs a player's command:
     * their command source, the dispatcher's parse and errors. What the command says comes
     * back through the player's system messages.
     *
     * @return false when the player isn't on Horizon's server (yet)
     */
    public static boolean command(String uuid, String line) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = KNOWN.get(UUID.fromString(uuid));
        HorizonData.Loaded data = HorizonData.loaded;
        if (player == null || data == null || !BY_UUID.containsKey(player.getUUID())) return false;
        if (HorizonClientCommands.owns(line, data.reloadable().getCommands().getDispatcher())) {
            HorizonClientCommands.run(player, line);
            syncInventories();
            return true;
        }
        try {
            if (DEBUG_COMMANDS) debugCommand(data, player, line);
            data.reloadable().getCommands().performPrefixedCommand(player.createCommandSourceStack(), line);
        } catch (Throwable t) {
            Natives.tell(uuid, chatJson(net.minecraft.network.chat.Component.literal("An error occurred while running /" + line + ": " + t)));
            fire("command /" + line, () -> { throw new RuntimeException(t); });
        }
        syncInventories();
        return true;
    }

    /**
     * HORIZON_JAVA_DEBUG=commands: each command also runs once straight through the dispatcher
     * first, so an exception vanilla would catch and hide ("An unexpected error occurred")
     * is logged with its stack. The command then runs twice: for finding a mod's needs only.
     */
    private static final boolean DEBUG_HOOKS = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("hooks");
    private static final boolean DEBUG_COMMANDS = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("commands");

    private static void debugCommand(HorizonData.Loaded data, ServerPlayer player, String line) {
        try {
            var dispatcher = data.reloadable().getCommands().getDispatcher();
            dispatcher.execute(dispatcher.parse(line, player.createCommandSourceStack()));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            Natives.log("debug /" + line + ": " + e.getMessage());
        } catch (Throwable t) {
            Natives.log("debug /" + line + ": " + t);
            for (StackTraceElement frame : t.getStackTrace()) {
                if (frame.getClassName().startsWith("com.mojang.brigadier")) break;
                Natives.log("    at " + frame);
            }
        }
    }

    private static BlockState state(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (Exception e) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
    }

    /** A text component as JSON (ComponentSerialization.CODEC), or null for none. */
    /**
     * A chat line for Horizon (Natives.tell, broadcast): the component as JSON, which Horizon
     * resolves with its own language, mods' lang files included (the server side's Language
     * is vanilla's English only: a mod's translatable line came out as its key).
     */
    public static String chatJson(net.minecraft.network.chat.Component component) {
        if (component == null) return "\"\"";
        String json = json(component);
        return json != null ? json : new com.google.gson.JsonPrimitive(component.getString()).toString();
    }

    public static String json(net.minecraft.network.chat.Component component) {
        if (component == null) return null;
        try {
            var ops = server.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
            return net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(ops, component)
                    .getOrThrow().toString();
        } catch (Throwable t) {
            // The plain text, if the component won't encode.
            return new com.google.gson.JsonPrimitive(component.getString()).toString();
        }
    }

    // ---- Blocks ---------------------------------------------------------------------------

    /** Block states by their names, parsed once (BlockStateParser). */
    private static final Map<String, BlockState> STATES = new ConcurrentHashMap<>();
    /** Per level: what mods set since Horizon last had the world, so they read their writes. */
    private static final Map<ServerLevel, Map<Long, BlockState>> WRITTEN = new IdentityHashMap<>();
    /** Whether WRITTEN holds anything (read without its lock on every block read). */
    private static volatile boolean anyWritten;
    /** The tick each pending write was made in (writes Horizon refuses go after a few). */
    private static final Map<Long, Long> WRITTEN_AT = new java.util.HashMap<>();
    /** Pending writes whose chunk Horizon hasn't loaded yet (their few ticks start at its load). */
    private static final java.util.Set<Long> WAITING_CHUNK = new java.util.HashSet<>();
    private static volatile long currentTick;

    /** The mirror's tick count (each server tick), for what is kept a tick. */
    static long tickCount() {
        return currentTick;
    }

    /** Horizon's tick count, as the last mirror tick had it. */
    static long currentTick() {
        return currentTick;
    }

    static String dimensionOf(Level level) {
        return level.dimension().identifier().toString();
    }

    /** {@code minecraft:oak_log[axis=y]} as a BlockState; null if it isn't one. */
    public static BlockState parseState(String name) {
        if (name == null) return null;
        BlockState known = STATES.get(name);
        if (known != null) return known;
        try {
            BlockState state = net.minecraft.commands.arguments.blocks.BlockStateParser
                    .parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, name, false).blockState();
            if (STATES.size() < 65536) STATES.put(name, state);
            return state;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A mirrored level's block: the call's own write, else Horizon's, else air (unloaded). */
    public static BlockState blockState(Level level, BlockPos pos) {
        if (anyWritten) synchronized (WRITTEN) {
            Map<Long, BlockState> written = WRITTEN.get(level);
            if (written != null) {
                BlockState mine = written.get(pos.asLong());
                if (mine != null) return mine;
            }
        }
        Section section = section(level, pos.getX(), pos.getY(), pos.getZ());
        if (section == null) {
            // A mod's own thread (VoxelMap's map, JourneyMap's tasks) can't read Horizon's world:
            // the chunks its client was given (HorizonChunks.held) answer.
            if (SECTIONS.get().noView && level instanceof ServerLevel server) {
                BlockState held = HorizonChunks.heldBlock(server, pos);
                if (held != null) return held;
            }
            // A chunk Horizon hasn't loaded that a mod made load (HorizonWorldgen.standIn).
            if (level instanceof ServerLevel server) {
                BlockState standIn = HorizonWorldgen.standInBlock(server, pos);
                if (standIn != null) return standIn;
            }
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        HorizonProfile.cached();
        return section.get((pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15));
    }

    /** Whether Horizon has the block at x y z loaded (in this thread's view). */
    public static boolean loaded(Level level, int x, int y, int z) {
        return section(level, x, y, z) != null;
    }

    /**
     * A section of Horizon's world as read: per block (y, z, x) an index into its palette, or
     * Horizon's state id itself (no palette: Natives.sectionIds, named once by stateById).
     */
    private record Section(int[] index, BlockState[] palette) {
        BlockState get(int i) {
            return palette != null ? palette[index[i]] : stateById(index[i]);
        }
    }

    /** Horizon's state ids as states, named the first time each is seen (Natives.stateName). */
    private static volatile BlockState[] BY_ID = new BlockState[0];

    private static BlockState stateById(int id) {
        BlockState[] known = BY_ID;
        if (id >= 0 && id < known.length && known[id] != null) return known[id];
        BlockState state = parseState(Natives.stateName(id));
        if (state == null) state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        if (id >= 0 && id < 1 << 20) {
            synchronized (HorizonServer.class) {
                BlockState[] grown = BY_ID.length > id ? BY_ID.clone() : java.util.Arrays.copyOf(BY_ID, Math.max(id + 1, BY_ID.length * 2));
                grown[id] = state;
                BY_ID = grown;
            }
        }
        return state;
    }

    /**
     * What this thread read of Horizon's world in the view it was lent: whole 16x16x16
     * sections (Natives.sectionIds, or chunkBlocks on a section's height where the view can't),
     * NONE where Horizon has no chunk. A mob's tick reads the same blocks over and over (its
     * collisions, fluids, paths): one JNI call a section instead of one a block. Good while the
     * view stays (Natives.threadEpoch); a call's own writes are WRITTEN's, read before.
     */
    private static final class Sections {
        long epoch = Long.MIN_VALUE;
        /** The last section read (most reads are near the one before). */
        Level lastLevel;
        long lastKey;
        Section last;
        final Map<Level, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Section>> levels = new IdentityHashMap<>();
        /** This view's chunks' revisions (Natives.chunkRevision), asked once a chunk. */
        final Map<Level, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap> revisions = new IdentityHashMap<>();
        /** This thread has no view of Horizon's world (a mod's own thread). */
        boolean noView;
        /** This thread's view counter, read in place (Natives.threadEpochBuffer); null: ask. */
        final java.nio.ByteBuffer epochBuffer = epochBuffer();

        long epoch() {
            return epochBuffer != null ? epochBuffer.getLong(0) : Natives.threadEpoch();
        }

        private static java.nio.ByteBuffer epochBuffer() {
            try {
                java.nio.ByteBuffer b = Natives.threadEpochBuffer();
                return b == null ? null : b.order(java.nio.ByteOrder.nativeOrder());
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static final Section NONE = new Section(new int[0], new BlockState[0]);
    private static final ThreadLocal<Sections> SECTIONS = ThreadLocal.withInitial(Sections::new);

    /** A section read in an earlier view, with its chunk's revision then. */
    private record Kept(long revision, Section section) {}

    /**
     * Sections read in earlier views, kept while their chunk's revision stays (Horizon's
     * chunks number their blocks' changes: Chunk::block_revision): a mob standing in the same
     * place reads its sections once, not once a tick.
     */
    private static final Map<Level, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Kept>> KEPT = new IdentityHashMap<>();

    /** The last thread's Sections (most reads come from the server thread, one after another). */
    private record Owned(Thread thread, Sections sections) {}

    private static volatile Owned lastOwned;

    private static Section section(Level level, int x, int y, int z) {
        Owned owned = lastOwned;
        Thread thread = Thread.currentThread();
        Sections sections;
        if (owned != null && owned.thread() == thread) {
            sections = owned.sections();
        } else {
            sections = SECTIONS.get();
            lastOwned = new Owned(thread, sections);
        }
        long epoch = sections.epoch();
        if (epoch != sections.epoch) {
            sections.levels.clear();
            sections.revisions.clear();
            sections.epoch = epoch;
            sections.last = null;
            sections.noView = false;
        }
        // A thread with no view of Horizon's world (a mod's own: VoxelMap's) reads nothing.
        if (sections.noView) return null;
        long key = net.minecraft.core.SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        if (sections.lastLevel == level && sections.lastKey == key && sections.last != null) {
            return sections.last == NONE ? null : sections.last;
        }
        var map = sections.levels.computeIfAbsent(level, l -> new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>());
        Section section = map.get(key);
        if (section != null) {
            sections.lastLevel = level;
            sections.lastKey = key;
            sections.last = section;
            return section == NONE ? null : section;
        }
        String dimension = dimensionOf(level);
        var revisions = sections.revisions.computeIfAbsent(level, l -> {
            var m = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
            m.defaultReturnValue(Long.MIN_VALUE);
            return m;
        });
        long chunk = net.minecraft.world.level.ChunkPos.pack(x >> 4, z >> 4);
        long revision = revisions.get(chunk);
        if (revision == Long.MIN_VALUE) {
            revision = Natives.chunkRevision(dimension, x >> 4, z >> 4);
            revisions.put(chunk, revision);
        }
        if (revision == -2) {
            map.put(key, NONE);
            return null;
        }
        if (revision >= 0) {
            synchronized (KEPT) {
                var kept = KEPT.get(level);
                Kept was = kept == null ? null : kept.get(key);
                if (was != null && was.revision() == revision) section = was.section();
            }
        }
        if (section == null) {
            long started = HorizonProfile.ON ? System.nanoTime() : 0L;
            section = fetch(level, dimension, x, y, z);
            if (revision == -1 && section == NONE) {
                // No view on this thread: nothing of Horizon's world can be read here.
                sections.noView = true;
                return null;
            }
            if (HorizonProfile.ON) HorizonProfile.read(System.nanoTime() - started);
            if (revision >= 0 && section != NONE) {
                synchronized (KEPT) {
                    var kept = KEPT.computeIfAbsent(level, l -> new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>());
                    if (kept.size() > 65536) kept.clear();
                    kept.put(key, new Kept(revision, section));
                }
            }
        } else {
            HorizonProfile.reused();
        }
        map.put(key, section);
        return section == NONE ? null : section;
    }

    /** A section from Horizon (Natives.sectionIds, or chunkBlocks where the view can't). */
    private static Section fetch(Level level, String dimension, int x, int y, int z) {
        int[] indices = new int[4096];
        int got = Natives.sectionIds(dimension, x >> 4, y >> 4, z >> 4, indices);
        if (got == 1) return new Section(indices, null);
        if (got == 0) return NONE;
        String[] palette = Natives.chunkBlocks(dimension, x >> 4, z >> 4, y & ~15, indices);
        if (palette == null) return NONE;
        BlockState[] parsed = new BlockState[palette.length];
        for (int i = 0; i < palette.length; i++) {
            BlockState state = parseState(palette[i]);
            parsed[i] = state != null ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        return new Section(indices, parsed);
    }

    /** A mod's Level.setBlock on a mirrored level: Horizon makes it; reads see it at once. */
    public static boolean setBlock(Level level, BlockPos pos, BlockState state) {
        if (level.isOutsideBuildHeight(pos)) return false;
        // A client half's prediction (a door opened on both sides): the server's change is the
        // one made, as a client's predicted blocks give way to the server's.
        if (clientHalf == Thread.currentThread()) return true;
        synchronized (WRITTEN) {
            WRITTEN.computeIfAbsent((ServerLevel) level, l -> new java.util.HashMap<>()).put(pos.asLong(), state);
            anyWritten = true;
            WRITTEN_AT.put(pos.asLong(), currentTick);
        }
        HorizonChunks.set((ServerLevel) level, pos, state);
        Natives.setBlock(dimensionOf(level), pos.getX(), pos.getY(), pos.getZ(),
                net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state));
        return true;
    }

    /** The blocks this call set on a mirrored level, Horizon not having made them yet (a copy). */
    static Map<Long, BlockState> written(Level level) {
        synchronized (WRITTEN) {
            Map<Long, BlockState> written = WRITTEN.get(level);
            return written == null ? null : new java.util.HashMap<>(written);
        }
    }

    /**
     * A mod spawned an entity in a mirrored level: items become Horizon's, mods' own entities
     * live in the level (HorizonEntities); vanilla's others, not yet.
     */
    public static boolean addEntity(Level level, net.minecraft.world.entity.Entity entity) {
        if (clientHalf == Thread.currentThread()) return false;
        if (HorizonEntities.modded(entity.getType())) return HorizonEntities.add((ServerLevel) level, entity);
        if (entity instanceof net.minecraft.world.entity.item.ItemEntity item && !item.getItem().isEmpty()) {
            // Kept to stand for Horizon's item once it is listed (HorizonItems: what the mod set
            // on it stays).
            HorizonItems.spawned((ServerLevel) level, item);
            var motion = item.getDeltaMovement();
            Natives.dropItem(dimensionOf(level), item.getX(), item.getY(), item.getZ(),
                    motion.x, motion.y, motion.z,
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString(),
                    item.getItem().getCount());
            return true;
        }
        // An experience orb (a routed break's ore, a mod's reward): Horizon's orb of that value
        // (dropItem with the item "#experience_orb", the value as its count).
        if (entity instanceof net.minecraft.world.entity.ExperienceOrb orb && orb.getValue() > 0) {
            var motion = orb.getDeltaMovement();
            Natives.dropItem(dimensionOf(level), orb.getX(), orb.getY(), orb.getZ(), motion.x, motion.y, motion.z,
                    "#experience_orb", orb.getValue());
            return true;
        }
        // A vanilla mob (Carry On putting a carried cow down): Horizon's own, from its NBT.
        if (HorizonVanillaActions.spawnVanilla((ServerLevel) level, entity)) return true;
        dropped(entity);
        return false;
    }

    /**
     * A level event of a mirrored level: Horizon plays it. 2001's data is a state, sent by name.
     * A mirrored player as its source (ServerLevel.levelEvent's broadcast leaves the source out:
     * a routed break's 2001, which the breaker's game played already) is sent by UUID, and
     * Horizon leaves that player out too.
     */
    public static void levelEvent(ServerLevel level, net.minecraft.world.entity.Entity source, int type, BlockPos pos, int data) {
        String state = null;
        if (type == 2001) { // LevelEvent.PARTICLES_DESTROY_BLOCK: data is Block.getId(state)
            state = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(
                    net.minecraft.world.level.block.Block.stateById(data));
        }
        String except = source instanceof ServerPlayer player && BY_UUID.get(player.getUUID()) == player
                ? player.getUUID().toString() : null;
        Natives.levelEvent(dimensionOf(level), type, pos.getX(), pos.getY(), pos.getZ(), data, state, except);
    }

    /**
     * A player uses a block (right-click), called by Horizon before it does: vanilla's
     * ServerPlayerGameMode.useItemOn runs on the mirrored player up to the end of the mods'
     * injections at its head (GameModeMixin). 0: no mod took the click (Horizon uses the
     * block); 1: a mod took it; 2: a mod took it and the arm swings.
     */
    public static int useBlock(String uuid, String dimension, int x, int y, int z, int face,
                               double hitX, double hitY, double hitZ, int hand, int selected) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return 0;
        ServerLevel level = level(dimension);
        if (selected >= 0 && selected < 9) player.getInventory().setSelectedSlot(selected);
        var interaction = hand == 1 ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        // The click already ended in a mod's use (see CLICK_TAKEN): the rest of it is taken too.
        if (clickTaken(player, level)) return 1;
        var hit = new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(hitX, hitY, hitZ),
                net.minecraft.core.Direction.from3DDataValue(face), new BlockPos(x, y, z), false);
        // The client's half first, as a vanilla client clicks (MultiPlayerGameMode.useItemOn
        // before its packet): a mod's client code may end the click there (a machine arm's
        // points chosen by right-clicking blocks), and the server never hears of it.
        if (client) {
            var first = HorizonClient.useBlockFirst(player, interaction, hit);
            syncInventories();
            if (first != null) {
                if (first.consumesAction()) CLICK_TAKEN.put(player.getUUID(), level.getGameTime());
                return first instanceof net.minecraft.world.InteractionResult.Success success && success.shouldSwing() ? 2 : 1;
            }
        }
        net.minecraft.world.InteractionResult[] result = {net.minecraft.world.InteractionResult.PASS};
        fire("ServerPlayerGameMode.useItemOn", () -> result[0] =
                player.gameMode.useItemOn(player, level, player.getItemInHand(interaction), interaction, hit));
        if (DEBUG_USE) Natives.log("use: " + player.getItemInHand(interaction) + " on " + blockState(level, hit.getBlockPos())
                + " at " + x + " " + y + " " + z + " (face " + hit.getDirection() + "): " + result[0]);
        syncInventories();
        // The client half (MultiPlayerGameMode.useItemOn) of using a mod's block: it opens its
        // screen there (Chunk Loaders' chunk grid). After the server's, as useItem's.
        if (client && HorizonBlockEntities.modded(blockState(level, hit.getBlockPos()))) {
            HorizonClient.useBlock(player, interaction, hit);
            syncInventories();
        }
        if (!result[0].consumesAction() && !(result[0] instanceof net.minecraft.world.InteractionResult.Fail)) return 0;
        if (result[0].consumesAction()) CLICK_TAKEN.put(player.getUUID(), level.getGameTime());
        return result[0] instanceof net.minecraft.world.InteractionResult.Success success && success.shouldSwing() ? 2 : 1;
    }

    /**
     * Per player, the game time of the last click a mod's block use consumed. Vanilla's client
     * ends a click at the first hand whose use consumes (MultiPlayerGameMode.useItemOn, then
     * useItem); Horizon's client can't run a mod block's use to know, so it sends the click's
     * other packets too (the off hand's useItemOn, both hands' useItem). They are what a vanilla
     * client never sends: an empty off hand on a Create depot took back the ingot the main hand
     * had just put on it. Within 4 ticks of a consumed use they are taken, doing nothing.
     */
    private static final Map<UUID, Long> CLICK_TAKEN = new ConcurrentHashMap<>();

    private static boolean clickTaken(ServerPlayer player, ServerLevel level) {
        Long at = CLICK_TAKEN.get(player.getUUID());
        if (at == null) return false;
        // The click's packets may come ticks apart on a busy server; a vanilla client uses again
        // 4 ticks later at the soonest (Minecraft.rightClickDelay).
        if (level.getGameTime() - at < 4) return true;
        CLICK_TAKEN.remove(player.getUUID());
        return false;
    }

    /**
     * A player uses the item in a hand on nothing (right-click in the air: a compass, a
     * scroll), called by Horizon before it does: vanilla's ServerPlayerGameMode.useItem on the
     * mirrored player, its mods' injections first (Fabric's UseItemCallback), the item's own
     * use for a mod's item (GameModeMixin: vanilla's items are Horizon's). Results as useBlock's.
     */
    public static int useItem(String uuid, String dimension, int hand, int selected, float yaw, float pitch) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return 0;
        ServerLevel level = level(dimension);
        if (selected >= 0 && selected < 9) player.getInventory().setSelectedSlot(selected);
        // handleUseItem's rotation: where the client looked as it clicked.
        player.setYRot(yaw);
        player.setXRot(pitch);
        if (clickTaken(player, level)) return 1;
        var interaction = hand == 1 ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        // A mod's item in use here already: the click is the button still held (see tickUse).
        if (stillHeld(player, interaction, level)) return 1;
        net.minecraft.world.InteractionResult[] result = {net.minecraft.world.InteractionResult.PASS};
        fire("ServerPlayerGameMode.useItem", () -> result[0] =
                player.gameMode.useItem(player, level, player.getItemInHand(interaction), interaction));
        // A mod's food or drink (Item.use → Consumable.startConsuming: Farmer's Delight's meals,
        // its bacon) started being used on the mirrored player, which is never ticked: its
        // updateUsingItem never counted down and nothing was eaten, while Horizon, told the
        // click was taken, never started eating either (the beta tester's bowl meal: the
        // animation on and on, hunger unchanged). The use is Horizon's: its consume runs the
        // item's components as the content build carried them (food, consume effects, the
        // bowl as use_remainder), with the mods' injections at the head of useItem already run.
        if (handOverConsumable(player, interaction)) {
            syncInventories();
            return 0;
        }
        // Any other mod item's use over time (sandpaper polishing what the other hand holds, a
        // mod's bow drawn) stays here: tickUse runs it while the button is held.
        if (player.isUsingItem() && player.getUsedItemHand() == interaction && modItem(player.getUseItem())) {
            USE_HELD.put(player.getUUID(), level.getGameTime());
            if (DEBUG_USE) Natives.log("use: " + BuiltInRegistries.ITEM.getKey(player.getUseItem().getItem()) + " used over "
                    + player.getUseItemRemainingTicks() + " ticks here");
        }
        syncInventories();
        // The client half (MultiPlayerGameMode.useItem): a mod's item opens its screen there
        // (Nature's Compass, guide books). It runs after the server's, so what the server sent
        // the client for it (delivered at syncInventories) is in.
        if (client && result[0] != null && modItem(player.getItemInHand(interaction))) {
            var clientResult = HorizonClient.useItem(player, interaction);
            syncInventories();
            if (!result[0].consumesAction() && clientResult != null && clientResult.consumesAction()) result[0] = clientResult;
        }
        if (!result[0].consumesAction() && !(result[0] instanceof net.minecraft.world.InteractionResult.Fail)) return 0;
        return result[0] instanceof net.minecraft.world.InteractionResult.Success success && success.shouldSwing() ? 2 : 1;
    }

    /**
     * Whether the mirrored player is now using a mod's consumable held in {@code hand}
     * (useItem started it): the use is stopped here and left to Horizon, which times and
     * finishes it with the item's components. A cancelled or refused use (a mod's
     * UseItemCallback, a full player) never started, and stays the mirror's answer.
     */
    private static boolean handOverConsumable(ServerPlayer player, net.minecraft.world.InteractionHand hand) {
        if (!player.isUsingItem() || player.getUsedItemHand() != hand) return false;
        ItemStack using = player.getUseItem();
        if (!modItem(using) || !using.has(net.minecraft.core.component.DataComponents.CONSUMABLE)) return false;
        if (DEBUG_USE) Natives.log("use: " + BuiltInRegistries.ITEM.getKey(using.getItem()) + " eaten by Horizon ("
                + using.getUseDuration(player) + " ticks)");
        player.stopUsingItem();
        return true;
    }

    private static final boolean DEBUG_USE = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("use");

    /**
     * Per player using a mod's item over time on the mirror (not a consumable: Horizon eats
     * those), the game time its game last asked to use it. Horizon's game doesn't know such an
     * item is used over time, so while the button is held it asks again every 4 ticks
     * (Minecraft.rightClickDelay) and never sends the release. A vanilla client would hold the
     * use and send RELEASE_USE_ITEM on letting go.
     */
    private static final Map<UUID, Long> USE_HELD = new ConcurrentHashMap<>();
    /** Ticks with no new ask after which the button was let go (asks come every 4). */
    private static final int USE_LET_GO = 6;

    /** Whether this use is the held button of the mod item's use already running: noted. */
    private static boolean stillHeld(ServerPlayer player, net.minecraft.world.InteractionHand hand, ServerLevel level) {
        if (!USE_HELD.containsKey(player.getUUID()) || !player.isUsingItem() || player.getUsedItemHand() != hand) return false;
        if (!ItemStack.isSameItem(player.getItemInHand(hand), player.getUseItem())) return false;
        USE_HELD.put(player.getUUID(), level.getGameTime());
        return true;
    }

    private static java.lang.reflect.Method updatingUsingItem;

    /**
     * A mirrored player's use of a mod's item, one tick: LivingEntity.updatingUsingItem (the
     * item's onUseTick, and at its end finishUsingItem: what it makes goes to Horizon with the
     * slots), as LivingEntity.tick runs it; once the button is let go (no ask for USE_LET_GO
     * ticks), ServerPlayer.releaseUsingItem (the item's releaseUsing), as RELEASE_USE_ITEM does.
     */
    private static void tickUse(ServerPlayer player) {
        Long held = USE_HELD.get(player.getUUID());
        if (held == null) return;
        if (!player.isUsingItem()) {
            USE_HELD.remove(player.getUUID());
            return;
        }
        long now = player.level().getGameTime();
        if (now - held > USE_LET_GO) {
            if (DEBUG_USE) Natives.log("use: let go after " + player.getTicksUsingItem() + " ticks");
            fire("ServerPlayer.releaseUsingItem", player::releaseUsingItem);
            USE_HELD.remove(player.getUUID());
            return;
        }
        fire("LivingEntity.updatingUsingItem", () -> {
            try {
                if (updatingUsingItem == null) {
                    updatingUsingItem = net.minecraft.world.entity.LivingEntity.class.getDeclaredMethod("updatingUsingItem");
                    updatingUsingItem.setAccessible(true);
                }
                updatingUsingItem.invoke(player);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new RuntimeException(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        if (!player.isUsingItem()) {
            if (DEBUG_USE) Natives.log("use: finished");
            USE_HELD.remove(player.getUUID());
        }
    }

    /** Whether a stack is a mod's item (vanilla's are Horizon's, both halves). */
    private static boolean modItem(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return false;
        return !"minecraft".equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
    }

    /**
     * ServerLevel.getEntities on a mirrored level (it has no entity manager): the level's
     * mirrored players and mods' entities, so a mod's checks for what stands in a block's way
     * see them, and mods' mobs find their targets.
     */
    public static net.minecraft.world.level.entity.LevelEntityGetter<net.minecraft.world.entity.Entity> entities(ServerLevel level) {
        return new net.minecraft.world.level.entity.LevelEntityGetter<>() {
            private List<net.minecraft.world.entity.Entity> all() {
                List<ServerPlayer> players = LEVEL_PLAYERS.get(level);
                if (players == null || players.isEmpty()) return HorizonEntities.of(level);
                List<net.minecraft.world.entity.Entity> all = new ArrayList<>();
                if (players != null) all.addAll(players);
                all.addAll(HorizonEntities.of(level));
                // Copies of Horizon's mobs standing in for a routed attack (HorizonCombat).
                all.addAll(HorizonCombat.visiting(level));
                // Horizon's own mobs near its players (HorizonNeighbours).
                all.addAll(HorizonNeighbours.of(level));
                // Horizon's items near its players (HorizonItems).
                all.addAll(HorizonItems.of(level));
                return all;
            }

            @Override
            public net.minecraft.world.entity.Entity get(int id) {
                for (var e : all()) if (e.getId() == id) return e;
                return null;
            }

            @Override
            public net.minecraft.world.entity.Entity get(UUID uuid) {
                for (var e : all()) if (e.getUUID().equals(uuid)) return e;
                return null;
            }

            @Override
            public Iterable<net.minecraft.world.entity.Entity> getAll() {
                return all();
            }

            @Override
            public <U extends net.minecraft.world.entity.Entity> void get(
                    net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, U> test,
                    net.minecraft.util.AbortableIterationConsumer<U> out) {
                for (var e : all()) {
                    U u = test.tryCast(e);
                    if (u != null && out.accept(u).shouldAbort()) return;
                }
            }

            /** The level's players, then its mods' entities near the box (HorizonEntities.near). */
            private void candidates(net.minecraft.world.phys.AABB box, java.util.function.Consumer<net.minecraft.world.entity.Entity> out) {
                List<ServerPlayer> players = LEVEL_PLAYERS.get(level);
                if (players != null) for (var p : players) out.accept(p);
                HorizonEntities.near(level, box, out);
                // Copies of Horizon mobs standing in the level during a routed call
                // (Better Combat's sweep finds its targets with a box query).
                for (var e : HorizonCombat.visiting(level)) if (e.getBoundingBox().intersects(box)) out.accept(e);
                for (var e : HorizonNeighbours.of(level)) if (e.getBoundingBox().intersects(box)) out.accept(e);
                for (var e : HorizonItems.of(level)) if (e.getBoundingBox().intersects(box)) out.accept(e);
            }

            @Override
            public void get(net.minecraft.world.phys.AABB box, java.util.function.Consumer<net.minecraft.world.entity.Entity> out) {
                List<net.minecraft.world.entity.Entity> found = new ArrayList<>();
                candidates(box, e -> {
                    if (e.getBoundingBox().intersects(box)) found.add(e);
                });
                for (var e : found) out.accept(e);
            }

            @Override
            public <U extends net.minecraft.world.entity.Entity> void get(
                    net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, U> test,
                    net.minecraft.world.phys.AABB box, net.minecraft.util.AbortableIterationConsumer<U> out) {
                List<net.minecraft.world.entity.Entity> found = new ArrayList<>();
                candidates(box, e -> {
                    if (e.getBoundingBox().intersects(box)) found.add(e);
                });
                for (var e : found) {
                    U u = test.tryCast(e);
                    if (u != null && out.accept(u).shouldAbort()) return;
                }
            }
        };
    }

    /**
     * Random ticks Horizon's level gave mods' blocks (ServerLevel.tickChunk's): each still a
     * mod's block that ticks randomly grows as its mod says (BlockState.randomTick).
     */
    /**
     * Java mods' blocks placed or removed in Horizon's world: a block with a block entity has
     * it made now (it ticks from its placement, as vanilla's do); a removed one's goes at its
     * next tick (HorizonBlockEntities.tick).
     */
    public static void blockEntities(String dimension, int[] positions, String previous) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return;
        ServerLevel level = level(dimension);
        String[] before = previous == null ? new String[0] : previous.split("\n", -1);
        java.util.Set<Long> placed = new java.util.HashSet<>();
        for (int i = 0; i + 2 < positions.length; i += 3) {
            BlockPos pos = new BlockPos(positions[i], positions[i + 1], positions[i + 2]);
            fire("BlockEntity (made)", () -> HorizonBlockEntities.ensure(level, pos));
            // What vanilla's LevelChunk.setBlockState does for the block it replaces and the one
            // it sets (the mirror's setBlock and Horizon's world do neither for mods' blocks):
            // the old block's affectNeighborsAfterRemoval, the new one's onPlace (Create's track
            // joins its railway graph there). Once a position a call (its first set's old block).
            int k = i / 3;
            if (k < before.length && !before[k].isEmpty() && placed.add(pos.asLong())) {
                BlockState old = parseState(before[k]);
                BlockState now = level.getBlockState(pos);
                if (old != null && old != now) {
                    if (!old.is(now.getBlock()) && HorizonBlockEntities.modded(old) && ownsVanilla(old.getBlock(), "affectNeighborsAfterRemoval")) {
                        fire("BlockState.affectNeighborsAfterRemoval", () -> old.affectNeighborsAfterRemoval(level, pos, false));
                    }
                    if (HorizonBlockEntities.modded(now) && ownsVanilla(now.getBlock(), "onPlace")) {
                        fire("BlockState.onPlace", () -> now.onPlace(level, pos, old, false));
                    }
                }
            }
            // A mod's fluid placed, or a neighbour of it changed (Horizon tells: the mod's
            // liquid block carries a vanilla template, LevelAccess::foreign_fluid): its tick,
            // as LiquidBlock.onPlace / neighborChanged / updateShape schedule it. The mirror
            // runs it (HorizonTicks), with the fluid's own delay.
            var fluid = level.getFluidState(pos);
            if (modFluid(fluid.getType())) {
                level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
            }
        }
    }

    /** Per block class and method: whether a mod's class in its chain overrides vanilla's. */
    private static final Map<String, Boolean> OWNS = new ConcurrentHashMap<>();

    /** Whether {@code block}'s class (a mod's, up its chain to vanilla's) declares {@code method} itself. */
    static boolean ownsVanilla(net.minecraft.world.level.block.Block block, String method) {
        return OWNS.computeIfAbsent(block.getClass().getName() + "." + method, k -> {
            for (Class<?> c = block.getClass(); c != null && !c.getName().startsWith("net.minecraft."); c = c.getSuperclass()) {
                for (var m : c.getDeclaredMethods()) if (m.getName().equals(method)) return true;
            }
            return false;
        });
    }

    /**
     * Horizon updated the neighbours of these positions (a block set or removed there) with a
     * mod's block among them. The mirror has no neighbour updater of its own (MirrorUpdatesMixin),
     * so the mods' blocks hear it here, as vanilla's updateNeighbourShapes and updateNeighborsAt
     * tell them: the mods' own injections at the head of ServerLevel.updateNeighborsAt (Create's
     * neighbour listeners: a hand crank whose cogwheel broke), then each mod block's shape update
     * toward the position (NeighborUpdater.executeShapeUpdate: it may break, as vanilla's
     * updateOrDestroy does) and its neighborChanged (executeUpdate). Vanilla's blocks are
     * Horizon's.
     */
    public static void neighbours(String dimension, int[] positions) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return;
        ServerLevel level = level(dimension);
        if (level == null) return;
        for (int i = 0; i + 2 < positions.length; i += 3) {
            BlockPos from = new BlockPos(positions[i], positions[i + 1], positions[i + 2]);
            BlockState source = level.getBlockState(from);
            var block = source.getBlock();
            fire("Block (neighbours updated)", () -> HorizonHooks.fireMergedSkipping(level, ServerLevel.class,
                    "updateNeighborsAt", "HEAD", true, from, block));
            for (var direction : net.minecraft.world.level.redstone.NeighborUpdater.UPDATE_ORDER) {
                BlockPos pos = from.relative(direction);
                BlockState state = level.getBlockState(pos);
                if (!modBlock(state)) continue;
                fire("Block (neighbour changed)", () -> {
                    net.minecraft.world.level.redstone.NeighborUpdater.executeShapeUpdate(level, direction.getOpposite(),
                            pos, from, source, net.minecraft.world.level.block.Block.UPDATE_ALL, 511);
                    BlockState now = level.getBlockState(pos);
                    if (now == state) {
                        net.minecraft.world.level.redstone.NeighborUpdater.executeUpdate(level, now, pos, block, null, false);
                    }
                });
            }
        }
    }

    /** Whether a block state is a mod's (its behaviour runs here; vanilla's is Horizon's). */
    static boolean modBlock(BlockState state) {
        return !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft");
    }

    /** Whether a fluid is a mod's (its flow is the mirror's; vanilla's water and lava are Horizon's). */
    static boolean modFluid(net.minecraft.world.level.material.Fluid fluid) {
        return fluid != net.minecraft.world.level.material.Fluids.EMPTY
                && !BuiltInRegistries.FLUID.getKey(fluid).getNamespace().equals("minecraft");
    }

    /**
     * The mods' fluid blocks (a LiquidBlock of a mod's fluid: BuildCraft's oil, Create's honey):
     * Horizon leaves their flow to the mirror, which runs the fluid's own FlowingFluid.tick.
     */
    public static String[] modFluidBlocks() {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        List<String> out = new ArrayList<>();
        for (var block : BuiltInRegistries.BLOCK) {
            if (block instanceof net.minecraft.world.level.block.LiquidBlock
                    && modFluid(block.defaultBlockState().getFluidState().getType())) {
                out.add(BuiltInRegistries.BLOCK.getKey(block).toString());
            }
        }
        return out.toArray(String[]::new);
    }

    private static java.lang.reflect.Method shouldSpreadLiquid;

    /**
     * Lava whose fluid interactions are the mods' (they patch LiquidBlock.shouldSpreadLiquid:
     * Create's own registry of them, honey and chocolate with lava), placed or next to a change
     * in Horizon's block tick: vanilla's shouldSpreadLiquid runs here, the mods' injections
     * first, as LiquidBlock.onPlace and neighborChanged call it. What it sets (obsidian,
     * cobblestone, a mod's stone) is the mirror's write; Horizon scheduled the lava's tick.
     */
    public static void liquidInteractions(String dimension, int[] positions) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return;
        ServerLevel level = level(dimension);
        try {
            if (shouldSpreadLiquid == null) {
                shouldSpreadLiquid = net.minecraft.world.level.block.LiquidBlock.class.getDeclaredMethod("shouldSpreadLiquid",
                        net.minecraft.world.level.Level.class, BlockPos.class, BlockState.class);
                shouldSpreadLiquid.setAccessible(true);
            }
        } catch (ReflectiveOperationException e) {
            Natives.log("Java mods: LiquidBlock.shouldSpreadLiquid not found: " + e);
            return;
        }
        for (int i = 0; i + 2 < positions.length; i += 3) {
            BlockPos pos = new BlockPos(positions[i], positions[i + 1], positions[i + 2]);
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock liquid)) continue;
            fire("LiquidBlock.shouldSpreadLiquid", () -> {
                try {
                    shouldSpreadLiquid.invoke(liquid, level, pos, state);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw new RuntimeException(e.getCause());
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            });
            if (TICK_DEBUG) Natives.log("fluid interaction " + pos + ": " + state + " -> " + level.getBlockState(pos));
        }
    }

    public static void randomTicks(String dimension, int[] positions) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return;
        ServerLevel level = level(dimension);
        for (int i = 0; i + 2 < positions.length; i += 3) {
            BlockPos pos = new BlockPos(positions[i], positions[i + 1], positions[i + 2]);
            BlockState state = level.getBlockState(pos);
            if (!state.isRandomlyTicking()) continue;
            if (!HorizonBlockEntities.modded(state) && !randomTickRouted(state.getBlock())) continue;
            fire("BlockState.randomTick", () -> state.randomTick(level, pos, level.getRandom()));
            if (TICK_DEBUG) Natives.log("random tick " + pos + ": " + state + " -> " + level.getBlockState(pos));
        }
        syncInventories();
    }

    // ---- Pickups: items and experience orbs ---------------------------------------------

    private static final String ITEM_ENTITY = "net/minecraft/world/entity/item/ItemEntity";
    private static final String ORB = "net/minecraft/world/entity/ExperienceOrb";
    private static Boolean pickupRouted, orbsRouted;

    /**
     * The actions besides breaking, using and random ticks that Horizon sends here because a
     * mod patches them: "item_pickup" (ItemEntity.playerTouch, Player.take, Inventory.add as a
     * pickup), "experience_orbs" (ExperienceOrb.playerTouch, its repair, the points given:
     * Clumps).
     */
    public static String[] routedActions() {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        List<String> out = new ArrayList<>();
        if (pickupRouted()) out.add("item_pickup");
        if (orbsRouted()) out.add("experience_orbs");
        if (HorizonCombat.routed()) out.add("player_attack");
        if (HorizonVanillaActions.weatherRouted()) out.add("precipitation");
        if (HorizonVanillaActions.fallRouted()) out.add("fall_damage");
        if (HorizonHooks.hooked("net/minecraft/world/entity/LivingEntity", "addEffect")) out.add("effects");
        if (HorizonVanillaActions.jumpsHooked()) out.add("jumps");
        if (HorizonVanillaActions.teleportsHooked()) out.add("teleports");
        if (HorizonVanillaActions.advancementsRouted()) out.add("advancements");
        if (HorizonHooks.hooked("net/minecraft/world/level/block/LiquidBlock", "shouldSpreadLiquid")
                || HorizonMixinTable.patchedInside(net.minecraft.world.level.block.LiquidBlock.class,
                        java.util.Set.of("shouldSpreadLiquid"), java.util.Set.of())) out.add("liquid_interactions");
        if (!out.isEmpty()) {
            Natives.log("Routed to the mods: " + out + " (item pickup: " + HorizonMixinTable.mods(ITEM_ENTITY, "playerTouch")
                    + ", orbs: " + HorizonMixinTable.mods(ORB, "playerTouch") + ")");
        }
        return out.toArray(String[]::new);
    }

    private static boolean pickupRouted() {
        if (pickupRouted == null) {
            pickupRouted = HorizonMixinTable.patchedInside(net.minecraft.world.entity.item.ItemEntity.class,
                    java.util.Set.of("playerTouch"), java.util.Set.of());
        }
        return pickupRouted;
    }

    private static boolean orbsRouted() {
        if (orbsRouted == null) {
            orbsRouted = HorizonMixinTable.patchedInside(net.minecraft.world.entity.ExperienceOrb.class,
                    java.util.Set.of("playerTouch", "repairPlayerItems"), java.util.Set.of());
        }
        return orbsRouted;
    }

    private static java.lang.reflect.Field orbCount;

    /** ExperienceOrb's private count (orbs merged into it): read, or set when {@code set} >= 0. */
    private static int orbCount(net.minecraft.world.entity.ExperienceOrb orb, int set) {
        try {
            if (orbCount == null) {
                orbCount = net.minecraft.world.entity.ExperienceOrb.class.getDeclaredField("count");
                orbCount.setAccessible(true);
            }
            if (set >= 0) orbCount.setInt(orb, set);
            return orbCount.getInt(orb);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A player touches Horizon's experience orb (Horizon's own takeXpDelay is 0): vanilla's
     * ExperienceOrb.playerTouch with the mods' patches (Clumps: all of it at once) on an orb
     * made for the call, the player's experience as Horizon has it. {orbs left of its count,
     * takeXpDelay, level, progress, total}; null when the mirror can't (Horizon does it).
     */
    public static double[] orbTouch(String uuid, String dimension, double x, double y, double z, int value, int count,
                                    int xpLevel, float progress, int total) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return null;
        ServerLevel level = level(dimension);
        double[][] out = {null};
        fire("ExperienceOrb.playerTouch", () -> {
            var orb = new net.minecraft.world.entity.ExperienceOrb(level, x, y, z, value);
            orbCount(orb, Math.max(1, count));
            player.experienceLevel = xpLevel;
            player.experienceProgress = progress;
            player.totalExperience = total;
            player.takeXpDelay = 0;
            orb.playerTouch(player);
            int left = orb.isRemoved() ? 0 : orbCount(orb, -1);
            out[0] = new double[] {left, player.takeXpDelay, player.experienceLevel, player.experienceProgress, player.totalExperience};
        });
        syncInventories();
        return out[0];
    }

    /**
     * A player touches Horizon's dropped item (no pickup delay, the player its target if it has
     * one): vanilla's ItemEntity.playerTouch with the mods' patches on an item entity made for
     * the call. How many of the stack are left (the rest went into the inventory, which comes
     * back as slots); -1 when the mirror can't (Horizon does it).
     */
    public static int itemTouch(String uuid, String dimension, double x, double y, double z, String snbt) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        ItemStack stack = item(snbt);
        if (player == null || stack.isEmpty()) return -1;
        ServerLevel level = level(dimension);
        int[] left = {-1};
        fire("ItemEntity.playerTouch", () -> {
            var entity = new net.minecraft.world.entity.item.ItemEntity(level, x, y, z, stack);
            entity.setNoPickUpDelay();
            entity.playerTouch(player);
            left[0] = entity.isRemoved() ? 0 : entity.getItem().getCount();
        });
        syncInventories();
        return left[0];
    }

    /**
     * A Horizon hopper at a Java mod's block pushes one item of its slots into it
     * (HorizonHoppers.insert): the slot's index, -1 when none fits, -2 when it holds no items.
     */
    public static int hopperInsert(String dimension, int x, int y, int z, int face, String[] stacks) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return -2;
        int[] slot = {-2};
        fire("hopper insert", () -> slot[0] = HorizonHoppers.insert(level(dimension), new BlockPos(x, y, z), face, stacks));
        syncInventories();
        return slot[0];
    }

    /**
     * A Horizon hopper under a Java mod's block pulls one item out of it
     * (HorizonHoppers.extract): its SNBT, "" when none, null when it holds no items.
     */
    /**
     * `/data get block` on a mod's block entity: its NBT as vanilla's command reads it
     * (BlockEntity.saveWithFullMetadata, SNBT), or null where no mod block entity is up.
     */
    public static String blockEntityData(String dimension, int x, int y, int z) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return null;
        String[] out = {null};
        fire("block entity data", () -> {
            ServerLevel level = level(dimension);
            var tag = HorizonBlockEntities.data(level, new BlockPos(x, y, z));
            if (tag != null) out[0] = tag.toString();
        });
        return out[0];
    }

    /**
     * Horizon saves these chunks of {@code dimension} ({@code chunks}: x, z pairs): the mods'
     * block entities in them, as their chunk's {@code block_entities} hold them (SNBT).
     */
    public static String[] chunkBlockEntities(String dimension, int[] chunks) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return new String[0];
        String[][] out = {new String[0]};
        fire("chunk block entities", () -> out[0] = HorizonBlockEntities.chunkData(level(dimension), chunks));
        return out[0];
    }

    /**
     * Mods' block entities Horizon's world loaded with their chunks at {@code positions} (x, y,
     * z triples) of {@code dimension}: made now (LevelChunk.registerAllBlockEntitiesAfterLevelLoad).
     */
    public static void chunkBlockEntitiesLoaded(String dimension, int[] positions) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return;
        ServerLevel level = level(dimension);
        for (int i = 0; i + 2 < positions.length; i += 3) {
            BlockPos pos = new BlockPos(positions[i], positions[i + 1], positions[i + 2]);
            fire("BlockEntity (loaded)", () -> HorizonBlockEntities.chunkLoaded(level, pos));
        }
    }

    /** The chunks whose mods' block entities changed since last asked ("dimension x z" lines). */
    public static String changedBlockEntityChunks() {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return "";
        return HorizonBlockEntities.changedChunks();
    }

    public static String hopperExtract(String dimension, int x, int y, int z, int face, boolean anyRoom, String[] partial) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return null;
        String[] out = {null};
        fire("hopper extract", () -> out[0] = HorizonHoppers.extract(level(dimension), new BlockPos(x, y, z), face, anyRoom, partial));
        syncInventories();
        return out[0];
    }

    /** HORIZON_JAVA_DEBUG=ticks: each random tick the mods run is logged with what it made. */
    private static final boolean TICK_DEBUG = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("ticks");
    private static final Map<net.minecraft.world.level.block.Block, Boolean> TICK_ROUTED = new ConcurrentHashMap<>();

    /** Whether a mod patches this vanilla block's random tick (its class's or a superclass's). */
    static boolean randomTickRouted(net.minecraft.world.level.block.Block block) {
        return TICK_ROUTED.computeIfAbsent(block, b ->
                HorizonMixinTable.patchedInside(b.getClass(), HorizonMixinTable.RANDOM_TICK, java.util.Set.of())
                        // A patch of every block's random tick (BlockStateBase.randomTick: Serene
                        // Seasons' growth by season) is taken for the plants that grow (crops,
                        // saplings, stems, cane...): the rest (grass, leaves, ice) stay Horizon's.
                        || growing(b) && HorizonMixinTable.patchedInside(
                                net.minecraft.world.level.block.state.BlockState.class, java.util.Set.of("randomTick"), java.util.Set.of()));
    }

    /**
     * A plant that grows by random ticks (crop growth): crops, stems, saplings, cocoa, berry
     * bushes, bamboo, cane, cactus, nether wart, chorus, kelp and the other growing plants.
     * Not grass, nylium or moss (bone-mealable, but they spread rather than grow, and there
     * are many of them).
     */
    static boolean growing(net.minecraft.world.level.block.Block b) {
        return b instanceof net.minecraft.world.level.block.CropBlock
                || b instanceof net.minecraft.world.level.block.StemBlock
                || b instanceof net.minecraft.world.level.block.SaplingBlock
                || b instanceof net.minecraft.world.level.block.CocoaBlock
                || b instanceof net.minecraft.world.level.block.SweetBerryBushBlock
                || b instanceof net.minecraft.world.level.block.BambooStalkBlock
                || b instanceof net.minecraft.world.level.block.BambooSaplingBlock
                || b instanceof net.minecraft.world.level.block.GrowingPlantHeadBlock
                || b instanceof net.minecraft.world.level.block.PitcherCropBlock
                || b instanceof net.minecraft.world.level.block.SugarCaneBlock
                || b instanceof net.minecraft.world.level.block.CactusBlock
                || b instanceof net.minecraft.world.level.block.NetherWartBlock
                || b instanceof net.minecraft.world.level.block.ChorusFlowerBlock;
    }

    /**
     * The vanilla blocks whose random ticks Horizon sends here instead of running them: a mod
     * patches their randomTick or what it calls (Serene Seasons' crops). Vanilla's code, with
     * the patches, runs them on Horizon's world.
     */
    public static String[] routedRandomTicks() {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        List<String> out = new ArrayList<>();
        Map<String, java.util.Set<String>> byMods = new java.util.TreeMap<>();
        for (var block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getNamespace().equals("minecraft") || !hasRandomTicks(block)) continue;
            if (!randomTickRouted(block)) continue;
            out.add(id.toString());
            for (Class<?> c = block.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (String m : HorizonMixinTable.RANDOM_TICK) {
                    var mods = HorizonMixinTable.mods(c.getName().replace('.', '/'), m);
                    if (!mods.isEmpty()) byMods.computeIfAbsent(c.getSimpleName() + "." + m, k -> new java.util.TreeSet<>()).addAll(mods);
                }
            }
        }
        var every = HorizonMixinTable.mods("net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase", "randomTick");
        if (!every.isEmpty()) byMods.put("BlockStateBase.randomTick (growing plants)", every);
        if (!out.isEmpty()) Natives.log("Random ticks of " + out.size() + " vanilla blocks go to the mods: " + byMods);
        return out.toArray(String[]::new);
    }

    /** Whether any state of the block ticks randomly (a crop's last age doesn't). */
    private static boolean hasRandomTicks(net.minecraft.world.level.block.Block block) {
        for (BlockState s : block.getStateDefinition().getPossibleStates()) if (s.isRandomlyTicking()) return true;
        return false;
    }

    private static final Map<Level, HorizonLight> LIGHT = new IdentityHashMap<>();

    /** A mirrored level's light: Horizon's (HorizonLight). */
    public static synchronized HorizonLight light(Level level) {
        return LIGHT.computeIfAbsent(level, HorizonLight::new);
    }

    /** A mod's item (not vanilla's). */
    public static boolean moddedItem(ItemStack stack) {
        return !stack.isEmpty()
                && !BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft");
    }

    private static final Map<Class<?>, Boolean> USABLE = new ConcurrentHashMap<>();

    /** Whether a block does something when used (its class has useItemOn or useWithoutItem). */
    public static boolean usable(BlockState state) {
        return USABLE.computeIfAbsent(state.getBlock().getClass(), c -> {
            for (Class<?> k = c; k != null && k != net.minecraft.world.level.block.state.BlockBehaviour.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals("useItemOn") || m.getName().equals("useWithoutItem")) return true;
                }
            }
            return false;
        });
    }

    /** useItemOn's head is where Horizon stops vanilla's body (GameModeMixin): mods' injections there run. */
    private static final java.util.Set<String> USE_FIRED =
            java.util.Set.of("net/minecraft/server/level/ServerPlayerGameMode.useItemOn");
    private static final Map<String, Boolean> USE_ROUTED = new ConcurrentHashMap<>();

    /**
     * Whether using {@code stack} on {@code state} is the mirror's: a mod patches what vanilla's
     * use runs for them (ServerPlayerGameMode.useItemOn past its head, the block's useItemOn or
     * useWithoutItem, the item's useOn or place, bone meal and the crop's growing).
     */
    public static boolean useRouted(BlockState state, ItemStack stack) {
        String key = state.getBlock().getClass().getName() + "|" + stack.getItem().getClass().getName();
        return USE_ROUTED.computeIfAbsent(key, k -> {
            boolean routed = HorizonMixinTable.patchedInside(ServerPlayerGameMode.class, java.util.Set.of("useItemOn"), USE_FIRED)
                    || HorizonMixinTable.patchedInside(state.getBlock().getClass(), HorizonMixinTable.USE_ON, java.util.Set.of())
                    || !stack.isEmpty() && HorizonMixinTable.patchedInside(stack.getItem().getClass(), HorizonMixinTable.USE_ON, java.util.Set.of())
                    || HorizonMixinTable.patchedInside(ItemStack.class, java.util.Set.of("useOn"), java.util.Set.of());
            if (routed) Natives.log("Using " + BuiltInRegistries.ITEM.getKey(stack.getItem()) + " on "
                    + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " goes to the mods (they patch its inside)");
            return routed;
        });
    }

    /**
     * A player starts digging a block (ServerPlayerGameMode.handleBlockBreakAction's START):
     * Fabric's AttackBlockCallback (Veinminer's mining speed for the vein, Carry On stopping a
     * dig while carrying). True when a mod took it (not PASS): Horizon doesn't dig.
     */
    public static boolean attackBlock(String uuid, String dimension, int x, int y, int z, int face, int selected) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return false;
        ServerLevel level = level(dimension);
        if (selected >= 0 && selected < 9) player.getInventory().setSelectedSlot(selected);
        BlockPos pos = new BlockPos(x, y, z);
        var direction = net.minecraft.core.Direction.from3DDataValue(face);
        boolean[] taken = {false};
        fireEach("AttackBlockCallback", net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT, l -> {
            if (taken[0]) return;
            var result = l.interact(player, level, net.minecraft.world.InteractionHand.MAIN_HAND, pos, direction);
            if (result != net.minecraft.world.InteractionResult.PASS) taken[0] = true;
        });
        syncInventories();
        if (DEBUG_HOOKS) Natives.log("hooks: " + player.getName().getString() + " attacks " + level.getBlockState(pos) + " at "
                + pos.toShortString() + (taken[0] ? ": a mod took it" : ""));
        return taken[0];
    }

    private static final String DESTROY_BLOCK = "(Lnet/minecraft/core/BlockPos;)Z";
    /** destroyBlock's injections Horizon fires around its own break (HEAD before, RETURN/TAIL after). */
    private static final java.util.Set<String> BREAK_FIRED =
            java.util.Set.of("net/minecraft/server/level/ServerPlayerGameMode.destroyBlock");
    /** Per block class and held item class: whether a mod patches the inside of breaking it. */
    private static final Map<String, Boolean> BREAK_ROUTED = new ConcurrentHashMap<>();

    /**
     * Whether breaking {@code state} with {@code tool} is the mirror's: a mod patches vanilla's
     * ServerPlayerGameMode.destroyBlock other than at its head or end, or what it calls for
     * this block or tool (Block.playerWillDestroy, playerDestroy, dropResources, getDrops,
     * spawnAfterBreak; ItemStack/Item.mineBlock). Vanilla's code with those patches then breaks
     * it on the mirror, which writes to Horizon's world and drops into it.
     */
    static boolean breakRouted(BlockState state, ItemStack tool) {
        String key = state.getBlock().getClass().getName() + "|" + tool.getItem().getClass().getName();
        return BREAK_ROUTED.computeIfAbsent(key, k -> {
            boolean routed = HorizonMixinTable.patchedInside(ServerPlayerGameMode.class, java.util.Set.of("destroyBlock"), BREAK_FIRED)
                    || HorizonMixinTable.patchedInside(state.getBlock().getClass(), HorizonMixinTable.BREAK, java.util.Set.of())
                    || HorizonMixinTable.patchedInside(tool.getItem().getClass(), java.util.Set.of("mineBlock", "canDestroyBlock"), java.util.Set.of())
                    || HorizonMixinTable.patchedInside(ItemStack.class, java.util.Set.of("mineBlock"), java.util.Set.of());
            if (routed) Natives.log("Breaking " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " goes to the mods (they patch the inside of breaking: "
                    + HorizonMixinTable.mods("net/minecraft/server/level/ServerPlayerGameMode", "destroyBlock") + ")");
            return routed;
        });
    }

    /** Horizon's break of a block is the mirror's now (a routed break: breakBlock's 3). */
    private static boolean routedBreak;

    /**
     * A player is about to break a block, called by Horizon before it does
     * (ServerPlayerGameMode.destroyBlock). 0: a mod stopped it; 1: Horizon breaks it; 2: Horizon
     * breaks it, then calls afterBreak (mods' injections at destroyBlock's end); 3: the mods'
     * vanilla code broke it on the mirror (breakRouted), Horizon does nothing more.
     */
    public static int breakBlock(String uuid, String dimension, int x, int y, int z, String stateName, int selected) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        BlockState state = parseState(stateName);
        if (player == null || state == null) return 1;
        if (selected >= 0 && selected < 9) player.getInventory().setSelectedSlot(selected);
        BlockPos pos = new BlockPos(x, y, z);
        if (breakRouted(state, player.getMainHandItem())) {
            // Vanilla's whole destroyBlock with the mods' patches: Fabric's BEFORE/AFTER (its own
            // injections), the mods' (a vein miner breaking the vein through destroyBlock
            // again), the block's removal, the tool's wear, the drops and experience.
            boolean[] broke = {false};
            routedBreak = true;
            try {
                fire("ServerPlayerGameMode.destroyBlock", () -> broke[0] = player.gameMode.destroyBlock(pos));
            } finally {
                routedBreak = false;
            }
            syncInventories();
            return broke[0] ? 3 : 0;
        }
        // Horizon breaks it: the mods' injections at destroyBlock's head first (vanilla's
        // order), then Fabric's BEFORE (its injection is inside, before playerWillDestroy).
        HorizonHooks.Result head = hook(player.gameMode, ServerPlayerGameMode.class, "destroyBlock", DESTROY_BLOCK, "HEAD", new Object[] {pos});
        if (head.cancelled()) {
            syncInventories();
            return Boolean.TRUE.equals(head.returnValue()) ? 3 : 0;
        }
        if (!beforeBreak(uuid, dimension, x, y, z, stateName)) return 0;
        boolean after = HorizonMixinTable.hasEvent("net/minecraft/server/level/ServerPlayerGameMode", "destroyBlock", "RETURN")
                || HorizonMixinTable.hasEvent("net/minecraft/server/level/ServerPlayerGameMode", "destroyBlock", "TAIL");
        return after ? 2 : 1;
    }

    /** Horizon broke the block breakBlock said 2 for: the mods' injections at destroyBlock's end. */
    public static void afterBreak(String uuid, String dimension, int x, int y, int z, String stateName) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return;
        Object[] args = {new BlockPos(x, y, z)};
        for (String at : new String[] {"RETURN", "TAIL"}) {
            if (HorizonHooks.hooked("net/minecraft/server/level/ServerPlayerGameMode", "destroyBlock")) {
                HorizonHooks.fire(player.gameMode, ServerPlayerGameMode.class, "destroyBlock", DESTROY_BLOCK, at, args, true, true);
            }
        }
        syncInventories();
    }

    /**
     * PlayerBlockBreakEvents.BEFORE, called by Horizon before a player breaks a block: false
     * when a mod cancels the break.
     */
    public static boolean beforeBreak(String uuid, String dimension, int x, int y, int z, String stateName) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        BlockState state = parseState(stateName);
        if (player == null || state == null) return true;
        ServerLevel level = level(dimension);
        BlockPos pos = new BlockPos(x, y, z);
        boolean[] allowed = {true};
        fire("PlayerBlockBreakEvents.BEFORE", () -> allowed[0] =
                net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker()
                        .beforeBlockBreak(level, player, pos, state, null));
        if (!allowed[0]) {
            fire("PlayerBlockBreakEvents.CANCELED", () ->
                    net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.CANCELED.invoker()
                            .onBlockBreakCanceled(level, player, pos, state, null));
        }
        syncInventories();
        return allowed[0];
    }

    // ---- Inventories ----------------------------------------------------------------------

    /** Vanilla's Inventory slots Horizon mirrors: 36 + armour 36-39 + the off hand 40. */
    private static final int SLOTS = 41;
    /** Per player: each slot as Horizon last had it, to find what the mods changed. */
    private static final Map<UUID, ItemStack[]> SEEN = new ConcurrentHashMap<>();

    private static com.mojang.serialization.DynamicOps<net.minecraft.nbt.Tag> nbtOps() {
        return server.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
    }

    /** An item stack from SNBT as ItemStack.CODEC reads it; empty for "" or what won't read. */
    static ItemStack item(String snbt) {
        if (snbt == null || snbt.isEmpty()) return ItemStack.EMPTY;
        try {
            var tag = net.minecraft.nbt.TagParser.parseCompoundFully(snbt);
            return ItemStack.CODEC.parse(nbtOps(), tag).result().orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** An item stack as SNBT (ItemStack.CODEC); null when empty. */
    static String snbt(ItemStack stack) {
        if (stack.isEmpty()) return null;
        return ItemStack.CODEC.encodeStart(nbtOps(), stack).result().map(Object::toString).orElse(null);
    }

    /** Horizon's inventory, when it changed, into the mirrored player. */
    private static void mirrorInventory(ServerPlayer player, String[] inventories, int i) {
        if (inventories == null || inventories.length < (i + 1) * SLOTS || inventories[i * SLOTS] == null) return;
        ItemStack[] seen = new ItemStack[SLOTS];
        var inventory = player.getInventory();
        syncing = true;
        try {
            for (int slot = 0; slot < SLOTS; slot++) {
                ItemStack stack = item(inventories[i * SLOTS + slot]);
                // An unchanged slot keeps its stack: mods hold on to the stack they were used
                // with and write to it later (Nature's Compass's search: its samples, its
                // state, what it found), as a vanilla server never swaps a stack it didn't change.
                ItemStack held = inventory.getItem(slot);
                if (ItemStack.matches(held, stack)) stack = held;
                else inventory.setItem(slot, stack);
                seen[slot] = stack.copy();
            }
        } finally {
            syncing = false;
        }
        SEEN.put(player.getUUID(), seen);
    }

    /** After mods ran: each slot a mod changed (given, taken, counted down) goes to Horizon. */
    /** Each player's food as Horizon last gave it, to send back what mods change. */
    private static final Map<UUID, float[]> FOOD_SEEN = new ConcurrentHashMap<>();

    // ---- Mods' entities (HorizonEntities) ------------------------------------------------

    /** Every mod entity type's id (Horizon's /summon hands these to the mods' server). */
    public static String[] entityTypes() {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        return HorizonEntities.types();
    }

    /**
     * Horizon saves the world (its autosave, /save-all, the world closing): what the mirror
     * keeps beside it is written now too, not only every 10 s (the mods' entities, their block
     * entities and their saved data), as MinecraftServer.saveEverything saves its levels.
     */
    public static void saveAll() {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return;
        for (Runnable save : new Runnable[] {HorizonEntities::save, HorizonBlockEntities::save, HorizonSavedData::save}) {
            try {
                save.run();
            } catch (Throwable t) {
                Natives.log("Java mods: saving failed: " + t);
            }
        }
    }

    /**
     * The mods' entities of every level, with their looks when the headless client draws them
     * (HorizonEntityRender's snapshot), as JSON.
     */
    public static String entities() {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return "{}";
        if (client) return HorizonEntityRender.snapshot();
        return "{}";
    }

    /** A player hit a mod's entity: true when it was one (HorizonEntities.attack). */
    public static boolean attackEntity(String uuid, int id) {
        boolean hit = HorizonEntities.attack(uuid, id);
        syncInventories();
        return hit;
    }

    /** A player used a mod's entity (HorizonEntities.interact): 0 nothing, 1 taken, 2 swing. */
    public static int interactEntity(String uuid, int id, int hand, double x, double y, double z, int selected) {
        return HorizonEntities.interact(uuid, id, hand, x, y, z, selected);
    }

    /** What a call into the mods leaves for Horizon: slots, menus, messages (syncInventories). */
    static void afterCall() {
        syncInventories();
    }

    private static void syncInventories() {
        HorizonBlockEntities.flushVanilla();
        syncMenus();
        // What mods sent their client code, now that the screens it is for are up.
        if (client) HorizonLoop.flush();
        for (ServerPlayer player : new ArrayList<>(BY_UUID.values())) {
            float[] seen = FOOD_SEEN.get(player.getUUID());
            if (seen == null) continue;
            var data = player.getFoodData();
            if (data.getFoodLevel() != (int) seen[0] || data.getSaturationLevel() != seen[1]) {
                seen[0] = data.getFoodLevel();
                seen[1] = data.getSaturationLevel();
                Natives.setFood(player.getStringUUID(), data.getFoodLevel(), data.getSaturationLevel());
            }
        }
        for (ServerPlayer player : new ArrayList<>(BY_UUID.values())) {
            ItemStack[] seen = SEEN.get(player.getUUID());
            if (seen == null) continue;
            var inventory = player.getInventory();
            for (int slot = 0; slot < SLOTS; slot++) {
                ItemStack now = inventory.getItem(slot);
                if (ItemStack.matches(now, seen[slot])) continue;
                Natives.setSlot(player.getStringUUID(), slot, snbt(now));
                seen[slot] = now.copy();
            }
        }
    }

    // ---- The server's tasks ---------------------------------------------------------------

    /** What mods scheduled on the server (server.execute), run at the end of the tick. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> TASKS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** The thread Horizon calls in on: the mirrored server's thread, to mods. */
    private static volatile Thread serverThread;

    public static void schedule(Runnable task) {
        if (TASKS.size() < 65536) TASKS.add(task);
    }

    /** Horizon's server thread, once it ticked the mirror (MinecraftServer.getRunningThread). */
    public static Thread serverThread() {
        return serverThread;
    }

    public static boolean onServerThread() {
        return Thread.currentThread() == serverThread;
    }

    /** Runs the tasks mods scheduled (those they schedule meanwhile wait for the next tick). */
    static void runTasks() {
        for (int n = TASKS.size(); n > 0; n--) {
            Runnable task = TASKS.poll();
            if (task == null) break;
            fire("a scheduled server task", task);
        }
    }
}
