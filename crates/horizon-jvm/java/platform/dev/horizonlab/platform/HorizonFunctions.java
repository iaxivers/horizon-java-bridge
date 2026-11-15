package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.world.level.timers.TimerQueue;

/**
 * The mods' data-pack functions: their {@code minecraft:load} and {@code minecraft:tick} tags
 * and what they {@code /schedule}, run on the mirrored server as vanilla's runs them
 * (MinecraftServer.tickChildren's commandFunctions, ServerLevel.tickTime's scheduled ones).
 * Mods that are data packs in a jar (Skyblock Infinite: its starting island is placed by its
 * load function) do their work here; their commands act on the mirrored levels, whose block
 * writes reach Horizon's. The world's own data packs are Horizon's (game/functions.rs): the
 * Java side's repository holds only vanilla's and the mods'.
 */
public final class HorizonFunctions {
    private HorizonFunctions() {}

    /** HORIZON_JAVA_FUNCTIONS_TRACE=1: every command the mods' functions run, and their errors, logged. */
    public static final boolean TRACE = "1".equals(System.getenv("HORIZON_JAVA_FUNCTIONS_TRACE"));

    public static net.minecraft.commands.execution.TraceCallbacks tracer() {
        return new net.minecraft.commands.execution.TraceCallbacks() {
            @Override public void onCommand(int depth, String command) { Natives.log("function> " + " ".repeat(depth) + command); }
            @Override public void onReturn(int depth, String command, int result) { }
            @Override public void onError(String message) { Natives.log("function! " + message); }
            @Override public void onCall(int depth, net.minecraft.resources.Identifier id, int size) { Natives.log("function> " + " ".repeat(depth) + "call " + id); }
            @Override public void close() { }
        };
    }

    private static ServerFunctionManager manager;
    private static TimerQueue<MinecraftServer> scheduled;
    private static boolean failed;

    /** Whether the mods' functions run now (this tick's), so a forced chunk not yet loaded can stop them. */
    private static boolean running;
    /** A function forced a chunk Horizon hasn't loaded: the rest of this run is dropped and the run retried. */
    private static boolean abort;
    private static final java.util.Set<String> WAITING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private record Waiting(net.minecraft.server.level.ServerLevel level, int x, int z) {}
    /** {@link #WAITING}'s chunks, until Horizon has loaded them (a new player waits for them too). */
    private static final java.util.Map<String, Waiting> WAITING_CHUNKS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * ServerLevel.setChunkForced (/forceload add) on a mirrored level: vanilla loads the chunk
     * there and then, and data packs rely on it (Skyblock Infinite forceloads 0 0, then places
     * its island there in the same function). Horizon loads it over the next ticks; until it
     * has, the function run that asked stops and runs again (FunctionsMixin), once a tick.
     */
    public static void forced(net.minecraft.server.level.ServerLevel level, int x, int z) {
        if (running && !level.hasChunk(x, z)) {
            String key = level.dimension().identifier() + " " + x + " " + z;
            WAITING_CHUNKS.put(key, new Waiting(level, x, z));
            if (WAITING.add(key)) {
                Natives.log("Java mods: a function forced chunk " + x + " " + z + " of " + level.dimension().identifier()
                        + "; it runs again once Horizon has loaded it");
            }
            if (TRACE) Natives.log("function! waiting for chunk " + x + " " + z);
            abort = true;
        }
    }

    /** Whether the running commands must stop (FunctionsMixin drops the queue). */
    public static boolean aborting() {
        return running && abort;
    }

    /** Whether the run that just ended was stopped, and must run again; clears it. */
    public static boolean takeAborted() {
        boolean was = abort;
        abort = false;
        return was;
    }

    /**
     * The game time by which what the load functions scheduled for the first moments has run
     * (Skyblock Infinite places its island 2 s after load, for players to have joined): the
     * latest of the events queued when they finished that fall within {@link #SETTLE_TICKS};
     * -1 until they finished. A player joins after it.
     */
    private static volatile long settleAt = -1;
    private static volatile long gameTime;
    /**
     * How long after load scheduled events still hold the first player (3 s). Skyblock Infinite places its
     * island 2 s after load: covered. Repeating clocks (Incendium's 7 s, other structure mods') are not
     * first-moment setup: waiting a whole period for them added 7 s to every join of a 100-mod pack
     * (UMP load test), and vanilla waits for no scheduled function at all.
     */
    private static final long SETTLE_TICKS = 60;

    /** Whether the load functions have run (or won't: no data, a failure), and what they scheduled for the first seconds. */
    static boolean ready() {
        if (failed || HorizonData.loaded == null) return true;
        if (manager == null) return false;
        try {
            java.lang.reflect.Field pending = ServerFunctionManager.class.getDeclaredField("postReload");
            pending.setAccessible(true);
            if ((boolean) pending.get(manager)) return false;
        } catch (ReflectiveOperationException e) {
            return true;
        }
        // A function waiting for a chunk it forced (Skyblock Infinite's island at 0 0) holds
        // the player too: the server can run the settle ticks faster than Horizon loads it.
        // And once it is loaded, until the run it stopped has gone through (Skyblock
        // Infinite's island landed ~1 s after the join: the chunk was there, the retried
        // function not yet run): no retry queued, and two ticks with no run stopped.
        return settleAt >= 0 && gameTime >= settleAt && WAITING_CHUNKS.isEmpty()
                && quietTicks >= 2 && !retryPending();
    }

    /** Ticks since a function run was last stopped for a chunk (or one was waited for). */
    private static volatile int quietTicks;

    /** Whether a scheduled function stopped for a chunk waits to run again (TimerCallbackMixin). */
    private static boolean retryPending() {
        if (scheduled == null) return false;
        try {
            for (String id : scheduled.getEventsIds()) if (id.startsWith("horizon-retry-")) return true;
        } catch (RuntimeException e) {
            // A queue being changed: ask again next time.
            return true;
        }
        return false;
    }

    /** {@link #settleAt}, once the load functions have finished. */
    private static void settle(long now) {
        long latest = now;
        try {
            java.lang.reflect.Field field = TimerQueue.class.getDeclaredField("queue");
            field.setAccessible(true);
            for (Object event : (java.util.Collection<?>) field.get(scheduled)) {
                long at = ((TimerQueue.Event<?>) event).triggerTime();
                if (at <= now + SETTLE_TICKS) latest = Math.max(latest, at);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Nothing to wait for.
        }
        settleAt = latest;
        if (latest > now) Natives.log("Java mods: a new player joins after what the load functions scheduled ("
                + (latest - now) + " ticks)");
    }

    /** One server tick of functions, once a world is open (its saved data holds the schedule). */
    static void tick(MinecraftServer server, long gameTime) {
        if (failed || HorizonData.loaded == null || !HorizonSavedData.ready()) return;
        running = true;
        try {
            if (manager == null) start(server);
            manager.tick();
            boolean stopped = abort;
            // The load functions, stopped for a chunk: they run again next tick.
            if (takeAborted()) {
                java.lang.reflect.Field again = ServerFunctionManager.class.getDeclaredField("postReload");
                again.setAccessible(true);
                again.set(manager, true);
            }
            int retriesBefore = WAITING.size();
            scheduled.tick(server, gameTime);
            stopped |= WAITING.size() > retriesBefore || retryPending();
            // The chunks waited for that Horizon has loaded now (the run that asked goes again).
            WAITING_CHUNKS.entrySet().removeIf(e -> {
                var w = e.getValue();
                if (!w.level().hasChunk(w.x(), w.z())) return false;
                WAITING.remove(e.getKey());
                Natives.log("Java mods: forced chunk " + e.getKey() + " is loaded");
                return true;
            });
            HorizonFunctions.gameTime = gameTime;
            quietTicks = stopped || !WAITING_CHUNKS.isEmpty() ? 0 : quietTicks + 1;
            if (settleAt < 0) {
                java.lang.reflect.Field done = ServerFunctionManager.class.getDeclaredField("postReload");
                done.setAccessible(true);
                if (!(boolean) done.get(manager)) settle(gameTime);
            }
        } catch (Throwable t) {
            failed = true;
            Natives.log("Java mods: the mods' functions stopped: " + t);
            StackTraceElement[] frames = t.getStackTrace();
            for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
        } finally {
            running = false;
            abort = false;
        }
    }

    private static void start(MinecraftServer server) throws ReflectiveOperationException {
        // The world's spawn, which commands run from the server start at (the server's
        // command source): the world data's.
        java.lang.reflect.Field respawn = MinecraftServer.class.getDeclaredField("effectiveRespawnData");
        respawn.setAccessible(true);
        if (respawn.get(server) == null) respawn.set(server, server.getWorldData().overworldData().getRespawnData());
        var library = HorizonData.loaded.reloadable().getFunctionLibrary();
        // Its constructor reads the load and tick tags; the load ones run on the first tick.
        manager = new ServerFunctionManager(server, library);
        java.lang.reflect.Field field = MinecraftServer.class.getDeclaredField("functionManager");
        field.setAccessible(true);
        field.set(server, manager);
        scheduled = server.getDataStorage().computeIfAbsent(TimerQueue.TYPE);
        java.lang.reflect.Field events = MinecraftServer.class.getDeclaredField("scheduledEvents");
        events.setAccessible(true);
        events.set(server, scheduled);
        int names = 0;
        for (var ignored : manager.getFunctionNames()) names++;
        Natives.log("Java mods: " + names + " data-pack functions; " + manager.getTag(
                net.minecraft.resources.Identifier.withDefaultNamespace("load")).size() + " run at load, "
                + manager.getTag(net.minecraft.resources.Identifier.withDefaultNamespace("tick")).size() + " each tick");
    }
}
