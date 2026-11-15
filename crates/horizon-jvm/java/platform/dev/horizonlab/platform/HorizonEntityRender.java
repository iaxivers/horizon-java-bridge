package dev.horizonlab.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizonlab.bridge.Natives;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Mods' entities as their own renderers draw them, for Horizon to draw. The headless client
 * has no GPU, so each tick each entity's renderer (the one the mod registered, made from an
 * {@code EntityRendererProvider.Context} of the headless client) extracts its render state and
 * submits it into a collector that keeps, for every model submitted, what the GPU would have
 * drawn: the model's part tree (sent once), its parts' poses after {@code setupAnim}, the
 * pose stack's matrix, the texture, tint, light and overlay. Horizon draws those with its own
 * entity renderer (game/java_entities.rs).
 *
 * <p>The snapshot is JSON: {@code {"dims": {dimension: [entity...]}, "models": {key: tree}}};
 * {@code models} holds the trees not sent before (Horizon keeps them). A tree is in the form of
 * Horizon's {@code entity-models.json} (VanillaEntityModelExporter's), its parts named by their
 * path ({@code /body/head}), so names are unique within a model.
 */
public final class HorizonEntityRender {
    private HorizonEntityRender() {}

    private static EntityRendererProvider.Context context;
    private static final Map<EntityType<?>, EntityRenderer<?, ?>> RENDERERS = new IdentityHashMap<>();
    /** Types whose renderer could not be made (logged once). */
    private static final Map<EntityType<?>, Boolean> NO_RENDERER = new IdentityHashMap<>();
    /** A key per model tree (a model's root part), and the trees made since the last snapshot. */
    private static final Map<ModelPart, String> KEYS = new IdentityHashMap<>();
    private static final Map<String, JsonObject> NEW_TREES = new TreeMap<>();
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static Field cubesField;
    private static Field childrenField;
    private static Field setupField;
    private static Field texturesField;

    /**
     * The renderers' context, as Minecraft.onResourceLoadFinished's EntityRenderDispatcher
     * makes it: the models of every layer (vanilla's and those mods registered through
     * Fabric API, which adds them to LayerDefinitions.createRoots), the font and atlases of
     * the headless client. What a GPU-less client lacks (item and block models, equipment
     * assets, skins) is null: renderers that reach for it fail alone.
     */
    private static EntityRendererProvider.Context context() throws ReflectiveOperationException {
        if (context != null) return context;
        var mc = HorizonClient.minecraft();
        var models = new net.minecraft.client.model.geom.EntityModelSet(
                net.minecraft.client.model.geom.LayerDefinitions.createRoots());
        var dispatcher = (net.minecraft.client.renderer.entity.EntityRenderDispatcher)
                Natives.allocate(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class);
        HorizonClient.set(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class, dispatcher, "options", mc.options);
        HorizonClient.set(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class, dispatcher, "font", mc.font);
        HorizonClient.set(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class, dispatcher, "entityModels",
                (java.util.function.Supplier<?>) () -> models);
        var camera = (net.minecraft.client.Camera) Natives.allocate(net.minecraft.client.Camera.class);
        HorizonClient.set(net.minecraft.client.Camera.class, camera, "position", net.minecraft.world.phys.Vec3.ZERO);
        dispatcher.camera = camera;
        // Item models: none (ItemModelResolverMixin), so held and worn items draw nothing.
        var items = new net.minecraft.client.renderer.item.ItemModelResolver(null);
        HorizonClient.set(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class, dispatcher, "itemModelResolver", items);
        // Block models once the client's are loaded (modelsLoaded): renderers that draw blocks
        // (Create's contraptions: their blocks through the model set) need the resolver.
        var blocks = HorizonClient.modelsReady() ? new net.minecraft.client.renderer.block.BlockModelResolver(mc.getModelManager()) : null;
        // The equipment assets (armour and wings' textures by asset id): an empty manager answers
        // MISSING for each, so the player's renderer and mods' layers made with this context run
        // (Better Combat's, Spell Engine's player layers failed on null). (UMP, 2026-11-11)
        var equipment = new net.minecraft.client.resources.model.EquipmentAssetManager();
        context = new EntityRendererProvider.Context(dispatcher, blocks, items, null, null, models, equipment,
                mc.getAtlasManager(), mc.font, null, null);
        // Its renderers by type (renderer(): vanilla's and the mods'), for renderers that draw
        // another entity through it (Carry On's carried mob), and the players' (AvatarRenderer,
        // with the layers mods add to it). The client's own dispatcher is this one.
        HorizonClient.set(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class, dispatcher, "renderers",
                new java.util.AbstractMap<EntityType<?>, EntityRenderer<?, ?>>() {
                    @Override
                    public EntityRenderer<?, ?> get(Object type) {
                        return type instanceof EntityType<?> t ? renderer(t) : null;
                    }

                    @Override
                    public java.util.Set<Map.Entry<EntityType<?>, EntityRenderer<?, ?>>> entrySet() {
                        return java.util.Set.of();
                    }
                });
        try {
            HorizonClient.set(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class, dispatcher, "playerRenderers",
                    net.minecraft.client.renderer.entity.EntityRenderers.createAvatarRenderers(context));
        } catch (Throwable t) {
            Natives.log("Java mods: players' renderers couldn't be made: " + t);
        }
        if (mc.getEntityRenderDispatcher() == null) {
            HorizonClient.set(net.minecraft.client.Minecraft.class, mc, "entityRenderDispatcher", dispatcher);
        }
        cubesField = ModelPart.class.getDeclaredField("cubes");
        cubesField.setAccessible(true);
        childrenField = ModelPart.class.getDeclaredField("children");
        childrenField.setAccessible(true);
        setupField = RenderType.class.getDeclaredField("state");
        setupField.setAccessible(true);
        texturesField = net.minecraft.client.renderer.rendertype.RenderSetup.class.getDeclaredField("textures");
        texturesField.setAccessible(true);
        return context;
    }

    /**
     * The client's models are in (HorizonClient.finishModels): the renderers' contexts are made
     * again with its block models, and renderers that failed for want of them are tried again.
     */
    static void modelsLoaded() {
        try {
            if (context == null) return;
            var mc = HorizonClient.minecraft();
            var blocks = new net.minecraft.client.renderer.block.BlockModelResolver(mc.getModelManager());
            HorizonClient.set(EntityRendererProvider.Context.class, context, "blockModelResolver", blocks);
            if (blockContext != null) {
                HorizonClient.set(net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context.class,
                        blockContext, "blockModelResolver", blocks);
                HorizonClient.set(net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context.class,
                        blockContext, "sprites", context.getSprites());
            }
            NO_RENDERER.clear();
            NO_BLOCK_RENDERER.clear();
        } catch (Throwable t) {
            Natives.log("Java mods: the renderers' block models couldn't be set: " + t);
        }
    }

    /** The renderer the mod registered for this type (EntityRenderers.PROVIDERS), made once. */
    @SuppressWarnings("unchecked")
    private static EntityRenderer<?, ?> renderer(EntityType<?> type) {
        EntityRenderer<?, ?> made = RENDERERS.get(type);
        if (made != null || NO_RENDERER.containsKey(type)) return made;
        try {
            Field field = net.minecraft.client.renderer.entity.EntityRenderers.class.getDeclaredField("PROVIDERS");
            field.setAccessible(true);
            var providers = (Map<EntityType<?>, EntityRendererProvider<?>>) field.get(null);
            EntityRendererProvider<?> provider = providers.get(type);
            if (provider == null) throw new IllegalStateException("no renderer registered");
            made = provider.create(context());
            RENDERERS.put(type, made);
        } catch (Throwable t) {
            NO_RENDERER.put(type, true);
            Natives.log("Java mods: no renderer for " + BuiltInRegistries.ENTITY_TYPE.getKey(type) + ": " + t);
            StackTraceElement[] frames = t.getStackTrace();
            for (int f = 0; f < Math.min(frames.length, 8); f++) Natives.log("    at " + frames[f]);
        }
        return made;
    }

    // ---- Block entities -------------------------------------------------------------------

    /** How far from a player a block entity is drawn (BlockEntityRenderer.getViewDistance's default). */
    private static final int BLOCK_RANGE = 64;
    private static net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context blockContext;
    private static final Map<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.client.renderer.blockentity.BlockEntityRenderer<?, ?>> BLOCK_RENDERERS = new IdentityHashMap<>();
    private static final Map<net.minecraft.world.level.block.entity.BlockEntityType<?>, Boolean> NO_BLOCK_RENDERER =
            new IdentityHashMap<>();
    /** A stable id a block entity's position (negative: never an entity's), for Horizon's tweening. */
    private static final Map<Long, Integer> BLOCK_IDS = new java.util.HashMap<>();
    private static final java.util.Set<Object> DRAWN_LOGGED = new java.util.HashSet<>();

    /**
     * The block entity renderers' context, as BlockEntityRenderDispatcher makes it on a
     * resource reload: the entity renderers' item resolver, model set and sprites (the client's
     * atlases: Create's saw reads its blade's), the headless client's font; block models once
     * they load (modelsLoaded).
     */
    private static net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context blockContext()
            throws ReflectiveOperationException {
        if (blockContext != null) return blockContext;
        var entities = context();
        var dispatcher = (net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher)
                Natives.allocate(net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher.class);
        blockContext = new net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context(
                dispatcher, entities.getBlockModelResolver(), entities.getItemModelResolver(), entities.getEntityRenderDispatcher(),
                entities.getModelSet(), entities.getFont(), entities.getSprites(), null);
        return blockContext;
    }

    /** The renderer the mod registered for a block entity type (BlockEntityRenderers.PROVIDERS). */
    @SuppressWarnings("unchecked")
    private static net.minecraft.client.renderer.blockentity.BlockEntityRenderer<?, ?> blockRenderer(
            net.minecraft.world.level.block.entity.BlockEntityType<?> type) {
        var made = BLOCK_RENDERERS.get(type);
        if (made != null || NO_BLOCK_RENDERER.containsKey(type)) return made;
        try {
            Field field = net.minecraft.client.renderer.blockentity.BlockEntityRenderers.class.getDeclaredField("PROVIDERS");
            field.setAccessible(true);
            var providers = (Map<net.minecraft.world.level.block.entity.BlockEntityType<?>,
                    net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider<?, ?>>) field.get(null);
            var provider = providers.get(type);
            if (provider == null) {
                // Most block entities draw only their block's model: Horizon's already.
                NO_BLOCK_RENDERER.put(type, true);
                if (DEBUG_BER) Natives.log("ber " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type) + ": no renderer registered");
                return null;
            }
            made = provider.create(blockContext());
            BLOCK_RENDERERS.put(type, made);
        } catch (Throwable t) {
            NO_BLOCK_RENDERER.put(type, true);
            Natives.log("Java mods: no renderer for " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type) + ": " + t);
            StackTraceElement[] frames = t.getStackTrace();
            for (int f = 0; f < Math.min(frames.length, 8); f++) Natives.log("    at " + frames[f]);
        }
        return made;
    }

    /** A level's mod block entities near its players, as their renderers submit them. */
    private static JsonArray blockEntities(ServerLevel level) {
        JsonArray out = new JsonArray();
        var players = level.players();
        // Not before the client's models and its level renderer's reload (a renderer drawing
        // earlier fails once and its first look is lost).
        if (players.isEmpty() || !HorizonClient.levelRendered()) return out;
        for (var be : HorizonBlockEntities.live(level)) {
            if (be.isRemoved()) continue;
            var pos = be.getBlockPos();
            boolean near = false;
            for (var p : players) {
                if (p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < BLOCK_RANGE * BLOCK_RANGE) {
                    near = true;
                    break;
                }
            }
            if (!near) continue;
            var renderer = blockRenderer(be.getType());
            // A model whose parts in a world aren't its own (worldParts): drawn here, at its place.
            JsonArray world = worldParts(level, be);
            if (renderer == null && world == null) continue;
            JsonArray itemsHere = new JsonArray();
            JsonArray models = renderer == null ? new JsonArray() : blockModels(renderer, HorizonClientBlockEntities.of(be), itemsHere);
            if (world != null) models.addAll(world);
            if (DEBUG_BER) debugBlock(level, be, models.size(), itemsHere.size());
            // HORIZON_JAVA_DEBUG=ber: what each block state's renderer drew, once.
            if (DEBUG_BER && DRAWN_LOGGED.add(be.getBlockState())) {
                StringBuilder d = new StringBuilder();
                for (var m : models) {
                    var mo = m.getAsJsonObject();
                    int quads = mo.has("v") ? (int) (mo.get("v").toString().chars().filter(c -> c == ',').count() + 1) / 36 : -1;
                    d.append(' ').append(mo.has("tex") ? mo.get("tex").getAsString() : mo.has("key") ? mo.get("key").getAsString() : "?");
                    if (quads >= 0) d.append(" (").append(quads).append(" quads)");
                }
                Natives.log("Java mods: drawn " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()) + " "
                        + be.getBlockState() + ":" + (d.length() == 0 ? " nothing" : d) + "; its block model: "
                        + chunkQuads(be.getBlockState()) + " quads" + (whole(be) ? ", drawn whole by its renderer" : ""));
            }
            if (models.isEmpty() && itemsHere.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", BLOCK_IDS.computeIfAbsent(pos.asLong(), k -> -1 - BLOCK_IDS.size()));
            o.addProperty("type", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
            o.addProperty("block", true);
            if (world != null || whole(be)) o.addProperty("whole", true);
            o.addProperty("x", pos.getX());
            o.addProperty("y", pos.getY());
            o.addProperty("z", pos.getZ());
            o.add("models", models);
            if (!itemsHere.isEmpty()) o.add("items", itemsHere);
            out.add(o);
        }
        return out;
    }

    /** HORIZON_JAVA_DEBUG=ber: each drawn mod block entity, every 5 s: what it drew and its data. */
    private static final boolean DEBUG_BER = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("ber");
    private static final Map<Long, Long> BER_LOGGED = new java.util.HashMap<>();

    /**
     * One line for a drawn block entity: its type and place, how many models and items its
     * renderer submitted, and its data as the mirror saves it (a Create shaft's Speed: whether
     * its kinetic network turns, which Horizon's /data can't tell, its world having no copy).
     */
    private static void debugBlock(ServerLevel level, net.minecraft.world.level.block.entity.BlockEntity be, int models, int items) {
        long now = level.getGameTime();
        Long last = BER_LOGGED.get(be.getBlockPos().asLong());
        if (last != null && now - last < 100) return;
        BER_LOGGED.put(be.getBlockPos().asLong(), now);
        String data;
        try {
            data = be.saveWithoutMetadata(level.registryAccess()).toString();
        } catch (Throwable t) {
            data = "(not saved: " + t + ")";
        }
        if (data.length() > 400) data = data.substring(0, 400) + "...";
        // The client's copy too (what its renderer reads), when it differs.
        var copy = HorizonClientBlockEntities.of(be);
        if (copy != be) {
            String c;
            try {
                c = copy.saveWithoutMetadata(level.registryAccess()).toString();
            } catch (Throwable t) {
                c = "(not saved: " + t + ")";
            }
            if (c.length() > 300) c = c.substring(0, 300) + "...";
            if (!c.equals(data)) data += "; client copy: " + c;
        }
        var pos = be.getBlockPos();
        Natives.log("ber " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()) + " at " + pos.getX() + " " + pos.getY()
                + " " + pos.getZ() + ": " + models + " model(s), " + items + " item(s), " + data);
    }

    /** Per block state: whether its renderer draws the whole block (see whole(BlockEntity)). */
    private static final Map<net.minecraft.world.level.block.state.BlockState, Boolean> WHOLE = new ConcurrentHashMap<>();

    /**
     * Whether a block entity's renderer draws its whole block: the block's model has geometry of
     * its own (an item, a block breaking) but none in a world. Create wraps its shafts' and
     * cogwheels' models (BracketedKineticBlockModel: in a world, only the brackets a block
     * entity has), and its renderers draw them turning; Horizon's chunk would draw them still
     * beneath. A wrapper adds a world's parts through {@code addPartsWithInfo(BlockAndTintGetter,
     * BlockPos, BlockState, RandomSource, List)}; models without one are drawn as they are.
     */
    private static boolean whole(net.minecraft.world.level.block.entity.BlockEntity be) {
        var state = be.getBlockState();
        Boolean known = WHOLE.get(state);
        if (known != null) return known;
        boolean result = false;
        try {
            var model = net.minecraft.client.Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            Method parts = null;
            for (Class<?> c = model.getClass(); c != null && parts == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    var types = m.getParameterTypes();
                    if (m.getName().equals("addPartsWithInfo") && types.length == 5 && !java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            && types[1] == net.minecraft.core.BlockPos.class && types[4] == List.class) {
                        parts = m;
                        break;
                    }
                }
            }
            if (parts != null && chunkQuads(state) > 0) {
                parts.setAccessible(true);
                var level = HorizonClient.clientLevel(be.getLevel());
                var list = new java.util.ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
                parts.invoke(model, level, be.getBlockPos(), state, net.minecraft.util.RandomSource.create(42), list);
                int n = 0;
                for (var part : list) {
                    for (var side : net.minecraft.core.Direction.values()) n += part.getQuads(side).size();
                    n += part.getQuads(null).size();
                }
                result = n == 0;
            }
        } catch (Throwable t) {
            if (FAILED.add("whole " + state.getBlock())) Natives.log("Java mods: " + state + "'s model in a world couldn't be read: " + t);
        }
        // Not kept until the models are loaded (the first ticks).
        if (HorizonClient.modelsLoaded()) WHOLE.put(state, result);
        return result;
    }

    /** Per block state: its model's addPartsWithInfo (a wrapper's parts in a world), or null when it has none. */
    private static final Map<net.minecraft.world.level.block.state.BlockState, Optional<Method>> WORLD_METHOD = new ConcurrentHashMap<>();
    /** Block states some place of which drew other parts in a world than the model's own: drawn here everywhere. */
    private static final java.util.Set<net.minecraft.world.level.block.state.BlockState> WORLD_DRAWN = ConcurrentHashMap.newKeySet();
    /** Each place's world parts as last made: its state, the game time, the quads. */
    private record WorldParts(net.minecraft.world.level.block.state.BlockState state, long time, JsonArray models) {}
    private static final Map<ServerLevel, Map<Long, WorldParts>> WORLD_PARTS = new java.util.WeakHashMap<>();
    /** How often a place's world parts are made again (they follow its block entity's data and its neighbours). */
    private static final int WORLD_PARTS_TICKS = 10;

    private static Method partsMethod(Object model) {
        for (Class<?> c = model.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                var types = m.getParameterTypes();
                if (m.getName().equals("addPartsWithInfo") && types.length == 5 && !java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && types[1] == net.minecraft.core.BlockPos.class && types[4] == List.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * A block whose client model draws other parts in a world than its own (a wrapper's
     * {@code addPartsWithInfo(BlockAndTintGetter, BlockPos, BlockState, RandomSource, List)}:
     * Create's fluid pipes add their arms and rims from the block entity's connections, its
     * shafts their brackets): those parts at its place in the client's level, as quads, and
     * the block drawn whole here (Horizon's chunk, which has only the model's own parts, leaves
     * that state out: a pipe drew only its middle). Null for every other block. Once one place
     * of a state differs, every place of that state is drawn here (the chunk leaves the state out).
     */
    private static JsonArray worldParts(ServerLevel level, net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!HorizonClient.modelsLoaded()) return null;
        var state = be.getBlockState();
        try {
            var model = net.minecraft.client.Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            Method parts = WORLD_METHOD.computeIfAbsent(state, s -> Optional.ofNullable(partsMethod(model))).orElse(null);
            if (parts == null) return null;
            long key = be.getBlockPos().asLong();
            long now = level.getGameTime();
            Map<Long, WorldParts> places;
            synchronized (WORLD_PARTS) {
                places = WORLD_PARTS.computeIfAbsent(level, l -> new java.util.HashMap<>());
            }
            WorldParts kept = places.get(key);
            if (kept != null && kept.state() == state && now - kept.time() >= 0 && now - kept.time() < WORLD_PARTS_TICKS) {
                return WORLD_DRAWN.contains(state) ? kept.models() : null;
            }
            var list = new java.util.ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
            var client = HorizonClient.clientLevel(level);
            parts.invoke(model, client, be.getBlockPos(), state, net.minecraft.util.RandomSource.create(42), list);
            int n = 0;
            for (var part : list) {
                for (var side : net.minecraft.core.Direction.values()) n += part.getQuads(side).size();
                n += part.getQuads(null).size();
            }
            JsonArray out = new JsonArray();
            if (n > 0) blockModel(out, new PoseStack(), list, null, null);
            places.put(key, new WorldParts(state, now, out));
            if (n != chunkQuads(state) && WORLD_DRAWN.add(state) && DEBUG_BER) {
                Natives.log("Java mods: " + state + " draws " + n + " quads in a world, " + chunkQuads(state) + " of its own: drawn whole by its block entity");
            }
            return WORLD_DRAWN.contains(state) ? out : null;
        } catch (Throwable t) {
            Throwable root = t instanceof java.lang.reflect.InvocationTargetException i && i.getCause() != null ? i.getCause() : t;
            if (FAILED.add("world " + state.getBlock())) Natives.log("Java mods: " + state + "'s model in a world couldn't be read: " + root);
            return null;
        }
    }

    /** The quads the client's model of a block state has (what a chunk would draw of it), or -1. */
    private static int chunkQuads(net.minecraft.world.level.block.state.BlockState state) {
        try {
            var model = net.minecraft.client.Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            var parts = new java.util.ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
            model.collectParts(net.minecraft.util.RandomSource.create(42), parts);
            int n = 0;
            for (var part : parts) {
                for (var side : net.minecraft.core.Direction.values()) n += part.getQuads(side).size();
                n += part.getQuads(null).size();
            }
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonArray blockModels(net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer,
                                         net.minecraft.world.level.block.entity.BlockEntity be, JsonArray heldItems) {
        JsonArray out = new JsonArray();
        items = heldItems;
        try {
            var state = renderer.createRenderState();
            renderer.extractRenderState(be, state, PARTIAL_TICK, net.minecraft.world.phys.Vec3.ZERO, null);
            CameraRenderState camera = new CameraRenderState();
            camera.orientation = new org.joml.Quaternionf();
            camera.pos = net.minecraft.world.phys.Vec3.ZERO;
            var c = collector(out);
            renderer.submit(state, new PoseStack(), c, camera);
            flush(c);
        } catch (Throwable t) {
            String key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()) + " " + t;
            if (FAILURES.merge(key, 1, Integer::sum) == 1) {
                Natives.log("Java mods: drawing " + key);
                StackTraceElement[] frames = t.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
            }
        } finally {
            items = null;
        }
        return out;
    }

    /** A float sent each tick (a matrix, a pose): to 1e-4, which is all a model needs. */
    static float round(float f) {
        return Math.round(f * 10000.0F) / 10000.0F;
    }

    /**
     * An entry of the snapshot as Horizon routes it: where it is and the model trees it draws
     * (which players are sent it, which trees go with it), then the entry itself ("e"), passed
     * on as it is.
     */
    private static JsonObject routed(JsonObject entry) {
        JsonObject out = new JsonObject();
        out.add("x", entry.get("x"));
        out.add("y", entry.get("y"));
        out.add("z", entry.get("z"));
        JsonArray keys = new JsonArray();
        if (entry.get("models") instanceof JsonArray models) {
            for (var m : models) {
                if (m instanceof JsonObject o && o.has("key")) keys.add(o.get("key"));
            }
        }
        if (!keys.isEmpty()) out.add("keys", keys);
        out.add("e", entry);
        return out;
    }

    /** The snapshot of every level's mods' entities (see the class), for Horizon. */
    public static String snapshot() {
        long phase = HorizonProfile.start();
        try {
            return snapshotNow();
        } finally {
            HorizonProfile.add("snapshot", phase);
        }
    }

    /**
     * What each entity looked like when last sent whole: its entry without id, type and
     * position (HorizonJson), and its model keys. An entity that looks the same (standing,
     * resting) is sent as a "keep" entry: its position only, the player's game keeps the look
     * (Horizon's side sends the whole entry to a player that hasn't had it).
     */
    private record Look(String json, String keys, double x, double y, double z, float yaw, float pitch, float head) {
        /** Standing as when this look was made: the same place and facing, not hurt. */
        boolean still(Entity e) {
            return e.getX() == x && e.getY() == y && e.getZ() == z && e.getYRot() == yaw && e.getXRot() == pitch
                    && e.getYHeadRot() == head && !(e instanceof net.minecraft.world.entity.LivingEntity l && (l.hurtTime > 0 || l.deathTime > 0));
        }
    }

    private static final Map<Entity, Look> LOOKS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static String snapshotNow() {
        StringBuilder out = new StringBuilder(16384);
        out.append("{\"dims\":{");
        boolean firstDim = true;
        var all = HorizonEntities.all();
        for (ServerLevel level : HorizonServer.levels()) {
            String dimension = level.dimension().identifier().toString();
            int mark = out.length();
            if (!firstDim) out.append(',');
            HorizonJson.string(dimension, out);
            out.append(":[");
            int listStart = out.length();
            var entities = all.get(level);
            if (entities != null) {
                // Its players' places: how far each entity is from the nearest.
                var players = level.players();
                double[] seen = new double[players.size() * 3];
                for (int i = 0; i < players.size(); i++) {
                    seen[i * 3] = players.get(i).getX();
                    seen[i * 3 + 1] = players.get(i).getY();
                    seen[i * 3 + 2] = players.get(i).getZ();
                }
                for (Entity entity : entities) {
                    if (entity.isRemoved()) continue;
                    if (out.length() > listStart) out.append(',');
                    entry(entity, nearest(entity, seen), out);
                }
            }
            // What mods draw on its players (their layers, as entries marked "block": no box).
            for (var p : level.players()) {
                String drawn = player(p);
                if (drawn == null) continue;
                if (out.length() > listStart) out.append(',');
                out.append(drawn);
            }
            // Mods' block entities their renderers draw (a cutting board's item, a stove's
            // food), as entities marked "block" at the block's corner.
            for (var b : blockEntities(level)) {
                if (out.length() > listStart) out.append(',');
                HorizonJson.write(routed(b.getAsJsonObject()), out);
            }
            if (out.length() == listStart) {
                out.setLength(mark);
                continue;
            }
            out.append(']');
            firstDim = false;
        }
        out.append("},\"hands\":{");
        boolean firstHand = true;
        for (var h : HANDS.entrySet()) {
            if (!firstHand) out.append(',');
            firstHand = false;
            HorizonJson.string(h.getKey(), out);
            out.append(':').append(h.getValue());
        }
        HANDS.clear();
        // What Horizon's own mobs see of the mods' living ones (HorizonNeighbours.seen).
        out.append("},\"seen\":{");
        HorizonNeighbours.seen(out);
        // Players riding a mod's mob: its id and the seat (positionRider's place this tick),
        // by UUID: their games sit them there.
        out.append("},\"rides\":{");
        boolean firstRide = true;
        for (net.minecraft.server.level.ServerPlayer p : HorizonServer.players()) {
            Entity vehicle = p.getVehicle();
            if (vehicle == null || !HorizonEntities.modded(vehicle.getType())) continue;
            if (!firstRide) out.append(',');
            firstRide = false;
            HorizonJson.string(p.getStringUUID(), out);
            out.append(":{\"id\":").append(vehicle.getId()).append(",\"p\":[")
                    .append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(']');
            // A horse-style jump its game charges a bar for (PlayerRideableJumping).
            if (vehicle instanceof net.minecraft.world.entity.PlayerRideableJumping j && j.canJump()) out.append(",\"jump\":true");
            // Its hearts on the rider's HUD (Gui's vehicle health: a living one that shows them).
            if (vehicle instanceof net.minecraft.world.entity.LivingEntity l && l.showVehicleHealth()) {
                out.append(",\"hp\":[").append(l.getHealth()).append(',').append(l.getMaxHealth()).append(']');
            }
            out.append('}');
        }
        // The blocks the mods' container entities are in (HorizonHoppers): Horizon's hoppers
        // and droppers there ask Java.
        out.append("},\"containers\":{");
        HorizonHoppers.containers(out);
        out.append("},\"models\":{");
        boolean first = true;
        for (var t : NEW_TREES.entrySet()) {
            if (!first) out.append(',');
            first = false;
            HorizonJson.string(t.getKey(), out);
            out.append(':');
            HorizonJson.write(t.getValue(), out);
        }
        NEW_TREES.clear();
        out.append("}}");
        return out.toString();
    }

    /** The squared distance from the entity to the nearest of these places (x, y, z each). */
    private static double nearest(Entity entity, double[] at) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 2 < at.length; i += 3) {
            double dx = entity.getX() - at[i], dy = entity.getY() - at[i + 1], dz = entity.getZ() - at[i + 2];
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return best;
    }

    /**
     * How many ticks apart an entity this far (squared) from the nearest player is drawn again:
     * every tick within 24 blocks, every 2nd to 48, every 4th beyond (its position still each
     * tick; the game keeps its look between). A renderer's work (a GeckoLib model's animated
     * mesh) is most of the snapshot's time.
     */
    static int drawEvery(double distance2) {
        if (distance2 <= 24.0 * 24.0) return 1;
        return distance2 <= 48.0 * 48.0 ? 2 : 4;
    }

    /** One entity's routed entry: {x, y, z, id, keys, keep?, e: the entry}. */
    private static void entry(Entity entity, double distance2, StringBuilder out) {
        StringBuilder head = new StringBuilder(96);
        head.append("\"id\":").append(entity.getId()).append(",\"type\":");
        HorizonJson.string(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), head);
        head.append(",\"x\":");
        HorizonJson.number(entity.getX(), head);
        head.append(",\"y\":");
        HorizonJson.number(entity.getY(), head);
        head.append(",\"z\":");
        HorizonJson.number(entity.getZ(), head);
        Look had = LOOKS.get(entity);
        // It didn't tick (far and idle, HorizonEntities.active): it looks as it did. Standing
        // still, it is drawn every other tick (its idle animation at 10 Hz, lerped).
        // Far from every player, it is drawn every 2nd or 4th tick (drawEvery), unless hurt.
        int every = drawEvery(distance2);
        boolean hurt = entity instanceof net.minecraft.world.entity.LivingEntity l && (l.hurtTime > 0 || l.deathTime > 0);
        boolean reuse = had != null && (HorizonEntities.resting(entity)
                || ((entity.tickCount + entity.getId()) & 1) == 1 && had.still(entity)
                || every > 1 && !hurt && Math.floorMod(entity.tickCount + entity.getId(), every) != 0);
        Look look = reuse ? had : look(entity);
        boolean keep = had != null && had.json().equals(look.json());
        if (!reuse) LOOKS.put(entity, look);
        String at = head.substring(head.indexOf(",\"x\":") + 1);
        out.append('{').append(at).append(",\"id\":").append(entity.getId());
        if (!look.keys().isEmpty()) out.append(",\"keys\":").append(look.keys());
        if (keep) {
            out.append(",\"keep\":true,\"e\":{").append(head).append(",\"keep\":true}}");
            return;
        }
        out.append(",\"e\":{").append(head);
        if (look.json().length() > 2) out.append(',').append(look.json(), 1, look.json().length());
        else out.append('}');
        out.append('}');
    }

    /** What its renderer draws now, and its name tag and shadow: the entry's look. */
    private static Look look(Entity entity) {
        posing = entity;
        JsonObject o = new JsonObject();
        o.addProperty("w", entity.getBbWidth());
        o.addProperty("h", entity.getBbHeight());
        JsonArray items = new JsonArray();
        long started = HorizonProfile.start();
        JsonArray models = models(entity, items);
        o.add("models", models);
        if (HorizonProfile.ON) HorizonProfile.render(
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), System.nanoTime() - started);
        if (!items.isEmpty()) o.add("items", items);
        // Its custom name, when its renderer couldn't say (Horizon shows it as its own
        // mobs': visible, or looked at).
        if (!extracted && entity.getCustomName() != null) {
            o.add("name", com.google.gson.JsonParser.parseString(HorizonServer.json(entity.getCustomName())));
            o.addProperty("name_visible", entity.isCustomNameVisible());
        }
        // The name tag its renderer gives (a custom name, or a mod's own: a pet's owner,
        // health): shown always, or when looked at; above its box, or where the renderer
        // attaches it.
        if (lastTag != null) {
            o.add("tag", com.google.gson.JsonParser.parseString(HorizonServer.json(lastTag)));
            if (lastTagAlways) o.addProperty("tag_always", true);
            if (lastTagY >= 0.0) o.addProperty("tag_y", lastTagY);
        }
        float shadow = lastShadow;
        if (shadow > 0.0F) o.addProperty("shadow", shadow);
        JsonArray keys = new JsonArray();
        for (var m : models) {
            if (m instanceof JsonObject mo && mo.has("key")) keys.add(mo.get("key"));
            if (m instanceof JsonObject mo && mo.has("img")) keys.add(mo.get("img"));
        }
        return new Look(HorizonJson.write(o), keys.isEmpty() ? "" : HorizonJson.write(keys),
                entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot(), entity.getYHeadRot());
    }

    /**
     * What the entity's renderer submits (EntityRenderDispatcher.submit without the camera's
     * translation: matrices are relative to the entity's position), at the tick's end.
     */
    // ---- Players: what mods draw on them (Carry On's carried block or mob) -------------

    /**
     * Recording only what mods' layers submit (a player: Horizon draws the body, its armour
     * and held items itself), and, in first person, what vanilla's own arm and held item
     * aren't (handStack: the hand's item; vanillaHand: vanilla's own drawing was seen).
     */
    private static boolean onlyMods, inModLayer, firstPerson, vanillaHand;
    private static net.minecraft.world.item.ItemStack handStack;

    private static boolean kept(Object model) {
        if (firstPerson && model instanceof net.minecraft.client.model.player.PlayerModel) {
            vanillaHand = true;
            return false;
        }
        return !onlyMods || inModLayer || submittedByMod();
    }

    private static final Map<String, Boolean> MOD_FRAMES = new ConcurrentHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /**
     * Whether a mod's code made this submit: going out from the collector, past Horizon's own
     * frames there, a mod's frame (a mod's class, or a mod's handler merged into a vanilla
     * class: TACZ draws its guns from its injection into vanilla's ItemInHandLayer) comes
     * before Horizon's driver, as HorizonGraphics' modsOnly decides for HUD ops.
     */
    private static boolean submittedByMod() {
        return WALKER.walk(frames -> {
            boolean inner = true;
            for (var it = frames.iterator(); it.hasNext(); ) {
                var frame = it.next();
                Class<?> owner = frame.getDeclaringClass();
                String name = owner.getName();
                boolean ours = name.startsWith("dev.horizonlab.") || name.startsWith("jdk.proxy") || name.startsWith("java.lang.reflect.");
                if (inner) {
                    if (ours) continue;
                    inner = false;
                }
                if (name.startsWith("dev.horizonlab.")) return false;
                if (MOD_FRAMES.computeIfAbsent(name + "#" + frame.getMethodName() + frame.getDescriptor(),
                        k -> HorizonGraphics.isModFrame(owner, frame))) return true;
            }
            return false;
        });
    }

    /** A mod's layer of a player renderer, marked while it submits (onlyMods keeps those). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class ModLayer extends net.minecraft.client.renderer.entity.layers.RenderLayer {
        final net.minecraft.client.renderer.entity.layers.RenderLayer inner;

        ModLayer(net.minecraft.client.renderer.entity.RenderLayerParent parent, net.minecraft.client.renderer.entity.layers.RenderLayer inner) {
            super(parent);
            this.inner = inner;
        }

        @Override
        public void submit(PoseStack pose, SubmitNodeCollector collector, int light, EntityRenderState state, float yRot, float xRot) {
            boolean was = inModLayer;
            inModLayer = true;
            try {
                inner.submit(pose, collector, light, state, yRot, xRot);
            } finally {
                inModLayer = was;
            }
        }
    }

    /** Player renderers whose mods' layers are wrapped, and whether they have any. */
    private static final Map<Object, Boolean> MARKED = new IdentityHashMap<>();

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean modLayers(net.minecraft.client.renderer.entity.LivingEntityRenderer renderer) throws ReflectiveOperationException {
        Boolean had = MARKED.get(renderer);
        if (had != null) return had;
        var layers = (List) HorizonClient.field(net.minecraft.client.renderer.entity.LivingEntityRenderer.class, renderer, "layers");
        boolean any = false;
        for (int i = 0; i < layers.size(); i++) {
            Object layer = layers.get(i);
            if (layer instanceof ModLayer) {
                any = true;
            } else if (!layer.getClass().getName().startsWith("net.minecraft.")) {
                layers.set(i, new ModLayer(renderer, (net.minecraft.client.renderer.entity.layers.RenderLayer) layer));
                any = true;
            }
        }
        MARKED.put(renderer, any);
        return any;
    }

    /** Each mirrored player's client avatar: what player renderers draw. */
    private static final Map<java.util.UUID, net.minecraft.client.player.RemotePlayer> AVATARS = new ConcurrentHashMap<>();

    /** A player's skin changed (HorizonSkins): its avatar is made again, its skin looked up anew. */
    static void forgetAvatar(java.util.UUID id) {
        AVATARS.remove(id);
    }

    /**
     * The player as a client sees another (a RemotePlayer in its client level), where the
     * server player is, posed as it is, its synced data, items and mods' attachments its own.
     */
    private static net.minecraft.client.player.RemotePlayer avatar(net.minecraft.server.level.ServerPlayer p) throws ReflectiveOperationException {
        var clientLevel = HorizonClient.clientLevel(p.level());
        var a = AVATARS.get(p.getUUID());
        if (a == null || a.level() != clientLevel) {
            a = new net.minecraft.client.player.RemotePlayer(clientLevel, p.getGameProfile());
            // Its tab-list entry (its skin), which a client's connection would have.
            HorizonClient.set(net.minecraft.client.player.AbstractClientPlayer.class, a, "playerInfo",
                    new net.minecraft.client.multiplayer.PlayerInfo(p.getGameProfile(), false));
            AVATARS.put(p.getUUID(), a);
        }
        a.setId(p.getId());
        a.setPos(p.getX(), p.getY(), p.getZ());
        a.xo = a.xOld = p.getX();
        a.yo = a.yOld = p.getY();
        a.zo = a.zOld = p.getZ();
        a.setYRot(p.getYRot());
        a.setXRot(p.getXRot());
        a.yRotO = p.getYRot();
        a.xRotO = p.getXRot();
        a.yBodyRot = a.yBodyRotO = p.yBodyRot;
        a.yHeadRot = a.yHeadRotO = p.yHeadRot;
        a.tickCount = p.tickCount;
        HorizonClient.set(net.minecraft.world.entity.LivingEntity.class, a, "swingState",
                HorizonClient.field(net.minecraft.world.entity.LivingEntity.class, p, "swingState"));
        a.setPose(p.getPose());
        a.setShiftKeyDown(p.isShiftKeyDown());
        for (String f : new String[] {"position", "speed", "speedOld"}) {
            HorizonClient.set(net.minecraft.world.entity.WalkAnimationState.class, a.walkAnimation, f,
                    HorizonClient.field(net.minecraft.world.entity.WalkAnimationState.class, p.walkAnimation, f));
        }
        HorizonClient.set(net.minecraft.world.entity.Entity.class, a, "entityData", p.getEntityData());
        HorizonClient.set(net.minecraft.world.entity.LivingEntity.class, a, "equipment",
                HorizonClient.field(net.minecraft.world.entity.LivingEntity.class, p, "equipment"));
        HorizonClient.set(net.minecraft.world.entity.player.Player.class, a, "inventory", p.getInventory());
        HorizonClient.shareAttachments(p, a);
        // What mods keep on the player (Fabric's attachments: Carry On's carry data; mixins'
        // own fields): the server player's.
        for (Class<?> c : new Class<?>[] {net.minecraft.world.entity.Entity.class,
                net.minecraft.world.entity.LivingEntity.class, net.minecraft.world.entity.player.Player.class}) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                String type = f.getType().getName();
                boolean added = f.getName().contains("$") || f.getName().startsWith("fabric_");
                boolean modType = !(type.startsWith("net.minecraft.") || type.startsWith("com.mojang.") || type.startsWith("java.")
                        || type.startsWith("javax.") || type.startsWith("org.joml.") || type.startsWith("it.unimi.") || f.getType().isArray());
                if (!added && !modType) continue;
                try {
                    f.setAccessible(true);
                    f.set(a, f.get(p));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // A field it can't reach stays its own.
                }
            }
        }
        return a;
    }

    private static Boolean firstPersonHooked;

    /** Whether mods' injections sit in FirstPersonHandsAndItemsRenderer's arm or hands drawing. */
    private static boolean firstPersonHooked() {
        if (firstPersonHooked == null) {
            boolean any = false;
            for (var m : net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class.getDeclaredMethods()) {
                var merged = m.getAnnotation(org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
                if (merged != null && !merged.mixin().startsWith("dev.horizonlab.") && !merged.mixin().startsWith("net.fabricmc.")) {
                    any = true;
                    break;
                }
            }
            firstPersonHooked = any;
        }
        return firstPersonHooked;
    }

    /** First person: vanilla's renderer of the hands, whose arm submits mods hook (Carry On). */
    private static net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer hands;
    private static java.lang.reflect.Method submitArm;
    /** Players that had something drawn by mods last tick (an empty entry clears it). */
    private static final java.util.Set<java.util.UUID> DREW = new java.util.HashSet<>();
    /** Each player's mods' first-person drawing this snapshot, by UUID (JSON). */
    private static final Map<String, String> HANDS = new java.util.LinkedHashMap<>();

    /**
     * What mods draw on a mirrored player (their layers of its renderer), as an entry like a
     * mod entity's: at the player, no box ("block"), its "owner" the player (its own game
     * draws it in third person only). Its first person (HANDS) too. Null when nothing.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String player(net.minecraft.server.level.ServerPlayer p) {
        try {
            var a = avatar(p);
            var dispatcher = context().getEntityRenderDispatcher();
            if (!(dispatcher.getRenderer(a) instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer renderer)) return null;
            // Mods that draw the first-person arm or its item themselves (TACZ's guns, at
            // submitArmWithItem's head) need its drawing whether or not they add layers.
            if (!modLayers(renderer) && !modelPartsPatched() && !firstPersonHooked()) return null;
            JsonArray out = new JsonArray();
            JsonArray held = new JsonArray();
            EntityRenderState[] made = new EntityRenderState[1];
            items = held;
            onlyMods = true;
            try {
                HorizonClient.as(p, () -> made[0] = renderer.createRenderState(a, PARTIAL_TICK));
                PoseStack pose = new PoseStack();
                var offset = renderer.getRenderOffset(made[0]);
                pose.translate(offset.x, offset.y, offset.z);
                CameraRenderState camera = new CameraRenderState();
                camera.orientation = new org.joml.Quaternionf();
                camera.pos = net.minecraft.world.phys.Vec3.ZERO;
                capturePlayer = modelPartsPatched();
                playerSubmit = null;
                var c = collector(out);
                renderer.submit(made[0], pose, c, camera);
                flush(c);
            } finally {
                onlyMods = false;
                capturePlayer = false;
                items = null;
            }
            modParts(out, p, a);
            firstPerson(p, a, made[0]);
            if (out.isEmpty() && held.isEmpty()) return null;
            StringBuilder e = new StringBuilder(256);
            int id = 0x7ff00000 | (p.getId() & 0xfffff);
            e.append("{\"x\":");
            HorizonJson.number(p.getX(), e);
            e.append(",\"y\":");
            HorizonJson.number(p.getY(), e);
            e.append(",\"z\":");
            HorizonJson.number(p.getZ(), e);
            e.append(",\"owner\":");
            HorizonJson.string(p.getStringUUID(), e);
            JsonArray keys = new JsonArray();
            for (var m : out) {
                if (m instanceof JsonObject mo && mo.has("key")) keys.add(mo.get("key"));
                if (m instanceof JsonObject mo && mo.has("img")) keys.add(mo.get("img"));
            }
            if (!keys.isEmpty()) e.append(",\"keys\":").append(HorizonJson.write(keys));
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("type", "minecraft:player");
            o.addProperty("x", p.getX());
            o.addProperty("y", p.getY());
            o.addProperty("z", p.getZ());
            o.addProperty("w", p.getBbWidth());
            o.addProperty("h", p.getBbHeight());
            o.addProperty("block", true);
            o.add("models", out);
            if (!held.isEmpty()) o.add("items", held);
            e.append(",\"e\":");
            HorizonJson.write(o, e);
            e.append('}');
            return e.toString();
        } catch (Throwable t) {
            if (FAILED.add("player")) Natives.log("Java mods: a player's mod layers couldn't be drawn: " + t);
            return null;
        }
    }

    private static final java.util.Set<String> FAILED = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static Boolean modelPartsPatched;

    /**
     * Mods patch how a model's parts draw (ModelPart.render or compile: a part drawn as
     * something other than its cubes, such as voxels made from the skin).
     */
    private static boolean modelPartsPatched() {
        if (modelPartsPatched == null) {
            modelPartsPatched = HorizonMixinTable.patchedAnywhere(ModelPart.class, java.util.Set.of("render", "compile"));
            if (modelPartsPatched) {
                Natives.log("Java mods: mods change how models' parts draw ("
                        + HorizonMixinTable.mods("net/minecraft/client/model/geom/ModelPart", "render") + "): players' parts they draw are shown");
            }
        }
        return modelPartsPatched;
    }

    private static boolean capturePlayer;
    /** The player's own model as its renderer submitted it: model, state, pose, render type, light, overlay. */
    private static Object[] playerSubmit;
    private static Field cubes, children;

    /**
     * The player model's parts a mod draws its own way (modelPartsPatched): Horizon draws the
     * player's model itself, so only these go to it, as custom geometry with the skin as
     * texture, where the renderer put the model. A part is the mod's when, rendered alone
     * (vanilla's ModelPart.render, with the mods' patches), it gives other quads than its own
     * cubes' faces. The model is set up first as vanilla's renderer does (setupAnim, as that
     * player, the camera on it: mods build their parts' look there, near the camera).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void modParts(JsonArray out, net.minecraft.server.level.ServerPlayer p,
                                 net.minecraft.client.player.RemotePlayer a) {
        Object[] submit = playerSubmit;
        playerSubmit = null;
        if (submit == null) return;
        try {
            if (cubes == null) {
                Field c = ModelPart.class.getDeclaredField("cubes"), ch = ModelPart.class.getDeclaredField("children");
                c.setAccessible(true);
                ch.setAccessible(true);
                children = ch;
                cubes = c;
            }
            var model = (net.minecraft.client.model.player.PlayerModel) submit[0];
            var mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.world.entity.Entity camera = null;
            try {
                camera = mc.getCameraEntity();
            } catch (RuntimeException e) {
                // No camera yet.
            }
            mc.setCameraEntity(a);
            try {
                HorizonClient.as(p, () -> ((net.minecraft.client.model.Model) model).setupAnim(submit[1]));
            } finally {
                if (camera != null) mc.setCameraEntity(camera);
            }
            ModelPart root = model.root();
            java.util.List<ModelPart> parts = root.getAllParts();
            java.util.List<ModelPart> skipped = new java.util.ArrayList<>();
            java.util.List<ModelPart> shown = new java.util.ArrayList<>();
            boolean any = false;
            int light = (Integer) submit[4], overlay = (Integer) submit[5];
            // The parts' names (the model's: "hat", "left_sleeve"), for Horizon to leave its
            // own versions of the mod's parts out.
            Map<ModelPart, String> names = new IdentityHashMap<>();
            partNames(root, names);
            JsonArray drawn = new JsonArray();
            for (ModelPart part : parts) {
                if (part != root && ((Map<?, ?>) children.get(part)).isEmpty() && drawnByMod(part, light, overlay)) {
                    any = true;
                    if (names.containsKey(part)) drawn.add(names.get(part));
                    // The mirrored player's skin parts (the client's settings, which Horizon
                    // keeps itself) are all off: the mod's parts are shown.
                    if (!part.visible) {
                        part.visible = true;
                        shown.add(part);
                    }
                } else if (!part.skipDraw) {
                    part.skipDraw = true;
                    skipped.add(part);
                }
            }
            try {
                if (!any) return;
                Recorder recorder = new Recorder();
                root.render((PoseStack) submit[2], recorder, light, overlay, -1);
                int n = recorder.vertices.size() / 4 * 4;
                String texture = texture((RenderType) submit[3]);
                if (n == 0 || texture == null) return;
                JsonObject o = new JsonObject();
                o.addProperty("tex", texture);
                o.addProperty("overlay", recorder.overlay);
                o.add("parts", drawn);
                StringBuilder v = new StringBuilder(n * 64).append('[');
                for (int i = 0; i < n; i++) {
                    float[] x = recorder.vertices.get(i);
                    for (int k = 0; k < 8; k++) {
                        if (i > 0 || k > 0) v.append(',');
                        HorizonJson.tenThousandths(x[k], v);
                    }
                    v.append(',').append((long) Float.floatToRawIntBits(x[8]) & 0xFFFFFFFFL);
                }
                o.add("v", new HorizonJson.Raw(v.append(']').toString()));
                out.add(o);
                // Once per texture: a player's skin arriving shows as a new line.
                if (FAILED.add("mod parts drawn " + texture)) {
                    Natives.log("Java mods: mods draw " + n / 4 + " quads of a player's model parts their own way (" + texture + ")");
                }
            } finally {
                for (ModelPart part : skipped) part.skipDraw = false;
                for (ModelPart part : shown) part.visible = false;
            }
        } catch (Throwable t) {
            if (FAILED.add("mod parts")) Natives.log("Java mods: the player's model parts mods draw couldn't be drawn: " + t);
        }
    }

    /** Each part under {@code part} by its name in its parent. */
    @SuppressWarnings("unchecked")
    private static void partNames(ModelPart part, Map<ModelPart, String> out) throws ReflectiveOperationException {
        for (var child : ((Map<String, ModelPart>) children.get(part)).entrySet()) {
            out.put(child.getValue(), child.getKey());
            partNames(child.getValue(), out);
        }
    }

    /** A part with no children, rendered alone, gives other quads than its cubes' faces. */
    @SuppressWarnings("unchecked")
    private static boolean drawnByMod(ModelPart part, int light, int overlay) throws ReflectiveOperationException {
        int faces = 0;
        for (ModelPart.Cube cube : (java.util.List<ModelPart.Cube>) cubes.get(part)) faces += cube.polygons.length;
        boolean visible = part.visible, skip = part.skipDraw;
        part.visible = true;
        part.skipDraw = false;
        try {
            Recorder recorder = new Recorder();
            part.render(new PoseStack(), recorder, light, overlay, -1);
            return recorder.vertices.size() / 4 != faces;
        } finally {
            part.visible = visible;
            part.skipDraw = skip;
        }
    }

    /**
     * The player's first person as vanilla's FirstPersonHandsAndItemsRenderer draws each arm
     * (submitArmWithItem, which mods hook: Carry On draws what is carried instead): kept when
     * something besides vanilla's own arm and held item is drawn, with whether vanilla's were
     * (else Horizon's game hides its own for that hand). In view space.
     */
    private static void firstPerson(net.minecraft.server.level.ServerPlayer p, net.minecraft.client.player.RemotePlayer a,
                                    EntityRenderState state) throws Throwable {
        if (!(state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar)) return;
        if (hands == null) {
            hands = new net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer(net.minecraft.client.Minecraft.getInstance());
            submitArm = net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class.getDeclaredMethod("submitArmWithItem",
                    net.minecraft.client.renderer.state.level.PlayerRenderState.class,
                    net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState.class, float.class, float.class,
                    net.minecraft.world.InteractionHand.class, float.class, net.minecraft.world.item.ItemStack.class, float.class,
                    PoseStack.class, SubmitNodeCollector.class, int.class);
            submitArm.setAccessible(true);
        }
        var level = new net.minecraft.client.renderer.state.level.PlayerRenderState();
        level.hasPlayer = true;
        level.avatarRenderState = avatar;
        var fp = level.firstPersonHandsAndItems;
        fp.attackHand = net.minecraft.world.InteractionHand.MAIN_HAND;
        fp.mainHandItem = p.getMainHandItem();
        fp.offHandItem = p.getOffhandItem();
        fp.mainHandHeight = fp.oldMainHandHeight = fp.offHandHeight = fp.oldOffHandHeight = 1.0F;
        fp.mainHandSwapScale = fp.offHandSwapScale = 1.0F;
        JsonArray out = new JsonArray();
        JsonArray held = new JsonArray();
        boolean[] hide = new boolean[2];
        int n = 0;
        for (var hand : net.minecraft.world.InteractionHand.values()) {
            var stack = p.getItemInHand(hand);
            int before = out.size() + held.size();
            items = held;
            firstPerson = true;
            handStack = stack;
            vanillaHand = false;
            var c = collector(out);
            try {
                HorizonClient.as(p, () -> {
                    submitArm.invoke(hands, level, fp, PARTIAL_TICK, a.getXRot(), hand, 0.0F, stack, 0.0F,
                            new PoseStack(), c, 0xF000F0);
                    flush(c);
                });
            } finally {
                firstPerson = false;
                handStack = null;
                items = null;
            }
            boolean drew = out.size() + held.size() > before;
            hide[n++] = drew && !vanillaHand;
        }
        if (out.isEmpty() && held.isEmpty()) return;
        JsonObject o = new JsonObject();
        o.add("models", out);
        if (!held.isEmpty()) o.add("items", held);
        // The trees and textures its models use, which the server sends that player's game.
        JsonArray keys = new JsonArray();
        for (var m : out) {
            if (m instanceof JsonObject mo && mo.has("key")) keys.add(mo.get("key"));
            if (m instanceof JsonObject mo && mo.has("img")) keys.add(mo.get("img"));
        }
        if (!keys.isEmpty()) o.add("keys", keys);
        JsonArray h = new JsonArray();
        h.add(hide[0]);
        h.add(hide[1]);
        o.add("hide", h);
        HANDS.put(p.getStringUUID(), HorizonJson.write(o));
    }

    // ---- Items (ItemModelResolverMixin, ItemStackRenderStateMixin) ----------------------

    /** Items render states hold for Horizon: the stack and how it is shown. */
    private record Kept(net.minecraft.world.item.ItemStack stack, net.minecraft.world.item.ItemDisplayContext context) {}

    private static final Map<Object, Kept> KEPT = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    /** Where the entity being drawn puts its items. */
    private static JsonArray items;

    /** Per item model: whether it is a mod's own (modDrawn). */
    private static final Map<net.minecraft.resources.Identifier, Boolean> MOD_DRAWN = new ConcurrentHashMap<>();

    /**
     * Whether an item's model is one only its mod can draw: a mod's own item model type
     * ({@code tacz:custom_renderer}, baked to a mod's class) or vanilla's special model with a
     * mod's renderer. Horizon's item renderer can't draw these (they resolve to its builtin
     * entity model: nothing), so the client's real resolver resolves them and their renderer
     * submits its geometry, recorded as the entity's (ItemModelResolverMixin). Only once the
     * client's models are loaded.
     */
    public static boolean modDrawn(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty() || !HorizonClient.modelsLoaded()) return false;
        var id = stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
        if (id == null || id.getNamespace().equals("minecraft")) return false;
        return MOD_DRAWN.computeIfAbsent(id, k -> {
            try {
                var model = net.minecraft.client.Minecraft.getInstance().getModelManager().getItemModel(k);
                if (model == null) return false;
                Object drawer = model;
                if (model instanceof net.minecraft.client.renderer.item.SpecialModelWrapper<?> special) {
                    drawer = HorizonClient.field(net.minecraft.client.renderer.item.SpecialModelWrapper.class, special, "specialRenderer");
                }
                boolean mod = drawer != null && !drawer.getClass().getName().startsWith("net.minecraft.");
                if (mod) Natives.log("Java mods: " + k + "'s item model is drawn by its mod (" + drawer.getClass().getName() + ")");
                return mod;
            } catch (Throwable t) {
                return false;
            }
        });
    }

    /** The client's resolver over its real models, once loaded (null before). */
    public static net.minecraft.client.renderer.item.ItemModelResolver realItemResolver() {
        if (!HorizonClient.modelsLoaded()) return null;
        return net.minecraft.client.Minecraft.getInstance().getItemModelResolver();
    }

    public static void keepItem(Object state, net.minecraft.world.item.ItemStack stack,
                                net.minecraft.world.item.ItemDisplayContext context) {
        if (!stack.isEmpty()) KEPT.put(state, new Kept(stack.copy(), context));
    }

    public static boolean keptItem(Object state) {
        return KEPT.containsKey(state);
    }

    public static void forgetItem(Object state) {
        KEPT.remove(state);
    }

    /** Items' SNBT by their stack (a held item is the same each tick): the last 256. */
    private static final Map<Integer, Object[]> SNBT = new java.util.LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Object[]> eldest) {
            return size() > 256;
        }
    };

    private static String snbt(net.minecraft.world.item.ItemStack stack) {
        int hash = net.minecraft.world.item.ItemStack.hashItemAndComponents(stack) * 31 + stack.getCount();
        synchronized (SNBT) {
            Object[] kept = SNBT.get(hash);
            if (kept != null && net.minecraft.world.item.ItemStack.matches((net.minecraft.world.item.ItemStack) kept[0], stack)) {
                return (String) kept[1];
            }
        }
        String snbt = HorizonServer.snbt(stack);
        synchronized (SNBT) {
            SNBT.put(hash, new Object[] {stack.copy(), snbt});
        }
        return snbt;
    }

    /** A kept item submitted: its stack, context and the pose then. False for other items. */
    public static boolean submitItem(Object state, PoseStack pose) {
        Kept kept = KEPT.get(state);
        if (kept == null) return false;
        if (items == null) return true;
        if (onlyMods && !inModLayer) return true;
        if (handStack != null && net.minecraft.world.item.ItemStack.matches(kept.stack(), handStack)) {
            vanillaHand = true;
            return true;
        }
        String snbt = snbt(kept.stack());
        if (snbt == null) return true;
        JsonObject o = new JsonObject();
        o.addProperty("item", snbt);
        o.addProperty("ctx", kept.context().getSerializedName());
        JsonArray matrix = new JsonArray();
        float[] m = new float[16];
        pose.last().pose().get(m);
        for (float f : m) matrix.add(round(f));
        o.add("m", matrix);
        items.add(o);
        return true;
    }

    private static java.lang.reflect.Method shadowRadius;
    /** The shadow radius of the entity drawn last (EntityRenderer.getShadowRadius). */
    private static float lastShadow;

    private static float shadow(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        try {
            if (shadowRadius == null) {
                shadowRadius = EntityRenderer.class.getDeclaredMethod("getShadowRadius", EntityRenderState.class);
                shadowRadius.setAccessible(true);
            }
            return (Float) shadowRadius.invoke(renderer, state);
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    /** The name tag of the entity drawn last (null: none), where, and whether always shown. */
    private static net.minecraft.network.chat.Component lastTag;
    private static double lastTagY;
    private static boolean lastTagAlways;
    /** Whether the entity drawn last had its render state made (its renderer chose its name). */
    private static boolean extracted;
    private static java.lang.reflect.Method shouldShowName;

    /** EntityRenderer.shouldShowName with nothing under the crosshair, from close by. */
    private static boolean showsName(EntityRenderer<?, ?> renderer, Entity entity) {
        try {
            if (shouldShowName == null) {
                shouldShowName = EntityRenderer.class.getDeclaredMethod("shouldShowName", Entity.class, double.class);
                shouldShowName.setAccessible(true);
            }
            return (Boolean) shouldShowName.invoke(renderer, entity, 0.0);
        } catch (Throwable t) {
            return entity.isCustomNameVisible();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonArray models(Entity entity, JsonArray heldItems) {
        items = heldItems;
        lastShadow = 0.0F;
        lastTag = null;
        extracted = false;
        try {
            return modelsOf(entity);
        } finally {
            items = null;
        }
    }

    /**
     * The partial tick entities are drawn at: the tick's end, a thousandth short of it.
     * GeckoLib takes exactly 1 for a paused game and stops its animations there
     * (AnimationController.checkControllerState); a thousandth of a tick shows nowhere.
     */
    private static final float PARTIAL_TICK = 0.999F;

    /**
     * HORIZON_JAVA_DEBUG=poses: each mod entity's model, once a second, as one line: its type, how far it moved
     * since the last line, and the sum of its parts' rotations. A walking mob's limbs swing, so the sum changes;
     * a frozen pose keeps it still (UMP's visual test reads these lines).
     */
    private static final boolean DEBUG_POSES = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("poses");
    private static final Map<Entity, double[]> POSE_LOG = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static Entity posing;
    /** Ticks between pose lines (HORIZON_JAVA_POSE_EVERY, default 20): short enough not to catch a wing beat in step. */
    private static final int POSE_EVERY = Math.max(1, Integer.parseInt(String.valueOf(System.getenv("HORIZON_JAVA_POSE_EVERY")).replaceAll("\\D", "").isEmpty() ? "20" : String.valueOf(System.getenv("HORIZON_JAVA_POSE_EVERY")).replaceAll("\\D", "")));

    private static void logPose(JsonObject poses) {
        Entity e = posing;
        if (e == null || e.tickCount % POSE_EVERY != 0) return;
        double rot = 0;
        int parts = 0;
        for (var en : poses.entrySet()) {
            var a = en.getValue().getAsJsonArray();
            rot += Math.abs(a.get(3).getAsDouble()) + Math.abs(a.get(4).getAsDouble()) + Math.abs(a.get(5).getAsDouble());
            parts++;
        }
        double[] had = POSE_LOG.put(e, new double[] {e.getX(), e.getZ()});
        double moved = had == null ? 0 : Math.hypot(e.getX() - had[0], e.getZ() - had[1]);
        Natives.log("UMP-POSE " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + " id=" + e.getId() + " tick=" + e.tickCount
                + " moved=" + String.format(java.util.Locale.ROOT, "%.2f", moved) + " parts=" + parts
                + " rot=" + String.format(java.util.Locale.ROOT, "%.4f", rot));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonArray modelsOf(Entity server) {
        // Its client twin when it has one: mods animate their mobs on the client (HorizonClientShadows).
        Entity entity = HorizonClientShadows.drawn(server);
        JsonArray out = new JsonArray();
        EntityRenderer renderer = renderer(entity.getType());
        if (renderer == null) return out;
        try {
            // Its name tag as a player looking at it from close by would see it
            // (EntityRenderer.extractRenderState: shouldShowName, getNameTag): the camera on it
            // and it under the crosshair; then whether it shows when not looked at.
            var dispatcher = context().getEntityRenderDispatcher();
            HorizonClient.set(net.minecraft.client.Camera.class, dispatcher.camera, "position", entity.position());
            dispatcher.crosshairPickEntity = entity;
            // The nearest player is the one looking (Minecraft.player: whether it is invisible to
            // them, their team's name tag rules).
            var viewer = entity.level() instanceof ServerLevel l
                    ? l.getNearestPlayer(entity, -1.0) instanceof net.minecraft.server.level.ServerPlayer p ? p : null
                    : null;
            EntityRenderState[] made = new EntityRenderState[1];
            boolean[] always = new boolean[1];
            HorizonClient.ThrowingRunnable extract = () -> {
                try {
                    made[0] = renderer.createRenderState(entity, PARTIAL_TICK);
                } finally {
                    dispatcher.crosshairPickEntity = null;
                }
                always[0] = made[0].nameTag != null && showsName(renderer, entity);
            };
            try {
                if (viewer != null) HorizonClient.as(viewer, extract);
                else extract.run();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            EntityRenderState state = made[0];
            lastTag = state.nameTag;
            lastTagY = state.nameTagAttachment == null ? -1.0 : state.nameTagAttachment.y;
            lastTagAlways = always[0];
            extracted = true;
            lastShadow = shadow(renderer, state);
            PoseStack pose = new PoseStack();
            var offset = renderer.getRenderOffset(state);
            pose.translate(offset.x, offset.y, offset.z);
            CameraRenderState camera = new CameraRenderState();
            camera.orientation = new org.joml.Quaternionf();
            camera.pos = net.minecraft.world.phys.Vec3.ZERO;
            var c = collector(out);
            renderer.submit(state, pose, c, camera);
            flush(c);
        } catch (Throwable t) {
            String key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " " + t;
            if (FAILURES.merge(key, 1, Integer::sum) == 1) {
                Natives.log("Java mods: drawing " + key);
                StackTraceElement[] frames = t.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
            }
        }
        return out;
    }

    /**
     * A SubmitNodeCollector keeping models (every model submit ends in the abstract
     * {@code submitModel}; the others are its defaults, run as they are) and custom geometry
     * (GeckoLib's models: {@code submitCustomGeometry}, vertices written by the renderer).
     * Flames, leashes and the rest are not kept yet.
     */
    /** The recording collector's custom geometry waiting for its submission's end. */
    interface Deferred {
        void flush() throws Throwable;
    }

    @FunctionalInterface
    private interface Pending {
        void run() throws ReflectiveOperationException;
    }

    /** Runs the custom geometry renderers a recording collector was given (collector). */
    private static void flush(SubmitNodeCollector collector) {
        try {
            ((Deferred) collector).flush();
        } catch (Throwable t) {
            if (FAILED.add("flush " + t.getClass().getName())) Natives.log("Java mods: a renderer's custom geometry couldn't be read: " + t);
        }
    }

    private static SubmitNodeCollector collector(JsonArray out) {
        List<Pending> pending = new java.util.ArrayList<>();
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.isDefault() && !method.getName().equals("submitBlockModel") && !method.getName().equals("submitItem")) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                switch (method.getName()) {
                    case "order" -> {
                        return proxy;
                    }
                    case "submitModel" -> {
                        if (!kept(args[0])) {
                            // The player's own model, left to Horizon: kept for mods that
                            // change how its parts draw (modParts).
                            if (capturePlayer && args[0] instanceof net.minecraft.client.model.player.PlayerModel && args.length == 9) {
                                PoseStack copy = new PoseStack();
                                copy.last().pose().set(((PoseStack) args[2]).last().pose());
                                copy.last().normal().set(((PoseStack) args[2]).last().normal());
                                playerSubmit = new Object[] {args[0], args[1], copy, args[3], args[4], args[5]};
                            }
                            return null;
                        }
                        if (args.length == 9) model(out, (Model) args[0], args[1], (PoseStack) args[2], (RenderType) args[3],
                                (Integer) args[4], (Integer) args[5], (Integer) args[6], args[7]);
                    }
                    case "submitCustomGeometry" -> {
                        if (!kept(null)) return null;
                        // Run when the submission ends (flush), as vanilla's feature dispatcher
                        // draws it after the submits: TACZ's buffer source submits first and
                        // writes its guns' vertices after.
                        PoseStack copy = new PoseStack();
                        copy.last().pose().set(((PoseStack) args[0]).last().pose());
                        copy.last().normal().set(((PoseStack) args[0]).last().normal());
                        RenderType type = (RenderType) args[1];
                        var renderer = (SubmitNodeCollector.CustomGeometryRenderer) args[2];
                        pending.add(() -> mesh(out, copy, type, renderer));
                        return null;
                    }
                    case "flush" -> {
                        var now = new java.util.ArrayList<>(pending);
                        pending.clear();
                        for (var run : now) run.run();
                        return null;
                    }
                    // Vanilla's (pose, render type, parts, tints, ...) and Fabric's (pose, render
                    // types, translucent, parts, mesh, tints, ...), whose default drops the mesh.
                    case "submitBlockModel" -> {
                        if (args.length == 9) blockModel(out, (PoseStack) args[0], (List<?>) args[3], args[4], (int[]) args[5]);
                        else blockModel(out, (PoseStack) args[0], (List<?>) args[2], null, (int[]) args[3]);
                        return null;
                    }
                    // An item's layer as the client's resolver made it (a block entity renderer
                    // drawing an item with the real item models: Create's depot, its belts):
                    // vanilla's (pose, context, light, overlay, outline, tints, quads, foil) and
                    // Fabric's, with the layer's mesh before the foil.
                    case "submitItem" -> {
                        if (!kept(null)) return null;
                        Object mesh = args.length == 9 ? args[7] : null;
                        itemModel(out, (PoseStack) args[0], (net.minecraft.client.resources.model.geometry.ItemQuads) args[6], mesh, (int[]) args[5]);
                        return null;
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "toString" -> {
                        return "HorizonEntityRender.collector";
                    }
                    default -> { }
                }
                return null;
            }
        };
        return (SubmitNodeCollector) Proxy.newProxyInstance(HorizonEntityRender.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class, Deferred.class}, handler);
    }

    /** One submitted model: posed as ModelFeatureRenderer does (setupAnim), then kept. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void model(JsonArray out, Model model, Object state, PoseStack pose, RenderType type,
                              int light, int overlay, int tint, Object uv) throws ReflectiveOperationException {
        // A sprite of an atlas (UvMapping: signs, banners) is not drawn yet.
        if (uv != null) return;
        String texture = texture(type);
        if (texture == null) return;
        model.setupAnim(state);
        ModelPart root = model.root();
        String key = KEYS.get(root);
        if (key == null) {
            key = "m" + KEYS.size();
            KEYS.put(root, key);
            NEW_TREES.put(key, tree(root));
        }
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("tex", texture);
        String image = imageKey(texture);
        if (image != null) o.addProperty("img", image);
        JsonArray matrix = new JsonArray();
        float[] m = new float[16];
        pose.last().pose().get(m);
        for (float f : m) matrix.add(round(f));
        o.add("m", matrix);
        o.addProperty("tint", tint);
        o.addProperty("overlay", overlay);
        o.addProperty("light", light);
        JsonObject poses = new JsonObject();
        JsonArray hidden = new JsonArray();
        JsonArray skipped = new JsonArray();
        poses(root, "root", poses, hidden, skipped);
        if (DEBUG_POSES) logPose(poses);
        o.add("poses", poses);
        if (!hidden.isEmpty()) o.add("hidden", hidden);
        if (!skipped.isEmpty()) o.add("skip", skipped);
        out.add(o);
    }

    /**
     * Custom geometry (SubmitNodeCollector.submitCustomGeometry: GeckoLib's bones, a mod's own
     * quads): what its renderer writes to the vertex consumer, kept as quads relative to the
     * entity (the pose stack is the renderer's to apply). Only quad render types (entities',
     * blocks'): lines and the rest are left out.
     */
    private static void mesh(JsonArray out, PoseStack pose, RenderType type,
                             SubmitNodeCollector.CustomGeometryRenderer renderer) throws ReflectiveOperationException {
        if (!String.valueOf(type.primitiveTopology()).contains("QUAD")) return;
        String texture = texture(type);
        if (texture == null) return;
        Recorder recorder = new Recorder();
        renderer.render(pose.last(), recorder);
        int n = recorder.vertices.size() / 4 * 4;
        if (n == 0) return;
        // An atlas's UVs (Create's SuperByteBuffers: block models through the block atlas): the
        // quads split by sprite, UVs made the sprite's own, as block models' are (blockModel).
        if (texture.contains(":textures/atlas/")) {
            atlasMesh(out, texture, recorder, n, glows(type));
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("tex", texture);
        String image = imageKey(texture);
        if (image != null) o.addProperty("img", image);
        o.addProperty("overlay", recorder.overlay);
        if (glows(type)) o.addProperty("glow", true);
        // Written as text at once (thousands of numbers: a JsonArray boxed each).
        StringBuilder v = new StringBuilder(n * 64);
        v.append('[');
        for (int i = 0; i < n; i++) {
            float[] x = recorder.vertices.get(i);
            for (int k = 0; k < 8; k++) {
                if (i > 0 || k > 0) v.append(',');
                HorizonJson.tenThousandths(x[k], v);
            }
            v.append(',').append((long) Float.floatToRawIntBits(x[8]) & 0xFFFFFFFFL);
        }
        v.append(']');
        o.add("v", new HorizonJson.Raw(v.toString()));
        out.add(o);
    }

    /** Each texture's image key ({@code img:<texture>}), or "" when Horizon's atlas has it. */
    private static final Map<String, String> IMAGE_KEYS = new ConcurrentHashMap<>();

    /**
     * The key of a texture's pixels for Horizon (sent once to each game as an {@code img:}
     * tree, kept in a Java image slot), or null when Horizon's GUI atlas packs the texture
     * itself: vanilla's, and the mods' jars' {@code textures/entity}, {@code block},
     * {@code gui}, {@code particle}, {@code hud} and top-level textures (hud.rs). Others are read
     * from the client's resources: textures a mod's own resource packs hold (TACZ's gun packs,
     * read at run time), or a jar's other folders.
     */
    static String imageKey(String texture) {
        String known = IMAGE_KEYS.get(texture);
        if (known != null) return known.isEmpty() ? null : known;
        String key = null;
        try {
            key = image(texture);
        } catch (Throwable t) {
            if (FAILED.add("image " + texture)) Natives.log("Java mods: the texture " + texture + " couldn't be read: " + t);
        }
        IMAGE_KEYS.put(texture, key == null ? "" : key);
        return key;
    }

    private static String image(String texture) throws java.io.IOException {
        var id = net.minecraft.resources.Identifier.tryParse(texture);
        if (id == null || id.getNamespace().equals("minecraft") || !id.getPath().endsWith(".png")) return null;
        String path = id.getPath();
        String file = "assets/" + id.getNamespace() + "/" + path;
        boolean packed = path.matches("textures/(entity|block|gui|particle|hud)/.*|textures/[^/]+\\.png");
        var loader = HorizonEntityRender.class.getClassLoader();
        if (packed && loader.getResource(file) != null) return null;
        java.awt.image.BufferedImage image = null;
        try (var in = loader.getResourceAsStream(file)) {
            if (in != null) image = javax.imageio.ImageIO.read(in);
        }
        var resources = net.minecraft.client.Minecraft.getInstance().getResourceManager();
        if (image == null && resources != null) {
            var found = resources.getResource(id);
            if (found.isPresent()) {
                try (var in = found.get().open()) {
                    image = javax.imageio.ImageIO.read(in);
                }
            }
        }
        if (image == null) return null;
        int w = image.getWidth(), h = image.getHeight();
        byte[] rgba = new byte[w * h * 4];
        int i = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                rgba[i++] = (byte) (argb >> 16);
                rgba[i++] = (byte) (argb >> 8);
                rgba[i++] = (byte) argb;
                rgba[i++] = (byte) (argb >>> 24);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("width", w);
        o.addProperty("height", h);
        o.addProperty("rgba", java.util.Base64.getEncoder().encodeToString(rgba));
        String key = "img:" + texture;
        synchronized (NEW_TREES) {
            NEW_TREES.put(key, o);
        }
        Natives.log("Java mods: " + texture + " (" + w + "x" + h + ") is sent to the players' games (Horizon's atlas hasn't it)");
        return key;
    }

    /** Atlas UVs found to sprites (atlasMesh): by atlas and the quad's middle, to 1/65536. */
    private static final Map<String, net.minecraft.client.renderer.texture.TextureAtlasSprite> SPRITE_AT = new ConcurrentHashMap<>();

    /**
     * Custom geometry in an atlas's UVs (`ns:textures/atlas/<name>.png`): each quad into the mesh
     * of the sprite holding its middle, its UVs made that sprite's own (Horizon packs sprites
     * by their texture files, not the client's atlas). A quad on no sprite is left out.
     */
    private static void atlasMesh(JsonArray out, String texture, Recorder recorder, int n, boolean glow) {
        int colon = texture.indexOf(':');
        String path = texture.substring(colon + 1).replace("textures/atlas/", "").replace(".png", "");
        var atlasId = net.minecraft.resources.Identifier.fromNamespaceAndPath(texture.substring(0, colon), path);
        Map<String, StringBuilder> bySprite = new java.util.LinkedHashMap<>();
        for (int q = 0; q < n; q += 4) {
            float mu = 0, mv = 0;
            for (int i = 0; i < 4; i++) {
                mu += recorder.vertices.get(q + i)[3] / 4;
                mv += recorder.vertices.get(q + i)[4] / 4;
            }
            String key = path + ' ' + Math.round(mu * 65536) + ' ' + Math.round(mv * 65536);
            var sprite = SPRITE_AT.get(key);
            if (sprite == null) {
                sprite = spriteAt(atlasId, mu, mv);
                if (sprite == null) continue;
                SPRITE_AT.put(key, sprite);
            }
            float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
            StringBuilder v = bySprite.computeIfAbsent(textureOf(sprite), k -> new StringBuilder(1024).append('['));
            for (int i = 0; i < 4; i++) {
                float[] x = recorder.vertices.get(q + i).clone();
                x[3] = du == 0 ? 0 : (x[3] - sprite.getU0()) / du;
                x[4] = dv == 0 ? 0 : (x[4] - sprite.getV0()) / dv;
                for (int k = 0; k < 8; k++) {
                    if (v.length() > 1) v.append(',');
                    HorizonJson.tenThousandths(x[k], v);
                }
                v.append(',').append((long) Float.floatToRawIntBits(x[8]) & 0xFFFFFFFFL);
            }
        }
        bySprite.forEach((tex, v) -> {
            JsonObject o = new JsonObject();
            o.addProperty("tex", tex);
            o.addProperty("overlay", recorder.overlay);
            if (glow) o.addProperty("glow", true);
            o.add("v", new HorizonJson.Raw(v.append(']').toString()));
            out.add(o);
        });
    }

    /**
     * A block model a renderer submits (BlockModelRenderState: Chunk Loaders' floating core,
     * through Fabric's mesh): its parts' baked quads, posed, kept as custom geometry, one mesh
     * per sprite with the sprite's own UVs (the client's block atlas isn't Horizon's): the
     * sprite's texture, `ns:textures/<path>.png`, which Horizon's GUI atlas packs.
     */
    private static void blockModel(JsonArray out, PoseStack pose, List<?> parts, Object mesh, int[] tints) {
        var matrix = pose.last().pose();
        Map<String, JsonArray> bySprite = new java.util.LinkedHashMap<>();
        var directions = new java.util.ArrayList<net.minecraft.core.Direction>(java.util.Arrays.asList(net.minecraft.core.Direction.values()));
        directions.add(null);
        org.joml.Vector3f p = new org.joml.Vector3f();
        org.joml.Vector3f n = new org.joml.Vector3f();
        float[][] xyz = new float[4][3];
        float[][] uv = new float[4][2];
        for (Object o : parts) {
            if (!(o instanceof net.minecraft.client.renderer.block.dispatch.BlockStateModelPart part)) continue;
            for (var side : directions) {
                for (var quad : part.getQuads(side)) {
                    var info = quad.materialInfo();
                    for (int i = 0; i < 4; i++) {
                        var at = quad.position(i);
                        xyz[i][0] = at.x();
                        xyz[i][1] = at.y();
                        xyz[i][2] = at.z();
                        long packed = quad.packedUV(i);
                        uv[i][0] = net.minecraft.client.model.geom.builders.UVPair.unpackU(packed);
                        uv[i][1] = net.minecraft.client.model.geom.builders.UVPair.unpackV(packed);
                    }
                    var dir = quad.direction();
                    n.set(dir.getStepX(), dir.getStepY(), dir.getStepZ());
                    int tint = info.isTinted() ? tint(tints, info.tintIndex()) : -1;
                    quad(bySprite, pose, matrix, info.sprite(), xyz, uv, null, n, tint, p);
                }
            }
        }
        meshQuads(bySprite, pose, matrix, mesh, tints);
        flush(out, bySprite);
    }

    /** An item layer's quads (see the collector's submitItem), as a block model's. */
    private static void itemModel(JsonArray out, PoseStack pose, net.minecraft.client.resources.model.geometry.ItemQuads quads,
                                  Object mesh, int[] tints) {
        var matrix = pose.last().pose();
        Map<String, JsonArray> bySprite = new java.util.LinkedHashMap<>();
        org.joml.Vector3f p = new org.joml.Vector3f();
        org.joml.Vector3f n = new org.joml.Vector3f();
        float[][] xyz = new float[4][3];
        float[][] uv = new float[4][2];
        if (quads != null) {
            for (var quad : quads.all()) {
                var info = quad.materialInfo();
                for (int i = 0; i < 4; i++) {
                    var at = quad.position(i);
                    xyz[i][0] = at.x();
                    xyz[i][1] = at.y();
                    xyz[i][2] = at.z();
                    long packed = quad.packedUV(i);
                    uv[i][0] = net.minecraft.client.model.geom.builders.UVPair.unpackU(packed);
                    uv[i][1] = net.minecraft.client.model.geom.builders.UVPair.unpackV(packed);
                }
                var dir = quad.direction();
                n.set(dir.getStepX(), dir.getStepY(), dir.getStepZ());
                int tint = info.isTinted() ? tint(tints, info.tintIndex()) : -1;
                quad(bySprite, pose, matrix, info.sprite(), xyz, uv, null, n, tint, p);
            }
        }
        meshQuads(bySprite, pose, matrix, mesh, tints);
        flush(out, bySprite);
    }

    /** One mesh a sprite: its texture's quads, into out. */
    private static void flush(JsonArray out, Map<String, JsonArray> bySprite) {
        bySprite.forEach((texture, v) -> {
            JsonObject o = new JsonObject();
            o.addProperty("tex", texture);
            o.addProperty("overlay", net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            o.add("v", v);
            out.add(o);
        });
    }

    /**
     * A Fabric mesh (QuadEmitter quads: atlas UVs, no sprite): the sprite holding each quad's
     * UVs, in its atlas.
     */
    private static void meshQuads(Map<String, JsonArray> bySprite, PoseStack pose, org.joml.Matrix4f matrix, Object mesh, int[] tints) {
        org.joml.Vector3f p = new org.joml.Vector3f();
        org.joml.Vector3f n = new org.joml.Vector3f();
        float[][] xyz = new float[4][3];
        float[][] uv = new float[4][2];
        if (mesh instanceof net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView view) {
            int[] colors = new int[4];
            view.forEach(q -> {
                for (int i = 0; i < 4; i++) {
                    xyz[i][0] = q.x(i);
                    xyz[i][1] = q.y(i);
                    xyz[i][2] = q.z(i);
                    uv[i][0] = q.u(i);
                    uv[i][1] = q.v(i);
                    colors[i] = q.color(i);
                }
                var face = q.faceNormal();
                n.set(face.x(), face.y(), face.z());
                float mu = (uv[0][0] + uv[2][0]) / 2, mv = (uv[0][1] + uv[2][1]) / 2;
                var sprite = spriteAt(q.atlas() == net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas.ITEM
                        ? net.minecraft.data.AtlasIds.ITEMS : net.minecraft.data.AtlasIds.BLOCKS, mu, mv);
                int tint = q.tintIndex() >= 0 ? tint(tints, q.tintIndex()) : -1;
                quad(bySprite, pose, matrix, sprite, xyz, uv, colors, n, tint, p);
            });
        }
    }

    /** A sprite's texture file: the headless sprite's (HorizonSprites), else by its name. */
    static String textureOf(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        var headless = HorizonSprites.at(sprite.getU0() + 1e-4F);
        if (headless != null && headless.atlasSprite() == sprite) return headless.texture();
        var name = sprite.contents().name();
        return name.getNamespace() + ":textures/" + name.getPath() + ".png";
    }

    private static int tint(int[] tints, int index) {
        return tints != null && index >= 0 && index < tints.length ? 0xFF000000 | tints[index] : -1;
    }

    /** One quad of a block model, posed, its UVs made the sprite's own, into its sprite's mesh. */
    private static void quad(Map<String, JsonArray> bySprite, PoseStack pose,
                             org.joml.Matrix4f matrix, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
                             float[][] xyz, float[][] uv, int[] colors, org.joml.Vector3f normal, int tint, org.joml.Vector3f p) {
        if (sprite == null) return;
        org.joml.Vector3f n = pose.last().normal().transform(new org.joml.Vector3f(normal)).normalize();
        JsonArray v = bySprite.computeIfAbsent(textureOf(sprite), k -> new JsonArray());
        float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
        for (int i = 0; i < 4; i++) {
            matrix.transformPosition(p.set(xyz[i][0], xyz[i][1], xyz[i][2]));
            float u = du == 0 ? 0 : (uv[i][0] - sprite.getU0()) / du;
            float w = dv == 0 ? 0 : (uv[i][1] - sprite.getV0()) / dv;
            for (float f : new float[] {p.x, p.y, p.z, u, w, n.x, n.y, n.z}) v.add(Math.round(f * 10000.0F) / 10000.0F);
            int color = colors == null ? -1 : colors[i];
            if (tint != -1) color = net.minecraft.util.ARGB.multiply(color, tint);
            v.add((long) color & 0xFFFFFFFFL);
        }
    }

    /** Per atlas: its sprites, to find the one holding a UV (Fabric's mesh quads). */
    private static final Map<Object, java.util.Collection<net.minecraft.client.renderer.texture.TextureAtlasSprite>> ATLAS_SPRITES =
            new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    static net.minecraft.client.renderer.texture.TextureAtlasSprite spriteAt(net.minecraft.resources.Identifier atlasId, float u, float v) {
        var sprites = ATLAS_SPRITES.get(atlasId);
        if (sprites == null) {
            try {
                var atlas = net.minecraft.client.Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(atlasId);
                Field field = net.minecraft.client.renderer.texture.TextureAtlas.class.getDeclaredField("texturesByName");
                field.setAccessible(true);
                sprites = java.util.List.copyOf(((Map<?, net.minecraft.client.renderer.texture.TextureAtlasSprite>) field.get(atlas)).values());
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
            // (Not kept while the atlas is still empty: the models load after the first frames.)
            if (sprites.isEmpty()) return null;
            ATLAS_SPRITES.put(atlasId, sprites);
        }
        for (var sprite : sprites) {
            if (u >= sprite.getU0() && u <= sprite.getU1() && v >= sprite.getV0() && v <= sprite.getV1()) return sprite;
        }
        return null;
    }

    /**
     * A vertex consumer keeping what is written: per vertex its position, UV, normal and
     * colour (ARGB, as the float's bits); the overlay (UV1) of the last vertex for all.
     */
    static final class Recorder implements com.mojang.blaze3d.vertex.VertexConsumer {
        final List<float[]> vertices = new java.util.ArrayList<>();
        int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
        private float[] last = new float[9];

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            last = new float[] {x, y, z, 0, 0, 0, 1, 0, Float.intBitsToFloat(-1)};
            vertices.add(last);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            last[8] = Float.intBitsToFloat(a << 24 | r << 16 | g << 8 | b);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int argb) {
            last[8] = Float.intBitsToFloat(argb);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            last[3] = u;
            last[4] = v;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            overlay = u | v << 16;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            last[5] = x;
            last[6] = y;
            last[7] = z;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    /** Emissive render types (eyes, GeckoLib's glow layers) draw at full light. */
    private static boolean glows(RenderType type) {
        String name = type.toString();
        return name.contains("eyes") || name.contains("emissive");
    }

    /** The texture a render type samples first (its Sampler0: the entity's texture). */
    @SuppressWarnings("unchecked")
    static String texture(RenderType type) throws ReflectiveOperationException {
        Object setup = setupField.get(type);
        var textures = (Map<String, Object>) texturesField.get(setup);
        Object binding = textures.get("Sampler0");
        if (binding == null && !textures.isEmpty()) binding = textures.values().iterator().next();
        if (binding == null) return null;
        Method location = binding.getClass().getDeclaredMethod("location");
        location.setAccessible(true);
        return location.invoke(binding).toString();
    }

    private static float[] pose(ModelPart p) {
        return new float[] {p.x, p.y, p.z, p.xRot, p.yRot, p.zRot, p.xScale, p.yScale, p.zScale};
    }

    private static float[] pose(PartPose p) {
        return new float[] {p.x(), p.y(), p.z(), p.xRot(), p.yRot(), p.zRot(), p.xScale(), p.yScale(), p.zScale()};
    }

    /** Each part's pose that differs from its initial one, its hidden and skipped parts. */
    @SuppressWarnings("unchecked")
    private static void poses(ModelPart part, String path, JsonObject poses, JsonArray hidden, JsonArray skipped)
            throws ReflectiveOperationException {
        if (!part.visible) {
            hidden.add(path);
            return;
        }
        if (part.skipDraw) skipped.add(path);
        float[] now = pose(part);
        if (!java.util.Arrays.equals(now, pose(part.getInitialPose()))) {
            JsonArray a = new JsonArray();
            for (float f : now) a.add(round(f));
            poses.add(path, a);
        }
        String base = path.equals("root") ? "" : path;
        for (var child : ((Map<String, ModelPart>) childrenField.get(part)).entrySet()) {
            poses(child.getValue(), base + "/" + child.getKey(), poses, hidden, skipped);
        }
    }

    /** A part tree as VanillaEntityModelExporter writes one: initial poses, polygons, children. */
    @SuppressWarnings("unchecked")
    private static JsonObject tree(ModelPart part) throws ReflectiveOperationException {
        return tree(part, "");
    }

    @SuppressWarnings("unchecked")
    private static JsonObject tree(ModelPart part, String path) throws ReflectiveOperationException {
        JsonObject o = new JsonObject();
        JsonArray pose = new JsonArray();
        for (float f : pose(part.getInitialPose())) pose.add(f);
        o.add("pose", pose);
        JsonArray polygons = new JsonArray();
        JsonArray normals = new JsonArray();
        for (ModelPart.Cube cube : (List<ModelPart.Cube>) cubesField.get(part)) {
            for (ModelPart.Polygon polygon : cube.polygons) {
                JsonArray p = new JsonArray();
                for (ModelPart.Vertex v : polygon.vertices()) {
                    JsonArray vertex = new JsonArray();
                    // Degenerate (zero-size) faces have NaN UVs: written as 0.
                    for (float f : new float[] {v.x(), v.y(), v.z(), v.u(), v.v()}) vertex.add(Float.isFinite(f) ? f : 0.0F);
                    p.add(vertex);
                }
                polygons.add(p);
                JsonArray normal = new JsonArray();
                normal.add(polygon.normal().x());
                normal.add(polygon.normal().y());
                normal.add(polygon.normal().z());
                normals.add(normal);
            }
        }
        o.add("polygons", polygons);
        o.add("normals", normals);
        JsonObject kids = new JsonObject();
        for (var child : ((Map<String, ModelPart>) childrenField.get(part)).entrySet()) {
            String childPath = path + "/" + child.getKey();
            kids.add(childPath, tree(child.getValue(), childPath));
        }
        o.add("children", kids);
        return o;
    }
}
