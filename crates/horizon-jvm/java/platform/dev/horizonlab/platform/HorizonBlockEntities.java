package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mod blocks' block entities (a cooking pot's inventory): Horizon has none for blocks it only
 * knows from a content mod, so they are Java's, made when a mod first asks for one, ticked with
 * the server and saved beside the world ({@code horizon-java/block_entities/}). A block entity
 * whose chunk Horizon unloaded is put away, not dropped; one whose block is gone drops what it
 * holds, as the mod's own block would have.
 */
public final class HorizonBlockEntities {
    private HorizonBlockEntities() {}

    /** Every 10 s, the changed ones are saved. */
    private static final int SAVE_TICKS = 200;

    private static final Map<ServerLevel, Map<Long, BlockEntity>> LIVE = new IdentityHashMap<>();
    /** Saved ones not made yet, per dimension. */
    private static final Map<String, Map<Long, CompoundTag>> SAVED = new HashMap<>();
    private static Path dir;
    private static boolean dirty;
    private static int ticks;

    /** A mod's block (Horizon has its own block entities for vanilla's). */
    public static boolean modded(BlockState state) {
        return !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft");
    }

    /** The world changed (a new folder): what was held belongs to the last one. */
    static synchronized void world(Path worldDir) {
        Path now = worldDir == null ? null : worldDir.resolve("horizon-java").resolve("block_entities");
        if (now == null || now.equals(dir)) return;
        if (dir != null) save();
        LIVE.clear();
        SAVED.clear();
        COPIES.clear();
        HorizonClientBlockEntities.clear();
        dir = now;
    }

    /** Level.getBlockEntity on a mirrored level. */
    public static synchronized BlockEntity get(Level level, BlockPos pos) {
        Map<Long, BlockEntity> live = LIVE.computeIfAbsent((ServerLevel) level, l -> new HashMap<>());
        long key = pos.asLong();
        BlockEntity be = live.get(key);
        BlockState state = HorizonServer.blockState(level, pos);
        if (!modded(state) && state.hasBlockEntity()) {
            if (be != null) remove(level, live, key, be, true);
            return vanilla(level, pos, state);
        }
        if (!(state.getBlock() instanceof EntityBlock entityBlock) || !modded(state)) {
            if (be != null) remove(level, live, key, be, true);
            return null;
        }
        if (be != null && be.getType().isValid(state)) {
            if (be.getBlockState() != state) be.setBlockState(state);
            return be;
        }
        if (be != null) remove(level, live, key, be, false);
        CompoundTag saved = saved(level).remove(key);
        BlockEntity made = null;
        if (saved != null) {
            try {
                made = BlockEntity.loadStatic(pos.immutable(), state, saved, level.registryAccess());
            } catch (Throwable t) {
                Natives.log("Java mods: a block entity at " + pos + " could not be read: " + t);
            }
        }
        if (made == null) made = entityBlock.newBlockEntity(pos.immutable(), state);
        if (made == null) return null;
        made.setLevel(level);
        live.put(key, made);
        dirty = true;
        loaded(level, made);
        return made;
    }

    /**
     * `/data get block` on a mod's block: the live block entity's NBT (saveWithFullMetadata), or
     * the saved one not made yet (a frozen world after a restart ticks none); null when none.
     */
    public static synchronized CompoundTag data(ServerLevel level, BlockPos pos) {
        BlockEntity be = peek(level, pos);
        if (be != null) return be.saveWithFullMetadata(level.registryAccess());
        CompoundTag saved = saved(level).get(pos.asLong());
        return saved == null ? null : saved.copy();
    }

    /** The live mod block entity at {@code pos}, without making one (null when none is up). */
    public static synchronized BlockEntity peek(Level level, BlockPos pos) {
        Map<Long, BlockEntity> live = LIVE.get((ServerLevel) level);
        return live == null ? null : live.get(pos.asLong());
    }

    /** The live mod block entities of a level (a copy: its renderers' snapshot reads them). */
    public static synchronized java.util.List<BlockEntity> live(ServerLevel level) {
        Map<Long, BlockEntity> live = LIVE.get(level);
        return live == null ? java.util.List.of() : new ArrayList<>(live.values());
    }

    /** A mod block placed at {@code pos}: its block entity made, if it has one and none is up. */
    public static synchronized void ensure(Level level, BlockPos pos) {
        BlockState state = HorizonServer.blockState(level, pos);
        if (!modded(state) || !state.hasBlockEntity()) return;
        Map<Long, BlockEntity> live = LIVE.get((ServerLevel) level);
        if (live == null || !live.containsKey(pos.asLong())) get(level, pos);
    }

    /**
     * Saved block entities whose chunks Horizon has loaded come back up, so they tick (a mod
     * machine works on with no one looking at it), as a loaded chunk's do.
     */
    private static void restore() {
        for (ServerLevel level : HorizonServer.levels()) {
            String dimension = level.dimension().identifier().toString();
            Map<Long, CompoundTag> saved = saved(level);
            for (Long key : new ArrayList<>(saved.keySet())) {
                BlockPos pos = BlockPos.of(key);
                if (Natives.blockState(dimension, pos.getX(), pos.getY(), pos.getZ()) != null) get(level, pos);
            }
        }
    }

    /** Level.setBlockEntity on a mirrored level. */
    public static synchronized void put(Level level, BlockEntity be) {
        if (!modded(be.getBlockState())) return;
        Map<Long, BlockEntity> live = LIVE.computeIfAbsent((ServerLevel) level, l -> new HashMap<>());
        BlockEntity old = live.put(be.getBlockPos().asLong(), be);
        if (old != null && old != be) old.setRemoved();
        be.setLevel(level);
        dirty = true;
    }

    /** Level.removeBlockEntity on a mirrored level: the mod's own removal, nothing dropped. */
    public static synchronized void removed(Level level, BlockPos pos) {
        Map<Long, BlockEntity> live = LIVE.get((ServerLevel) level);
        BlockEntity be = live == null ? null : live.remove(pos.asLong());
        if (be != null) {
            be.setRemoved();
            unloaded(level, be);
        }
        saved(level).remove(pos.asLong());
        dirty = true;
    }

    /** Level.blockEntityChanged (BlockEntity.setChanged). */
    public static synchronized void changed(Level level, BlockPos pos) {
        dirty = true;
        HorizonClientBlockEntities.changed((ServerLevel) level, pos);
        Map<Long, Copy> copies = COPIES.get((ServerLevel) level);
        Copy copy = copies == null ? null : copies.get(pos.asLong());
        if (copy != null) copy.changed = true;
    }

    // ---- Vanilla blocks' block entities: Horizon's ------------------------------------------

    /**
     * Horizon's block entities (a chest's) as vanilla ones Java's mods use: one lasting copy a
     * position, so what a mod keeps of it (Tom's Storage's connector, the chest's item storage)
     * stays the one in use. Each tick it is read again from Horizon in place (what players did to
     * the chest shows), and what mods change of it goes back to Horizon (flushVanilla).
     */
    private static final Map<ServerLevel, Map<Long, Copy>> COPIES = new IdentityHashMap<>();

    private static final class Copy {
        final BlockEntity be;
        /** Horizon's block entity as last read (null: read it again). */
        String snbt;
        /** Mods changed it since it was last written to Horizon. */
        boolean changed;

        Copy(BlockEntity be, String snbt) {
            this.be = be;
            this.snbt = snbt;
        }
    }

    /** Horizon's block entity at {@code pos}, as a vanilla one Java's mods can use. */
    private static BlockEntity vanilla(Level level, BlockPos pos, BlockState state) {
        Map<Long, Copy> copies = COPIES.computeIfAbsent((ServerLevel) level, l -> new HashMap<>());
        long key = pos.asLong();
        Copy copy = copies.get(key);
        if (copy != null && !copy.be.isRemoved() && copy.be.getType().isValid(state)) {
            if (copy.be.getBlockState() != state) copy.be.setBlockState(state);
            if (!copy.changed) refresh(level, pos, copies, copy);
            return copy.be.isRemoved() ? null : copy.be;
        }
        if (copy != null) {
            copies.remove(key);
            copy.be.setRemoved();
        }
        String dimension = level.dimension().identifier().toString();
        String snbt = Natives.blockEntity(dimension, pos.getX(), pos.getY(), pos.getZ());
        if (snbt == null) return null;
        BlockEntity be;
        try {
            CompoundTag tag = net.minecraft.nbt.TagParser.parseCompoundFully(snbt);
            be = BlockEntity.loadStatic(pos.immutable(), state, tag, level.registryAccess());
        } catch (Throwable t) {
            Natives.log("Java mods: Horizon's block entity at " + pos + " could not be read: " + t);
            return null;
        }
        if (be == null) return null;
        be.setLevel(level);
        copies.put(key, new Copy(be, snbt));
        return be;
    }

    /** A copy read again from Horizon, in place; removed when Horizon's is gone. */
    private static void refresh(Level level, BlockPos pos, Map<Long, Copy> copies, Copy copy) {
        String dimension = level.dimension().identifier().toString();
        String snbt = Natives.blockEntity(dimension, pos.getX(), pos.getY(), pos.getZ());
        if (snbt == null) {
            copies.remove(pos.asLong());
            copy.be.setRemoved();
            return;
        }
        if (snbt.equals(copy.snbt)) return;
        try {
            CompoundTag tag = net.minecraft.nbt.TagParser.parseCompoundFully(snbt);
            copy.be.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag));
            copy.snbt = snbt;
        } catch (Throwable t) {
            Natives.log("Java mods: Horizon's block entity at " + pos + " could not be read: " + t);
        }
    }

    /** Each tick, before mods' block entities run: the copies read again from Horizon. */
    private static void refreshVanilla() {
        for (Map.Entry<ServerLevel, Map<Long, Copy>> e : COPIES.entrySet()) {
            Map<Long, Copy> copies = e.getValue();
            for (Copy copy : new ArrayList<>(copies.values())) {
                if (!copy.changed) refresh(e.getKey(), copy.be.getBlockPos(), copies, copy);
            }
        }
    }

    /**
     * The end of a call: what mods changed of Horizon's block entities goes back to Horizon (the
     * copies stay, to be read again next tick).
     */
    public static synchronized void flushVanilla() {
        for (Map.Entry<ServerLevel, Map<Long, Copy>> e : COPIES.entrySet()) {
            ServerLevel level = e.getKey();
            String dimension = level.dimension().identifier().toString();
            for (Copy copy : e.getValue().values()) {
                if (!copy.changed) continue;
                copy.changed = false;
                copy.snbt = null;
                try {
                    CompoundTag tag = copy.be.saveWithFullMetadata(level.registryAccess());
                    BlockPos pos = copy.be.getBlockPos();
                    Natives.setBlockEntity(dimension, pos.getX(), pos.getY(), pos.getZ(), tag.toString());
                } catch (Throwable t) {
                    Natives.log("Java mods: a block entity at " + copy.be.getBlockPos() + " could not be saved: " + t);
                }
            }
        }
    }

    /** Each server tick: the tickers run; gone blocks drop, unloaded ones are put away. */
    public static synchronized void tick() {
        refreshVanilla();
        for (Map.Entry<ServerLevel, Map<Long, BlockEntity>> e : LIVE.entrySet()) {
            ServerLevel level = e.getKey();
            Map<Long, BlockEntity> live = e.getValue();
            String dimension = level.dimension().identifier().toString();
            for (Map.Entry<Long, BlockEntity> entry : new ArrayList<>(live.entrySet())) {
                BlockEntity be = entry.getValue();
                BlockPos pos = be.getBlockPos();
                String name = Natives.blockState(dimension, pos.getX(), pos.getY(), pos.getZ());
                if (name == null) {
                    // Horizon unloaded its chunk: put away until a mod asks again.
                    saved(level).put(entry.getKey(), be.saveWithFullMetadata(level.registryAccess()));
                    live.remove(entry.getKey());
                    be.setRemoved();
                    unloaded(level, be);
                    continue;
                }
                BlockState state = HorizonServer.blockState(level, pos);
                if (!be.getType().isValid(state)) {
                    remove(level, live, entry.getKey(), be, true);
                    continue;
                }
                if (be.getBlockState() != state) be.setBlockState(state);
                tick(level, pos, state, be);
            }
        }
        if (++ticks % SAVE_TICKS == 0 && dirty) save();
        if (ticks % 20 == 0) restore();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void tick(ServerLevel level, BlockPos pos, BlockState state, BlockEntity be) {
        BlockEntityTicker ticker = state.getTicker(level, be.getType());
        if (ticker == null) return;
        try {
            ticker.tick(level, pos, state, be);
        } catch (Throwable t) {
            Natives.log("Java mods: " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + pos
                    + " failed to tick: " + t);
        }
    }

    private static void remove(Level level, Map<Long, BlockEntity> live, long key, BlockEntity be, boolean drop) {
        live.remove(key);
        if (drop && be instanceof Container container) {
            Containers.dropContents(level, be.getBlockPos(), container);
        }
        be.setRemoved();
        unloaded(level, be);
        dirty = true;
    }

    /**
     * Fabric API's block entity load event, as a level fires it for a block entity it adds (as its
     * chunk loads, or the block is placed): mods set theirs up there (Tom's Storage's terminals
     * find their network).
     */
    private static void loaded(Level level, BlockEntity be) {
        try {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.invoker()
                    .onLoad(be, (ServerLevel) level);
        } catch (Throwable t) {
            Natives.log("Java mods: a block entity's load failed: " + t);
        }
    }

    /** Fabric API's block entity unload event, for one removed or put away. */
    private static void unloaded(Level level, BlockEntity be) {
        try {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.invoker()
                    .onUnload(be, (ServerLevel) level);
        } catch (Throwable t) {
            Natives.log("Java mods: a block entity's unload failed: " + t);
        }
    }

    private static Map<Long, CompoundTag> saved(Level level) {
        String dimension = level.dimension().identifier().toString();
        return SAVED.computeIfAbsent(dimension, HorizonBlockEntities::read);
    }

    private static Path file(String dimension) {
        return dir == null ? null : dir.resolve(dimension.replace(':', '_').replace('/', '_') + ".nbt");
    }

    private static Map<Long, CompoundTag> read(String dimension) {
        Map<Long, CompoundTag> out = new HashMap<>();
        Path file = file(dimension);
        if (file == null || !Files.exists(file)) return out;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("block_entities");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompoundOrEmpty(i);
                BlockPos pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
                out.put(pos.asLong(), tag);
            }
        } catch (Throwable t) {
            Natives.log("Java mods: " + file + " could not be read: " + t);
        }
        return out;
    }

    /** Writes every dimension's block entities: the live ones and those put away. */
    public static synchronized void save() {
        if (dir == null) return;
        Map<String, ListTag> lists = new HashMap<>();
        for (Map.Entry<String, Map<Long, CompoundTag>> e : SAVED.entrySet()) {
            ListTag list = lists.computeIfAbsent(e.getKey(), d -> new ListTag());
            for (CompoundTag tag : e.getValue().values()) list.add(tag);
        }
        for (Map.Entry<ServerLevel, Map<Long, BlockEntity>> e : LIVE.entrySet()) {
            ServerLevel level = e.getKey();
            ListTag list = lists.computeIfAbsent(level.dimension().identifier().toString(), d -> new ListTag());
            for (BlockEntity be : e.getValue().values()) {
                try {
                    list.add(be.saveWithFullMetadata(level.registryAccess()));
                } catch (Throwable t) {
                    Natives.log("Java mods: a block entity at " + be.getBlockPos() + " could not be saved: " + t);
                }
            }
        }
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, ListTag> e : lists.entrySet()) {
                CompoundTag root = new CompoundTag();
                root.put("block_entities", (Tag) e.getValue());
                NbtIo.writeCompressed(root, file(e.getKey()));
            }
            dirty = false;
        } catch (Throwable t) {
            Natives.log("Java mods: block entities could not be saved in " + dir + ": " + t);
        }
    }
}
