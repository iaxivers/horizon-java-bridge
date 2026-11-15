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

    public static CompoundTag update(DataFixTypes type, DataFixer fixer, CompoundTag tag, int version) {
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        if (DIR == null || version >= current) return type.updateToCurrentVersion(fixer, tag, version);
        Path file = null;
        try {
            file = DIR.resolve(key(tag) + ".nbt");
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
