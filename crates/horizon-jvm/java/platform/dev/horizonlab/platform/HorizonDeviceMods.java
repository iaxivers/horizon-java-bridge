package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/**
 * Mods whose client half uses the player's own sound devices: it opens a microphone or a
 * speaker of its own with OpenAL (Simple Voice Chat) or Java's sound API. Their client half must
 * run on the machine of the player it is for: a LAN host's or a dedicated server's headless
 * client runs every player's client, and would open the host's microphone for each guest (and
 * such mods keep one client per process: a second player's join replaced the first's).
 *
 * <p>So, on a server, these mods' client code runs only for the player whose game this process
 * is (the window's own player: single player, a LAN host); for everyone else it is left out
 * (their client events, ticks, key handlers, HUD), and their messages on these mods' channels go
 * to and from that player's own game, which runs the mods' client half itself
 * (HorizonDeviceClient: a game connected to another's server). Found by what the mods' classes
 * call, not by name: any mod that takes the microphone or opens a speaker device.
 */
public final class HorizonDeviceMods {
    private HorizonDeviceMods() {}

    /** What a class calls to take a device (in its constant pool). */
    private static final String[] DEVICE_CALLS = {
        "alcCaptureOpenDevice", "alcOpenDevice", "javax/sound/sampled/TargetDataLine",
    };

    /**
     * HORIZON_JAVA_OWN_CLIENT=1: every mod's client half runs in each player's own game, as in
     * vanilla (one client a process), not only the device mods'. A server's headless client then
     * runs none of a guest's client (its ticks, HUD, key handlers), and a game connected to
     * another's server runs all of its mods' client code for its player.
     */
    static final boolean OWN_CLIENT = "1".equals(System.getenv("HORIZON_JAVA_OWN_CLIENT"));

    private static volatile Set<String> mods;
    private static Thread scan;

    /** The player whose game this process is (Rust: the single-player world's owner), or null. */
    static volatile UUID own;

    /**
     * On a server: players whose own game runs some mods' client half (it said so as it joined,
     * on RUNS_OWN_CHANNEL), and which mods ("*": every mod). Their client code is left out here
     * for that player, and their channels' messages go to that player's game.
     */
    static final java.util.Map<UUID, Set<String>> RUN_THEIR_OWN = new ConcurrentHashMap<>();

    /**
     * In a game whose own client runs only the mods its server doesn't (HorizonDeviceClient's
     * own client for some mods): those mods and what they depend on; null: every mod.
     */
    static volatile Set<String> ownRuns;

    /** The loader, the game, Fabric API and Horizon: there on both sides, never "missing". */
    static boolean platform(String id) {
        return id.equals("minecraft") || id.equals("java") || id.equals("horizon") || id.equals("fabricloader")
                || id.equals("fabric") || id.startsWith("fabric-") || id.equals("fabric-api") || id.equals("mixinextras");
    }

    /** Every mod loaded here, comma separated: what a server tells the games joining it it runs. */
    public static String ids() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (var mod : FabricLoader.getInstance().getAllMods()) ids.add(mod.getMetadata().getId());
        return String.join(",", ids);
    }

    /** This game's mods (not the platform's) that a server running {@code server} doesn't run. */
    static Set<String> missing(Set<String> server) {
        Set<String> out = new TreeSet<>();
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (!platform(id) && !server.contains(id)) out.add(id);
        }
        return out;
    }

    /** {@code mods} and every mod they depend on (their libraries' client code runs with them). */
    static Set<String> withDependencies(Set<String> mods) {
        Set<String> out = new TreeSet<>(mods);
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(mods);
        while (!todo.isEmpty()) {
            var mod = FabricLoader.getInstance().getModContainer(todo.poll());
            if (mod.isEmpty()) continue;
            for (var dep : mod.get().getMetadata().getDependencies()) {
                if (dep.getKind() != net.fabricmc.loader.api.metadata.ModDependency.Kind.DEPENDS
                        && dep.getKind() != net.fabricmc.loader.api.metadata.ModDependency.Kind.RECOMMENDS) continue;
                String id = dep.getModId();
                if (FabricLoader.getInstance().isModLoaded(id) && out.add(id)) todo.add(id);
            }
        }
        return out;
    }

    /** The scan starts beside the client's start (HorizonClient.finish). */
    static synchronized void startScan() {
        if (scan != null || mods != null) return;
        scan = new Thread(() -> mods = scanNow(), "Horizon device mods");
        scan.setDaemon(true);
        scan.start();
    }

    /** The device mods' ids (empty: none). Waits for the scan the first time. */
    public static Set<String> mods() {
        Set<String> found = mods;
        if (found != null) return found;
        Thread running;
        synchronized (HorizonDeviceMods.class) {
            running = scan;
        }
        if (running != null) {
            try {
                running.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (HorizonDeviceMods.class) {
            if (mods == null) mods = scanNow();
            return mods;
        }
    }

    /**
     * The scan's result kept on disk by the set of mods (their ids, versions and jar sizes and
     * times): reading every class of every mod (182 jars, ~20 s) on each start ran on the main
     * thread when the first mod handler asked before the background scan ended. (UMP, 2026-11-14)
     */
    private static Path cacheFile() {
        StringBuilder key = new StringBuilder("v1");
        var all = new java.util.ArrayList<>(FabricLoader.getInstance().getAllMods());
        all.sort(java.util.Comparator.comparing(m -> m.getMetadata().getId()));
        for (var mod : all) {
            key.append('|').append(mod.getMetadata().getId()).append(' ').append(mod.getMetadata().getVersion().getFriendlyString());
            for (Path p : mod.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH
                    ? mod.getOrigin().getPaths() : java.util.List.<Path>of()) {
                try {
                    key.append(' ').append(Files.size(p)).append(' ').append(Files.getLastModifiedTime(p).toMillis());
                } catch (java.io.IOException | UnsupportedOperationException e) {
                    // a folder or a nested jar: its id and version stand for it
                }
            }
        }
        String hash = Integer.toHexString(key.toString().hashCode()) + Integer.toHexString(key.length());
        return FabricLoader.getInstance().getGameDir().resolve(".horizon-cache").resolve("device-mods-" + hash + ".txt");
    }

    private static Set<String> scanNow() {
        Path cache = null;
        try {
            cache = cacheFile();
            if (Files.isRegularFile(cache)) {
                Set<String> read = new TreeSet<>();
                for (String line : Files.readAllLines(cache, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) read.add(line.trim());
                }
                return java.util.Collections.unmodifiableSet(read);
            }
        } catch (Throwable t) {
            cache = null;
        }
        Set<String> found = scanJars();
        if (cache != null) {
            try {
                Files.createDirectories(cache.getParent());
                try (var old = Files.newDirectoryStream(cache.getParent(), "device-mods-*.txt")) {
                    for (Path p : old) Files.deleteIfExists(p);
                }
                Files.write(cache, found, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                // only costs the next start the scan
            }
        }
        return found;
    }

    private static Set<String> scanJars() {
        Set<String> out = new TreeSet<>();
        byte[][] needles = new byte[DEVICE_CALLS.length][];
        for (int i = 0; i < needles.length; i++) needles[i] = DEVICE_CALLS[i].getBytes(StandardCharsets.UTF_8);
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (id.equals("minecraft") || id.equals("java") || id.equals("horizon") || id.equals("fabricloader")
                    || id.startsWith("fabric-") || id.equals("fabric-api") || id.equals("mixinextras")) {
                continue;
            }
            // Client code only: a mod with no client entrypoint or client mixins has no client half.
            try {
                for (Path root : mod.getRootPaths()) {
                    if (takesDevice(root, needles)) {
                        out.add(id);
                        break;
                    }
                }
            } catch (Throwable t) {
                Natives.log("Java mods: couldn't read " + id + "'s classes for device use: " + t);
            }
        }
        if (OWN_CLIENT) Natives.log("Java mods: every mod's client half runs in each player's own game");
        if (!out.isEmpty()) {
            Natives.log("Java mods: " + String.join(", ", out) + " use the player's own sound devices: their client half"
                    + " runs in each player's own game");
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    private static boolean takesDevice(Path root, byte[][] needles) throws java.io.IOException {
        try (var files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.toString();
                // Libraries the mod carries (shaded: concentus, lame...) count as the mod's.
                if (!name.endsWith(".class") || name.contains("META-INF")) continue;
                byte[] bytes = Files.readAllBytes(file);
                for (byte[] needle : needles) {
                    if (contains(bytes, needle)) return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0, end = hay.length - needle.length; i <= end; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** Whether a mod's id is a device mod's. */
    public static boolean device(String mod) {
        return mod != null && !mods().isEmpty() && mods().contains(mod);
    }

    /**
     * Whether {@code mod}'s client code runs now: in a device client (HorizonDeviceClient) only
     * the device mods' does; on a server, a device mod's runs only for this process's own player
     * (or when no player's client runs).
     */
    public static boolean runs(String mod) {
        // A server's headless client runs none of the client code its player's own game runs
        // (every mod's, or the mods the server lacked as it said); everything else runs as
        // before (this game's own player, players whose game runs none of it).
        var mc = net.minecraft.client.Minecraft.getInstance();
        var player = mc == null ? null : mc.player;
        Set<String> theirs = player == null ? null : RUN_THEIR_OWN.get(player.getUUID());
        if (theirs != null && (theirs.contains("*") || mod != null && theirs.contains(mod))) {
            if (DEBUG && SAID.add(mod + " " + player.getUUID())) {
                Natives.log("device mods: " + mod + " is left out for " + player.getUUID() + " (their own game runs it)");
            }
            return false;
        }
        // In a game connected to another's server, its own client for some mods: those only (the
        // game's and Fabric's own code always).
        if (HorizonDeviceClient.own()) {
            Set<String> only = ownRuns;
            return only == null || mod == null || only.contains(mod);
        }
        if (mods().isEmpty()) return true;
        boolean device = mod != null && mods().contains(mod);
        if (HorizonDeviceClient.on()) return device;
        if (!device) return true;
        boolean runs = player == null || player.getUUID().equals(own);
        if (DEBUG && SAID.add(mod + " " + (player == null ? null : player.getUUID()) + " " + runs)) {
            Natives.log("device mods: " + mod + (runs ? " runs" : " is left out") + " for " + (player == null ? "no player" : player.getUUID()));
        }
        return runs;
    }

    /** HORIZON_JAVA_DEBUG=device: each decision is logged once. */
    private static final boolean DEBUG = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("device");
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    /** {@link #runs} for code of a class (a listener, a lambda's class). */
    public static boolean runs(Class<?> owner) {
        if (quiet()) return true;
        String name = owner.getName();
        int lambda = name.indexOf("$$");
        String mod = HorizonFailures.modOf(lambda > 0 ? name.substring(0, lambda) : name);
        if (DEBUG && SAID.add(name)) Natives.log("device mods: " + name + " is " + mod + "'s");
        return runs(mod);
    }

    /** {@link #runs} for a mixin's handler merged into a game class. */
    public static boolean runs(Method handler) {
        if (quiet()) return true;
        MixinMerged merged = handler.getAnnotation(MixinMerged.class);
        if (merged == null) return runs(handler.getDeclaringClass());
        return runs(HorizonFailures.modOf(merged.mixin()));
    }

    /** Nothing to leave out anywhere: no device mods, no own client, nobody running their own. */
    private static boolean quiet() {
        return !OWN_CLIENT && RUN_THEIR_OWN.isEmpty() && !HorizonDeviceClient.own() && mods().isEmpty() && !HorizonDeviceClient.on();
    }

    /** A channel any mod's client half could use: all but vanilla's and Horizon's (Fabric API's too). */
    private static boolean modChannel(net.minecraft.resources.Identifier channel) {
        return !channel.getNamespace().equals("minecraft") && !channel.getNamespace().equals("horizon");
    }

    /**
     * Game side: whether a message on {@code channel} goes between this game's client half and
     * the server (a device mod's; with its own client, a mod's it runs: every mod's channel when
     * it runs every mod).
     */
    static boolean deviceChannel(net.minecraft.resources.Identifier channel) {
        if (OWN_CLIENT) return modChannel(channel);
        if (HorizonDeviceClient.own()) {
            Set<String> only = ownRuns;
            if (only == null) return modChannel(channel);
            if (only.contains(channel.getNamespace())) return true;
        }
        return device(channel.getNamespace());
    }

    /**
     * Server side: whether a message on {@code channel} is between the mods' server code and
     * {@code player}'s own game (a device mod's, or a mod's its game runs itself).
     */
    static boolean deviceChannel(net.minecraft.resources.Identifier channel, UUID player) {
        if (OWN_CLIENT) return modChannel(channel);
        Set<String> theirs = player == null ? null : RUN_THEIR_OWN.get(player);
        if (theirs != null) {
            if (theirs.contains("*")) return modChannel(channel);
            if (theirs.contains(channel.getNamespace())) return true;
        }
        return device(channel.getNamespace());
    }
}
