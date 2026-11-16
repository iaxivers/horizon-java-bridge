package dev.horizonlab.platform;

import com.google.gson.JsonArray;
import dev.horizonlab.bridge.Natives;
import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;

/**
 * What a mod's screen draws, recorded for Horizon to draw: the screen extracts into this as
 * into vanilla's GuiGraphicsExtractor, and each image, sprite, rectangle, text and item becomes
 * an operation (with the pose it was drawn with), in drawing order. Nothing reaches a GPU.
 */
public final class HorizonGraphics extends GuiGraphicsExtractor {
    private final JsonArray ops = new JsonArray();

    public HorizonGraphics(int width, int height) {
        this(width, height, new Capture());
    }

    private HorizonGraphics(int width, int height, Capture state) {
        super(HorizonClient.minecraft(), state, width, height);
        state.owner = this;
        // The game's GUI render state is this frame's too: mods add to it directly
        // (Xaero's World Map: gameRenderer.gameRenderState().guiRenderState, its map's blit).
        try {
            var game = HorizonClient.minecraft().gameRenderer;
            var renderState = game == null ? null : game.gameRenderState();
            if (renderState != null) HorizonClient.set(net.minecraft.client.renderer.state.GameRenderState.class, renderState, "guiRenderState", state);
        } catch (Throwable t) {
            // No game render state: nothing reads it.
        }
    }

    /**
     * The render state GUI elements go to when mods add them themselves (Jade's float
     * rectangles: GuiRenderState.addGuiElement), or through a vanilla method this class doesn't
     * record: their vertices, built as vanilla would build them, become rectangles. Untextured
     * quads are fills (two colours down: gradients); textured ones have no texture name here.
     */
    /** GuiTextRenderState's text, font, x, y, color and dropShadow. */
    private static final java.lang.reflect.Field[] TEXT_FIELDS = textFields();

    private static java.lang.reflect.Field[] textFields() {
        var fields = new java.lang.reflect.Field[6];
        String[] names = {"text", "font", "x", "y", "color", "dropShadow"};
        try {
            for (int i = 0; i < names.length; i++) {
                fields[i] = net.minecraft.client.renderer.state.gui.GuiTextRenderState.class.getDeclaredField(names[i]);
                fields[i].setAccessible(true);
            }
        } catch (ReflectiveOperationException e) {
            dev.horizonlab.bridge.Natives.log("Horizon's client: render states' text can't be read: " + e);
        }
        return fields;
    }

    /** The HorizonGraphics that records into {@code state}, or null (another render state). */
    static HorizonGraphics ownerOf(Object state) {
        return state instanceof Capture capture ? capture.owner : null;
    }

    private static final class Capture extends GuiRenderState {
        HorizonGraphics owner;

        @Override
        public void addGuiElement(net.minecraft.client.renderer.state.gui.GuiElementRenderState element) {
            capture(element);
        }

        /**
         * Text added to the render state itself (corelib's submitText, not GuiGraphics.text):
         * a text op, in the state's pose.
         */
        @Override
        public void addText(net.minecraft.client.renderer.state.gui.GuiTextRenderState state) {
            if (owner == null) return;
            try {
                var text = (FormattedCharSequence) TEXT_FIELDS[0].get(state);
                var font = (Font) TEXT_FIELDS[1].get(state);
                int x = TEXT_FIELDS[2].getInt(state), y = TEXT_FIELDS[3].getInt(state), color = TEXT_FIELDS[4].getInt(state);
                boolean shadow = TEXT_FIELDS[5].getBoolean(state);
                owner.pose().pushMatrix();
                try {
                    owner.pose().set(state.pose);
                    owner.text(font, text, x, y, color, shadow);
                } finally {
                    owner.pose().popMatrix();
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Not drawn.
            }
        }

        /** Vanilla's blits of atlas sprites (blitSprite with a TextureAtlasSprite, nine-slices). */
        @Override
        public void addBlitToCurrentLayer(net.minecraft.client.renderer.state.gui.BlitRenderState element) {
            capture(element);
        }

        /**
         * A picture in picture (Xaero's minimap, an entity in a screen): its renderer draws it
         * into its texture now, on the headless client's GPU (HorizonGpu), and adds the blit of
         * that texture here, as GuiRenderer.preparePictureInPicture does.
         */
        @Override
        public void addPicturesInPictureState(net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState state) {
            if (owner == null || !HorizonGpu.installed()) return;
            var renderer = pictureRenderer(state);
            if (renderer == null) return;
            // Drawn (and read back) at most PIP_INTERVAL apart for each receiver; in between the
            // last blit is repeated, the receiver keeping its image.
            Object key = java.util.List.of(state.getClass(), owner.imagesSent == null ? "" : System.identityHashCode(owner.imagesSent));
            long now = System.nanoTime();
            Object[] last = PIP_LAST.get(key);
            if (last != null && now - (long) last[0] < PIP_INTERVAL && last[1] != null) {
                owner.gpuQuad((Object[]) last[1], false);
                return;
            }
            Object[] record = {now, null};
            PIP_LAST.put(key, record);
            owner.recording = record;
            long started = HorizonProfile.start();
            try {
                prepare(renderer, state, this);
                HorizonProfile.add("HUD: picture in picture drawn", started);
            } catch (Throwable t) {
                if (PIP_FAILURES.merge(state.getClass().getName(), 1, Integer::sum) <= 3) {
                    Natives.log("Horizon's client: a picture in picture (" + state.getClass().getName() + ") failed: " + t);
                    for (var f : t.getStackTrace()) Natives.log("    at " + f);
                }
            } finally {
                owner.recording = null;
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static void prepare(net.minecraft.client.gui.render.pip.PictureInPictureRenderer renderer,
                                    net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState state, GuiRenderState into) {
            renderer.prepare(state, into, null, guiScale());
        }

        private void capture(net.minecraft.client.renderer.state.gui.GuiElementRenderState element) {
            if (owner == null) return;
            Vertices vertices = new Vertices(new java.util.ArrayList<>(), new java.util.ArrayList<>());
            try {
                element.buildVertices(vertices);
            } catch (RuntimeException e) {
                return;
            }
            var texture = element.textureSetup();
            boolean textured = texture != null && texture.texure0() != null;
            for (int i = 0; i + 3 < vertices.points().size(); i += 4) {
                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
                int top = 0, bottom = 0;
                for (int k = 0; k < 4; k++) {
                    float[] v = vertices.points().get(i + k);
                    int color = vertices.colors().get(i + k);
                    x0 = Math.min(x0, v[0]);
                    x1 = Math.max(x1, v[0]);
                    u0 = Math.min(u0, v[2]);
                    u1 = Math.max(u1, v[2]);
                    v0 = Math.min(v0, v[3]);
                    v1 = Math.max(v1, v[3]);
                    if (v[1] < y0) {
                        y0 = v[1];
                        top = color;
                    }
                    if (v[1] >= y1) {
                        y1 = v[1];
                        bottom = color;
                    }
                }
                if (textured && texture.texure0() instanceof HorizonGpu.View gpu && HorizonGpu.drawnInto(gpu) && !HorizonGpu.atlas(gpu)) {
                    // A texture of the headless client's GPU (a picture in picture): its pixels
                    // as an image, the UVs' direction kept (renderpearl's rows are bottom up).
                    float[] first = vertices.points().get(i);
                    float[] third = vertices.points().get(i + 2);
                    boolean flipU = (first[0] < third[0]) != (first[2] < third[2]) && first[2] != third[2];
                    boolean flipV = (first[1] < third[1]) != (first[3] < third[3]) && first[3] != third[3];
                    owner.gpuQuad(gpu, x0, y0, x1, y1, flipU ? u1 : u0, flipU ? u0 : u1, flipV ? v1 : v0, flipV ? v0 : v1, top);
                    continue;
                }
                if (textured && texture.texure0() instanceof HorizonTextures.View view
                        && !view.name().contains("textures/atlas/")) {
                    // A named texture's quad (TextureManager.getTexture, a dynamic texture): its
                    // UVs are the texture's.
                    owner.spriteQuad(view.name(), x0, y0, x1, y1, u0, u1, v0, v1, top);
                    continue;
                }
                // A headless atlas sprite's quad (its UVs in the sprite's own square: HorizonSprites).
                // (Within one sprite's square: a quad spanning several is no sprite of ours.)
                var sprite = u1 > u0 && Math.floor(u0 + 1e-4) == Math.floor(u1 - 1e-4)
                        ? HorizonSprites.at(u0 + (u1 - u0) / 2) : null;
                // Else a real atlas's sprite (the block atlas once the client's models load: a
                // fluid's sprite tiled into JEI's list or a mod's tank): its own texture, its UVs.
                String atlasName = !textured ? null
                        : texture.texure0() instanceof HorizonTextures.View named ? named.name()
                        : texture.texure0() != null && HorizonGpu.atlas(texture.texure0()) ? atlasName(texture.texure0().texture())
                        : null;
                var real = sprite == null && atlasName != null
                        ? realSprite(atlasName, (u0 + u1) / 2, (v0 + v1) / 2) : null;
                if (sprite != null) {
                    float k = (float) Math.floor(u0 + (u1 - u0) / 2);
                    owner.spriteQuad(sprite.texture(), x0, y0, x1, y1, u0 - k, u1 - k, v0, v1, top);
                } else if (real != null) {
                    float du = real.getU1() - real.getU0(), dv = real.getV1() - real.getV0();
                    owner.spriteQuad(HorizonEntityRender.textureOf(real), x0, y0, x1, y1,
                            (u0 - real.getU0()) / du, (u1 - real.getU0()) / du,
                            (v0 - real.getV0()) / dv, (v1 - real.getV0()) / dv, top);
                } else if (!textured) {
                    float[][] corners = {vertices.points().get(i), vertices.points().get(i + 1),
                            vertices.points().get(i + 2), vertices.points().get(i + 3)};
                    if (axisAligned(corners)) owner.quad(x0, y0, x1, y1, top, bottom);
                    else owner.slanted(corners, top);
                }
            }
        }
    }

    private static final java.util.Map<Object, String> ATLAS_NAMES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** The atlas a GPU texture is (`ns:textures/atlas/<name>.png`), else null. */
    static String atlasName(com.mojang.renderpearl.api.textures.GpuTexture texture) {
        String known = ATLAS_NAMES.get(texture);
        if (known != null) return known.isEmpty() ? null : known;
        String[] found = {null};
        try {
            net.minecraft.client.Minecraft.getInstance().getAtlasManager().forEach((id, atlas) -> {
                if (atlas.getTexture() == texture) found[0] = atlas.location().toString();
            });
        } catch (RuntimeException e) {
            return null;
        }
        if (found[0] != null) ATLAS_NAMES.put(texture, found[0]);
        return found[0];
    }

    /** The real atlas sprite (`ns:textures/atlas/<name>.png`) holding a UV, if any. */
    static net.minecraft.client.renderer.texture.TextureAtlasSprite realSprite(String atlas, float u, float v) {
        int colon = atlas.indexOf(':');
        if (colon < 0 || !atlas.contains("textures/atlas/") || u > 1 || v > 1) return null;
        var id = Identifier.fromNamespaceAndPath(atlas.substring(0, colon),
                atlas.substring(colon + 1).replace("textures/atlas/", "").replace(".png", ""));
        var real = HorizonEntityRender.spriteAt(id, u, v);
        return real != null && real.getU1() > real.getU0() && real.getV1() > real.getV0() ? real : null;
    }

    /** Whether a quad's corners make an upright rectangle (each edge level or plumb). */
    private static boolean axisAligned(float[][] c) {
        for (int k = 0; k < 4; k++) {
            float[] a = c[k], b = c[(k + 1) % 4];
            if (Math.abs(a[0] - b[0]) > 0.01F && Math.abs(a[1] - b[1]) > 0.01F) return false;
        }
        return true;
    }

    /** Collects a GUI element's vertices: x, y (posed), u, v, and their colours. */
    private record Vertices(java.util.List<float[]> points, java.util.List<Integer> colors)
            implements com.mojang.blaze3d.vertex.VertexConsumer {
        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            points.add(new float[] {x, y, 0, 0});
            colors.add(-1);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            return setColor((a << 24) | (r << 16) | (g << 8) | b);
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int argb) {
            if (!colors.isEmpty()) colors.set(colors.size() - 1, argb);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            if (!points.isEmpty()) {
                points.get(points.size() - 1)[2] = u;
                points.get(points.size() - 1)[3] = v;
            }
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
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
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    /** HORIZON_JAVA_DEBUG=tiled: each sprite blitTiledSprite draws, once. */
    private static final boolean DEBUG_TILED = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("tiled");
    private static final java.util.Set<String> TILED_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final boolean DEBUG_GPU = "gpu".equals(System.getenv("HORIZON_JAVA_DEBUG"));
    /** How often a picture in picture is drawn and read back (ns): 10 a second. */
    private static final long PIP_INTERVAL = 100_000_000L;
    /** Per picture-in-picture class and receiver: when it was last drawn, and its blit. */
    private static final java.util.Map<Object, Object[]> PIP_LAST = new java.util.concurrent.ConcurrentHashMap<>();
    /** While a picture in picture draws: where its blit is kept. */
    private Object[] recording;
    private static long debugImages;
    private static final java.util.Set<String> DEBUG_NAMES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** HORIZON_JAVA_DEBUG=gpu: a GPU texture blitted is saved in the temp folder as horizon-gpu-N.png now and then. */
    private static void debugImage(String name, int w, int h, byte[] rgba) {
        if (rgba == null) {
            Natives.log("gpu: " + name + " has no pixels");
            return;
        }
        // Every 100th image is saved and counted; a target's first that isn't all black is
        // counted too (a check can name it).
        boolean save = debugImages++ % 100 == 0;
        if (!save && DEBUG_NAMES.contains(name)) return;
        int opaque = 0, lit = 0;
        for (int i = 0; i < w * h * 4; i += 4) {
            if (rgba[i + 3] == 0) continue;
            opaque++;
            if ((rgba[i] | rgba[i + 1] | rgba[i + 2]) != 0) lit++;
        }
        if (!save && (lit == 0 || !DEBUG_NAMES.add(name))) return;
        if (save) {
            var image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    image.setRGB(x, y, ((rgba[i + 3] & 255) << 24) | ((rgba[i] & 255) << 16) | ((rgba[i + 1] & 255) << 8) | (rgba[i + 2] & 255));
                }
            }
            try {
                javax.imageio.ImageIO.write(image, "png", new java.io.File(System.getProperty("java.io.tmpdir"), "horizon-gpu-" + (debugImages / 100) + ".png"));
            } catch (java.io.IOException e) {
                Natives.log("gpu: " + e);
            }
        }
        Natives.log("gpu: " + name + " " + w + "x" + h + ", " + opaque + " pixels not transparent, " + lit + " not black");
    }
    private static final java.util.Map<String, Integer> PIP_FAILURES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, net.minecraft.client.gui.render.pip.PictureInPictureRenderer<?>> PIP_RENDERERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The GUI scale pictures in picture are drawn at (the window's: HorizonClient's). */
    private static int guiScale() {
        try {
            return Math.max(1, HorizonClient.minecraft().getWindow().getGuiScale());
        } catch (Throwable t) {
            return 3;
        }
    }

    /**
     * The renderer of a picture in picture's class: one of each renderer mods registered
     * through Fabric's registry (made once, as GameRenderer makes them for its GuiRenderer).
     */
    @SuppressWarnings("unchecked")
    private static synchronized net.minecraft.client.gui.render.pip.PictureInPictureRenderer<?> pictureRenderer(
            net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState state) {
        if (!pipRenderersMade) {
            pipRenderersMade = true;
            try {
                Class<?> registry = Class.forName("net.fabricmc.fabric.impl.client.rendering.PictureInPictureRendererRegistryImpl");
                var field = registry.getDeclaredField("FACTORIES");
                field.setAccessible(true);
                net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.Context context = HorizonClient::minecraft;
                for (var factory : (java.util.List<net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.Factory>) field.get(null)) {
                    try {
                        var renderer = factory.createRenderer(context);
                        if (renderer != null) PIP_RENDERERS.putIfAbsent(renderer.getRenderStateClass(), renderer);
                    } catch (Throwable t) {
                        Natives.log("Horizon's client: a picture-in-picture renderer couldn't be made: " + t);
                    }
                }
                Natives.log("Horizon's client: " + PIP_RENDERERS.size() + " picture-in-picture renderer(s)");
            } catch (Throwable t) {
                Natives.log("Horizon's client: no picture-in-picture renderers: " + t);
            }
        }
        return PIP_RENDERERS.get(state.getClass());
    }

    private static boolean pipRenderersMade;

    /**
     * A captured blit of a texture of the headless client's GPU: its pixels go first (as a
     * dynamic texture's do) when they changed for the receiver, then the blit.
     */
    private void gpuQuad(HorizonGpu.View view, float x0, float y0, float x1, float y1,
                         float u0, float u1, float v0, float v1, int color) {
        Object[] blit = {view, x0, y0, x1, y1, u0, u1, v0, v1, color};
        if (recording != null) recording[1] = blit;
        gpuQuad(blit, true);
    }

    /** A blit of a GPU texture ({view, x0, y0, x1, y1, u0, u1, v0, v1, color}); {@code read}: its pixels are read back. */
    private void gpuQuad(Object[] blit, boolean read) {
        var view = (HorizonGpu.View) blit[0];
        float x0 = (float) blit[1], y0 = (float) blit[2], x1 = (float) blit[3], y1 = (float) blit[4];
        float u0 = (float) blit[5], u1 = (float) blit[6], v0 = (float) blit[7], v1 = (float) blit[8];
        int color = (int) blit[9];
        String name = HorizonTextures.DYNAMIC_PREFIX + "gpu" + view.id;
        if (DEBUG_GPU) Natives.log("gpu: a blit of " + name + " " + view.getWidth(0) + "x" + view.getHeight(0) + " at " + x0 + "," + y0 + " " + x1 + "," + y1
                + " uv " + u0 + "," + v0 + " " + u1 + "," + v1 + (modsOnly && !drawnByModNow() ? " (not a mod's)" : ""));
        var across = repeats(x0, x1, u0, u1);
        var down = repeats(y0, y1, v0, v1);
        if ((across != null || down != null) && (across == null ? 1 : across.size()) * (down == null ? 1 : down.size()) <= MAX_TILES) {
            // A repeated texture (repeats): its image read once, then a blit per repeat.
            if (across == null) across = java.util.List.of(new float[] {x0, x1, u0, u1});
            if (down == null) down = java.util.List.of(new float[] {y0, y1, v0, v1});
            boolean first = read;
            for (float[] h : across) {
                for (float[] v : down) {
                    gpuQuad(new Object[] {view, h[0], v[0], h[1], v[1], h[2], h[3], v[2], v[3], color}, first);
                    first = false;
                }
            }
            return;
        }
        if (read && imagesSent != null && (!modsOnly || drawnByModNow())) {
            // Its pixels are read when the frame's ops are taken, not now: a mod may still draw
            // into the target (or clear it first: XaeroPlus clears the World Map's target as
            // it adds its GUI element, and draws the map after), as the real game's GUI draws
            // its elements after the whole screen is extracted.
            var image = new JsonObject();
            ops.add(image);
            pendingRead = new Object[]{image, view, name, null};
            pendingReads.add(pendingRead);
        }
        pose().pushMatrix();
        pose().identity();
        try {
            JsonObject o = op("blit");
            if (pendingRead != null && pendingRead[3] == null && pendingRead[1] == view) pendingRead[3] = o;
            o.addProperty("texture", name);
            o.addProperty("x0", x0);
            o.addProperty("y0", y0);
            o.addProperty("x1", x1);
            o.addProperty("y1", y1);
            o.addProperty("u0", u0);
            o.addProperty("u1", u1);
            o.addProperty("v0", v0);
            o.addProperty("v1", v1);
            if (color != -1) o.addProperty("color", color);
        } finally {
            pose().popMatrix();
        }
    }

    /** Image ops waiting for their GPU texture's pixels: {placeholder, view, name}. */
    private final java.util.List<Object[]> pendingReads = new java.util.ArrayList<>();
    /** The pending read the next blit belongs to ({placeholder, view, name, blit op}). */
    private Object[] pendingRead;

    /** Fills the image ops of the GPU textures blitted this frame, or drops them (no change). */
    private void readPending() {
        var drop = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<JsonObject, Boolean>());
        for (Object[] p : pendingReads) {
            var image = (JsonObject) p[0];
            var view = (HorizonGpu.View) p[1];
            var name = (String) p[2];
            // A big target (a full-screen map: the window's size) is read at most PIP_INTERVAL
            // apart for each receiver, and sent shrunk to fit the receiver's largest image
            // (maxImage: the window's own game takes more than a network player's).
            int w = view.getWidth(0), h = view.getHeight(0);
            boolean big = Math.max(w, h) > SMALL_IMAGE;
            Object key = java.util.List.of(name, System.identityHashCode(imagesSent));
            long now = System.nanoTime();
            Long last = big ? BIG_LAST.get(key) : null;
            byte[] rgba = last != null && now - last < PIP_INTERVAL || view.isClosed() ? null : HorizonGpu.pixels(view);
            if (rgba != null && big) BIG_LAST.put(key, now);
            // A big target drawn only in part (JourneyMap's minimap renders into a window-sized
            // texture and blits all of it, transparent but for its corner): only the rectangle that
            // has pixels is sent, its blit moved onto it. Sending the whole window's pixels every few
            // ticks was 12 MB of the HUD each time and stalled the window's frames. (UMP, 2026-11-11)
            int[] crop = rgba != null && big && p[3] instanceof JsonObject ? drawnRect(rgba, w, h) : null;
            if (crop != null && (long) crop[2] * crop[3] * 4 < (long) w * h * 3) {
                rgba = cropped(rgba, w, crop);
                moveBlit((JsonObject) p[3], w, h, crop);
                w = crop[2];
                h = crop[3];
            }
            if (rgba != null && Math.max(w, h) > maxImage) {
                double k = (double) Math.max(w, h) / maxImage;
                int nw = Math.max(1, (int) Math.round(w / k)), nh = Math.max(1, (int) Math.round(h / k));
                rgba = shrink(rgba, w, h, nw, nh);
                w = nw;
                h = nh;
            }
            if (DEBUG_GPU) debugImage(name, w, h, rgba);
            int version = rgba == null ? 0 : 31 * java.util.Arrays.hashCode(rgba) + (w * 65_537 + h);
            if (rgba == null || Integer.valueOf(version).equals(imagesSent.get(name))) {
                drop.add(image);
                continue;
            }
            imagesSent.put(name, version);
            image.addProperty("op", "image");
            image.addProperty("name", name);
            image.addProperty("width", w);
            image.addProperty("height", h);
            image.addProperty("rgba", java.util.Base64.getEncoder().encodeToString(rgba));
        }
        pendingReads.clear();
        if (drop.isEmpty()) return;
        for (int i = ops.size() - 1; i >= 0; i--) {
            if (ops.get(i) instanceof JsonObject o && drop.contains(o)) ops.remove(i);
        }
    }

    /** Images over this many pixels on a side are read at most PIP_INTERVAL apart. */
    private static final int SMALL_IMAGE = 512;
    /** The largest image a network player's game takes, in pixels on a side (what goes over the network). */
    static final int REMOTE_MAX_IMAGE = 512;
    /** The largest image this process's own game takes (in memory): its own wgpu textures, up to 2048. */
    static final int LOCAL_MAX_IMAGE = 2048;
    /** The largest image the receiver of these ops takes, in pixels on a side (bigger ones are shrunk). */
    int maxImage = REMOTE_MAX_IMAGE;
    private static final java.util.Map<Object, Long> BIG_LAST = new java.util.concurrent.ConcurrentHashMap<>();

    /** The rectangle {x, y, w, h} of {@code rgba}'s pixels that aren't fully transparent; null when none are. */
    static int[] drawnRect(byte[] rgba, int w, int h) {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w * 4 + 3;
            for (int x = 0; x < w; x++) {
                if (rgba[row + x * 4] != 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    maxY = y;
                }
            }
        }
        return maxX < 0 ? null : new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    /** The rectangle {x, y, w, h} of rows {@code w} wide. */
    static byte[] cropped(byte[] rgba, int w, int[] r) {
        byte[] out = new byte[r[2] * r[3] * 4];
        for (int y = 0; y < r[3]; y++) {
            System.arraycopy(rgba, ((r[1] + y) * w + r[0]) * 4, out, y * r[2] * 4, r[2] * 4);
        }
        return out;
    }

    /**
     * A blit of a whole w x h texture moved onto its rectangle r (texels): its screen corners where
     * r's edges fall, its UVs within r's image. UVs run either way (render targets are upside down).
     */
    static void moveBlit(JsonObject o, int w, int h, int[] r) {
        float x0 = o.get("x0").getAsFloat(), x1 = o.get("x1").getAsFloat();
        float y0 = o.get("y0").getAsFloat(), y1 = o.get("y1").getAsFloat();
        float u0 = o.get("u0").getAsFloat(), u1 = o.get("u1").getAsFloat();
        float v0 = o.get("v0").getAsFloat(), v1 = o.get("v1").getAsFloat();
        float[] across = clip(x0, x1, u0, u1, r[0] / (float) w, (r[0] + r[2]) / (float) w);
        float[] down = clip(y0, y1, v0, v1, r[1] / (float) h, (r[1] + r[3]) / (float) h);
        o.addProperty("x0", across[0]);
        o.addProperty("x1", across[1]);
        o.addProperty("u0", across[2]);
        o.addProperty("u1", across[3]);
        o.addProperty("y0", down[0]);
        o.addProperty("y1", down[1]);
        o.addProperty("v0", down[2]);
        o.addProperty("v1", down[3]);
    }

    /** One axis of moveBlit: screen p0..p1 showing texture t0..t1, kept to texture lo..hi (renumbered 0..1). */
    private static float[] clip(float p0, float p1, float t0, float t1, float lo, float hi) {
        if (t0 == t1) return new float[] {p0, p1, 0f, 1f};
        float a = Math.max(Math.min(t0, t1), lo), b = Math.min(Math.max(t0, t1), hi);
        if (b <= a) return new float[] {p0, p0, 0f, 0f};
        // where texture coordinate t falls on screen
        float atA = p0 + (a - t0) / (t1 - t0) * (p1 - p0), atB = p0 + (b - t0) / (t1 - t0) * (p1 - p0);
        float ua = (a - lo) / (hi - lo), ub = (b - lo) / (hi - lo);
        // keep the blit's direction: the screen edge that showed t0 first
        boolean forward = t1 > t0;
        return forward ? new float[] {atA, atB, ua, ub} : new float[] {atB, atA, ub, ua};
    }

    /**
     * RGBA rows {@code w} x {@code h} shrunk to {@code nw} x {@code nh} (not bigger) by a box
     * filter: each new pixel the average of the source area it covers, partly covered source
     * pixels weighted by how much of them it covers (any ratio, not only whole steps).
     */
    static byte[] shrink(byte[] rgba, int w, int h, int nw, int nh) {
        byte[] out = new byte[nw * nh * 4];
        double sx = (double) w / nw, sy = (double) h / nh;
        double[] acc = new double[4];
        for (int y = 0; y < nh; y++) {
            double y0 = y * sy, y1 = y0 + sy;
            for (int x = 0; x < nw; x++) {
                double x0 = x * sx, x1 = x0 + sx;
                java.util.Arrays.fill(acc, 0);
                double total = 0;
                for (int py = (int) y0; py < Math.min(h, (int) Math.ceil(y1)); py++) {
                    double wy = Math.min(y1, py + 1) - Math.max(y0, py);
                    if (wy <= 0) continue;
                    for (int px = (int) x0; px < Math.min(w, (int) Math.ceil(x1)); px++) {
                        double wx = Math.min(x1, px + 1) - Math.max(x0, px);
                        if (wx <= 0) continue;
                        double weight = wx * wy;
                        int p = (py * w + px) * 4;
                        for (int c = 0; c < 4; c++) acc[c] += (rgba[p + c] & 255) * weight;
                        total += weight;
                    }
                }
                int o = (y * nw + x) * 4;
                for (int c = 0; c < 4; c++) out[o + c] = (byte) Math.min(255, (int) Math.round(acc[c] / total));
            }
        }
        return out;
    }

    /** A captured piece of a sprite (already posed): a blit of its texture's part, tinted. */
    /** At most this many tiles of a repeated texture in one quad (else drawn as it is). */
    private static final int MAX_TILES = 1024;

    /**
     * One axis of a quad whose UVs repeat the texture (past 0..1, a repeating sampler: Pufferfish's
     * Skills' backgrounds): its pieces {x start, x end, u at start, u at end}, x rising, each
     * piece's UVs within one repeat and made 0..1 (their direction kept); null when the UVs stay
     * within one repeat. Horizon draws a blit's UVs inside its image only: past them it drew
     * what lay beside the image.
     */
    static java.util.List<float[]> repeats(float x0, float x1, float u0, float u1) {
        float lo = Math.min(u0, u1), hi = Math.max(u0, u1);
        double c0 = Math.floor(lo + 1e-4), c1 = Math.ceil(hi - 1e-4);
        if (hi <= lo || (c1 - c0 <= 1 && c0 == 0) || c1 - c0 > MAX_TILES) return null;
        var pieces = new java.util.ArrayList<float[]>();
        for (double c = c0; c < c1; c++) {
            float a = (float) Math.max(lo, c), b = (float) Math.min(hi, c + 1);
            if (b <= a) continue;
            float xa = x0 + (x1 - x0) * (a - u0) / (u1 - u0), xb = x0 + (x1 - x0) * (b - u0) / (u1 - u0);
            float la = (float) (a - c), lb = (float) (b - c);
            pieces.add(xa <= xb ? new float[] {xa, xb, la, lb} : new float[] {xb, xa, lb, la});
        }
        return pieces;
    }

    private void spriteQuad(String texture, float x0, float y0, float x1, float y1,
                            float u0, float u1, float v0, float v1, int color) {
        var across = repeats(x0, x1, u0, u1);
        var down = repeats(y0, y1, v0, v1);
        if ((across != null || down != null) && (across == null ? 1 : across.size()) * (down == null ? 1 : down.size()) <= MAX_TILES) {
            if (across == null) across = java.util.List.of(new float[] {x0, x1, u0, u1});
            if (down == null) down = java.util.List.of(new float[] {y0, y1, v0, v1});
            for (float[] h : across) for (float[] v : down) spriteQuad(texture, h[0], v[0], h[1], v[1], h[2], h[3], v[2], v[3], color);
            return;
        }
        // A dynamic texture's pixels go first, where the receiver lacks them.
        if (texture.startsWith(HorizonTextures.DYNAMIC_PREFIX) && (!modsOnly || drawnByModNow())) {
            var image = HorizonTextures.imageOp(texture, imagesSent);
            if (image != null) ops.add(image);
        }
        pose().pushMatrix();
        pose().identity();
        try {
            JsonObject o = op("blit");
            o.addProperty("texture", texture);
            o.addProperty("x0", x0);
            o.addProperty("y0", y0);
            o.addProperty("x1", x1);
            o.addProperty("y1", y1);
            o.addProperty("u0", u0);
            o.addProperty("u1", u1);
            o.addProperty("v0", v0);
            o.addProperty("v1", v1);
            if (color != -1) o.addProperty("color", color);
        } finally {
            pose().popMatrix();
        }
    }

    /** Rows a slanted quad is cut into at most. */
    private static final int MAX_ROWS = 96;

    /**
     * A captured quad that isn't upright (a turned pose: Pufferfish's Skills' lines between its
     * skills), as fills a GUI pixel row high, each the quad's span on that row: Horizon's frames
     * draw upright rectangles only, and the quad's bounding box filled squares around each line.
     */
    private void slanted(float[][] corners, int color) {
        float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE, left0 = Float.MAX_VALUE, right0 = -Float.MAX_VALUE;
        for (float[] c : corners) {
            top = Math.min(top, c[1]);
            bottom = Math.max(bottom, c[1]);
            left0 = Math.min(left0, c[0]);
            right0 = Math.max(right0, c[0]);
        }
        // Off the screen (a scrolled skill tree's far lines): nothing to send.
        if (right0 <= 0 || left0 >= guiWidth() || bottom <= 0 || top >= guiHeight()) return;
        float first = Math.max(top, 0), last = Math.min(bottom, guiHeight());
        float step = Math.max(1F, (last - first) / MAX_ROWS);
        top = first;
        bottom = last;
        for (float y = top; y < bottom; y += step) {
            float mid = Math.min(bottom, y + step / 2), left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                float[] a = corners[k], b = corners[(k + 1) % 4];
                if ((a[1] <= mid && b[1] >= mid) || (b[1] <= mid && a[1] >= mid)) {
                    float x = a[1] == b[1] ? a[0] : a[0] + (b[0] - a[0]) * (mid - a[1]) / (b[1] - a[1]);
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    if (a[1] == b[1]) {
                        left = Math.min(left, b[0]);
                        right = Math.max(right, b[0]);
                    }
                }
            }
            if (right > left) quad(left, y, right, Math.min(bottom, y + step), color, color);
        }
    }

    /** A captured rectangle (already posed): a fill, or a gradient from top to bottom. */
    private void quad(float x0, float y0, float x1, float y1, int top, int bottom) {
        pose().pushMatrix();
        pose().identity();
        try {
            JsonObject o = op(top == bottom ? "fill" : "gradient");
            o.addProperty("x0", x0);
            o.addProperty("y0", y0);
            o.addProperty("x1", x1);
            o.addProperty("y1", y1);
            if (top == bottom) {
                o.addProperty("color", top);
            } else {
                o.addProperty("top", top);
                o.addProperty("bottom", bottom);
            }
        } finally {
            pose().popMatrix();
        }
    }

    /** Horizon's GUI size (the screen has no window to ask; read in the constructor too). */
    @Override
    public int guiWidth() {
        return HorizonClient.width;
    }

    @Override
    public int guiHeight() {
        return HorizonClient.height;
    }

    /** The frame's operations, in order. */
    public JsonArray ops() {
        if (!pendingReads.isEmpty()) readPending();
        return ops;
    }

    /**
     * Only what mods' code draws is kept (the HUD: vanilla's own parts are Horizon's, drawn by
     * Horizon). An operation is the mods' when, going out from the drawing call, a mod's frame
     * comes before Horizon's driver: a class of a mod, or a mod's mixin handler merged into a
     * vanilla class (Fabric's own are vanilla's here: its HUD wrapper draws vanilla parts).
     */
    boolean modsOnly;
    /** What the frame's receiver has of dynamic textures (name to version): HorizonTextures.imageOp. */
    java.util.Map<String, Integer> imagesSent;

    private static boolean drawnByModNow() {
        return drawnByMod();
    }
    /** Operations left out as vanilla's (modsOnly). */
    int dropped;

    private static final java.util.Map<String, Boolean> MOD_FRAMES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static boolean drawnByMod() {
        return WALKER.walk(frames -> {
            var it = frames.iterator();
            while (it.hasNext()) {
                var frame = it.next();
                Class<?> owner = frame.getDeclaringClass();
                String name = owner.getName();
                // GuiGraphicsExtractor's methods hand a mod's own extractor's draws on
                // (HorizonGraphicsWrappers): the mod is further up.
                if (name.startsWith("dev.horizonlab.platform.HorizonGraphics")
                        || owner == GuiGraphicsExtractor.class || name.startsWith("net.minecraft.client.gui.ActiveTextCollector")) {
                    continue;
                }
                if (name.startsWith("dev.horizonlab.")) return false;
                Boolean mod = MOD_FRAMES.computeIfAbsent(name + "#" + frame.getMethodName() + frame.getDescriptor(),
                        k -> isModFrame(owner, frame));
                if (mod) return true;
            }
            return false;
        });
    }

    static boolean isModFrame(Class<?> owner, StackWalker.StackFrame frame) {
        String name = owner.getName();
        boolean platform = name.startsWith("net.minecraft.") || name.startsWith("com.mojang.") || name.startsWith("net.fabricmc.")
                || name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith("org.joml.")
                || name.startsWith("com.google.") || name.startsWith("it.unimi.") || name.startsWith("org.spongepowered.");
        if (!platform) return true;
        if (!frame.getMethodName().contains("$")) return false;
        try {
            var method = owner.getDeclaredMethod(frame.getMethodName(), frame.getMethodType().parameterArray());
            var merged = method.getAnnotation(org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
            return merged != null && !merged.mixin().startsWith("net.fabricmc.") && !merged.mixin().startsWith("dev.horizonlab.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private JsonObject op(String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("op", kind);
        if (modsOnly && !drawnByMod()) {
            dropped++;
            return o;
        }
        Matrix3x2f m = new Matrix3x2f(pose());
        if (!m.equals(new Matrix3x2f())) {
            JsonArray pose = new JsonArray();
            pose.add(m.m00);
            pose.add(m.m01);
            pose.add(m.m10);
            pose.add(m.m11);
            pose.add(m.m20);
            pose.add(m.m21);
            o.add("pose", pose);
        }
        ops.add(o);
        return o;
    }

    private static void rect(JsonObject o, int x0, int y0, int x1, int y1) {
        o.addProperty("x0", x0);
        o.addProperty("y0", y0);
        o.addProperty("x1", x1);
        o.addProperty("y1", y1);
    }

    // ---- Images ---------------------------------------------------------------------------

    private void image(Identifier texture, int x, int y, float u, float v, int w, int h,
                       int uw, int vh, int texW, int texH, int color) {
        JsonObject o = op("blit");
        o.addProperty("texture", texture.toString());
        rect(o, x, y, x + w, y + h);
        o.addProperty("u0", u / texW);
        o.addProperty("u1", (u + uw) / texW);
        o.addProperty("v0", v / texH);
        o.addProperty("v1", (v + vh) / texH);
        if (color != -1) o.addProperty("color", color);
    }

    @Override
    public void blit(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h,
                     int texW, int texH, int color) {
        image(texture, x, y, u, v, w, h, w, h, texW, texH, color);
    }

    @Override
    public void blit(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h,
                     int texW, int texH) {
        image(texture, x, y, u, v, w, h, w, h, texW, texH, -1);
    }

    @Override
    public void blit(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h,
                     int uw, int vh, int texW, int texH) {
        image(texture, x, y, u, v, w, h, uw, vh, texW, texH, -1);
    }

    @Override
    public void blit(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h,
                     int uw, int vh, int texW, int texH, int color) {
        image(texture, x, y, u, v, w, h, uw, vh, texW, texH, color);
    }

    /**
     * Vanilla's private innerBlit(pipeline, texture, x0, x1, y0, y1, u0, u1, v0, v1, colour),
     * which mods widen and call themselves (REI's textured widgets): what a wrapper made by
     * HorizonGraphicsWrappers forwards it to.
     */
    public void horizon$innerBlit(RenderPipeline pipeline, Identifier texture, int x0, int x1, int y0, int y1,
                                  float u0, float u1, float v0, float v1, int color) {
        blit(texture, x0, y0, x1, y1, u0, u1, v0, v1);
        if (color != -1) ops.get(ops.size() - 1).getAsJsonObject().addProperty("color", color);
    }

    /**
     * Vanilla's blitTiledSprite (GuiGraphicsMixin): the sprite's own texture, a blit per tile of
     * {@code tileW} x {@code tileH} over the rectangle (the last ones cut), each tile the
     * sprite's {@code u, v} square of {@code texW} x {@code texH}, tinted by {@code color}.
     */
    public void horizon$tiledSprite(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, int x, int y, int w, int h,
                                    int u, int v, int tileW, int tileH, int texW, int texH, int color) {
        if (w <= 0 || h <= 0 || tileW <= 0 || tileH <= 0 || texW <= 0 || texH <= 0) return;
        String name = HorizonEntityRender.textureOf(sprite);
        if (DEBUG_TILED && TILED_LOGGED.add(name)) Natives.log("gui: a tiled sprite, " + name + " tinted " + Integer.toHexString(color));
        for (int ty = 0; ty < h; ty += tileH) {
            int th = Math.min(tileH, h - ty);
            for (int tx = 0; tx < w; tx += tileW) {
                int tw = Math.min(tileW, w - tx);
                JsonObject o = op("blit");
                o.addProperty("texture", name);
                rect(o, x + tx, y + ty, x + tx + tw, y + ty + th);
                o.addProperty("u0", (float) u / texW);
                o.addProperty("u1", (float) (u + tw) / texW);
                o.addProperty("v0", (float) v / texH);
                o.addProperty("v1", (float) (v + th) / texH);
                if (color != -1) o.addProperty("color", color);
            }
        }
    }

    @Override
    public void blit(Identifier texture, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1) {
        String name = texture.toString();
        // An atlas blitted with a sprite's own UVs (corelib's submitSprite: its screens'
        // backgrounds): the sprite's texture, by its UV square (HorizonSprites).
        if (name.contains("textures/atlas/") && u1 > u0 && Math.floor(u0 + 1e-4) == Math.floor(u1 - 1e-4)) {
            var sprite = HorizonSprites.at(u0 + (u1 - u0) / 2);
            if (sprite != null) {
                float k = (float) Math.floor(u0 + (u1 - u0) / 2);
                name = sprite.texture();
                u0 -= k;
                u1 -= k;
            }
        }
        // A real atlas's sprite (the client's block atlas, once its models load: a fluid's still
        // sprite JEI tiles into its list and mods into their tanks): the sprite's own texture,
        // its UVs made the sprite's own, as block entity renderers' atlas meshes are. Left as the
        // atlas, Horizon's window had no such picture and drew a black square.
        if (name.contains("textures/atlas/")) {
            var real = realSprite(name, (u0 + u1) / 2, (v0 + v1) / 2);
            if (real != null && real.getU1() > real.getU0() && real.getV1() > real.getV0()) {
                float du = real.getU1() - real.getU0(), dv = real.getV1() - real.getV0();
                name = HorizonEntityRender.textureOf(real);
                u0 = du == 0 ? 0 : (u0 - real.getU0()) / du;
                u1 = du == 0 ? 0 : (u1 - real.getU0()) / du;
                v0 = dv == 0 ? 0 : (v0 - real.getV0()) / dv;
                v1 = dv == 0 ? 0 : (v1 - real.getV0()) / dv;
            }
        }
        JsonObject o = op("blit");
        o.addProperty("texture", name);
        rect(o, x0, y0, x1, y1);
        o.addProperty("u0", u0);
        o.addProperty("u1", u1);
        o.addProperty("v0", v0);
        o.addProperty("v1", v1);
    }

    // ---- GUI atlas sprites (textures/gui/sprites/, with their nine-slice metadata) ----------

    private JsonObject sprite(Identifier sprite, int x, int y, int w, int h) {
        JsonObject o = op("sprite");
        o.addProperty("sprite", sprite.toString());
        o.addProperty("texture", HorizonSprites.guiTexture(sprite));
        rect(o, x, y, x + w, y + h);
        return o;
    }

    /**
     * Whether a sprite is nine-sliced or tiled (its gui.scaling metadata): vanilla's drawing
     * splits it here, into pieces of the headless sprite (Capture), as Horizon's frame drawing
     * stretches a sprite op (RebornCore's 20x20 nine-sliced background over a whole screen).
     */
    private static boolean slicedHere(Identifier sprite) {
        var made = HorizonSprites.get(null, sprite);
        if (made == null) return false;
        return made.contents().getAdditionalMetadata(net.minecraft.client.resources.metadata.gui.GuiMetadataSection.TYPE)
                .map(m -> !(m.scaling() instanceof net.minecraft.client.resources.metadata.gui.GuiSpriteScaling.Stretch))
                .orElse(false);
    }

    @Override
    public void blitSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
        if (slicedHere(sprite)) {
            super.blitSprite(pipeline, sprite, x, y, w, h);
            return;
        }
        sprite(sprite, x, y, w, h);
    }

    @Override
    public void blitSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, float alpha) {
        if (slicedHere(sprite)) {
            super.blitSprite(pipeline, sprite, x, y, w, h, alpha);
            return;
        }
        sprite(sprite, x, y, w, h).addProperty("alpha", alpha);
    }

    @Override
    public void blitSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int color) {
        if (slicedHere(sprite)) {
            super.blitSprite(pipeline, sprite, x, y, w, h, color);
            return;
        }
        sprite(sprite, x, y, w, h).addProperty("color", color);
    }

    /** A part of a sprite: the sprite is texW by texH; (u, v) w by h of it, drawn at x y. */
    @Override
    public void blitSprite(RenderPipeline pipeline, Identifier sprite, int texW, int texH, int u, int v, int x, int y,
                           int w, int h) {
        JsonObject o = sprite(sprite, x, y, w, h);
        o.addProperty("u0", (float) u / texW);
        o.addProperty("u1", (float) (u + w) / texW);
        o.addProperty("v0", (float) v / texH);
        o.addProperty("v1", (float) (v + h) / texH);
    }

    @Override
    public void blitSprite(RenderPipeline pipeline, Identifier sprite, int texW, int texH, int u, int v, int x, int y,
                           int w, int h, int color) {
        blitSprite(pipeline, sprite, texW, texH, u, v, x, y, w, h);
        ops.get(ops.size() - 1).getAsJsonObject().addProperty("color", color);
    }

    // Sprites mods look up themselves (TextureAtlasSprite) go through vanilla's blitSprite:
    // HorizonSprites made them, with their GUI scaling, and innerBlit names their texture.

    // ---- Rectangles -----------------------------------------------------------------------

    @Override
    public void fill(int x0, int y0, int x1, int y1, int color) {
        JsonObject o = op("fill");
        rect(o, Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
        o.addProperty("color", color);
    }

    @Override
    public void fill(RenderPipeline pipeline, int x0, int y0, int x1, int y1, int color) {
        fill(x0, y0, x1, y1, color);
    }

    /**
     * A text box's selection, which vanilla draws by inverting the text over it (a logic-op
     * pipeline): a see-through blue over the text here.
     */
    @Override
    public void textHighlight(int x0, int y0, int x1, int y1, boolean invert) {
        fill(x0, y0, x1, y1, invert ? 0x800000FF : 0x80FFFFFF);
    }

    @Override
    public void fillGradient(int x0, int y0, int x1, int y1, int top, int bottom) {
        JsonObject o = op("gradient");
        rect(o, x0, y0, x1, y1);
        o.addProperty("top", top);
        o.addProperty("bottom", bottom);
    }

    @Override
    public void enableScissor(int x0, int y0, int x1, int y1) {
        super.enableScissor(x0, y0, x1, y1);
        rect(op("scissor"), x0, y0, x1, y1);
    }

    @Override
    public void disableScissor() {
        super.disableScissor();
        op("unscissor");
    }

    // ---- Text -----------------------------------------------------------------------------

    private void text(JsonObject o, int x, int y, int color, boolean shadow) {
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("color", color);
        if (shadow) o.addProperty("shadow", true);
    }

    @Override
    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        if (text == null) return;
        JsonObject o = op("text");
        o.addProperty("text", text);
        text(o, x, y, color, shadow);
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (text == null) return;
        // Resolved here, its runs styled (the mods' translations: a LAN guest's or a dedicated
        // server player's game has none of a mod's lang files, and would show
        // "jm.minimap.compass.n").
        text(font, text.getVisualOrderText(), x, y, color, shadow);
    }

    @Override
    public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        if (text == null) return;
        // Its runs of one style each (Jade's blue italic mod name): a component when any run is
        // styled, else plain text.
        StringBuilder plain = new StringBuilder();
        java.util.List<net.minecraft.network.chat.Style> styles = new java.util.ArrayList<>();
        java.util.List<StringBuilder> runs = new java.util.ArrayList<>();
        text.accept((index, style, codePoint) -> {
            plain.appendCodePoint(codePoint);
            if (styles.isEmpty() || !styles.get(styles.size() - 1).equals(style)) {
                styles.add(style);
                runs.add(new StringBuilder());
            }
            runs.get(runs.size() - 1).appendCodePoint(codePoint);
            return true;
        });
        JsonObject o = op("text");
        if (styles.stream().allMatch(net.minecraft.network.chat.Style::isEmpty)) {
            o.addProperty("text", plain.toString());
        } else {
            var root = Component.empty();
            for (int i = 0; i < runs.size(); i++) {
                root.append(Component.literal(runs.get(i).toString()).withStyle(styles.get(i)));
            }
            o.addProperty("json", HorizonServer.json(root));
        }
        text(o, x, y, color, shadow);
    }

    /**
     * Widgets' text (a button's label, a list's lines: AbstractWidget's scrolling strings) goes
     * through a text collector, which vanilla's adds to the render state directly: here each
     * line is a text op, in the collector's pose, with its opacity. Long labels scroll as
     * vanilla's do (defaultScrollingHelper), unclipped.
     */
    private final class Collector implements net.minecraft.client.gui.ActiveTextCollector {
        private Parameters parameters = new Parameters(new Matrix3x2f(pose()));

        @Override
        public Parameters defaultParameters() {
            return parameters;
        }

        @Override
        public void defaultParameters(Parameters parameters) {
            this.parameters = parameters;
        }

        @Override
        public void accept(net.minecraft.client.gui.TextAlignment alignment, int x, int y, Parameters at,
                           FormattedCharSequence text) {
            Font font = HorizonClient.minecraft().font;
            int left = alignment.calculateLeft(x, font, text);
            pose().pushMatrix();
            pose().set(at.pose());
            try {
                HorizonGraphics.this.text(font, text, left, y, net.minecraft.util.ARGB.white(at.opacity()), true);
            } finally {
                pose().popMatrix();
            }
        }

        @Override
        public void acceptScrolling(Component text, int centerX, int left, int right, int top, int bottom, Parameters at) {
            Font font = HorizonClient.minecraft().font;
            defaultScrollingHelper(text, centerX, left, right, top, bottom, font.width(text), font.lineHeight, at);
        }
    }

    @Override
    public net.minecraft.client.gui.ActiveTextCollector textRenderer() {
        return new Collector();
    }

    @Override
    public net.minecraft.client.gui.ActiveTextCollector textRenderer(HoveredTextEffects effects) {
        return new Collector();
    }

    @Override
    public net.minecraft.client.gui.ActiveTextCollector textRenderer(HoveredTextEffects effects,
                                                                      java.util.function.Consumer<net.minecraft.network.chat.Style> hovered) {
        return new Collector();
    }

    @Override
    public net.minecraft.client.gui.ActiveTextCollector textRendererForWidget(net.minecraft.client.gui.components.AbstractWidget widget,
                                                                               HoveredTextEffects effects) {
        return new Collector();
    }

    // ---- Items ----------------------------------------------------------------------------

    private void item(String kind, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) return;
        JsonObject o = op(kind);
        o.addProperty("item", HorizonServer.snbt(stack));
        o.addProperty("x", x);
        o.addProperty("y", y);
    }

    @Override
    public void item(ItemStack stack, int x, int y) {
        item("item", stack, x, y);
    }

    @Override
    public void item(ItemStack stack, int x, int y, int seed) {
        item("item", stack, x, y);
    }

    @Override
    public void fakeItem(ItemStack stack, int x, int y) {
        item("item", stack, x, y);
    }

    @Override
    public void fakeItem(ItemStack stack, int x, int y, int seed) {
        item("item", stack, x, y);
    }

    @Override
    public void itemDecorations(Font font, ItemStack stack, int x, int y, String count) {
        if (stack == null || stack.isEmpty()) return;
        JsonObject o = op("decorations");
        o.addProperty("item", HorizonServer.snbt(stack));
        o.addProperty("x", x);
        o.addProperty("y", y);
        if (count != null) o.addProperty("count", count);
    }

    @Override
    public void itemDecorations(Font font, ItemStack stack, int x, int y) {
        itemDecorations(font, stack, x, y, null);
    }

    // ---- Tooltips: one operation, its lines as text components ---------------------------

    private void tooltip(java.util.List<net.minecraft.network.chat.Component> lines, int x, int y) {
        if (lines == null || lines.isEmpty()) return;
        JsonObject o = op("tooltip");
        JsonArray out = new JsonArray();
        for (var line : lines) out.add(HorizonServer.json(line));
        o.add("lines", out);
        o.addProperty("x", x);
        o.addProperty("y", y);
    }

    /** A styled character sequence as a component (its runs of one style). */
    private static Component component(FormattedCharSequence text) {
        var out = Component.empty();
        StringBuilder run = new StringBuilder();
        net.minecraft.network.chat.Style[] style = {null};
        text.accept((index, s, codePoint) -> {
            if (style[0] != null && !s.equals(style[0])) {
                out.append(Component.literal(run.toString()).withStyle(style[0]));
                run.setLength(0);
            }
            style[0] = s;
            run.appendCodePoint(codePoint);
            return true;
        });
        if (style[0] != null) out.append(Component.literal(run.toString()).withStyle(style[0]));
        return out;
    }

    private static java.util.List<Component> components(java.util.List<? extends FormattedCharSequence> lines) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        for (var line : lines) out.add(component(line));
        return out;
    }

    @Override
    public void setTooltipForNextFrame(Font font, ItemStack stack, int x, int y) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(font, stack, x, y);
            return;
        }
        JsonObject o = op("item_tooltip");
        o.addProperty("item", HorizonServer.snbt(stack));
        o.addProperty("x", x);
        o.addProperty("y", y);
    }

    @Override
    public void setTooltipForNextFrame(Component text, int x, int y) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(text, x, y);
            return;
        }
        tooltip(java.util.List.of(text), x, y);
    }

    @Override
    public void setTooltipForNextFrame(java.util.List<FormattedCharSequence> lines, int x, int y) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(lines, x, y);
            return;
        }
        tooltip(components(lines), x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<Component> lines,
                                       java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image, int x, int y) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(font, lines, image, x, y);
            return;
        }
        tooltip(lines, x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<Component> lines,
                                       java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image, int x, int y,
                                       Identifier style) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(font, lines, image, x, y, style);
            return;
        }
        tooltip(lines, x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<Component> lines,
                                       java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image, int x, int y,
                                       Identifier style, boolean focused) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(font, lines, image, x, y, style, focused);
            return;
        }
        tooltip(lines, x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<FormattedCharSequence> lines,
                                       java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image,
                                       net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
                                       int x, int y, boolean focused, Identifier style) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(font, lines, image, positioner, x, y, focused, style);
            return;
        }
        tooltip(components(lines), x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, Component text, int x, int y) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(font, text, x, y);
            return;
        }
        tooltip(java.util.List.of(text), x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, Component text, int x, int y, Identifier style) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(font, text, x, y, style);
            return;
        }
        tooltip(java.util.List.of(text), x, y);
    }

    @Override
    public void setComponentTooltipForNextFrame(Font font, java.util.List<Component> lines, int x, int y) {
        if (vanillaTooltips()) {
            super.setComponentTooltipForNextFrame(font, lines, x, y);
            return;
        }
        tooltip(lines, x, y);
    }

    @Override
    public void setComponentTooltipForNextFrame(Font font, java.util.List<Component> lines, int x, int y, Identifier style) {
        if (vanillaTooltips()) {
            super.setComponentTooltipForNextFrame(font, lines, x, y, style);
            return;
        }
        tooltip(lines, x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<? extends FormattedCharSequence> lines, int x, int y) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(font, lines, x, y);
            return;
        }
        tooltip(components(lines), x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<? extends FormattedCharSequence> lines, int x, int y,
                                       Identifier style) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(font, lines, x, y, style);
            return;
        }
        tooltip(components(lines), x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<FormattedCharSequence> lines,
                                       net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
                                       int x, int y, boolean focused) {
        if (vanillaTooltips()) {
            super.setTooltipForNextFrame(font, lines, positioner, x, y, focused);
            return;
        }
        tooltip(components(lines), x, y);
    }

    /**
     * A tooltip drawn at once: Horizon draws tooltips (the deferred ones above), unless mods
     * change how tooltips draw (vanillaTooltips).
     */
    @Override
    public void tooltip(Font font, java.util.List<net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent> lines,
                        int x, int y, net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
                        Identifier style, boolean focused) {
        if (vanillaTooltips()) super.tooltip(font, lines, x, y, positioner, style, focused);
    }

    private static Boolean vanillaTooltips;

    /**
     * Whether tooltips draw as vanilla's do, here (deferred, then GuiGraphicsExtractor.tooltip:
     * its sprites, text and images, captured like the rest), instead of as one Horizon op: when
     * mods' mixins merged handlers into the tooltip's drawing (Legendary Tooltips' frames and
     * item, Iceberg's), which only run on vanilla's path.
     */
    static boolean vanillaTooltips() {
        if (vanillaTooltips != null) return vanillaTooltips;
        boolean hooked = false;
        for (Class<?> c : new Class<?>[]{GuiGraphicsExtractor.class,
                net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil.class}) {
            for (var m : c.getDeclaredMethods()) {
                String n = m.getName();
                if (n.contains("$") && !n.startsWith("lambda$") && !n.startsWith("access$")) {
                    hooked = true;
                    break;
                }
            }
        }
        if (hooked) Natives.log("Horizon's client: mods change how tooltips draw: Java screens' tooltips draw as vanilla's");
        return vanillaTooltips = hooked;
    }
}
