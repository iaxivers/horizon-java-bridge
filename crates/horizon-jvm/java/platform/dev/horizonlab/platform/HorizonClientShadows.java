package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Client twins of mods' mobs (UMP, 2026-11-10). In vanilla each mob also exists on every player's client, and mods
 * animate there: Naturalist's deer starts its walk/run/idle AnimationStates in {@code tick()} only when
 * {@code level.isClientSide()}, and {@code LivingEntity.aiStep} steps the limb swing only on a client level. Mods'
 * mobs here live on the mirror's server level alone, so that code never ran and their models were drawn in their
 * standing pose while they moved (UMP anim test: 47 of 66 mod mobs).
 *
 * Each mod mob gets a twin in the mirror's client level ({@link HorizonClient#clientLevel}), made by its type like a
 * client's entity, and every tick: the server mob's place, facing, motion and synced data are copied in (what a
 * client gets from packets), then the twin ticks as a client entity would (no AI on a client), which runs the mod's
 * client animation code. {@link HorizonEntityRender} draws the twin. A type whose twin can't be made or tick is drawn
 * from the server mob as before (logged once).
 */
final class HorizonClientShadows {
    private HorizonClientShadows() {}

    private static final Map<Entity, Entity> TWINS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Set<String> BROKEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final boolean OFF = "0".equals(System.getenv("HORIZON_JAVA_CLIENT_TWINS"));

    /** The twin to draw for {@code entity}: its client twin, or the entity itself. */
    static Entity drawn(Entity entity) {
        Entity twin = TWINS.get(entity);
        return twin != null && !twin.isRemoved() ? twin : entity;
    }

    /** After the server mob's tick: bring its twin up to date and tick it as a client would. */
    static void tick(Entity entity) {
        if (OFF || !(entity instanceof LivingEntity living) || entity instanceof Player) return;
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        if (type.startsWith("minecraft:") || BROKEN.contains(type)) return;
        Entity twin = TWINS.get(entity);
        try {
            if (twin == null) {
                twin = entity.getType().create(HorizonClient.clientLevel(entity.level()), EntitySpawnReason.LOAD);
                if (twin == null) {
                    broken(type, "its type made no client entity");
                    return;
                }
                twin.setId(entity.getId());
                twin.setUUID(entity.getUUID());
                copy(entity, twin);
                twin.setOldPosAndRot();
                TWINS.put(entity, twin);
            }
            twin.setOldPosAndRot();
            copy(entity, twin);
            twin.tickCount = entity.tickCount - 1;
            twin.tick();
            copy(entity, twin); // the server's place stands: a client entity follows its packets
            twin.tickCount = entity.tickCount;
        } catch (Throwable t) {
            TWINS.remove(entity);
            broken(type, t.toString());
        }
    }

    private static void copy(Entity from, Entity to) {
        var data = from.getEntityData().getNonDefaultValues();
        if (data != null) to.getEntityData().assignValues(data);
        to.setPos(from.getX(), from.getY(), from.getZ());
        to.setYRot(from.getYRot());
        to.setXRot(from.getXRot());
        to.setDeltaMovement(from.getDeltaMovement());
        to.setOnGround(from.onGround());
        if (from instanceof LivingEntity a && to instanceof LivingEntity b) {
            b.yBodyRot = a.yBodyRot;
            b.yHeadRot = a.yHeadRot;
            b.hurtTime = a.hurtTime;
            b.deathTime = a.deathTime;
        }
    }

    private static void broken(String type, String why) {
        if (BROKEN.add(type)) Natives.log("Java mods: " + type + " has no client twin (" + why + "): drawn from the server's mob");
    }

    /** The entity left the world: forget its twin. */
    static void removed(Entity entity) {
        Entity twin = TWINS.remove(entity);
        if (twin != null) twin.discard();
    }
}
