package dev.horizonlab.platform;

import com.mojang.datafixers.DataFixer;
import dev.horizonlab.bridge.Natives;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.datafix.DataFixTypes;

/**
 * Structure templates brought up to the current DataVersion, kept in {@code <horizon.work>/
 * template-cache/<game data version>/} by the SHA-256 of the original tag: a template a mod ships
 * from an older version is updated by DataFixerUpper once, not each time it is loaded. A current
 * template isn't touched. Anything that fails falls back to the fixer, as vanilla does.
 */
public final class HorizonTemplateCache {
    private HorizonTemplateCache() {}

    private static final Path DIR;
    private static volatile int hits, misses;
    /**
     * Every cached file's bytes, read on a thread of their own at the bridge's start
     * ({@link #preload}): read one at a time as the spawn's structures were placed, each read held
     * a chunk's generation lock while the disk answered (a slow drive: ~0.5 s each), and the other
     * generation threads queued behind it (UMP join samples: HorizonWorldgen.advance BLOCKED on a
     * chunk whose thread was in readCompressed). (UMP, 2026-11-14)
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, byte[]> BYTES = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Thread preloading;

    static {
        String work = System.getProperty("horizon.work");
        Path dir = null;
        if (work != null) {
            dir = Path.of(work, "template-cache", String.valueOf(SharedConstants.getCurrentVersion().dataVersion().version()));
            try {
                Files.createDirectories(dir);
            } catch (Exception e) {
                dir = null;
            }
        }
        DIR = dir;
    }

    /**
     * All updated templates in one file ({@code templates.pack}: name, length, bytes, repeated),
     * read whole at the start: thousands of small files took a minute to read from a slow drive
     * and each held a chunk's generation lock while it waited. Templates updated this run are
     * added to it at the next {@link #save}; the single files stay the fallback.
     * (UMP, 2026-11-14)
     */
    private static final String PACK = "templates.pack";
    private static final java.util.concurrent.ConcurrentHashMap<String, byte[]> NEW = new java.util.concurrent.ConcurrentHashMap<>();

    /** Reads the pack file into memory, on a background thread (the single files when there is none). */
    public static void preload() {
        if (DIR == null || preloading != null) return;
        Thread t = new Thread(() -> {
            long started = System.nanoTime();
            int count = 0;
            Path pack = DIR.resolve(PACK);
            if (Files.isRegularFile(pack)) {
                try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(pack), 1 << 20))) {
                    while (true) {
                        String name;
                        try {
                            name = in.readUTF();
                        } catch (java.io.EOFException end) {
                            break;
                        }
                        byte[] bytes = new byte[in.readInt()];
                        in.readFully(bytes);
                        BYTES.putIfAbsent(name, bytes);
                        count++;
                    }
                } catch (Exception e) {
                    // A broken pack: the single files are still there.
                }
            } else {
                // No pack yet: it is made from the single files once, here, off the game's way.
                try (var files = Files.list(DIR)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        String file = f.getFileName().toString();
                        if (!file.endsWith(".nbt")) continue;
                        try {
                            NEW.putIfAbsent(file.substring(0, file.length() - 4), Files.readAllBytes(f));
                        } catch (Exception e) {
                            // Skipped.
                        }
                    }
                } catch (Exception e) {
                    // No cache yet.
                }
                BYTES.putAll(NEW);
                count = NEW.size();
                save();
            }
            Natives.log("Java mods: " + count + " updated structure templates read ahead in "
                    + (System.nanoTime() - started) / 1_000_000 + " ms");
        }, "Horizon template cache");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        preloading = t;
        t.start();
        // This run's new templates join the pack as the game exits (it ends through System.exit).
        Runtime.getRuntime().addShutdownHook(new Thread(HorizonTemplateCache::save, "Horizon template cache save"));
    }

    /** Adds this run's newly updated templates to the pack file (atomically replaced). */
    public static synchronized void save() {
        if (DIR == null || NEW.isEmpty()) return;
        Path pack = DIR.resolve(PACK);
        Path part = DIR.resolve(PACK + ".part");
        try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(part), 1 << 20))) {
            java.util.Set<String> written = new java.util.HashSet<>();
            if (Files.isRegularFile(pack)) {
                try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(pack), 1 << 20))) {
                    while (true) {
                        String name;
                        try {
                            name = in.readUTF();
                        } catch (java.io.EOFException end) {
                            break;
                        }
                        byte[] bytes = new byte[in.readInt()];
                        in.readFully(bytes);
                        if (written.add(name)) {
                            out.writeUTF(name);
                            out.writeInt(bytes.length);
                            out.write(bytes);
                        }
                    }
                }
            }
            for (var e : NEW.entrySet()) {
                if (!written.add(e.getKey())) continue;
                out.writeUTF(e.getKey());
                out.writeInt(e.getValue().length);
                out.write(e.getValue());
            }
        } catch (Exception e) {
            try {
                Files.deleteIfExists(part);
            } catch (Exception ignored) {
                // Left.
            }
            return;
        }
        try {
            Files.move(part, pack, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            NEW.clear();
        } catch (Exception e) {
            // The single files remain.
        }
    }

    public static CompoundTag update(DataFixTypes type, DataFixer fixer, CompoundTag tag, int version) {
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        if (DIR == null || version >= current) return type.updateToCurrentVersion(fixer, tag, version);
        Path file = null;
        try {
            String key = key(tag);
            file = DIR.resolve(key + ".nbt");
            byte[] ahead = BYTES.remove(key);
            if (ahead != null) {
                CompoundTag cached = NbtIo.readCompressed(new java.io.ByteArrayInputStream(ahead), NbtAccounter.unlimitedHeap());
                if (++hits == 1) Natives.log("Java mods: structure templates updated before are read from " + DIR);
                return cached;
            }
            if (Files.isRegularFile(file)) {
                CompoundTag cached = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                if (++hits == 1) Natives.log("Java mods: structure templates updated before are read from " + DIR);
                return cached;
            }
        } catch (Throwable t) {
            // Unreadable: updated again below and written over.
        }
        CompoundTag updated = type.updateToCurrentVersion(fixer, tag, version);
        if (file != null) {
            try {
                Path part = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".part");
                NbtIo.writeCompressed(updated, part);
                Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                String name = file.getFileName().toString();
                NEW.put(name.substring(0, name.length() - 4), Files.readAllBytes(file));
                misses++;
            } catch (Throwable t) {
                // Kept only in memory this time.
            }
        }
        return updated;
    }

    /** The SHA-256 of the tag as written, in hex. */
    private static String key(CompoundTag tag) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.toString();
    }
}
