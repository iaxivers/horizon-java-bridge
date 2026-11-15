package dev.horizonlab.platform.mixin;

import dev.horizonlab.bridge.Natives;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * One mod's unreadable recipe or advancement no longer stops the game (UMP, 2026-11-11). In 26.3 recipes and
 * advancements load as registries, and vanilla turns any element that fails to parse into a crash of the whole
 * world load. Mods ship data for other mods' versions (Just Enough Breeding 3.4.1's Friends &amp; Foes recipes fail
 * to parse with Friends &amp; Foes 5.0.4, on plain Minecraft too), so one stale file made a 100-mod pack unplayable.
 * These leaf entries are now left out with a line naming them; every other registry error still fails as vanilla's.
 */
@Mixin(RegistryDataLoader.class)
public abstract class TolerantRegistryMixin {
    private static final Set<ResourceKey<?>> TOLERATED = Set.of(Registries.RECIPE, Registries.ADVANCEMENT);

    @Inject(method = "lambda$load$2", at = @At("HEAD"), require = 0)
    private static void horizon$leaveOutBrokenData(List<?> tasks, Map<ResourceKey<?>, Exception> errors, Void unused,
                                                   CallbackInfoReturnable<?> cir) {
        if (errors == null || errors.isEmpty()) return;
        synchronized (errors) {
            for (Iterator<Map.Entry<ResourceKey<?>, Exception>> it = errors.entrySet().iterator(); it.hasNext();) {
                var e = it.next();
                ResourceKey<?> key = e.getKey();
                if (!TOLERATED.contains(ResourceKey.createRegistryKey(key.registry()))) continue;
                Throwable root = e.getValue();
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                String why = String.valueOf(root.getMessage());
                if (why.length() > 160) why = why.substring(0, 160) + "...";
                Natives.log("Java mods: " + key.identifier().getNamespace() + "'s " + key.registry().getPath() + " "
                        + key.identifier() + " can't be read (left out): " + why);
                it.remove();
            }
        }
    }
}
