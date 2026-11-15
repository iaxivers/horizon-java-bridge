package dev.horizonlab.bridge.fabric;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import dev.horizonlab.bridge.Natives;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.knot.Knot;

/**
 * Starts Fabric Loader inside Horizon's JVM (called from Rust, horizon-jvm): Knot with
 * {@link HorizonGameProvider}, mod discovery in mods-java/ (plus Fabric API, added by the
 * bridge), Mixin on the game's classes, the game's own bootstrap (registries, as vanilla's
 * server Main does it before Fabric's hook), then the mods' {@code main} and {@code client}
 * (or {@code server}) entrypoints.
 */
public final class FabricBoot {
    private FabricBoot() {}

    private static ClassLoader gameLoader;

    /**
     * @param side "client" or "server"
     * @param version the game version Horizon plays ("26.3")
     * @param gameJar the game jar (the player's client jar, or a facade of it)
     * @param libraries the game's libraries, on Knot's class path beside the game jar
     * @param modsDir the folder of mod jars (mods-java/)
     * @param addMods more mod jars to load (Fabric API), or none
     * @param gameDir the game folder (config/ goes there)
     * @return one line per loaded mod, "id version"
     */
    public static String[] start(String side, String version, String gameJar, String[] libraries, String modsDir, String[] addMods, String gameDir) throws ReflectiveOperationException {
        EnvType env = "server".equals(side) ? EnvType.SERVER : EnvType.CLIENT;
        System.setProperty("fabric.skipMcProvider", "true");
        System.setProperty("fabric.modsFolder", modsDir);
        System.setProperty("fabric.noGui", "true");
        if (addMods.length > 0) {
            System.setProperty("fabric.addMods", String.join(File.pathSeparator, addMods));
        }
        HorizonGameProvider.version = version;
        HorizonGameProvider.gameJar = Paths.get(gameJar).toAbsolutePath();
        HorizonGameProvider.gameDir = Paths.get(gameDir).toAbsolutePath();
        // A vanilla game folder always has mods/ (a launcher makes it): mods read their add-on
        // packs from there (Yuushya's addons) and fail to start when it's missing. Horizon keeps
        // Java mods in mods-java/, so an empty mods/ is made beside it. (UMP, 2026-11-12)
        try {
            java.nio.file.Files.createDirectories(HorizonGameProvider.gameDir.resolve("mods"));
        } catch (java.io.IOException | RuntimeException e) {
            // A read-only game folder: mods that need it say so themselves.
        }
        List<Path> libs = new ArrayList<>();
        for (String library : libraries) libs.add(Paths.get(library).toAbsolutePath());
        HorizonGameProvider.libraries = libs;
        // Knot may load from the system class path only what is there: the loader, its
        // libraries and this bridge.
        List<Path> parent = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isEmpty()) parent.add(Paths.get(entry).toAbsolutePath().normalize());
        }
        HorizonGameProvider.parentClassPath = parent;

        Knot knot = new Knot(env);
        gameLoader = knot.init(new String[0]);
        // Mods' log lines into Horizon's log from here on (the platform's HorizonLog).
        try {
            Class.forName("dev.horizonlab.platform.HorizonLog", true, gameLoader).getMethod("install").invoke(null);
        } catch (ClassNotFoundException e) {
            // No platform mod: the console has them.
        }
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        bootstrapGame();
        loader.prepareModInit(HorizonGameProvider.gameDir, null);
        if (env == EnvType.CLIENT) {
            // Horizon's headless client, which mods' code reaches (Minecraft.getInstance()): made
            // first, as a client's Minecraft starts Fabric's entrypoints from its constructor.
            // Then the mods' injections at that start, before the entrypoints (gameStart).
            try {
                Class<?> client = Class.forName("dev.horizonlab.platform.HorizonClient", true, gameLoader);
                client.getMethod("boot", String.class).invoke(null, gameDir);
                client.getMethod("gameStart", boolean.class).invoke(null, false);
            } catch (ClassNotFoundException e) {
                // No platform mod: no headless client.
            }
        }
        try {
            timedEntrypoints(loader, "main", ModInitializer.class, ModInitializer::onInitialize);
        } catch (RuntimeException e) {
            // Fabric stops every mod for one that fails here: say where, then give up as it does.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("A mod's initializer failed: " + e.getMessage() + ", caused by " + root);
            StackTraceElement[] frames = root.getStackTrace();
            for (int f = 0; f < Math.min(frames.length, 25); f++) Natives.log("    at " + frames[f]);
            for (Throwable other : e.getSuppressed()) {
                Throwable cause = other;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                Natives.log("  and: " + other.getMessage() + ", caused by " + cause);
            }
            throw e;
        }
        if (env == EnvType.CLIENT) {
            // Every client entrypoint runs, then Fabric throws what failed: a mod whose client
            // code needs more of a client than the headless one has loses that, not the rest.
            try {
                timedEntrypoints(loader, "client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
            } catch (RuntimeException e) {
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                Natives.log("Some mods' client code failed (their screens may be missing): " + e.getMessage()
                        + ", caused by " + root);
                StackTraceElement[] frames = root.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
                // Each other entrypoint that failed (Fabric adds them as suppressed).
                for (Throwable other : e.getSuppressed()) {
                    Throwable cause = other;
                    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                    Natives.log("  and: " + other.getMessage() + ", caused by " + cause);
                }
                // Kept for the reason a later failure gives: a mod whose client start failed half way
                // (registered part of its content) can make the data load fail with a message naming
                // none of them (Paladin's Furniture beside The Graveyard: "Unbound tags"). (UMP, 2026-11-13)
                java.util.List<String> ids = new java.util.ArrayList<>();
                for (Throwable t : prepend(e, e.getSuppressed())) {
                    var m = java.util.regex.Pattern.compile("provided by '([^']+)'").matcher(String.valueOf(t.getMessage()));
                    if (m.find()) ids.add(m.group(1));
                }
                clientFailures = String.join(", ", ids);
            }
            try {
                Class<?> client = Class.forName("dev.horizonlab.platform.HorizonClient", true, gameLoader);
                client.getMethod("gameStart", boolean.class).invoke(null, true);
                try {
                    client.getMethod("finish").invoke(null);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable root = e;
                    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    Natives.log("Horizon's headless client could not be set up: " + root);
                    StackTraceElement[] frames = root.getStackTrace();
                    for (int f = 0; f < Math.min(frames.length, 20); f++) Natives.log("    at " + frames[f]);
                    throw e;
                }
            } catch (ClassNotFoundException e) {
                // No platform mod.
            }
        } else {
            loader.invokeEntrypoints("server", DedicatedServerModInitializer.class, DedicatedServerModInitializer::onInitializeServer);
        }
        freezeRegistries();
        if (env == EnvType.CLIENT) {
            // Minecraft's constructor goes on to its options: the mods' injections there.
            try {
                Class.forName("dev.horizonlab.platform.HorizonClient", true, gameLoader).getMethod("optionsStart").invoke(null);
            } catch (ClassNotFoundException e) {
                // No platform mod.
            } catch (ReflectiveOperationException e) {
                Natives.log("The client's start at its options failed: " + (e.getCause() != null ? e.getCause() : e));
            }
        }
        List<String> mods = new ArrayList<>();
        for (ModContainer mod : loader.getAllMods()) {
            mods.add(mod.getMetadata().getId() + " " + mod.getMetadata().getVersion().getFriendlyString());
        }
        Natives.log("Fabric Loader started: " + mods.size() + " mods");
        return mods.toArray(new String[0]);
    }

    /**
     * What vanilla's server Main runs before Fabric calls the entrypoints: the version, then
     * the built-in registries (blocks, items, entity types...) and their check. Pure Java data,
     * as the data generator runs it: no world, no window.
     */
    private static void bootstrapGame() throws ReflectiveOperationException {
        long started = System.nanoTime();
        Class<?> constants;
        try {
            constants = Class.forName("net.minecraft.SharedConstants", true, gameLoader);
        } catch (ClassNotFoundException e) {
            Natives.log("No Minecraft bootstrap: the game jar holds no net.minecraft.SharedConstants");
            return;
        }
        constants.getMethod("tryDetectVersion").invoke(null);
        Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap", true, gameLoader);
        bootstrap.getMethod("bootStrap").invoke(null);
        bootstrap.getMethod("validate").invoke(null);
        Natives.log("Minecraft bootstrap done in " + (System.nanoTime() - started) / 1_000_000 + " ms");
    }

    /**
     * Freezes the built-in registries once the mods have registered their content, as Fabric
     * API's MainMixin does right after the entrypoints (Fabric API delays vanilla's freeze).
     */
    private static void freezeRegistries() {
        try {
            Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, gameLoader);
            registries.getMethod("bootStrap").invoke(null);
            Class<?> tabs = Class.forName("net.minecraft.world.item.CreativeModeTabs", true, gameLoader);
            tabs.getMethod("validate").invoke(null);
        } catch (ClassNotFoundException e) {
            // Not a Minecraft game jar (the bridge's own tests).
        } catch (ReflectiveOperationException e) {
            Natives.log("Freezing the registries failed: " + (e.getCause() != null ? e.getCause() : e));
        }
    }

    /** A game or mod class, loaded and initialized by Knot, so Mixins apply to it. */
    /** The mods whose client entrypoint failed, for a later failure's message ("" when none). */
    public static volatile String clientFailures = "";

    private static Throwable[] prepend(Throwable first, Throwable[] rest) {
        Throwable[] all = new Throwable[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    public static Class<?> gameClass(String name) throws ClassNotFoundException {
        if (gameLoader == null) throw new IllegalStateException("Fabric Loader is not started");
        return Class.forName(name.replace('/', '.'), true, gameLoader);
    }

    /**
     * Fabric's invokeEntrypoints, timing each mod's (UMP, 2026-11-11): with HORIZON_JAVA_INIT_TIMES set, or always
     * for the slow ones (over 500 ms), one line each, so a slow start names the mods it waits for. Failures are
     * collected and thrown at the end as Fabric's own does, so one mod's failure doesn't skip the rest.
     */
    private static <T> void timedEntrypoints(FabricLoaderImpl loader, String key, Class<T> type,
                                             java.util.function.Consumer<? super T> invoker) {
        boolean all = System.getenv("HORIZON_JAVA_INIT_TIMES") != null;
        RuntimeException failed = null;
        java.util.List<String> slow = new java.util.ArrayList<>();
        long total = System.nanoTime();
        for (var container : loader.getEntrypointContainers(key, type)) {
            long started = System.nanoTime();
            try {
                invoker.accept(container.getEntrypoint());
            } catch (Throwable t) {
                RuntimeException e = new RuntimeException("Could not execute entrypoint stage '" + key
                        + "' due to errors, provided by '" + container.getProvider().getMetadata().getId() + "'", t);
                if (failed == null) failed = e;
                else failed.addSuppressed(e);
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (all || ms >= 500) slow.add(container.getProvider().getMetadata().getId() + " " + ms + " ms");
        }
        if (!slow.isEmpty() || all) {
            Natives.log("Java mods: '" + key + "' entrypoints took " + (System.nanoTime() - total) / 1_000_000
                    + " ms" + (slow.isEmpty() ? "" : "; slowest: " + String.join(", ", slow)));
        }
        if (failed != null) throw failed;
    }
}
