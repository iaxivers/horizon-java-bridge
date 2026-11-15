package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Remembered answers of Shapes.joinIsNotEmpty by the identity of its two shapes and its operation
 * (mixin.ShapesJoinMixin). Shapes are immutable, so the same objects always give the same answer;
 * keys hold the shapes, which their block states hold anyway. Bounded: past MAX entries it starts
 * over. HORIZON_SHAPE_MEMO=0 turns it off. (UMP, 2026-11-14)
 */
public final class HorizonShapeMemo {
    private HorizonShapeMemo() {}

    private static final boolean ON = !"0".equals(System.getenv("HORIZON_SHAPE_MEMO"));
    private static final int MAX = 2_000_000;
    private static final ConcurrentHashMap<Key, Boolean> MEMO = new ConcurrentHashMap<>();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();

    private record Key(VoxelShape first, VoxelShape second, BooleanOp op) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.first == first && k.second == second && k.op == op;
        }

        @Override
        public int hashCode() {
            return (System.identityHashCode(first) * 31 + System.identityHashCode(second)) * 31 + System.identityHashCode(op);
        }
    }

    public static Boolean get(VoxelShape first, VoxelShape second, BooleanOp op) {
        if (!ON) return null;
        Boolean known = MEMO.get(new Key(first, second, op));
        if (known != null) HITS.increment();
        else MISSES.increment();
        return known;
    }

    public static void put(VoxelShape first, VoxelShape second, BooleanOp op, boolean value) {
        if (!ON) return;
        if (MEMO.size() >= MAX) MEMO.clear();
        MEMO.put(new Key(first, second, op), value);
    }

    /** One line: how often the memory answered (called after the mods' entrypoints). */
    public static void report() {
        long hits = HITS.sum(), misses = MISSES.sum();
        if (hits + misses > 0) {
            Natives.log("Java mods: shape joins remembered " + hits + " of " + (hits + misses)
                    + " times; " + MEMO.size() + " kept");
        }
    }
}
