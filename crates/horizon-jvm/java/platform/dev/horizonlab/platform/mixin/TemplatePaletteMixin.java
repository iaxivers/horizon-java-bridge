package dev.horizonlab.platform.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * A structure template's palette caches its blocks by type in a HashMap (Palette.blocks(Block),
 * which jigsaw placement reads). Vanilla builds structure starts on one thread; Horizon runs the
 * mods' world generation on several, and a template two of them place at once threw
 * ConcurrentModificationException from that map's computeIfAbsent: the chunk fell back to
 * Horizon's own generator, without the mods' structure. The cache is a ConcurrentHashMap here.
 * (UMP, 2026-11-14)
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class TemplatePaletteMixin {
    @Shadow @Final @Mutable private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void horizon$concurrentCache(CallbackInfo info) {
        this.cache = new java.util.concurrent.ConcurrentHashMap<>(this.cache);
    }
}
