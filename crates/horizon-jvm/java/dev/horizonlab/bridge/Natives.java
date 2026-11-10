package dev.horizonlab.bridge;

/** Functions Horizon implements in Rust and registers when the JVM starts (horizon-jvm). */
public final class Natives {
    private Natives() {}

    /** A line in Horizon's log, as {@code [java] text}. */
    public static native void log(String text);

    /** A system message to one player, by {@code UUID.toString()}. */
    public static native void tell(String uuid, String text);

    /** A line for one player's action bar (an overlay system message), as a JSON component. */
    public static native void overlay(String uuid, String text);
    /** What the mods did to Horizon's item entities this tick (HorizonItems): a JSON list. */
    public static native void itemsChanged(String json);

    /** Moves a player (by UUID) to a dimension ("minecraft:the_nether"), position and rotation. */
    /** A mod's block entity set a player's motion: the player's client moves at it (ClientboundSetEntityMotionPacket). */
    public static native void playerMotion(String uuid, double x, double y, double z);

    public static native void teleport(String uuid, String dimension, double x, double y, double z, float yaw, float pitch);

    /**
     * A player's abilities (vanilla's Abilities) as computed in game mode {@code gameMode}:
     * {@code flags} invulnerable 1, flying 2, may fly 4, instabuild 8.
     */
    public static native void abilities(String uuid, int gameMode, int flags, float flyingSpeed, float walkingSpeed);

    /**
     * The name a player (by UUID) shows in the tab list, as a JSON text component; null for
     * the player's own name.
     */
    public static native void tabName(String uuid, String json);

    /**
     * A mod opened a menu for a player (ServerPlayer.openMenu): its MenuType's id
     * ("minecraft:crafting") and title as a JSON text component. Horizon opens its own.
     */
    public static native void openMenu(String uuid, String menu, String title);

    /**
     * Horizon's light at x y z of dimension: sky light in the high four bits, block light in
     * the low; -1 where no chunk is loaded (or outside a call that lent the world).
     */
    public static native int light(String dimension, int x, int y, int z);

    /**
     * A section's light (section coordinates), 4096 bytes in vanilla's index order (y, z, x),
     * each packed as {@link #light} gives it; false where a block has none.
     */
    public static native boolean lightSection(String dimension, int sx, int sy, int sz, byte[] out);

    /** Horizon's block entity at x y z (a chest's), as vanilla saves it, in SNBT; else null. */
    public static native String blockEntity(String dimension, int x, int y, int z);

    /** A mod changed a vanilla block's block entity: Horizon's is now this (SNBT). */
    public static native void setBlockEntity(String dimension, int x, int y, int z, String snbt);

    /** A frame of a player's mod screen (HorizonGraphics' operations, JSON), for Horizon to draw. */
    public static native void screenFrame(String uuid, String frame);

    /**
     * A player's client-only screen (Gui.setScreen with no menu: a mod's settings, a guide
     * book) opened, or closed: Horizon's window shows it, and its frames, with no window of a
     * menu under it.
     */
    public static native void clientScreen(String uuid, boolean open);

    /** What mods draw on a player's HUD (HorizonGraphics' operations, JSON), when it changed. */
    public static native void hudFrame(String uuid, String frame);

    /**
     * A player's field of view as the mods make it (Camera.calculateFov's hooks: a zoom), over
     * vanilla's: the scale their game's camera takes, when it changed.
     */
    public static native void view(String uuid, float scale);

    /** A frame of what mods draw over a player's inventory (HorizonClient's overlay), when it changed. */
    public static native void overlayFrame(String uuid, String frame);

    /** A mod changed a player's food (FoodData): its food level and saturation now. */
    public static native void setFood(String uuid, int food, float saturation);

    /**
     * A mod's menu a player has open, shown by Horizon and run by Java: on opening, the menu
     * type the player's game draws and its title (else null); the shown slots' items as SNBT
     * (null empty) and the carried one. All null: the menu closed.
     */
    public static native void menuContent(String uuid, String menu, String title, String[] items, String carried);

    /**
     * Horizon's block at x y z of a dimension ("minecraft:oak_log[axis=y]"), or null where no
     * chunk is loaded or outside a call that lent the world.
     */
    public static native String blockState(String dimension, int x, int y, int z);

    /**
     * A loaded chunk's blocks at once, from minY up: out[((y - minY) * 16 + z) * 16 + x] is an
     * index into the returned palette of states; null where the chunk isn't loaded (or outside
     * a call that lent the world).
     */
    public static native String[] chunkBlocks(String dimension, int cx, int cz, int minY, int[] out);

    /** A loaded chunk's biomes, one a quart: out[((qy - minY / 4) * 4 + qz) * 4 + qx], as chunkBlocks. */
    public static native String[] chunkBiomes(String dimension, int cx, int cz, int minY, int[] out);

    /** The number of the world view Horizon lent last: what was read of an older one may be stale. */
    public static native long viewEpoch();

    /**
     * This thread's view changes so far (one when a call lends it a view of the world, one when
     * the view is put back): what the thread read of the world stays true while it stays.
     */
    public static native long threadEpoch();

    /**
     * This thread's view counter itself (the 8 bytes threadEpoch reads, native order), for a
     * read without a JNI call: a mob's collisions read thousands of blocks a tick and each asks
     * whether the view changed. Good for the calling thread only, while it lives.
     */
    public static native java.nio.ByteBuffer threadEpochBuffer();

    /**
     * Horizon's state ids of the 16x16x16 section cx sy cz into out[(y * 16 + z) * 16 + x]
     * (named by stateName): 1, 0 where its chunk isn't loaded, -1 when this call's view can't
     * give them (read chunkBlocks then).
     */
    public static native int sectionIds(String dimension, int cx, int sy, int cz, int[] out);

    /**
     * A number for chunk cx cz's blocks as they are (new when one changes, or the chunk is
     * another): -2 where it isn't loaded, -1 when this call's view can't say.
     */
    public static native long chunkRevision(String dimension, int cx, int cz);

    /** The state a sectionIds id is ({@code minecraft:oak_log[axis=y]}), or null. */
    public static native String stateName(int id);

    /** WORLD_SURFACE's height at x z (one above the highest non-air block); MIN_VALUE where unloaded. */
    public static native int surface(String dimension, int x, int z);

    /** The chunks of the dimension Horizon generated since the last call: cx, cz pairs. */
    public static native int[] freshChunks(String dimension);

    /**
     * A mod's block entity came up at x y z of a dimension (up), or went: Horizon keeps a
     * placeholder for it in its block entity list, so its tick comes in vanilla's order.
     */
    public static native void blockEntityTicker(String dimension, int x, int y, int z, boolean up);

    /** A mod set a block of a mirrored level Horizon can't write now: queued, made after the call. */
    public static native void setBlock(String dimension, int x, int y, int z, String state);

    /**
     * A mod's Level.setBlock made in Horizon's world now, with vanilla's updates (neighbours,
     * shapes, block entities): {changed (0/1), n, (x, y, z, Horizon's new state id) per block the
     * set changed, n, (x, y, z, the state id replaced, -1 for none) per mod block placed or
     * removed, n, (x, y, z) per position whose neighbours Horizon updated beside a mod's block}.
     * Null where Horizon can't write there now (another dimension, a chunk not loaded, no view):
     * queue it with setBlock.
     */
    public static native int[] setBlockNow(String dimension, int x, int y, int z, String state, int flags);

    /** A mod dropped an item (a block's drops): Horizon spawns the item entity. */
    public static native void dropItem(String dimension, double x, double y, double z,
                                       double vx, double vy, double vz, String item, int count);

    /**
     * A mod's level event (LevelEvent: 2001 a block's break particles and sound): {@code state}
     * is the block state the data names, for the events whose data is one, else null;
     * {@code except} the UUID of the player it leaves out (its source), else null.
     */
    public static native void levelEvent(String dimension, int type, int x, int y, int z, int data, String state, String except);

    /**
     * A vanilla entity a mod's code added to a mirrored level (Carry On putting a carried mob
     * down), as vanilla saves it (SNBT with its id): Horizon loads it as its own.
     */
    public static native void spawnEntity(String dimension, String snbt);

    /**
     * A mod changed a player's inventory slot (vanilla's Inventory order): the stack as SNBT
     * (ItemStack.CODEC), or null for empty.
     */
    public static native void setSlot(String uuid, int slot, String item);

    /** A system message to every player on the server, and its log. */
    public static native void broadcast(String text);

    /** The mods' fluids as the client's models draw them (HorizonFluids): their sprites and tint. */
    public static native void modFluids(String json);

    /** Mods' items drawn with their own model types' quads (HorizonItemModels), once the models load. */
    public static native void modItemModels(String json);

    /**
     * A clientbound packet a mod sent a player that Horizon sends as it is (a sound, particles):
     * vanilla's encoding of it, its id first (a VarInt), then its body.
     */
    public static native void packet(String uuid, byte[] data);

    /**
     * A device mod's message from this game's own client half to the server it plays on
     * (HorizonDeviceClient): its channel and body, sent as a custom payload.
     */
    public static native void devicePayload(String channel, byte[] body);

    /**
     * Java hurt a Horizon player (ServerPlayerHurtMixin): Horizon hurts its player. NaN: no
     * source position; {@code by}: the attacker's name, or null.
     */
    public static native void hurtPlayer(String uuid, String damageType, float amount, double x, double y, double z, String by);

    /**
     * A mod's mob hurt one of Horizon's (its shadow in the mirror, HorizonNeighbours): Horizon's
     * mob `id` of `dimension` takes `amount` of `damageType` from (x, y, z) (NaN: nowhere), by
     * the mods' entity `by` (-1: none).
     */
    /**
     * A hurt of one of Horizon's mobs (its shadow); {@code resetInvulnerable}: the mod cleared
     * the shadow's invulnerable ticks before it (TACZ's bullets), so Horizon's don't block it.
     */
    public static native void hurtMob(String dimension, int id, String damageType, float amount, double x, double y, double z, int by,
                                      boolean resetInvulnerable);

    /**
     * The chunks mods' tickets keep loaded and ticking in a dimension, as forced chunks
     * (ticket level 31): "x,z;x,z;..." (empty: none). Sent when they change.
     */
    public static native void chunkTickets(String dimension, String chunks);

    /** ServerLevel.setRespawnData on a mirrored level: Horizon's world spawn moves there. */
    public static native void worldSpawn(String dimension, int x, int y, int z, float yaw, float pitch);

    /** A game rule a mod or a data pack set on the mirror (its id and serialized value): Horizon's follows. */
    public static native void gameRule(String id, String value);

    /**
     * An instance of {@code type} made without running a constructor (JNI AllocObject): the
     * game objects Horizon's state is mirrored into. Every field starts at its default.
     */
    public static native Object allocate(Class<?> type);
}
