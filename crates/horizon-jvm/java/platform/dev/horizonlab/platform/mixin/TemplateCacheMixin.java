package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonTemplateCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import com.mojang.datafixers.DataFixer;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplateSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A structure template of an older DataVersion (mods ship them by the thousand, from many
 * versions) is brought up to date by DataFixerUpper each time it is loaded: hundreds of ms for
 * one, as structures generate (UMP profile, 100 mods: worldgen threads in DataFixTypes.update).
 * The updated tag is kept on disk by the original's content (HorizonTemplateCache), so the next
 * load (later chunks, the next start) reads it as current. (UMP, 2026-11-12)
 */
@Mixin(TemplateSource.class)
public abstract class TemplateCacheMixin {
    @Redirect(method = "readStructure(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/datafix/DataFixTypes;updateToCurrentVersion(Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/nbt/CompoundTag;I)Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag horizon$cachedUpdate(DataFixTypes type, DataFixer fixer, CompoundTag tag, int version) {
        return HorizonTemplateCache.update(type, fixer, tag, version);
    }
}
