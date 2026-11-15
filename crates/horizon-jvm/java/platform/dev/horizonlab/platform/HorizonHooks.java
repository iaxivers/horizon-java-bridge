package dev.horizonlab.platform;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.horizonlab.bridge.Natives;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/**
 * The mods' event-like Mixins: every {@code @Inject} at the HEAD, TAIL or RETURN of a vanilla
 * method, read from the mods' own Mixin configs (as the census does, here at start-up). Where
 * Horizon reaches such a method (a player joins, a tick, a death), it fires those injections
 * directly: the handler Mixin merged into the vanilla class is called with a real CallbackInfo,
 * and no vanilla code runs. A handler that cancels, or sets a return value, says so to Horizon.
 *
 * <p>Injections at other points (INVOKE, FIELD...), redirects and overwrites patch the inside
 * of vanilla's code: they take effect only where that code runs on the Java side.
 */
public final class HorizonHooks {
    private HorizonHooks() {}

    /** One @Inject of a mod: its target, where, and the handler as the mixin names it. */
    record Hook(String mod, String owner, String method, String desc, String at, String mixin, String handler,
                boolean cancellable) {}

    /** By "owner.method" (owner with slashes): the hooks there. */
    private static final Map<String, List<Hook>> HOOKS = new HashMap<>();
    /** The handlers resolved for a (owner, method, desc, at), in Mixin's priority order. */
    private static final Map<String, List<Method>> RESOLVED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static boolean scanned;
    /** HORIZON_JAVA_DEBUG=hooks: each firing is logged (ticks aside). */
    static final boolean DEBUG = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("hooks");

    /** What a firing did: whether a handler cancelled, and the return value it left. */
    public record Result(boolean cancelled, Object returnValue) {
        static final Result NONE = new Result(false, null);
    }

    // ---- The census, at start-up ----------------------------------------------------------

    /** Reads every loaded mod's Mixin configs once. */
    public static synchronized void scan() {
        if (scanned) return;
        scanned = true;
        HorizonProfile.stallWatch();
        int injections = 0;
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            for (String config : mixinConfigs(mod)) {
                Optional<Path> path = mod.findPath(config);
                if (path.isEmpty()) continue;
                try {
                    JsonObject json = JsonParser.parseString(Files.readString(path.get())).getAsJsonObject();
                    String pkg = json.has("package") ? json.get("package").getAsString().replace('.', '/') : "";
                    for (String key : new String[] {"mixins", "server"}) {
                        if (!json.has(key) || !json.get(key).isJsonArray()) continue;
                        for (JsonElement name : json.getAsJsonArray(key)) {
                            // (A trailing comma reads as a null entry: Simple Pocket Machines'.)
                            if (name.isJsonNull()) continue;
                            String file = pkg + "/" + name.getAsString().replace('.', '/') + ".class";
                            Optional<Path> bytes = mod.findPath(file);
                            if (bytes.isPresent()) {
                                byte[] b = Files.readAllBytes(bytes.get());
                                injections += read(id, b);
                                readFieldInits(b);
                            }
                        }
                    }
                } catch (Throwable t) {
                    Natives.log("Mixin census: " + id + "'s " + config + ": " + t);
                }
            }
        }
        Natives.log("Mixin census: " + injections + " event-like injections into " + HOOKS.size() + " vanilla methods");
        scanClient();
        // Every patch of every kind, and where Horizon runs what it patches.
        HorizonMixinTable.scan();
    }

    // ---- The headless client's tick -------------------------------------------------------

    /** One injection of a mod into the client's frame or tick (Minecraft.runTick, .tick). */
    record ClientHook(String mod, String method, String mixin, String handler, boolean modifyArg) {}

    /** The client classes and methods whose injections run each headless client tick. */
    private static final String CLIENT_OWNER = "net/minecraft/client/Minecraft";
    private static final String HUD_OWNER = "net/minecraft/client/gui/Hud";
    /** The mods' injections into the HUD (Hud's methods): (method, mixin, handler). */
    private static final List<String[]> HUD_HOOKS = new ArrayList<>();

    private static void readHud(String mod, ClassNode node) {
        String mixin = node.name.replace('/', '.');
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                if (!a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                        && !a.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")) continue;
                if (!(get(a, "method") instanceof List<?> specs)) continue;
                for (Object spec : specs) HUD_HOOKS.add(new String[] {parseSpec((String) spec)[0], mixin, m.name, mod});
            }
        }
    }

    /** The Hud methods mods inject into (any injector: their HUD overlays draw there). */
    public static java.util.Set<String> hudMethods() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String[] h : HUD_HOOKS) out.add(h[0]);
        return out;
    }

    /**
     * Calls the mods' handlers merged into Hud for {@code method} whose parameters are the
     * method's own then a CallbackInfo (event-like injections at its head or end: what they
     * draw is theirs), for a part Horizon does not run (Hud.extractRenderState).
     */
    public static void fireHud(Object hud, String method, Object... args) {
        for (Method m : hud.getClass().getDeclaredMethods()) {
            MixinMerged merged = m.getAnnotation(MixinMerged.class);
            if (merged == null) continue;
            for (String[] h : HUD_HOOKS) {
                if (!h[0].equals(method) || !merged.mixin().equals(h[1])) continue;
                if (!handlerNamed(m.getName(), h[2])) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != args.length + 1 || !CallbackInfo.class.isAssignableFrom(params[args.length])) continue;
                Object[] call = java.util.Arrays.copyOf(args, args.length + 1);
                call[args.length] = new CallbackInfo(method, true);
                try {
                    m.setAccessible(true);
                    m.invoke(Modifier.isStatic(m.getModifiers()) ? null : hud, call);
                } catch (Throwable t) {
                    Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                    if (FAILURES.merge(m.toString(), 1, Integer::sum) <= 3) {
                        Natives.log("A mod failed in the HUD's " + method + " (" + h[3] + "'s " + h[2] + "): " + cause);
                        HorizonFailures.report("the HUD", cause, m);
                        StackTraceElement[] frames = cause.getStackTrace();
                        for (int f = 0; f < Math.min(frames.length, 8); f++) Natives.log("    at " + frames[f]);
                    }
                }
            }
        }
    }
    private static final java.util.Set<String> CLIENT_METHODS = java.util.Set.of("runTick", "tick", "renderFrame", "handleKeybinds");
    private static final List<ClientHook> CLIENT_HOOKS = new ArrayList<>();
    private static final Map<String, List<Method>> CLIENT_RESOLVED = new ConcurrentHashMap<>();

    /**
     * The mods' injections into Minecraft.runTick, tick and renderFrame, from their client Mixin
     * configs too: every @Inject (at any point: an INVOKE inside a frame is once a frame) and
     * every @ModifyArg there. The headless client runs no frame, so fireClient calls them.
     */
    private static void scanClient() {
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (id.equals("horizon") || id.startsWith("fabric-") || id.equals("fabric-api")) continue;
            for (String config : mixinConfigs(mod, true)) {
                Optional<Path> path = mod.findPath(config);
                if (path.isEmpty()) continue;
                try {
                    JsonObject json = JsonParser.parseString(Files.readString(path.get())).getAsJsonObject();
                    String pkg = json.has("package") ? json.get("package").getAsString().replace('.', '/') : "";
                    for (String key : new String[] {"mixins", "client"}) {
                        if (!json.has(key) || !json.get(key).isJsonArray()) continue;
                        for (JsonElement name : json.getAsJsonArray(key)) {
                            // (A trailing comma reads as a null entry: Simple Pocket Machines'.)
                            if (name.isJsonNull()) continue;
                            String file = pkg + "/" + name.getAsString().replace('.', '/') + ".class";
                            Optional<Path> bytes = mod.findPath(file);
                            if (bytes.isPresent()) {
                                readClient(id, Files.readAllBytes(bytes.get()));
                                if (key.equals("client")) {
                                    read(id, Files.readAllBytes(bytes.get()), true);
                                    readFieldInits(Files.readAllBytes(bytes.get()));
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    Natives.log("Mixin census: " + id + "'s " + config + " (client): " + t);
                }
            }
        }
        if (!CLIENT_HOOKS.isEmpty()) {
            Natives.log("Mixin census: " + CLIENT_HOOKS.size() + " injections into the client's tick, run each tick ("
                    + CLIENT_HOOKS.stream().map(h -> h.mod() + " " + h.method()).distinct().toList() + ")");
        }
    }

    /**
     * Whether a merged method is the handler {@code handler} of a mixin: Mixin renames it
     * {@code handler$<id>$<mod>$<name>}, and drops a leading {@code <mod>_} from the name
     * (MaLiLib's {@code malilib_onPreGameInit} becomes {@code ...$malilib$onPreGameInit}).
     */
    static boolean handlerNamed(String merged, String handler) {
        if (merged.equals(handler) || merged.endsWith("$" + handler)) return true;
        int prefix = handler.indexOf('_');
        if (prefix <= 0) return false;
        String mod = handler.substring(0, prefix);
        return merged.endsWith("$" + mod + "$" + handler.substring(prefix + 1));
    }

    /** The in-game GUI, whose frame (extractRenderState) mods hook at its tail (MaLiLib's overlays). */
    private static final String GUI_OWNER = "net/minecraft/client/gui/Gui";
    /** The mods' injections at the tail of Gui.extractRenderState: (mixin, handler, mod). */
    private static final List<String[]> GUI_HOOKS = new ArrayList<>();
    private static List<Method> guiResolved;

    private static void readGui(String mod, ClassNode node) {
        String mixin = node.name.replace('/', '.');
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc)) continue;
                if (!(get(a, "method") instanceof List<?> specs)) continue;
                List<?> ats = get(a, "at") instanceof List<?> l ? l : List.of();
                if (ats.size() != 1 || !(ats.get(0) instanceof AnnotationNode at)) continue;
                if (!"TAIL".equals(get(at, "value")) && !"RETURN".equals(get(at, "value"))) continue;
                for (Object spec : specs) {
                    if (parseSpec((String) spec)[0].equals("extractRenderState")) GUI_HOOKS.add(new String[] {mixin, m.name, mod});
                }
            }
        }
    }

    /**
     * The mods' injections at the tail of Gui.extractRenderState, the in-game GUI's frame:
     * {@code graphics} as its GuiGraphicsExtractor (a captured local too), the frame's delta
     * ONE, booleans true.
     */
    public static void fireGui(Object gui, net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
        if (GUI_HOOKS.isEmpty() || gui == null) return;
        if (guiResolved == null) {
            List<Method> found = new ArrayList<>();
            for (Method m : gui.getClass().getDeclaredMethods()) {
                MixinMerged merged = m.getAnnotation(MixinMerged.class);
                if (merged == null) continue;
                for (String[] h : GUI_HOOKS) {
                    if (!merged.mixin().equals(h[0])) continue;
                    if (!handlerNamed(m.getName(), h[1])) continue;
                    m.setAccessible(true);
                    found.add(m);
                }
            }
            if (!found.isEmpty()) Natives.log("Horizon's client: " + found.size() + " mod injection(s) run at the end of the GUI's frame");
            guiResolved = found;
        }
        for (Method handler : guiResolved) {
            Class<?>[] params = handler.getParameterTypes();
            Object[] call = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                if (params[i] == boolean.class) call[i] = true;
                else if (params[i] == float.class) call[i] = 1F;
                else if (CallbackInfo.class.isAssignableFrom(params[i])) call[i] = new CallbackInfo("extractRenderState", true);
                else if (params[i] == net.minecraft.client.DeltaTracker.class) call[i] = net.minecraft.client.DeltaTracker.ONE;
                else if (params[i].isInstance(graphics)) call[i] = graphics;
            }
            try {
                handler.invoke(Modifier.isStatic(handler.getModifiers()) ? null : gui, call);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                if (FAILURES.merge(handler.toString(), 1, Integer::sum) <= 3) {
                    Natives.log("A mod failed in the GUI's frame (" + handler.getName() + "): " + cause);
                    HorizonFailures.report("the HUD", cause, handler);
                    StackTraceElement[] frames = cause.getStackTrace();
                    for (int f = 0; f < Math.min(frames.length, 14); f++) Natives.log("    at " + frames[f]);
                }
            }
        }
    }

    private static void readClient(String mod, byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean minecraft = false, hud = false, frame = false, gui = false;
        for (AnnotationNode a : annotations(node.invisibleAnnotations, node.visibleAnnotations)) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc)) continue;
            if (get(a, "value") instanceof List<?> list) {
                for (Object t : list) {
                    minecraft |= CLIENT_OWNER.equals(((org.objectweb.asm.Type) t).getInternalName());
                    hud |= HUD_OWNER.equals(((org.objectweb.asm.Type) t).getInternalName());
                    frame |= FRAME_OWNER.equals(((org.objectweb.asm.Type) t).getInternalName());
                    gui |= GUI_OWNER.equals(((org.objectweb.asm.Type) t).getInternalName());
                }
            }
            if (get(a, "targets") instanceof List<?> list) {
                for (Object t : list) {
                    minecraft |= CLIENT_OWNER.equals(((String) t).replace('.', '/'));
                    hud |= HUD_OWNER.equals(((String) t).replace('.', '/'));
                }
            }
        }
        if (hud) readHud(mod, node);
        if (frame) readFrame(mod, node);
        if (gui) readGui(mod, node);
        if (!minecraft) return;
        String mixin = node.name.replace('/', '.');
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                boolean modifyArg = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;".equals(a.desc);
                if (!modifyArg && !"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc)) continue;
                if (!(get(a, "method") instanceof List<?> specs)) continue;
                for (Object spec : specs) {
                    String name = parseSpec((String) spec)[0];
                    if (CLIENT_METHODS.contains(name)) CLIENT_HOOKS.add(new ClientHook(mod, name, mixin, m.name, modifyArg));
                    // The game's start: Fabric starts the entrypoints in Minecraft's constructor at
                    // its first Thread.currentThread() (EntrypointPatch). Mods inject there to run
                    // just before them (shift BEFORE: SuperMartijn642's Core Lib gathers its
                    // registry fields) or just after (AFTER).
                    String start = name.equals("<init>") && !modifyArg ? startPoint(get(a, "at")) : null;
                    if (start != null) CLIENT_HOOKS.add(new ClientHook(mod, start, mixin, m.name, false));
                }
            }
        }
    }

    /** The game renderer, whose frame (extract) mods hook at its head and tail. */
    private static final String FRAME_OWNER = "net/minecraft/client/renderer/GameRenderer";
    /** The mods' injections at the head and tail of GameRenderer.extract: (at, mixin, handler). */
    private static final List<String[]> FRAME_HOOKS = new ArrayList<>();
    private static final Map<String, List<Method>> FRAME_RESOLVED = new ConcurrentHashMap<>();

    private static void readFrame(String mod, ClassNode node) {
        String mixin = node.name.replace('/', '.');
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc)) continue;
                if (!(get(a, "method") instanceof List<?> specs)) continue;
                List<?> ats = get(a, "at") instanceof List<?> l ? l : List.of();
                if (ats.size() != 1 || !(ats.get(0) instanceof AnnotationNode at)) continue;
                Object value = get(at, "value");
                if (!"HEAD".equals(value) && !"TAIL".equals(value) && !"RETURN".equals(value)) continue;
                for (Object spec : specs) {
                    String name = parseSpec((String) spec)[0];
                    // The frame's draw (render(), after extract): its head (Diorama bakes its map
                    // tiles on the GPU there).
                    if (name.equals("render") && "HEAD".equals(value)) {
                        FRAME_HOOKS.add(new String[] {RENDER, mixin, m.name});
                        continue;
                    }
                    if (!name.equals("extract")) continue;
                    FRAME_HOOKS.add(new String[] {"HEAD".equals(value) ? "HEAD" : "TAIL", mixin, m.name});
                }
            }
        }
    }

    /**
     * The mods' injections at the head ({@code head}) or tail of GameRenderer.extract, the
     * frame's start and end (Xaero's render tick: its minimap's chunk writing), with
     * (DeltaTracker.ONE, true) as the frame's arguments.
     */
    public static void fireFrame(Object gameRenderer, boolean head) {
        fireFrame(gameRenderer, head ? "HEAD" : "TAIL");
    }

    /** The head of GameRenderer.render() (no arguments): the frame drawn after its extract. */
    private static final String RENDER = "RENDER";

    /** The mods' injections at the head of GameRenderer.render(), as the frame is drawn. */
    public static void fireRender(Object gameRenderer) {
        fireFrame(gameRenderer, RENDER);
    }

    private static void fireFrame(Object gameRenderer, String at) {
        if (FRAME_HOOKS.isEmpty() || gameRenderer == null) return;
        List<Method> handlers = FRAME_RESOLVED.computeIfAbsent(at, k -> {
            List<Method> found = new ArrayList<>();
            for (Method m : gameRenderer.getClass().getDeclaredMethods()) {
                MixinMerged merged = m.getAnnotation(MixinMerged.class);
                if (merged == null) continue;
                for (String[] h : FRAME_HOOKS) {
                    if (!h[0].equals(at) || !merged.mixin().equals(h[1])) continue;
                    if (!handlerNamed(m.getName(), h[2])) continue;
                    Class<?>[] p = m.getParameterTypes();
                    boolean fits = at.equals(RENDER)
                            ? p.length == 1 && CallbackInfo.class.isAssignableFrom(p[0])
                            : p.length == 3 && p[0] == net.minecraft.client.DeltaTracker.class && p[1] == boolean.class
                                    && CallbackInfo.class.isAssignableFrom(p[2]);
                    if (fits) {
                        m.setAccessible(true);
                        found.add(m);
                    }
                }
            }
            if (!found.isEmpty()) Natives.log("Horizon's client: " + found.size() + " mod injection(s) run at the "
                    + (at.equals(RENDER) ? "draw" : at) + " of each frame");
            return found;
        });
        for (Method handler : handlers) {
            if (!HorizonDeviceMods.runs(handler)) continue;
            try {
                Object self = Modifier.isStatic(handler.getModifiers()) ? null : gameRenderer;
                if (at.equals(RENDER)) handler.invoke(self, new CallbackInfo("render", true));
                else handler.invoke(self, net.minecraft.client.DeltaTracker.ONE, true, new CallbackInfo("extract", true));
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                if (FAILURES.merge(handler.toString(), 1, Integer::sum) <= 3) {
                    Natives.log("A mod failed in the client's frame (" + handler.getName() + "): " + cause);
                    HorizonFailures.report("the client's frame", cause, handler);
                    StackTraceElement[] frames = cause.getStackTrace();
                    for (int f = 0; f < Math.min(frames.length, 16); f++) Natives.log("    at " + frames[f]);
                }
            }
        }
    }

    /** The game's start point, before and after Fabric's entrypoints (fireClient's methods). */
    public static final String START_BEFORE = "<start before entrypoints>";
    public static final String START_AFTER = "<start after entrypoints>";
    /**
     * Minecraft's constructor where it makes the options: after the entrypoints and the
     * registries' freeze (FabricBoot runs it then). Yumi runs its mods' client initializers
     * there (Trinkets Updated's), which register receivers of payloads its common init made.
     */
    public static final String START_OPTIONS = "<start at the options>";
    /** Minecraft's constructor's end: after START_OPTIONS (FabricBoot runs both then). */
    public static final String START_END = "<start at its end>";
    /**
     * Minecraft's constructor at its resource pack list's first reload (PackRepository.reload):
     * mods add their own pack sources there (TACZ's gun packs); HorizonClient's client
     * resources fire it on their repository before reloading it.
     */
    public static final String START_PACKS = "<start at the packs' reload>";

    /** START_BEFORE or START_AFTER for an @At at Minecraft.<init>'s first Thread.currentThread(); else null. */
    private static String startPoint(Object at) {
        List<?> ats = at instanceof List<?> l ? l : at == null ? List.of() : List.of(at);
        if (ats.size() != 1 || !(ats.get(0) instanceof AnnotationNode node)) return null;
        // The constructor's end (MaLiLib's game-init-done: its mods register their renderers).
        if ("RETURN".equals(get(node, "value")) || "TAIL".equals(get(node, "value"))) return START_END;
        if (!"INVOKE".equals(get(node, "value"))) return null;
        // The options' construction comes after the entrypoints (Yumi runs its mods' client
        // initializers there: Trinkets Updated's); the save folder's validator after them
        // (MaLiLib's pre-init: its config folder).
        String target = String.valueOf(get(node, "target"));
        if (target.startsWith("Lnet/minecraft/client/Options;<init>(")
                || target.startsWith("Lnet/minecraft/world/level/storage/LevelStorageSource;parseValidator(")) return START_OPTIONS;
        // The first resource reload's start, late in the constructor (JEI registers its
        // CLIENT_STARTED listener there, which starts it in a world): before the client's first
        // reload and CLIENT_STARTED, as in a real client.
        if (target.startsWith("Lnet/minecraft/client/ResourceLoadStateTracker;startReload(")) return START_END;
        if (target.startsWith("Lnet/minecraft/server/packs/repository/PackRepository;reload(")) return START_PACKS;
        if (!"Ljava/lang/Thread;currentThread()Ljava/lang/Thread;".equals(get(node, "target"))) return null;
        Object ordinal = get(node, "ordinal");
        if (ordinal instanceof Integer o && o != 0) return null;
        Object shift = get(node, "shift");
        boolean after = shift instanceof String[] e && e.length == 2 && "AFTER".equals(e[1]);
        return after ? START_AFTER : START_BEFORE;
    }

    /** Reads the mods' Mixin configs now (the census), if not yet: before the entrypoints run. */
    public static void scanNow() {
        scan();
    }

    /**
     * Runs the mods' injections into {@code minecraft}'s {@code method} (runTick or tick), as one
     * frame or game tick would reach them. An @Inject gets the method's arguments (runTick's
     * advanceGameTime: true) and a CallbackInfo; a @ModifyArg of a boolean gets true, its result
     * unused. Handlers taking locals, or other arguments, are left out. Failures are logged a
     * few times each (a handler failing once a tick fails every tick).
     */
    public static void fireClient(Object minecraft, String method) {
        if (CLIENT_HOOKS.isEmpty()) return;
        List<Method> handlers = CLIENT_RESOLVED.computeIfAbsent(method, k -> resolveClient(minecraft.getClass(), method));
        for (Method handler : handlers) {
            if (!HorizonDeviceMods.runs(handler)) continue;
            Class<?>[] params = handler.getParameterTypes();
            Object[] call = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                if (params[i] == boolean.class) call[i] = true;
                else if (CallbackInfo.class.isAssignableFrom(params[i])) call[i] = new CallbackInfo(method, true);
                else if (params[i] == net.minecraft.client.main.GameConfig.class) call[i] = HorizonClient.gameConfig();
            }
            try {
                handler.invoke(Modifier.isStatic(handler.getModifiers()) ? null : minecraft, call);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                if (FAILURES.merge(handler.toString(), 1, Integer::sum) <= 3) {
                    Natives.log("A mod failed in the client's " + method + " (" + handler.getName() + "): " + cause);
                    HorizonFailures.report("the client's " + method, cause, handler);
                    StackTraceElement[] frames = cause.getStackTrace();
                    for (int f = 0; f < Math.min(frames.length, 14); f++) Natives.log("    at " + frames[f]);
                }
            }
        }
    }

    private static List<Method> resolveClient(Class<?> owner, String method) {
        List<Method> found = new ArrayList<>();
        for (Method m : owner.getDeclaredMethods()) {
            MixinMerged merged = m.getAnnotation(MixinMerged.class);
            if (merged == null) continue;
            for (ClientHook h : CLIENT_HOOKS) {
                if (!h.method().equals(method) || !merged.mixin().equals(h.mixin())) continue;
                if (!handlerNamed(m.getName(), h.handler())) continue;
                Class<?>[] params = m.getParameterTypes();
                boolean fits = h.modifyArg()
                        ? params.length == 1 && params[0] == boolean.class
                        // runTick's handlers may take its boolean or leave it out, as Mixin allows.
                        : method.equals("runTick") || method.equals("renderFrame")
                                ? (params.length == 2 && params[0] == boolean.class && CallbackInfo.class.isAssignableFrom(params[1]))
                                        || (params.length == 1 && CallbackInfo.class.isAssignableFrom(params[0]))
                                // The constructor's: its GameConfig may be taken (null here).
                                : method.equals(START_AFTER) || method.equals(START_BEFORE) || method.equals(START_OPTIONS)
                                        || method.equals(START_END) || method.equals(START_PACKS)
                                        ? (params.length == 1 && CallbackInfo.class.isAssignableFrom(params[0]))
                                                || (params.length == 2 && params[0] == net.minecraft.client.main.GameConfig.class
                                                        && CallbackInfo.class.isAssignableFrom(params[1]))
                                        : params.length == 1 && CallbackInfo.class.isAssignableFrom(params[0]);
                if (!fits) {
                    Natives.log("Mixin census: " + h.mod() + "'s " + h.handler() + " in the client's " + method
                            + " takes what Horizon can't give; not run");
                    continue;
                }
                m.setAccessible(true);
                found.add(m);
            }
        }
        if (!found.isEmpty()) Natives.log("Horizon's client: " + found.size() + " mod injection(s) run each client " + method);
        return found;
    }

    private static List<String> mixinConfigs(ModContainer mod) {
        return mixinConfigs(mod, false);
    }

    /** A mod's Mixin configs; {@code client}: those for the client only too. */
    private static List<String> mixinConfigs(ModContainer mod, boolean client) {
        List<String> out = new ArrayList<>();
        try {
            Optional<Path> meta = mod.findPath("fabric.mod.json");
            if (meta.isEmpty()) return out;
            JsonObject json = JsonParser.parseString(Files.readString(meta.get())).getAsJsonObject();
            if (!json.has("mixins")) return out;
            for (JsonElement m : json.getAsJsonArray("mixins")) {
                if (m.isJsonPrimitive()) out.add(m.getAsString());
                else if (m.isJsonObject()) {
                    JsonObject o = m.getAsJsonObject();
                    String env = o.has("environment") ? o.get("environment").getAsString() : "*";
                    if ((client || !"client".equals(env)) && o.has("config")) out.add(o.get("config").getAsString());
                }
            }
        } catch (Throwable ignored) {
            // A mod without readable metadata has no configs for us.
        }
        return out;
    }

    /** The event-like @Injects of one mixin class: how many. */
    private static int read(String mod, byte[] bytes) {
        return read(mod, bytes, false);
    }

    /**
     * {@code constructorsOnly}: a client mixin's, whose constructor-end injections an object the
     * headless client makes without its constructor runs (constructed: the client's player,
     * Better Combat's and Spell Engine's animation stacks); its other injections stay the
     * client's own.
     */
    private static int read(String mod, byte[] bytes, boolean constructorsOnly) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> targets = new ArrayList<>();
        for (AnnotationNode a : annotations(node.invisibleAnnotations, node.visibleAnnotations)) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc)) continue;
            Object value = get(a, "value");
            if (value instanceof List<?> list) {
                for (Object t : list) targets.add(((org.objectweb.asm.Type) t).getInternalName());
            }
            Object named = get(a, "targets");
            if (named instanceof List<?> list) {
                for (Object t : list) targets.add(((String) t).replace('.', '/'));
            }
        }
        if (targets.isEmpty()) return 0;
        String mixin = node.name.replace('/', '.');
        int n = 0;
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc)) continue;
                String at = eventPoint(get(a, "at"));
                if (at == null) continue;
                boolean cancellable = Boolean.TRUE.equals(get(a, "cancellable"));
                Object methods = get(a, "method");
                if (!(methods instanceof List<?> specs)) continue;
                for (Object spec : specs) {
                    String[] nameDesc = parseSpec((String) spec);
                    if (constructorsOnly && !nameDesc[0].equals("<init>")) continue;
                    for (String owner : targets) {
                        HOOKS.computeIfAbsent(owner + "." + nameDesc[0], k -> new ArrayList<>())
                                .add(new Hook(mod, owner, nameDesc[0], nameDesc[1], at, mixin, m.name, cancellable));
                        n++;
                    }
                }
            }
        }
        return n;
    }

    @SafeVarargs
    private static List<AnnotationNode> annotations(List<AnnotationNode>... lists) {
        List<AnnotationNode> out = new ArrayList<>();
        for (List<AnnotationNode> l : lists) if (l != null) out.addAll(l);
        return out;
    }

    private static Object get(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (key.equals(a.values.get(i))) return a.values.get(i + 1);
        }
        return null;
    }

    /** HEAD, TAIL or RETURN when every @At of the injection is one of them; else null. */
    private static String eventPoint(Object at) {
        List<?> ats = at instanceof List<?> l ? l : at == null ? List.of() : List.of(at);
        String point = null;
        for (Object o : ats) {
            if (!(o instanceof AnnotationNode node)) return null;
            Object v = get(node, "value");
            if (!"HEAD".equals(v) && !"TAIL".equals(v) && !"RETURN".equals(v)) return null;
            if (point != null && !point.equals(v)) return null;
            point = (String) v;
        }
        return point;
    }

    /** "INVOKE target" for an injection at one INVOKE @At; else null. */
    private static String invokePoint(Object at) {
        List<?> ats = at instanceof List<?> l ? l : at == null ? List.of() : List.of(at);
        if (ats.size() != 1 || !(ats.get(0) instanceof AnnotationNode node) || !"INVOKE".equals(get(node, "value"))) return null;
        return get(node, "target") instanceof String target ? "INVOKE " + target : null;
    }

    /** "name", "name(desc)", "Lowner;name(desc)" or "owner.name" as {name, desc or null}. */
    static String[] parseSpec(String spec) {
        String s = spec;
        int paren = s.indexOf('(');
        String desc = paren >= 0 ? s.substring(paren) : null;
        String head = paren >= 0 ? s.substring(0, paren) : s;
        int semi = head.indexOf(';');
        if (semi >= 0) head = head.substring(semi + 1);
        int dot = head.lastIndexOf('.');
        if (dot >= 0) head = head.substring(dot + 1);
        int colon = head.indexOf(':');
        if (colon >= 0) head = head.substring(0, colon);
        // "<init>*": every constructor (Mixin's wildcard).
        if (head.endsWith("*")) head = head.substring(0, head.length() - 1);
        // "<init>*": every constructor (Cardinal Components' level components).
        if (head.endsWith("*")) head = head.substring(0, head.length() - 1);
        return new String[] {head, desc};
    }

    /**
     * The mods' @Inject handlers merged into {@code owner} for its {@code method}, at any point
     * (INVOKE too), whose parameters are {@code params} then a CallbackInfo; Fabric API's and
     * Horizon's own left out. Found by the handler's mixin class (its annotation names the
     * target), for methods whose handlers share a shape with other methods' (SDLEventHandler's
     * handle*Event(SDL_Event)).
     */
    public static List<Method> injected(Class<?> owner, String method, Class<?>... params) {
        return injected(owner, method, false, params);
    }

    /**
     * As {@link #injected(Class, String, Class[])}; with {@code locals}, handlers that also take
     * locals after their CallbackInfo (MixinExtras' @Local: Create's scroll handler takes the
     * scaled offset) are found too.
     */
    public static List<Method> injected(Class<?> owner, String method, boolean locals, Class<?>... params) {
        List<Method> found = new ArrayList<>();
        Map<String, ClassNode> mixins = new HashMap<>();
        for (Method m : owner.getDeclaredMethods()) {
            MixinMerged merged = m.getAnnotation(MixinMerged.class);
            if (merged == null || merged.mixin().startsWith("net.fabricmc.") || merged.mixin().startsWith("dev.horizonlab.platform.")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (locals ? p.length < params.length + 1 : p.length != params.length + 1) continue;
            if (!CallbackInfo.class.isAssignableFrom(p[params.length])) continue;
            if (!java.util.Arrays.equals(java.util.Arrays.copyOf(p, params.length), params)) continue;
            ClassNode node = mixins.computeIfAbsent(merged.mixin(), name -> {
                try (var in = HorizonHooks.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null) return null;
                    ClassNode n = new ClassNode();
                    new ClassReader(in.readAllBytes()).accept(n, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    return n;
                } catch (Throwable t) {
                    return null;
                }
            });
            if (node == null) continue;
            boolean targets = false;
            for (MethodNode handler : node.methods) {
                if (!handlerNamed(m.getName(), handler.name)) continue;
                for (AnnotationNode a : annotations(handler.invisibleAnnotations, handler.visibleAnnotations)) {
                    if (!a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                    if (get(a, "method") instanceof List<?> specs) {
                        for (Object spec : specs) if (parseSpec((String) spec)[0].equals(method)) targets = true;
                    }
                }
            }
            if (!targets) continue;
            m.setAccessible(true);
            found.add(m);
        }
        return found;
    }

    // ---- Mods' @WrapOperation around a call inside a vanilla method ----------------------------

    private static final Map<String, List<Method>> WRAPS = new ConcurrentHashMap<>();

    /**
     * The mods' MixinExtras {@code @WrapOperation} handlers merged into {@code owner} that wrap
     * the call to {@code target} (a descriptor, "Lowner;name(desc)R") inside {@code method}:
     * handlers taking the call's receiver, its arguments and an Operation. Fabric API's and
     * Horizon's own are left out (Horizon fires Fabric's events itself).
     */
    static List<Method> wraps(Class<?> owner, String method, String target, int callArgs) {
        return WRAPS.computeIfAbsent(owner.getName() + "." + method + "|" + target, k -> {
            List<Method> found = new ArrayList<>();
            Map<String, ClassNode> mixins = new HashMap<>();
            for (Method m : owner.getDeclaredMethods()) {
                MixinMerged merged = m.getAnnotation(MixinMerged.class);
                if (merged == null || merged.mixin().startsWith("net.fabricmc.") || merged.mixin().startsWith("dev.horizonlab.")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != callArgs + 1 || !p[callArgs].getName().equals("com.llamalad7.mixinextras.injector.wrapoperation.Operation")) continue;
                ClassNode node = mixins.computeIfAbsent(merged.mixin(), HorizonHooks::mixinNode);
                if (node == null) continue;
                boolean targets = false;
                for (MethodNode handler : node.methods) {
                    if (!handlerNamed(m.getName(), handler.name)) continue;
                    for (AnnotationNode a : annotations(handler.invisibleAnnotations, handler.visibleAnnotations)) {
                        if (!a.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")) continue;
                        boolean inMethod = false;
                        if (get(a, "method") instanceof List<?> specs) {
                            for (Object spec : specs) if (parseSpec((String) spec)[0].equals(method)) inMethod = true;
                        }
                        Object at = get(a, "at");
                        for (Object o : at instanceof List<?> l ? l : at == null ? List.of() : List.of(at)) {
                            if (inMethod && o instanceof AnnotationNode node2 && target.equals(get(node2, "target"))) targets = true;
                        }
                    }
                }
                if (!targets) continue;
                m.setAccessible(true);
                found.add(m);
            }
            if (!found.isEmpty()) {
                Natives.log("Horizon's client: " + found.size() + " mod wrap(s) of " + target + " in " + owner.getSimpleName() + "." + method);
            }
            return found;
        });
    }

    /**
     * The mods' handlers merged into {@code owner} that target one of {@code methods}, of any
     * injector (@Inject, @ModifyReturnValue, @WrapOperation...): "mixin.handler (@Kind on
     * method)" each, Fabric API's and Horizon's own left out. For logs: who hooks what.
     */
    static List<String> hooking(Class<?> owner, java.util.Set<String> methods) {
        List<String> found = new ArrayList<>();
        for (Method m : owner.getDeclaredMethods()) {
            MixinMerged merged = m.getAnnotation(MixinMerged.class);
            if (merged == null || merged.mixin().startsWith("net.fabricmc.") || merged.mixin().startsWith("dev.horizonlab.platform.")) continue;
            ClassNode node = mixinNode(merged.mixin());
            if (node == null) continue;
            for (MethodNode handler : node.methods) {
                if (!handlerNamed(m.getName(), handler.name)) continue;
                for (AnnotationNode a : annotations(handler.invisibleAnnotations, handler.visibleAnnotations)) {
                    if (!(get(a, "method") instanceof List<?> specs)) continue;
                    for (Object spec : specs) {
                        String target = parseSpec((String) spec)[0];
                        if (!methods.contains(target)) continue;
                        String kind = a.desc.substring(a.desc.lastIndexOf('/') + 1, a.desc.length() - 1);
                        found.add(merged.mixin() + "." + handler.name + " (@" + kind + " on " + target + ")");
                    }
                }
            }
        }
        return found;
    }

    private static ClassNode mixinNode(String name) {
        try (var in = HorizonHooks.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) return null;
            ClassNode n = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(n, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return n;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A call vanilla's {@code owner.method} makes to {@code target} with {@code call} (the
     * receiver, then the arguments), as Mixin's wrapping turns it: through the mods' wraps of it
     * ({@link #wraps}), the innermost calling {@code original}. What the outermost returns (a
     * wrap may skip the call: Architectury's input events cancelling a screen's click).
     */
    public static Object wrapCall(Object self, Class<?> owner, String method, String target, Object[] call,
                                  java.util.function.Function<Object[], Object> original) {
        List<Method> wraps = wraps(owner, method, target, call.length);
        if (wraps.isEmpty()) return original.apply(call);
        return wrapStep(self, wraps, 0, call, original, method);
    }

    private static Object wrapStep(Object self, List<Method> wraps, int i, Object[] call,
                                   java.util.function.Function<Object[], Object> original, String method) {
        if (i == wraps.size()) return original.apply(call);
        Method handler = wraps.get(i);
        Class<?> operation = handler.getParameterTypes()[call.length];
        Object next = java.lang.reflect.Proxy.newProxyInstance(operation.getClassLoader(), new Class<?>[] {operation}, (proxy, m, a) -> {
            if (m.getName().equals("call")) {
                Object[] args = a == null || a.length == 0 ? new Object[0] : (Object[]) a[0];
                return wrapStep(self, wraps, i + 1, args.length == call.length ? args : call, original, method);
            }
            return switch (m.getName()) {
                case "toString" -> "Horizon's operation " + method;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == a[0];
                default -> null;
            };
        });
        Object[] args = java.util.Arrays.copyOf(call, call.length + 1);
        args[call.length] = next;
        try {
            return handler.invoke(Modifier.isStatic(handler.getModifiers()) ? null : self, args);
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
            if (FAILURES.merge(handler.toString(), 1, Integer::sum) <= 3) {
                Natives.log("A mod's wrap failed in " + method + " (" + handler.getName() + "): " + cause);
                HorizonFailures.report(method, cause, handler);
            }
            return wrapStep(self, wraps, i + 1, call, original, method);
        }
    }

    // ---- A client method's injections, read from the merged class ---------------------------

    /** The mixin classes read for fireMerged, by name: their injections (method, handler, at). */
    private static final Map<String, List<String[]>> MERGED_MIXINS = new ConcurrentHashMap<>();
    private static final Map<String, List<Method>> MERGED_RESOLVED = new ConcurrentHashMap<>();

    /**
     * The mods' @Injects at {@code at} (HEAD, or TAIL/RETURN as one) of {@code owner.method}, as
     * Mixin merged them into the (client) class: found from the class's own merged handlers,
     * whatever config or Mixin plugin applied them (Create's client mixins come from its
     * plugin's list, which the census doesn't read). Horizon's own are left out. For a vanilla
     * method the headless client never calls, or whose body Horizon cancels at its head
     * (LevelExtractor.allChanged), where vanilla would have run it.
     */
    private static List<Method> merged(Class<?> owner, String method, String at) {
        return MERGED_RESOLVED.computeIfAbsent(owner.getName() + "." + method + "|" + at, k -> {
            List<Method> found = new ArrayList<>();
            for (Method m : owner.getDeclaredMethods()) {
                MixinMerged info = m.getAnnotation(MixinMerged.class);
                if (info == null || info.mixin().startsWith("dev.horizonlab.platform.")) continue;
                for (String[] inject : mixinInjects(info.mixin())) {
                    if (!inject[0].equals(method) || !handlerNamed(m.getName(), inject[1])) continue;
                    // An INVOKE point: the call named (a call the bridge makes in vanilla's place).
                    boolean invoke = inject[2].startsWith("INVOKE ");
                    if (invoke != at.startsWith("INVOKE ")) continue;
                    if (invoke && !inject[2].contains(at.substring("INVOKE ".length()))) continue;
                    boolean head = "HEAD".equals(inject[2]);
                    if (!invoke && head != "HEAD".equals(at)) continue;
                    m.setAccessible(true);
                    found.add(m);
                    break;
                }
            }
            found.sort(Comparator.comparing(Method::getName));
            if (!found.isEmpty()) {
                Natives.log("Horizon's client: " + found.size() + " mod injection(s) at " + at + " of "
                        + owner.getSimpleName() + "." + method);
            }
            return found;
        });
    }

    /**
     * Where a merged handler injects, for running a method's injections in the order its body
     * reaches them: 0 at its HEAD, 2 at a RETURN or its TAIL, 1 anywhere else (or unknown).
     */
    public static int injectOrder(Method merged) {
        MixinMerged info = merged.getAnnotation(MixinMerged.class);
        if (info == null) return 1;
        for (String[] inject : mixinInjects(info.mixin())) {
            if (!handlerNamed(merged.getName(), inject[1])) continue;
            return "HEAD".equals(inject[2]) ? 0 : 2;
        }
        return 1;
    }

    /** A mixin class's event-like @Injects: (target method, handler, HEAD/TAIL/RETURN). */
    private static List<String[]> mixinInjects(String mixin) {
        return MERGED_MIXINS.computeIfAbsent(mixin, name -> {
            List<String[]> out = new ArrayList<>();
            try (var in = HorizonHooks.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) return out;
                ClassNode node = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (MethodNode m : node.methods) {
                    for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                        if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc)) continue;
                        String point = eventPoint(get(a, "at"));
                        if (point == null) point = invokePoint(get(a, "at"));
                        if (point == null || !(get(a, "method") instanceof List<?> specs)) continue;
                        for (Object spec : specs) out.add(new String[] {parseSpec((String) spec)[0], m.name, point});
                    }
                }
            } catch (Throwable t) {
                Natives.log("Horizon's client: mixin " + name + " couldn't be read: " + t);
            }
            return out;
        });
    }

    /**
     * Runs the mods' injections at {@code at} of {@code self}'s {@code method} (see merged):
     * each handler gets the arguments of {@code args} its parameters take by type, else null
     * (zero), and the call's one CallbackInfo; a cancel ends the call, as in the method (the
     * result says so). Failures are logged a few times each.
     */
    public static Result fireMerged(Object self, Class<?> owner, String method, String at, Object... args) {
        return fireMergedSkipping(self, owner, method, at, false, args);
    }

    /** As {@link #fireMerged(Object, Class, String, String, Object...)}, Fabric API's own left out with {@code skipFabric}. */
    public static Result fireMergedSkipping(Object self, Class<?> owner, String method, String at, boolean skipFabric, Object... args) {
        return fireHandlers(self, owner, method, at, merged(owner, method, at), skipFabric, args);
    }

    /**
     * The mods' @Injects at {@code owner.method}'s HEAD and at any call inside it (INVOKE
     * points), in that order: for a vanilla method whose body the bridge doesn't run but whose
     * injections must (the client half of a click, MultiPlayerGameMode.performUseItemOn). Each
     * gets the arguments of {@code args} it takes by type (the method's own and its locals).
     */
    public static Result fireMergedInside(Object self, Class<?> owner, String method, boolean skipFabric, Object... args) {
        return fireHandlers(self, owner, method, "inside", mergedInside(owner, method), skipFabric, args);
    }

    /** Whether mods inject at {@code owner.method}'s HEAD or inside it (see fireMergedInside). */
    public static boolean hasMergedInside(Class<?> owner, String method) {
        return !mergedInside(owner, method).isEmpty();
    }

    private static List<Method> mergedInside(Class<?> owner, String method) {
        return MERGED_RESOLVED.computeIfAbsent(owner.getName() + "." + method + "|inside", k -> {
            List<Method> heads = new ArrayList<>(), inside = new ArrayList<>();
            for (Method m : owner.getDeclaredMethods()) {
                MixinMerged info = m.getAnnotation(MixinMerged.class);
                if (info == null || info.mixin().startsWith("dev.horizonlab.platform.")) continue;
                for (String[] inject : mixinInjects(info.mixin())) {
                    if (!inject[0].equals(method) || !handlerNamed(m.getName(), inject[1])) continue;
                    if ("HEAD".equals(inject[2])) heads.add(m);
                    else if (inject[2].startsWith("INVOKE ")) inside.add(m);
                    else continue;
                    m.setAccessible(true);
                    break;
                }
            }
            heads.sort(Comparator.comparing(Method::getName));
            inside.sort(Comparator.comparing(Method::getName));
            heads.addAll(inside);
            if (!heads.isEmpty()) {
                Natives.log("Horizon's client: " + heads.size() + " mod injection(s) inside " + owner.getSimpleName() + "." + method);
            }
            return heads;
        });
    }

    private static Result fireHandlers(Object self, Class<?> owner, String method, String at, List<Method> handlers,
                                       boolean skipFabric, Object... args) {
        if (handlers.isEmpty()) return Result.NONE;
        // One callback for the call, as Mixin gives the method's injections: a cancel ends it.
        CallbackInfo info = null;
        // MixinExtras' @Share/@Local refs: one per type for the call, as the method's own frame
        // would give every handler that shares it (XaeroPlus' seenChunkRef was null).
        Map<Class<?>, Object> refs = new HashMap<>();
        for (Method handler : handlers) {
            if (skipFabric && handler.getAnnotation(MixinMerged.class).mixin().startsWith("net.fabricmc.")) continue;
            Class<?>[] params = handler.getParameterTypes();
            Object[] call = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                if (CallbackInfo.class.isAssignableFrom(params[i])) {
                    if (info == null) {
                        info = params[i] == CallbackInfo.class ? new CallbackInfo(method, true)
                                : new CallbackInfoReturnable<>(method, true);
                    }
                    call[i] = info;
                    continue;
                }
                for (Object arg : args) {
                    if (params[i].isInstance(arg) || params[i] == boolean.class && arg instanceof Boolean) {
                        call[i] = arg;
                        break;
                    }
                }
                if (call[i] == null && params[i].isPrimitive()) call[i] = zero(params[i]);
                if (call[i] == null) call[i] = sugarRef(params[i], refs);
            }
            try {
                handler.invoke(Modifier.isStatic(handler.getModifiers()) ? null : self, call);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                if (FAILURES.merge(handler.toString(), 1, Integer::sum) <= 3) {
                    Natives.log("A mod failed at " + at + " of " + owner.getSimpleName() + "." + method + " (" + handler.getName() + "): " + cause);
                    HorizonFailures.report(owner.getSimpleName() + "." + method, cause, handler);
                    StackTraceElement[] frames = cause.getStackTrace();
                    for (int f = 0; f < Math.min(frames.length, 10); f++) Natives.log("    at " + frames[f]);
                }
            }
            if (info != null && info.isCancelled()) break;
        }
        if (info == null) return Result.NONE;
        Object value = info instanceof CallbackInfoReturnable<?> cir ? cir.getReturnValue() : null;
        return new Result(info.isCancelled(), value);
    }

    /** Whether mods inject at {@code at} of {@code owner.method} (see merged). */
    public static boolean hasMerged(Class<?> owner, String method, String at) {
        return !merged(owner, method, at).isEmpty();
    }

    // ---- Firing -----------------------------------------------------------------------------

    /** Whether any mod has an event-like injection into owner.method (owner with slashes). */
    public static boolean hooked(String owner, String method) {
        return HOOKS.containsKey(owner + "." + method);
    }

    /** The vanilla methods mods hook at their HEAD, TAIL or RETURN, with how many injections. */
    public static Map<String, Integer> census() {
        Map<String, Integer> out = new TreeMap<>();
        HOOKS.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    /**
     * Fires the mods' injections at {@code at} (HEAD, TAIL or RETURN) of
     * {@code owner.method(desc)} for {@code self} (null for a static method) with the method's
     * arguments. {@code skipFabric}: leave Fabric API's own injections out (Horizon fires
     * Fabric's events there itself). For RETURN, {@code returnValue} is what the method returns.
     */
    public static Result fire(Object self, Class<?> owner, String method, String desc, String at, Object[] args,
                              Object returnValue, boolean skipFabric) {
        String key = owner.getName().replace('.', '/') + "." + method;
        List<Hook> hooks = HOOKS.get(key);
        if (hooks == null) return Result.NONE;
        List<Method> handlers = RESOLVED.computeIfAbsent(key + "|" + desc + "|" + at + "|" + skipFabric,
                k -> resolve(owner, hooks, method, desc, at, args.length, skipFabric));
        if (DEBUG && !method.equals("tick")) {
            Natives.log("hooks: " + at + " of " + owner.getSimpleName() + "." + method + ": " + handlers.size()
                    + " handler(s) " + handlers.stream().map(Method::getName).toList());
        }
        if (handlers.isEmpty()) return Result.NONE;
        CallbackInfo info = desc.endsWith(")V")
                ? new CallbackInfo(method, true)
                : new CallbackInfoReturnable<>(method, true, returnValue);
        Map<Class<?>, Object> refs = new HashMap<>();
        for (Method handler : handlers) {
            Object[] call = new Object[args.length + 1];
            System.arraycopy(args, 0, call, 0, args.length);
            call[args.length] = info;
            Class<?>[] shape = handler.getParameterTypes();
            for (int i = 0; i < args.length && i < shape.length; i++) if (call[i] == null) call[i] = sugarRef(shape[i], refs);
            try {
                handler.invoke(Modifier.isStatic(handler.getModifiers()) ? null : self, call);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                if (FAILURES.merge(handler.toString(), 1, Integer::sum) <= 3) {
                    Natives.log("A mod failed at " + at + " of " + owner.getSimpleName() + "." + method + " (" + handler.getName() + "): " + cause);
                    HorizonFailures.report(owner.getSimpleName() + "." + method, cause, handler);
                    StackTraceElement[] frames = cause.getStackTrace();
                    for (int f = 0; f < Math.min(frames.length, 8); f++) Natives.log("    at " + frames[f]);
                }
            }
            if (info.isCancelled()) break;
        }
        Object value = info instanceof CallbackInfoReturnable<?> cir ? cir.getReturnValue() : null;
        return new Result(info.isCancelled(), value);
    }

    /**
     * The mods' injections at the end (TAIL, RETURN) of the constructors of {@code self}'s
     * classes, superclass first, as its constructors would run them: for an object the mirror
     * made without its constructor (a level), once it is set up. Mods give vanilla's objects
     * fields there (Chunk Loaders' per-level loaders). The constructor's own arguments aren't
     * there to give: a handler that takes them gets nulls (zeros), and may fail (logged).
     */
    public static void constructed(Object self) {
        constructed(self, new Object[0]);
    }

    /**
     * As {@link #constructed(Object)}, with values for constructor arguments the object didn't
     * keep (a chunk map's world storage): each goes to a parameter of its type, before the
     * object's own fields.
     */
    public static void constructed(Object self, Object... extras) {
        // A level can be made before the census ran (the content export's).
        scan();
        // The mixins' field initializers run first in a constructor.
        initFields(self);
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = self.getClass(); c != null && c != Object.class; c = c.getSuperclass()) chain.add(0, c);
        for (Class<?> owner : chain) {
            List<Hook> hooks = HOOKS.get(owner.getName().replace('.', '/') + ".<init>");
            // A call inside the constructor comes before its end (a level's, a chunk map's: an
            // entity's wrapped calls make what the mirror's entity already has).
            if (!(self instanceof net.minecraft.world.entity.Entity)) constructorWraps(owner, self, extras);
            if (hooks == null) continue;
            for (String at : new String[] {"RETURN", "TAIL"}) {
                for (Method handler : constructorHandlers(owner, hooks, at)) {
                    Class<?>[] params = handler.getParameterTypes();
                    java.lang.reflect.Type[] types = handler.getGenericParameterTypes();
                    Object[] call = new Object[params.length];
                    for (int i = 0; i < params.length - 1; i++) call[i] = extra(extras, params[i]);
                    for (int i = 0; i < params.length - 1; i++) if (call[i] == null) call[i] = argument(owner, self, params[i], types[i]);
                    call[params.length - 1] = new CallbackInfo("<init>", false);
                    try {
                        handler.invoke(Modifier.isStatic(handler.getModifiers()) ? null : self, call);
                    } catch (Throwable t) {
                        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                        Natives.log("A mod failed at the end of " + owner.getSimpleName() + "'s constructor ("
                                + handler.getName() + "): " + cause);
                    }
                }
            }
        }
    }

    /**
     * The mods' mixin fields made with a no-argument {@code new} in the mixin's initializer
     * ({@code private RollManager rollManager = new RollManager();}: Combat Roll's on the
     * client player), by target class (internal name): (field, type). Mixin merges these into
     * the target's constructors, which the mirror's objects skip.
     */
    private static final Map<String, List<String[]>> FIELD_INITS = new HashMap<>();
    /** The mods' @WrapOperation handlers on calls in constructors, by target class: (mixin, handler, wrapped call). */
    private static final Map<String, List<String[]>> CONSTRUCTOR_WRAPS = new HashMap<>();

    private static void readFieldInits(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> targets = new ArrayList<>();
        for (AnnotationNode a : annotations(node.invisibleAnnotations, node.visibleAnnotations)) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc)) continue;
            if (get(a, "value") instanceof List<?> l) for (Object t : l) targets.add(((org.objectweb.asm.Type) t).getInternalName());
            if (get(a, "targets") instanceof List<?> l) for (Object t : l) targets.add(((String) t).replace('.', '/'));
        }
        if (targets.isEmpty()) return;
        HorizonMixinInit.read(node, targets);
        String mixin = node.name.replace('/', '.');
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                if (!("L" + WRAP_OPERATION.replace('.', '/') + ";").equals(a.desc)) continue;
                if (!(get(a, "method") instanceof List<?> specs)) continue;
                boolean init = false;
                for (Object spec : specs) init |= parseSpec((String) spec)[0].equals("<init>");
                List<?> ats = get(a, "at") instanceof List<?> l ? l : List.of();
                if (!init || ats.isEmpty() || !(ats.get(0) instanceof AnnotationNode at)
                        || !(get(at, "target") instanceof String target)) continue;
                for (String t : targets) CONSTRUCTOR_WRAPS.computeIfAbsent(t, k -> new ArrayList<>()).add(new String[] {mixin, m.name, target});
            }
            if (!m.name.equals("<init>")) continue;
            List<org.objectweb.asm.tree.AbstractInsnNode> code = new ArrayList<>();
            for (var insn : m.instructions) if (insn.getOpcode() >= 0) code.add(insn);
            for (int i = 0; i + 1 < code.size(); i++) {
                // new T, dup, invokespecial T.<init>()V, putfield <mixin>.field
                if (i + 3 < code.size() && code.get(i) instanceof org.objectweb.asm.tree.TypeInsnNode n && n.getOpcode() == org.objectweb.asm.Opcodes.NEW
                        && code.get(i + 1).getOpcode() == org.objectweb.asm.Opcodes.DUP
                        && code.get(i + 2) instanceof org.objectweb.asm.tree.MethodInsnNode c
                        && c.getOpcode() == org.objectweb.asm.Opcodes.INVOKESPECIAL && c.owner.equals(n.desc)
                        && c.name.equals("<init>") && c.desc.equals("()V")
                        && code.get(i + 3) instanceof org.objectweb.asm.tree.FieldInsnNode f
                        && f.getOpcode() == org.objectweb.asm.Opcodes.PUTFIELD && f.owner.equals(node.name)) {
                    for (String t : targets) FIELD_INITS.computeIfAbsent(t, k -> new ArrayList<>()).add(new String[] {f.name, n.desc});
                }
                // new T, dup, aload_0 (checkcast), invokespecial T.<init>(Owner)V, putfield
                // <mixin>.field: a part made for the object itself (Fabric's packet context
                // on a Connection).
                if (i + 4 < code.size() && code.get(i) instanceof org.objectweb.asm.tree.TypeInsnNode n && n.getOpcode() == org.objectweb.asm.Opcodes.NEW
                        && code.get(i + 1).getOpcode() == org.objectweb.asm.Opcodes.DUP
                        && code.get(i + 2) instanceof org.objectweb.asm.tree.VarInsnNode self && self.getOpcode() == org.objectweb.asm.Opcodes.ALOAD
                        && self.var == 0) {
                    int k = i + 3;
                    if (code.get(k).getOpcode() == org.objectweb.asm.Opcodes.CHECKCAST) k++;
                    if (k + 1 < code.size() && code.get(k) instanceof org.objectweb.asm.tree.MethodInsnNode c
                            && c.getOpcode() == org.objectweb.asm.Opcodes.INVOKESPECIAL && c.owner.equals(n.desc) && c.name.equals("<init>")
                            && org.objectweb.asm.Type.getArgumentTypes(c.desc).length == 1
                            && org.objectweb.asm.Type.getArgumentTypes(c.desc)[0].getSort() == org.objectweb.asm.Type.OBJECT
                            && code.get(k + 1) instanceof org.objectweb.asm.tree.FieldInsnNode f
                            && f.getOpcode() == org.objectweb.asm.Opcodes.PUTFIELD && f.owner.equals(node.name)) {
                        String param = org.objectweb.asm.Type.getArgumentTypes(c.desc)[0].getInternalName();
                        for (String t : targets) FIELD_INITS.computeIfAbsent(t, x -> new ArrayList<>()).add(new String[] {f.name, n.desc, THIS, param});
                    }
                }
                // invokestatic Owner.getter()T, putfield <mixin>.field: a mod's shared object
                // (Enderscape's EnderscapeClient.clientsideVariables() on the HUD).
                if (code.get(i) instanceof org.objectweb.asm.tree.MethodInsnNode c
                        && c.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC && c.desc.startsWith("()L")
                        && code.get(i + 1) instanceof org.objectweb.asm.tree.FieldInsnNode f
                        && f.getOpcode() == org.objectweb.asm.Opcodes.PUTFIELD && f.owner.equals(node.name)) {
                    for (String t : targets) {
                        FIELD_INITS.computeIfAbsent(t, k -> new ArrayList<>()).add(new String[] {f.name, c.owner, c.name});
                    }
                }
            }
        }
    }

    /** A field init's marker for {@code new T(this)}. */
    private static final String THIS = "<this>";

    /**
     * The mixins' {@code new T()} (or {@code new T(this)}) fields of {@code self}'s classes
     * that are still null, made ({@link #FIELD_INITS}).
     */
    public static void initFields(Object self) {
        scan();
        for (Class<?> c = self.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            // The mixins' initializers themselves, where their code can be copied.
            HorizonMixinInit.run(self, c);
            List<String[]> inits = FIELD_INITS.get(c.getName().replace('.', '/'));
            if (inits == null) continue;
            for (String[] init : inits) {
                try {
                    java.lang.reflect.Field field = c.getDeclaredField(init[0]);
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    if (field.get(self) != null) continue;
                    Class<?> type = Class.forName(init[1].replace('/', '.'), true, c.getClassLoader());
                    if (init.length == 4 && THIS.equals(init[2])) {
                        // new T(this).
                        Class<?> param = Class.forName(init[3].replace('/', '.'), false, c.getClassLoader());
                        var make = type.getDeclaredConstructor(param);
                        make.setAccessible(true);
                        field.set(self, make.newInstance(self));
                        continue;
                    }
                    if (init.length == 3) {
                        // A static getter: (field, owner, method).
                        Method getter = type.getDeclaredMethod(init[2]);
                        getter.setAccessible(true);
                        Object value = getter.invoke(null);
                        if (value != null) field.set(self, value);
                        else if (DEBUG) Natives.log("Mixin fields: " + c.getSimpleName() + "." + init[0] + ": " + init[2] + "() gave null");
                        continue;
                    }
                    var make = type.getDeclaredConstructor();
                    make.setAccessible(true);
                    field.set(self, make.newInstance());
                } catch (Throwable t) {
                    // A field Mixin renamed, or one that can't be made here, stays as it is.
                    if (FAILURES.merge("init " + c.getName() + "." + init[0], 1, Integer::sum) == 1) {
                        Natives.log("Mixin fields: " + c.getSimpleName() + "." + init[0] + " not made: " + t);
                    }
                }
            }
        }
    }

    private static final String WRAP_OPERATION = "com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation";

    /**
     * The mods' @WrapOperation handlers around a call inside {@code owner}'s constructor, run
     * once as {@link #constructed} runs the TAIL ones: Biolith seeds its biome noise in one
     * around ServerLevel's {@code getGeneratorState()} (without it every Java worldgen chunk
     * failed). The handler's receiver and arguments are the object's fields of their types;
     * its Operation calls the wrapped method (null if that fails); what it returns is dropped.
     */
    private static void constructorWraps(Class<?> owner, Object self, Object[] extras) {
        List<String[]> wraps = CONSTRUCTOR_WRAPS.get(owner.getName().replace('.', '/'));
        if (wraps == null) return;
        for (Method m : owner.getDeclaredMethods()) {
            MixinMerged merged = m.getAnnotation(MixinMerged.class);
            // (MixinExtras' bridges to a handler carry its name too.)
            if (merged == null || m.getName().contains("$mixinextras$bridge$")) continue;
            String found = null;
            for (String[] w : wraps) {
                if (merged.mixin().equals(w[0]) && handlerNamed(m.getName(), w[1])) found = w[2];
            }
            if (found == null) continue;
            // (A chunk map's random state is made with its wraps where it is kept: wrapConstructorCall.)
            if (found.contains(RANDOM_STATE_CREATE)) continue;
            final String target = found;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 0 || !params[params.length - 1].isInterface()) continue;
            java.lang.reflect.Type[] types = m.getGenericParameterTypes();
            Object[] call = new Object[params.length];
            for (int i = 0; i < params.length - 1; i++) {
                call[i] = extra(extras, params[i]);
                if (call[i] == null) call[i] = argument(owner, self, params[i], types[i]);
                // A chunk map's noise settings are its generator's (Lithostitched wraps their
                // noiseRouter() as the map makes its random state).
                if (call[i] == null && params[i] == net.minecraft.world.level.levelgen.NoiseGeneratorSettings.class) {
                    for (Object e : extras) {
                        if (e instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise) {
                            call[i] = noise.generatorSettings().value();
                        }
                    }
                }
            }
            Class<?> operation = params[params.length - 1];
            call[params.length - 1] = java.lang.reflect.Proxy.newProxyInstance(operation.getClassLoader(),
                    new Class<?>[] {operation}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return method.getName().equals("toString") ? "Horizon's operation " + target
                                    : method.getName().equals("hashCode") ? System.identityHashCode(proxy) : proxy == args[0];
                        }
                        Object[] values = args != null && args.length == 1 && args[0] instanceof Object[] a ? a : args;
                        return wrappedCall(target, values);
                    });
            try {
                m.setAccessible(true);
                m.invoke(Modifier.isStatic(m.getModifiers()) ? null : self, call);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                Natives.log("A mod failed around a call in " + owner.getSimpleName() + "'s constructor ("
                        + m.getName() + "): " + cause);
            }
        }
    }

    /** RandomState.create in ChunkMap's constructor (Lithostitched wraps the noise router there). */
    public static final String RANDOM_STATE_CREATE = "RandomState;create";

    /**
     * The mods' @WrapOperation handlers around the call {@code targetPart} names in
     * {@code owner}'s constructor, for a value the mirror makes itself where it keeps it (a
     * chunk map's random state): each handler gets the value so far from its Operation, its
     * parameters from {@code values} by type (the call's arguments and the constructor's
     * locals it captures), and what it returns is the value.
     */
    public static Object wrapConstructorCall(Class<?> owner, Object self, String targetPart, Object original, Object... values) {
        scan();
        List<String[]> wraps = CONSTRUCTOR_WRAPS.get(owner.getName().replace('.', '/'));
        if (wraps == null) return original;
        Object result = original;
        for (Method m : owner.getDeclaredMethods()) {
            MixinMerged merged = m.getAnnotation(MixinMerged.class);
            if (merged == null || m.getName().contains("$mixinextras$bridge$")) continue;
            boolean match = false;
            for (String[] w : wraps) {
                match |= merged.mixin().equals(w[0]) && handlerNamed(m.getName(), w[1])
                        && w[2].contains(targetPart);
            }
            if (!match) continue;
            Class<?>[] params = m.getParameterTypes();
            Object[] call = new Object[params.length];
            boolean[] used = new boolean[values.length];
            int operation = -1;
            for (int i = 0; i < params.length; i++) {
                if (params[i].isInterface() && params[i].getName().endsWith(".wrapoperation.Operation")) {
                    operation = i;
                    continue;
                }
                Class<?> type = boxed(params[i]);
                for (int j = 0; j < values.length; j++) {
                    if (!used[j] && values[j] != null && type.isInstance(values[j])) {
                        call[i] = values[j];
                        used[j] = true;
                        break;
                    }
                }
                if (call[i] == null && params[i].isPrimitive()) call[i] = zero(params[i]);
            }
            if (operation < 0) continue;
            final Object current = result;
            call[operation] = java.lang.reflect.Proxy.newProxyInstance(params[operation].getClassLoader(),
                    new Class<?>[] {params[operation]}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                                    : method.getName().equals("equals") ? proxy == args[0] : "Horizon's operation";
                        }
                        return current;
                    });
            try {
                m.setAccessible(true);
                Object made = m.invoke(Modifier.isStatic(m.getModifiers()) ? null : self, call);
                if (made != null) result = made;
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                Natives.log("A mod failed around " + targetPart + " in " + owner.getSimpleName() + "'s constructor ("
                        + m.getName() + "): " + cause);
            }
        }
        return result;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == long.class) return Long.class;
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        return Character.class;
    }

    /** The method a @WrapOperation target names ({@code Lowner;name(desc)R}), called with these values (receiver first unless static); null when it can't be. */
    private static Object wrappedCall(String target, Object[] values) {
        int semi = target.indexOf(';'), paren = target.indexOf('(');
        if (!target.startsWith("L") || semi < 0 || paren < semi) return null;
        String name = target.substring(semi + 1, paren);
        if (name.equals("<init>")) return null;
        try {
            Class<?> owner = Class.forName(target.substring(1, semi).replace('/', '.'), false,
                    HorizonHooks.class.getClassLoader());
            int count = org.objectweb.asm.Type.getArgumentTypes(target.substring(paren)).length;
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (!method.getName().equals(name) || method.getParameterCount() != count) continue;
                    method.setAccessible(true);
                    if (Modifier.isStatic(method.getModifiers())) return method.invoke(null, values);
                    if (values == null || values.length == 0 || values[0] == null) return null;
                    return method.invoke(values[0], java.util.Arrays.copyOfRange(values, 1, values.length));
                }
            }
        } catch (Throwable t) {
            // The original call failed on the mirror object: the handler gets null.
        }
        return null;
    }

    /**
     * A constructor argument a handler takes, for an object made without its constructor: the
     * value of the owner's one field of that exact (generic) type, which the constructor kept
     * from it (Level's dimension type holder, which Lithium caches the height from at its end:
     * without it every level is 0 blocks tall and worldgen fails); otherwise null or zero.
     */
    private static Object extra(Object[] extras, Class<?> type) {
        for (Object e : extras) if (e != null && !type.isPrimitive() && type.isInstance(e)) return e;
        return null;
    }

    /** Whether a handler's parameters (CallbackInfo last) are more than the CallbackInfo and match no overload of {@code method}. */
    private static boolean takesLocals(Class<?> owner, String method, Class<?>[] params) {
        int ci = -1;
        for (int i = 0; i < params.length; i++) if (CallbackInfo.class.isAssignableFrom(params[i])) { ci = i; break; }
        if (ci <= 0) return ci < 0;
        Class<?>[] head = java.util.Arrays.copyOf(params, ci);
        java.util.List<Class<?>[]> overloads = new java.util.ArrayList<>();
        if (method.equals("<init>")) for (var c : owner.getDeclaredConstructors()) overloads.add(c.getParameterTypes());
        else for (var mm : owner.getDeclaredMethods()) if (mm.getName().equals(method)) overloads.add(mm.getParameterTypes());
        for (Class<?>[] o : overloads) if (java.util.Arrays.equals(o, head)) return params.length - 1 > ci;
        return true;
    }

    private static Object argument(Class<?> owner, Object self, Class<?> type, java.lang.reflect.Type generic) {
        if (type.isPrimitive()) return zero(type);
        // A level's stem (ServerLevel's constructor: its generator, which BCLib's and BetterEnd's
        // handlers read), from the registries by the level's dimension.
        if (type == net.minecraft.world.level.dimension.LevelStem.class && self instanceof net.minecraft.world.level.Level level) {
            try {
                return HorizonData.stem(level.dimension().identifier());
            } catch (RuntimeException e) {
                return null;
            }
        }
        // The owner's fields, then its superclasses' (ServerLevel's dimension key is Level's);
        // by generic type, then (a handler declared with a raw type) by class alone.
        for (boolean exact : new boolean[] {true, false}) {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                java.lang.reflect.Field match = null;
                boolean several = false;
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (exact ? !f.getGenericType().equals(generic) : f.getType() != type) continue;
                    if (match != null) several = true;
                    match = f;
                }
                if (several) break;
                if (match == null) continue;
                try {
                    match.setAccessible(true);
                    return match.get(self);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * A MixinExtras reference (LocalRef, LocalBooleanRef, LocalIntRef...: @Share and @Local by
     * reference) for a handler parameter of that type, shared by the call's handlers; null for
     * any other type. Horizon runs no vanilla frame to take locals from, so it holds the type's
     * default, as a fresh @Share does.
     */
    private static Object sugarRef(Class<?> type, Map<Class<?>, Object> refs) {
        if (!type.isInterface() || !type.getName().startsWith("com.llamalad7.mixinextras.sugar.ref.")) return null;
        // MixinExtras generates its implementations at run time; these are plain interfaces
        // (get, set, getAndSet...), so a holder answers them: the type's default to start.
        return refs.computeIfAbsent(type, t -> {
            Class<?> held = null;
            for (Method m : t.getMethods()) if (m.getName().equals("get") && m.getParameterCount() == 0) held = m.getReturnType();
            final Object[] value = {held != null && held.isPrimitive() ? zero(held) : null};
            return java.lang.reflect.Proxy.newProxyInstance(t.getClassLoader(), new Class<?>[] {t}, (proxy, m, a) -> {
                switch (m.getName()) {
                    case "get": return value[0];
                    case "set": value[0] = a[0]; return null;
                    case "getAndSet": { Object old = value[0]; value[0] = a[0]; return old; }
                    case "toString": return "ref(" + value[0] + ")";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == a[0];
                    default: return m.getReturnType().isPrimitive() && m.getReturnType() != void.class ? zero(m.getReturnType()) : null;
                }
            });
        });
    }

    private static Object zero(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }

    /** The merged constructor handlers at {@code at}, priority first; each ends with its CallbackInfo. */
    private static List<Method> constructorHandlers(Class<?> owner, List<Hook> hooks, String at) {
        return RESOLVED.computeIfAbsent(owner.getName() + ".<init>|" + at, k -> {
            List<Method> found = new ArrayList<>();
            Map<Method, Integer> priority = new HashMap<>();
            for (Method m : owner.getDeclaredMethods()) {
                MixinMerged merged = m.getAnnotation(MixinMerged.class);
                if (merged == null) continue;
                for (Hook h : hooks) {
                    if (!h.at().equals(at) || h.mod().equals("horizon")) continue;
                    if (!merged.mixin().equals(h.mixin())) continue;
                    if (!handlerNamed(m.getName(), h.handler())) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 0 || !CallbackInfo.class.isAssignableFrom(params[params.length - 1])) continue;
                    m.setAccessible(true);
                    found.add(m);
                    priority.put(m, merged.priority());
                }
            }
            found.sort(Comparator.comparingInt(priority::get));
            return found;
        });
    }

    /** The merged handlers of these hooks in the target class, priority first, for one call shape. */
    private static List<Method> resolve(Class<?> owner, List<Hook> hooks, String method, String desc, String at,
                                        int arguments, boolean skipFabric) {
        Map<String, Hook> wanted = new HashMap<>();
        for (Hook h : hooks) {
            if (!h.at().equals(at) || !h.method().equals(method)) continue;
            if (h.desc() != null && !h.desc().equals(desc)) continue;
            if (skipFabric && (h.mod().startsWith("fabric-") || h.mod().equals("fabric-api"))) continue;
            if (h.mod().equals("horizon")) continue;
            wanted.put(h.mixin() + "#" + h.handler(), h);
        }
        List<Method> found = new ArrayList<>();
        Map<Method, Integer> priority = new HashMap<>();
        for (Method m : owner.getDeclaredMethods()) {
            MixinMerged merged = m.getAnnotation(MixinMerged.class);
            if (merged == null) continue;
            for (Hook h : wanted.values()) {
                if (!merged.mixin().equals(h.mixin())) continue;
                if (!handlerNamed(m.getName(), h.handler())) continue;
                Class<?>[] params = m.getParameterTypes();
                // The method's arguments, then the CallbackInfo; locals captured after it can't
                // be given (no vanilla frame runs), so such a handler is left out.
                if (params.length != arguments + 1 || !CallbackInfo.class.isAssignableFrom(params[arguments])) {
                    // A handler shaped for another of the target's calls (only the CallbackInfo, or
                    // exactly an overload's parameters) is the other fire's: Horizon fires both
                    // shapes (MinecraftServer.<init>), so it runs there and isn't a gap. Only one
                    // that takes more than any overload (captured locals) is said. (UMP, 2026-11-11)
                    if (!takesLocals(owner, method, params)) continue;
                    if (FAILURES.merge("shape " + m, 1, Integer::sum) == 1) {
                        Natives.log("Mixin census: " + h.mod() + "'s " + h.handler() + " at " + at + " of "
                                + owner.getSimpleName() + "." + method + " takes locals; Horizon can't give them");
                    }
                    continue;
                }
                m.setAccessible(true);
                found.add(m);
                priority.put(m, merged.priority());
            }
        }
        found.sort(Comparator.comparingInt(priority::get));
        return found;
    }
}
