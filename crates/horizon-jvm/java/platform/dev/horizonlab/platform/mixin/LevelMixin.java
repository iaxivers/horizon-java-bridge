package dev.horizonlab.platform.mixin;

import dev.horizonlab.bridge.Natives;
import dev.horizonlab.platform.HorizonBlockEntities;
import dev.horizonlab.platform.HorizonServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mirrored level's blocks are Horizon's: a mod reads them from Horizon's world, by name,
 * and what it sets is made in Horizon's world at once (HorizonServer.setBlock). The level holds
 * no chunks.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
    /**
     * LevelAccessor.hasChunk (a default method, which nothing can inject into: overridden
     * here, as vanilla's for other levels): a mirrored level holds the chunks Horizon has.
     */
    public boolean hasChunk(int x, int z) {
        if (HorizonServer.isMirror(this)) {
            Level level = (Level) (Object) this;
            return HorizonServer.loaded(level, x << 4, 64, z << 4) || horizon$standIn(level, x, z);
        }
        return ((net.minecraft.world.level.LevelAccessor) (Object) this).getChunkSource().hasChunk(x, z);
    }

    /** A chunk a mod made load that Horizon hasn't yet (HorizonWorldgen.standIn): loaded, as in vanilla. */
    private static boolean horizon$standIn(Level level, int x, int z) {
        return level instanceof net.minecraft.server.level.ServerLevel server
                && dev.horizonlab.platform.HorizonWorldgen.standInNow(server, x, z) != null;
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void horizon$blockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.blockState(HorizonServer.serverSide((Level) (Object) this), pos));
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void horizon$fluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.blockState(HorizonServer.serverSide((Level) (Object) this), pos).getFluidState());
    }

    /** Vanilla blocks' block entities are Horizon's (none here); mod blocks' are Java's. */
    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void horizon$blockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (!HorizonServer.isMirror(this)) return;
        // A client level has its own copies of mods' block entities, as a client does.
        if (dev.horizonlab.platform.HorizonClient.headless() && (Object) this instanceof net.minecraft.client.multiplayer.ClientLevel client) {
            cir.setReturnValue(dev.horizonlab.platform.HorizonClientBlockEntities.get(client, pos));
            return;
        }
        cir.setReturnValue(HorizonBlockEntities.get(HorizonServer.serverSide((Level) (Object) this), pos));
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void horizon$setBlockEntity(BlockEntity be, CallbackInfo ci) {
        if (!HorizonServer.isMirror(this)) return;
        ci.cancel();
        HorizonBlockEntities.put(HorizonServer.serverSide((Level) (Object) this), be);
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void horizon$removeBlockEntity(BlockPos pos, CallbackInfo ci) {
        if (!HorizonServer.isMirror(this)) return;
        ci.cancel();
        HorizonBlockEntities.removed(HorizonServer.serverSide((Level) (Object) this), pos);
    }

    /** BlockEntity.setChanged: saved with the others; the level holds no chunk to mark. */
    @Inject(method = "blockEntityChanged", at = @At("HEAD"), cancellable = true)
    private void horizon$blockEntityChanged(BlockPos pos, CallbackInfo ci) {
        if (!HorizonServer.isMirror(this)) return;
        ci.cancel();
        HorizonBlockEntities.changed(HorizonServer.serverSide((Level) (Object) this), pos);
    }

    /** Comparators are Horizon's. */
    @Inject(method = "updateNeighbourForOutputSignal", at = @At("HEAD"), cancellable = true)
    private void horizon$outputSignal(BlockPos pos, net.minecraft.world.level.block.Block block, CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    /**
     * Neighbour updates a mod asks for itself (Create's contraptions as they lift their blocks:
     * the shape updates around each): the mirror's levels are made without their constructor,
     * so they have no neighbour updater (a null one failed the bearing's whole assembly).
     * Horizon updates the neighbours of every block the mirror sets, as vanilla's setBlock
     * would; these are left to it.
     */
    @Inject(method = "neighborShapeChanged", at = @At("HEAD"), cancellable = true)
    private void horizon$shapeChanged(net.minecraft.core.Direction direction, BlockPos pos, BlockPos neighbor, BlockState state,
                                      int flags, int recursion, CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    @Inject(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V",
            at = @At("HEAD"), cancellable = true)
    private void horizon$neighbors(BlockPos pos, net.minecraft.world.level.block.Block block,
                                   net.minecraft.world.level.redstone.Orientation orientation, CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    @Inject(method = "updateNeighborsAtExceptFromFacing", at = @At("HEAD"), cancellable = true)
    private void horizon$neighborsExcept(BlockPos pos, net.minecraft.world.level.block.Block block, net.minecraft.core.Direction skip,
                                         net.minecraft.world.level.redstone.Orientation orientation, CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V",
            at = @At("HEAD"), cancellable = true)
    private void horizon$neighborChanged(BlockPos pos, net.minecraft.world.level.block.Block block,
                                         net.minecraft.world.level.redstone.Orientation orientation, CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    /** Horizon's light, read where a mod asks (the level has no chunk source). */
    @Inject(method = "getLightEngine", at = @At("HEAD"), cancellable = true)
    private void horizon$lightEngine(CallbackInfoReturnable<net.minecraft.world.level.lighting.LevelLightEngine> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.light((Level) (Object) this));
    }

    @Inject(method = "isLoaded", at = @At("HEAD"), cancellable = true)
    private void horizon$loaded(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) {
            Level level = (Level) (Object) this;
            cir.setReturnValue(HorizonServer.loaded(level, pos.getX(), pos.getY(), pos.getZ())
                    || horizon$standIn(level, pos.getX() >> 4, pos.getZ() >> 4));
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void horizon$setBlock(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.setBlock(HorizonServer.serverSide((Level) (Object) this), pos, state, flags));
    }

    /**
     * The client half of a player's action (HorizonClient.useItem: the item's use as the
     * player's client runs it) sees a client level: the headless client's players stand in the
     * mirror's server levels, and mods open their screens under isClientSide().
     */
    @Inject(method = "isClientSide", at = @At("HEAD"), cancellable = true)
    private void horizon$clientSide(CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.clientHalf == Thread.currentThread()) cir.setReturnValue(true);
    }

    /**
     * A headless client's level (HorizonClient.clientLevel) has no chunk source of its own: its
     * chunks are copies of Horizon's, as its server level's are (HorizonChunks: Chunk Loaders'
     * grid draws them, heightmaps and blocks).
     */
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void horizon$clientChunk(int x, int z, net.minecraft.world.level.chunk.status.ChunkStatus status, boolean load,
                                     CallbackInfoReturnable<net.minecraft.world.level.chunk.ChunkAccess> cir) {
        if (!HorizonServer.isMirror(this) || (Object) this instanceof net.minecraft.server.level.ServerLevel) return;
        var server = HorizonServer.serverSide((Level) (Object) this);
        if (server instanceof net.minecraft.server.level.ServerLevel level) {
            net.minecraft.world.level.chunk.ChunkAccess chunk = dev.horizonlab.platform.HorizonChunks.chunk(level, x, z, (Level) (Object) this);
            // Where Horizon has none loaded, an empty chunk (a client's view beyond its chunks).
            if (chunk == null && load) {
                Level self = (Level) (Object) this;
                chunk = new net.minecraft.world.level.chunk.EmptyLevelChunk(self, new net.minecraft.world.level.ChunkPos(x, z),
                        self.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                                .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
            }
            cir.setReturnValue(chunk);
        }
    }

    /** Entities collide with Horizon's blocks: the level is its own chunk for collisions. */
    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true)
    private void horizon$collisions(int x, int z, CallbackInfoReturnable<net.minecraft.world.level.BlockGetter> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue((Level) (Object) this);
    }
}
