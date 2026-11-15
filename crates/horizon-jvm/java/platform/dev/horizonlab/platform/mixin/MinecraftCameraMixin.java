package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The headless client's camera is its player (the one whose client runs now): it has no game
 * renderer, whose main camera vanilla's getCameraEntity reads. The HUD's parts draw for it.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftCameraMixin {
    @Inject(method = "getCameraEntity", at = @At("HEAD"), cancellable = true)
    private void horizon$camera(CallbackInfoReturnable<Entity> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(((Minecraft) (Object) this).player);
    }

    /**
     * No frames, so no partial ticks: whole ticks. A real Timer, the type vanilla's field has (mods
     * cast to it: Paladin's Furniture calls Timer.updatePauseState, and DeltaTracker.ONE, another
     * class, failed the cast and stopped Fabric's start). It is never advanced, so its deltas stay
     * one tick and its partial tick a whole one, as ONE's. (UMP, 2026-11-13)
     */
    @Inject(method = "getDeltaTracker", at = @At("HEAD"), cancellable = true)
    private void horizon$deltaTracker(CallbackInfoReturnable<DeltaTracker> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(WHOLE_TICKS);
    }

    private static final DeltaTracker.Timer WHOLE_TICKS = wholeTicks();

    private static DeltaTracker.Timer wholeTicks() {
        DeltaTracker.Timer timer = new DeltaTracker.Timer(20.0F, 0L, mspt -> mspt);
        // Its fields as one whole tick: what ONE answers.
        try {
            for (String name : new String[] {"deltaTicks", "realtimeDeltaTicks"}) {
                var field = DeltaTracker.Timer.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setFloat(timer, 1.0F);
            }
            var residual = DeltaTracker.Timer.class.getDeclaredField("deltaTickResidual");
            residual.setAccessible(true);
            residual.setFloat(timer, 1.0F);
        } catch (ReflectiveOperationException e) {
            // Fields renamed: its deltas start at 0, the partial tick too.
        }
        return timer;
    }

    /**
     * No window to draw a frame into: setScreenAndShow (a screen opened mid-tick, REI's recipe
     * view) draws one at once; Horizon draws the screen from its recorded frames instead.
     */
    @Inject(method = "renderFrame", at = @At("HEAD"), cancellable = true)
    private void horizon$noFrame(boolean advanceGameTime, CallbackInfo ci) {
        if (HorizonClient.headless()) ci.cancel();
    }
}
