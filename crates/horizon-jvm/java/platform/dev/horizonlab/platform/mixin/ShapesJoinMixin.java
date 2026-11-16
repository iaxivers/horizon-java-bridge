package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonShapeMemo;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shapes.joinIsNotEmpty answered from memory for the same two shapes and operation. It is a pure
 * function of immutable shapes, and block registration asks it for every state's faces and support
 * types (BlockStateBase.Cache: 18 a state), with the same shape objects shared by many states: with
 * 184 mods, 826,000 states, it was most of the mods' start (UMP startup samples).
 * See {@link HorizonShapeMemo}. (UMP, 2026-11-14)
 */
@Mixin(Shapes.class)
public abstract class ShapesJoinMixin {
    @Inject(method = "joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void horizon$remembered(VoxelShape first, VoxelShape second, BooleanOp op, CallbackInfoReturnable<Boolean> info) {
        Boolean known = HorizonShapeMemo.get(first, second, op);
        if (known != null) info.setReturnValue(known);
    }

    /** Shapes.join (an optimized new shape) the same way: block shapes are joined from the same parts again and again (Snow! Real Magic's snowy fences and walls, Diagonal Blocks'). */
    @Inject(method = "join(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private static void horizon$rememberedJoin(VoxelShape first, VoxelShape second, BooleanOp op, CallbackInfoReturnable<VoxelShape> info) {
        VoxelShape known = HorizonShapeMemo.getJoin(first, second, op);
        if (known != null) info.setReturnValue(known);
    }

    @Inject(method = "join(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"), cancellable = true)
    private static void horizon$rememberJoin(VoxelShape first, VoxelShape second, BooleanOp op, CallbackInfoReturnable<VoxelShape> info) {
        // The shape made before with the same boxes, if any: shapes joined from new parts each time
        // (wall and fence shapes, Yuushya's collision files) are then one instance, which the
        // identity memos above answer for next time.
        VoxelShape shape = HorizonShapeMemo.canonical(info.getReturnValue());
        if (shape != info.getReturnValue()) info.setReturnValue(shape);
        HorizonShapeMemo.putJoin(first, second, op, shape);
    }

    /**
     * Shapes.create (box, and every block's Block.box/column) gives the same instance for the same
     * six coordinates: mods build their shapes from new boxes on every call (Yuushya's collision
     * files, Stoneworks, Macaw's), so the joins above saw new objects each time and remembered
     * nothing. Equal boxes are equal shapes; a shared one changes nothing a mod can see.
     */
    @Inject(method = "create(DDDDDD)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("HEAD"), cancellable = true)
    private static void horizon$sharedBox(double x0, double y0, double z0, double x1, double y1, double z1,
                                          CallbackInfoReturnable<VoxelShape> info) {
        VoxelShape known = HorizonShapeMemo.getBox(x0, y0, z0, x1, y1, z1);
        if (known != null) info.setReturnValue(known);
    }

    @Inject(method = "create(DDDDDD)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("RETURN"))
    private static void horizon$keepBox(double x0, double y0, double z0, double x1, double y1, double z1,
                                        CallbackInfoReturnable<VoxelShape> info) {
        HorizonShapeMemo.putBox(x0, y0, z0, x1, y1, z1, info.getReturnValue());
    }

    @Inject(method = "joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z",
            at = @At("RETURN"))
    private static void horizon$remember(VoxelShape first, VoxelShape second, BooleanOp op, CallbackInfoReturnable<Boolean> info) {
        HorizonShapeMemo.put(first, second, op, info.getReturnValueZ());
    }
}
