package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Mods' world generation: the chunks of a dimension whose generation mods change (biomes a
 * mod adds, as TerraBlender's regions put Biomes O' Plenty's into the overworld's biome
 * source; features a mod adds to biomes) are generated here, by vanilla's own chunk steps
 * (ChunkPyramid.GENERATION_PYRAMID) run on the mirror's level, and handed to Horizon as
 * vanilla saves them. Horizon keeps its own generator for vanilla dimensions.
 *
 * <p>Horizon drives it as it drives its own generator: {@link #proto} brings a chunk to
 * TERRAIN (structure starts and references, biomes, noise, surface, carvers), {@link
 * #decorate} runs FEATURES once its eight neighbours are protos too (features write into
 * them), {@link #finish} gives the chunk and lets it go, {@link #forget} drops one Horizon let
 * go unfinished. The chunks Horizon holds are "live"; the neighbours vanilla's steps read
 * beyond them (structure starts eight chunks out, biomes one chunk out) are made here and
 * kept in a small cache, as ChunkMap keeps its lower-status chunks.
 */
public final class HorizonWorldgen {
    private HorizonWorldgen() {}

    /** The steps of a proto chunk, in order; FEATURES is decoration's. */
    private static final ChunkStatus[] STEPS = {
            ChunkStatus.STRUCTURE_STARTS, ChunkStatus.STRUCTURE_REFERENCES, ChunkStatus.BIOMES, ChunkStatus.TERRAIN,
            ChunkStatus.FEATURES};
    /** Chunks Horizon doesn't hold kept for their neighbours' steps (ChunkMap's lower statuses). */
    private static final int PARTIAL_CHUNKS = 3072;

    private record Live(ProtoChunk chunk, long token) {}

    private static final class Dimension {
        final ServerLevel level;
        final ChunkGenerator generator;
        final WorldGenContext context;
        final PalettedContainerFactory containers;
        final Map<Long, Live> live = new ConcurrentHashMap<>();
        /** Horizon's noise fills the protos (horizonNoise). */
        boolean horizonNoise;
        /**
         * Lower-status chunks, oldest first out (a lock-free map: every chunk's structure
         * references read 289 of them, from every worker at once; a synchronized LRU map was
         * one lock for all).
         */
        final Map<Long, ProtoChunk> partial = new ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentLinkedQueue<Long> partialOrder = new java.util.concurrent.ConcurrentLinkedQueue<>();

        /** Keeps {@code chunk} as chunk {@code key}'s unless one is kept: the kept one. */
        ProtoChunk keep(long key, ProtoChunk chunk) {
            ProtoChunk raced = partial.putIfAbsent(key, chunk);
            if (raced != null) return raced;
            partialOrder.add(key);
            while (partial.size() > PARTIAL_CHUNKS) {
                Long old = partialOrder.poll();
                if (old == null) break;
                partial.remove(old);
            }
            return chunk;
        }

        Dimension(ServerLevel level, ChunkGenerator generator, WorldGenContext context, PalettedContainerFactory containers) {
            this.level = level;
            this.generator = generator;
            this.context = context;
            this.containers = containers;
        }
    }

    private static final Map<String, Dimension> DIMENSIONS = new ConcurrentHashMap<>();
    private static final AtomicLong TOKENS = new AtomicLong();
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static boolean serverReady;

    // ---- Start ----------------------------------------------------------------------------

    /**
     * Readies generation of {@code dimension} for Horizon's world (its seed, whether it has
     * structures): whether mods change that dimension's generation, so Horizon generates its
     * chunks here. Called once a dimension, before its first chunk. {@code horizonCannot}:
     * "structure type" of each structure the dimension's sets place that Horizon's generator
     * can't make (a mod's own structure type); those mods' structures need this side too.
     */
    public static synchronized boolean start(String dimension, long seed, boolean structures,
            String[] horizonCannot) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        try {
            HorizonServer.ensureServer();
            HorizonServer.worldSeed(seed);
            if (!serverReady) {
                serverReady = true;
                readyServer(seed, structures);
            } else if (structures != readyStructures) {
                // Readied before the first tick with structures assumed on (readyBeforeTicks).
                set(MinecraftServer.class, HorizonServer.server(), "worldGenSettings",
                        WorldGenSettings.of(new WorldOptions(seed, structures, false), HorizonServer.server().registryAccess()));
                readyStructures = structures;
            }
            ServerLevel level = HorizonServer.level(dimension);
            ChunkGenerator generator = HorizonServer.generator(level);
            if (generator == null) return false;
            levelHooks(level, dimension, seed);
            List<String> mods = modded(level, generator);
            for (String cannot : horizonCannot == null ? new String[0] : horizonCannot) {
                String[] parts = cannot.split(" ", 2);
                String structure = parts[0];
                String namespace = structure.contains(":") ? structure.substring(0, structure.indexOf(':')) : structure;
                Natives.log("Java mods: " + dimension + " has " + structure + " (" + (parts.length > 1 ? parts[1] : "?")
                        + "), a structure Horizon's generator can't make");
                if (!mods.contains(namespace)) mods.add(namespace);
            }
            if (mods.isEmpty()) {
                Natives.log("Java mods: " + dimension + " generates as vanilla's (Horizon's generator)");
                return false;
            }
            MinecraftServer server = HorizonServer.server();
            PalettedContainerFactory containers = PalettedContainerFactory.create(level.registryAccess());
            readyLevel(level, generator, server, containers, seed);
            WorldGenContext context = new WorldGenContext(level, generator, server.getStructureTemplateManager(),
                    null, Runnable::run, null);
            Dimension made = new Dimension(level, generator, context, containers);
            made.horizonNoise = horizonNoise(dimension, level, generator);
            DIMENSIONS.put(dimension, made);
            Natives.log("Java mods: " + dimension + "'s noise is "
                    + (made.horizonNoise ? "Horizon's" : "Java's") + (CHECK ? " (checked against vanilla's)" : ""));
            Natives.log("Java mods: " + dimension + " generates through the mods (" + String.join(", ", mods) + "), "
                    + generator.getBiomeSource().getClass().getSimpleName() + " with "
                    + generator.getBiomeSource().possibleBiomes().size() + " biomes");
            return true;
        } catch (Throwable t) {
            Natives.log("Java mods: " + dimension + " can't generate through the mods: " + t);
            log(t);
            return false;
        }
    }

    /**
     * What MinecraftServer's constructor gives world generation, on the mirrored server: the
     * world's settings (seed, structures), the structure templates, the data fixer; then the
     * mods' injections at the constructor's end, where world-generation mods set up
     * (TerraBlender puts its regions into the level stems' biome sources and its surface rules
     * into their noise settings; Fabric API applies biome modifications).
     */
    /**
     * The server's start hooks before its first tick when no dimension's generation readied them
     * yet: a world reopened in a level whose chunks around the player are saved (a mod's
     * dimension, the Nether) starts generating ticks later, and by then the tick's start had
     * marked the registries modified, so Fabric's biome modifications failed ("This dynamic
     * registries instance has already been modified") and weren't applied anywhere.
     */
    static synchronized void readyBeforeTicks(long seed) {
        if (serverReady || HorizonData.loaded == null) return;
        serverReady = true;
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        try {
            HorizonServer.worldSeed(seed);
            readyServer(seed, true);
        } catch (Throwable t) {
            Natives.log("Java mods: the server's start hooks failed: " + t);
            log(t);
        }
    }

    /** The structure setting the server was readied with. */
    private static boolean readyStructures;

    private static void readyServer(long seed, boolean structures) throws ReflectiveOperationException {
        readyStructures = structures;
        MinecraftServer server = HorizonServer.server();
        var registries = server.registryAccess();
        set(MinecraftServer.class, server, "worldGenSettings",
                WorldGenSettings.of(new WorldOptions(seed, structures, false), registries));
        var fixer = net.minecraft.util.datafix.DataFixers.getDataFixer();
        set(MinecraftServer.class, server, "fixerUpper", fixer);
        // Structure templates from the data packs (vanilla's and the mods'); Horizon keeps
        // the world's own structure files.
        StructureTemplateManager templates = (StructureTemplateManager) Natives.allocate(StructureTemplateManager.class);
        var source = new net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource(
                fixer, registries.lookupOrThrow(Registries.BLOCK), HorizonData.loaded.resources(),
                new net.minecraft.resources.FileToIdConverter("structure", ".nbt"));
        set(StructureTemplateManager.class, templates, "resourceManagerSource", source);
        set(StructureTemplateManager.class, templates, "sources", List.of(source));
        set(StructureTemplateManager.class, templates, "structureRepository", new ConcurrentHashMap<>());
        set(MinecraftServer.class, server, "structureTemplateManager", templates);
        // Handlers that take only the CallbackInfo, then those that take the constructor's
        // arguments (WorldWeaver's: its registry-ready and server-ready events).
        Object[] args = serverArguments(server, seed, structures);
        long hooks = System.nanoTime();
        for (String at : new String[] {"RETURN", "TAIL"}) {
            HorizonHooks.fire(server, MinecraftServer.class, "<init>", "()V", at, new Object[0], null, false);
            if (args != null) HorizonHooks.fire(server, MinecraftServer.class, "<init>", SERVER_CONSTRUCTOR, at, args, null, false);
        }
        // Fabric's biome modifications and TerraBlender's server start run here.
        Natives.log("Java mods: the server's start hooks (biome modifications) ran in "
                + (System.nanoTime() - hooks) / 1_000_000 + " ms");
    }

    private static final String SERVER_CONSTRUCTOR = "(Ljava/lang/Thread;"
            + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
            + "Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/WorldStem;"
            + "Ljava/util/Optional;Ljava/net/Proxy;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/server/Services;"
            + "Lnet/minecraft/server/level/progress/LevelLoadListener;ZLnet/minecraft/server/notifications/NotificationManager;)V";

    /**
     * MinecraftServer's constructor's arguments as a server would have them: the world's
     * storage (a folder of the bridge's own, beside Horizon's world, so nothing of Horizon's
     * is locked), the data packs, the world stem (registries, resources, world data and
     * settings), the data fixer and services. Null if they can't be made.
     */
    private static Object[] serverArguments(MinecraftServer server, long seed, boolean structures) {
        try {
            java.nio.file.Path base = HorizonServer.worldDir() != null
                    ? HorizonServer.worldDir().resolve("horizon-java").resolve("levels")
                    : java.nio.file.Files.createTempDirectory("horizon-java-levels");
            java.nio.file.Files.createDirectories(base);
            var storage = storage(base);
            HorizonData.Loaded data = HorizonData.loaded;
            var stem = new net.minecraft.server.WorldStem(data.resources(), data.reloadable(), data.registries(),
                    new net.minecraft.world.level.storage.LevelDataAndDimensions.WorldDataAndGenSettings(
                            data.worldData(), server.getWorldGenSettings()));
            return new Object[] {Thread.currentThread(), storage, HorizonData.repository, stem, java.util.Optional.empty(),
                    java.net.Proxy.NO_PROXY, server.getFixerUpper(), HorizonServer.services(), null, false, null};
        } catch (Throwable t) {
            Natives.log("Java mods: no server arguments for mods' set-up: " + t);
            return null;
        }
    }

    /** The bridge's own world storage for mods' set-up (one a process: it holds the folder's lock). */
    private static net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess storageAccess;

    private static synchronized net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess storage(
            java.nio.file.Path base) throws java.io.IOException {
        if (storageAccess == null) {
            storageAccess = net.minecraft.world.level.storage.LevelStorageSource.createDefault(base).createAccess("world");
        }
        return storageAccess;
    }

    /** That storage for a level's constructor hooks, made if the server's start hasn't: null if it can't be. */
    static net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess levelStorage() {
        try {
            java.nio.file.Path base = HorizonServer.worldDir() != null
                    ? HorizonServer.worldDir().resolve("horizon-java").resolve("levels")
                    : java.nio.file.Files.createTempDirectory("horizon-java-levels");
            java.nio.file.Files.createDirectories(base);
            return storage(base);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String LEVEL_CONSTRUCTOR = "(Lnet/minecraft/server/MinecraftServer;Ljava/util/concurrent/Executor;"
            + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
            + "Lnet/minecraft/world/level/storage/ServerLevelData;Lnet/minecraft/resources/ResourceKey;"
            + "Lnet/minecraft/world/level/dimension/LevelStem;ZJLjava/util/List;Z)V";

    /**
     * The mods' injections at the end of ServerLevel's constructor, which the mirror skips:
     * world-generation mods set up a level's biome source there (WorldWeaver gives its End
     * and Nether sources the seed and noise settings: Better End's biomes). Given the
     * constructor's arguments as a server would (no storage, an inline executor).
     */
    private static void levelHooks(ServerLevel level, String dimension, long seed) {
        var stem = HorizonData.stem(level.dimension().identifier());
        if (stem == null) return;
        // The world's storage as a server's (BCLib/BetterEnd read its level id there): the bridge's
        // own folder beside Horizon's world, not Horizon's files. (UMP, 2026-11-11)
        Object[] args = {HorizonServer.server(), (java.util.concurrent.Executor) Runnable::run, levelStorage(),
                HorizonData.loaded.worldData(), level.dimension(), stem, false,
                net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(seed), List.of(), true};
        for (String at : new String[] {"RETURN", "TAIL"}) {
            HorizonHooks.fire(level, ServerLevel.class, "<init>", LEVEL_CONSTRUCTOR, at, args, null, true);
        }
    }

    /** What ServerLevel's constructor makes for generation, on a mirrored level. */
    private static void readyLevel(ServerLevel level, ChunkGenerator generator, MinecraftServer server,
                                   PalettedContainerFactory containers, long seed) throws ReflectiveOperationException {
        var random = level.getChunkSource().randomState();
        if (level.palettedContainerFactory() == null) {
            set(net.minecraft.world.level.Level.class, level, "palettedContainerFactory", containers);
        }
        // Nothing of Horizon's chunks is scanned for structures (locating is Horizon's).
        net.minecraft.world.level.chunk.storage.ChunkScanAccess scanner =
                (pos, visitor) -> CompletableFuture.completedFuture(null);
        StructureCheck check = new StructureCheck(scanner, level.registryAccess(), server.getStructureTemplateManager(),
                level.dimension(), generator, random, level, generator.getBiomeSource(), seed, server.getFixerUpper());
        set(ServerLevel.class, level, "structureCheck", check);
        set(ServerLevel.class, level, "structureManager",
                new StructureManager(level, server.getWorldGenSettings().options(), check));
        set(ServerLevel.class, level, "uncachedBiomeResolver", generator.getBiomeSource().createUncachedResolver(random));
    }

    /** The mods that change a dimension's generation: their biomes in its source, their features in its biomes. */
    private static List<String> modded(ServerLevel level, ChunkGenerator generator) {
        Set<String> mods = new java.util.TreeSet<>();
        // A level beyond vanilla's three (a mod's dimension: The Sift's, a mining dimension on
        // vanilla's flat generator) is the mods' however it's made: Horizon has no stem of it.
        var dimensionId = level.dimension().identifier();
        if (!dimensionId.getNamespace().equals("minecraft") || !java.util.List.of("overworld", "the_nether", "the_end")
                .contains(dimensionId.getPath())) {
            mods.add(dimensionId.getNamespace());
        }
        var features = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        for (Holder<Biome> biome : generator.getBiomeSource().possibleBiomes()) {
            biome.unwrapKey().ifPresent(k -> {
                if (!k.identifier().getNamespace().equals("minecraft")) mods.add(k.identifier().getNamespace());
            });
            for (var step : generator.getBiomeGenerationSettings(biome).features()) {
                for (var feature : step) {
                    feature.unwrapKey().or(() -> features.getResourceKey(feature.value())).ifPresent(k -> {
                        if (!k.identifier().getNamespace().equals("minecraft")) mods.add(k.identifier().getNamespace());
                    });
                }
            }
        }
        // A generator of a mod's own (One Block's), or vanilla's on a mod's noise settings.
        String generatorClass = generator.getClass().getName();
        if (!generatorClass.startsWith("net.minecraft.")) mods.add(owner(generator.getClass()));
        if (!generator.getBiomeSource().getClass().getName().startsWith("net.minecraft.")) {
            mods.add(owner(generator.getBiomeSource().getClass()));
        }
        if (generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise) {
            noise.generatorSettings().unwrapKey().ifPresent(k -> {
                if (!k.identifier().getNamespace().equals("minecraft")) mods.add(k.identifier().getNamespace());
            });
        }
        // Data packs that change vanilla's own generation files (a skyblock's void noise
        // settings, a one-block's dimension, biomes with other features): the pack's id.
        var resources = HorizonData.loaded == null ? null : HorizonData.loaded.resources();
        if (resources != null) {
            String dimension = level.dimension().identifier().getPath();
            for (String folder : new String[] {"worldgen/noise_settings", "worldgen/density_function", "worldgen/noise",
                    "worldgen/biome", "worldgen/placed_feature", "worldgen/feature", "dimension"}) {
                for (var entry : resources.listResourceStacks(folder, id -> id.getNamespace().equals("minecraft")).entrySet()) {
                    if (folder.equals("dimension") && !entry.getKey().getPath().equals("dimension/" + dimension + ".json")) continue;
                    for (var resource : entry.getValue()) {
                        if (!"vanilla".equals(resource.sourcePackId())) mods.add(resource.sourcePackId());
                    }
                }
            }
        }
        return new ArrayList<>(mods);
    }

    /** The mod a class comes from (its jar's mod), or the class's package. */
    private static String owner(Class<?> c) {
        try {
            var source = c.getProtectionDomain().getCodeSource();
            if (source != null) {
                for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                    for (var root : mod.getOrigin().getPaths()) {
                        if (source.getLocation().toString().contains(root.getFileName().toString())) return mod.getMetadata().getId();
                    }
                }
            }
        } catch (RuntimeException e) {
            // Its package, then.
        }
        return c.getPackageName();
    }

    // ---- Horizon's calls ------------------------------------------------------------------

    // ---- Horizon's noise -------------------------------------------------------------------

    /** Horizon's noise fill of a chunk (NoiseBasedChunkGenerator.doFill's result): Fills.take. */
    private record Fill(String[] palette, int[] blocks, int[] postprocess) {}

    /** Fills Horizon handed over, by the proto chunk they are for (taken by doFill). */
    private static final Map<ProtoChunk, Fill> FILLS = new ConcurrentHashMap<>();
    /** HORIZON_JAVA_NOISE_CHECK=1: vanilla's doFill runs too, and differences are logged. */
    private static final boolean CHECK = "1".equals(System.getenv("HORIZON_JAVA_NOISE_CHECK"));
    private static final java.util.concurrent.atomic.AtomicLong CHECKED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DIFFERENT = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Whether Horizon's own noise fills this dimension's chunks (HORIZON_JAVA_NOISE=horizon,
     * off by default: see below), when it can: its generator is vanilla's
     * noise for the dimension (the level stem's noise settings are vanilla's key, and no data
     * pack but vanilla's has noise settings, density functions or noises), and no mod's
     * structure adapts the terrain (Horizon's beardifier knows vanilla's structures). Mods
     * that add biomes and features (TerraBlender, BOP) leave the noise alone: the noise router
     * doesn't read biomes.
     */
    private static boolean horizonNoise(String dimension, ServerLevel level, ChunkGenerator generator) {
        // Opt-in (HORIZON_JAVA_NOISE=horizon): measured 2026-10-31 on 2 cores, Horizon's noise
        // (12-13 ms a chunk, plus 3 ms to copy and 1 to apply) costs a little more than
        // vanilla's doFill (11 ms: HotSpot's batched sampleVolume), and chunks came ~5% slower.
        if (!"horizon".equals(System.getenv("HORIZON_JAVA_NOISE"))) return false;
        if (!(generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise)
                || generator.getClass() != net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.class) return false;
        var vanilla = switch (dimension) {
            case "minecraft:overworld" -> net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD;
            case "minecraft:the_nether" -> net.minecraft.world.level.levelgen.NoiseGeneratorSettings.NETHER;
            case "minecraft:the_end" -> net.minecraft.world.level.levelgen.NoiseGeneratorSettings.END;
            default -> null;
        };
        if (vanilla == null || !noise.generatorSettings().is(vanilla)) return false;
        var resources = HorizonData.loaded.resources();
        for (String folder : new String[] {"worldgen/noise_settings", "worldgen/density_function", "worldgen/noise"}) {
            for (var stack : resources.listResourceStacks(folder, id -> true).values()) {
                for (var resource : stack) {
                    if (!"vanilla".equals(resource.sourcePackId())) {
                        Natives.log("Java mods: " + dimension + "'s noise comes from " + resource.sourcePackId()
                                + ": Java fills it");
                        return false;
                    }
                }
            }
        }
        var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (var entry : structures.listElements().toList()) {
            if (!entry.key().identifier().getNamespace().equals("minecraft")
                    && entry.value().terrainAdaptation() != net.minecraft.world.level.levelgen.structure.TerrainAdjustment.NONE) {
                Natives.log("Java mods: " + entry.key().identifier() + " adapts the terrain: Java fills " + dimension);
                return false;
            }
        }
        return true;
    }

    /** Whether Horizon's noise fills this dimension's protos (worldgenProto's fill). */
    public static boolean noiseFromHorizon(String dimension) {
        Dimension d = DIMENSIONS.get(dimension);
        return d != null && d.horizonNoise;
    }

    /**
     * NoiseBasedChunkGenerator.doFill for {@code chunk} with Horizon's fill, if it gave one:
     * its blocks, the world-generation heightmaps doFill keeps, and the fluids the aquifer
     * marked. True when filled (doFill is skipped).
     */
    public static boolean fill(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        if (!(chunk instanceof ProtoChunk proto)) return false;
        // A structure here that adapts the terrain (a village's beard) is the mods' worldgen's
        // own (its biomes chose it): Horizon's noise has none, so vanilla's fills this chunk.
        if (adapted(proto)) {
            FILLS.remove(proto);
            return false;
        }
        Fill fill = CHECK ? FILLS.get(proto) : FILLS.remove(proto);
        if (fill == null || CHECK) return false;
        apply(proto, fill);
        return true;
    }

    /** Whether a structure that adapts the terrain reaches the chunk (Beardifier.forStructuresInChunk's). */
    private static boolean adapted(ProtoChunk chunk) {
        for (var structure : chunk.getAllReferences().keySet()) {
            if (structure.terrainAdaptation() != net.minecraft.world.level.levelgen.structure.TerrainAdjustment.NONE
                    && !chunk.getAllReferences().get(structure).isEmpty()) {
                return true;
            }
        }
        return false;
    }


    /** HORIZON_JAVA_NOISE_CHECK: after vanilla's doFill, how Horizon's fill compares. */
    public static void check(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        if (!CHECK || !(chunk instanceof ProtoChunk proto)) return;
        Fill fill = FILLS.remove(proto);
        if (fill == null) return;
        BlockState[] states = states(fill.palette());
        int minY = chunk.getMinY();
        long different = 0;
        String first = null;
        for (int i = 0; i < fill.blocks().length; i++) {
            int x = i & 15, z = (i >> 4) & 15, y = minY + (i >> 8);
            BlockState mine = chunk.getSection(chunk.getSectionIndex(y)).getBlockState(x, y & 15, z);
            if (mine != states[fill.blocks()[i]]) {
                if (first == null) first = (chunk.getPos().getMinBlockX() + x) + " " + y + " " + (chunk.getPos().getMinBlockZ() + z)
                        + ": vanilla " + mine + ", Horizon " + states[fill.blocks()[i]];
                different++;
            }
        }
        long n = CHECKED.incrementAndGet();
        if (different > 0) DIFFERENT.incrementAndGet();
        if (different > 0 || n % 50 == 0) {
            Natives.log("Java mods' worldgen: noise check: " + n + " chunks, " + DIFFERENT.get() + " differ"
                    + (different > 0 ? "; " + chunk.getPos() + " has " + different + " (first at " + first + ")" : ""));
        }
    }

    private static BlockState[] states(String[] palette) {
        BlockState[] states = new BlockState[palette.length];
        for (int i = 0; i < palette.length; i++) {
            BlockState state = HorizonServer.parseState(palette[i]);
            states[i] = state != null ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        return states;
    }

    private static void apply(ProtoChunk chunk, Fill fill) {
        BlockState[] states = states(fill.palette());
        int minY = chunk.getMinY();
        int[] blocks = fill.blocks();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < blocks.length; i++) {
            BlockState state = states[blocks[i]];
            if (state.isAir()) continue;
            int y = minY + (i >> 8);
            sections[chunk.getSectionIndex(y)].setBlockState(i & 15, y & 15, (i >> 4) & 15, state, false);
        }
        net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(
                net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG));
        int[] post = fill.postprocess();
        for (int i = 0; i + 2 < post.length; i += 3) chunk.markPosForPostProcessing(new BlockPos(post[i], post[i + 1], post[i + 2]));
    }

    /**
     * Where a dimension the mods generate starts (MinecraftServer.setInitialSpawn's
     * ChunkGenerator.getOrigin and getSpawnHeight), and whether its generator is a mod's own
     * rather than vanilla's noise generator (a void, a grid): {x, z,
     * height, own}. Null if the mods don't generate it.
     */
    public static int[] origin(String dimension) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return null;
        try {
            ChunkPos origin = d.generator.getOrigin(d.level.getChunkSource().randomState());
            int height = d.generator.getSpawnHeight(d.level);
            boolean own = d.generator.getClass() != net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.class;
            boolean noise = d.generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
            return new int[] {origin.x(), origin.z(), height, own ? 1 : 0, noise ? 1 : 0};
        } catch (Throwable t) {
            failed("origin", t);
            return null;
        }
    }

    /** Brings chunk x z of dimension to TERRAIN and holds it for Horizon: its token (0: failed). */
    public static long proto(String dimension, int x, int z) {
        return proto(dimension, x, z, null, null, null);
    }

    /**
     * {@link #proto(String, int, int)} with Horizon's noise fill of the chunk (palette of
     * states, an index a block from the chunk's bottom, fluids to update as x y z triples):
     * TERRAIN's doFill takes it in place of sampling the noise.
     */
    public static long proto(String dimension, int x, int z, String[] palette, int[] blocks, int[] postprocess) {
        // Horizon's generation threads, attached to the JVM, have no context class loader.
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        HorizonDataFixer.check();
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return 0;
        long key = ChunkPos.pack(x, z);
        try {
            ProtoChunk chunk = d.partial.remove(key);
            Live old = d.live.get(key);
            if (old != null) chunk = old.chunk();
            if (chunk == null) chunk = fresh(d, x, z);
            long token = TOKENS.incrementAndGet();
            d.live.put(key, new Live(chunk, token));
            if (palette != null && blocks != null && chunk.getPersistedStatus().isBefore(ChunkStatus.TERRAIN)
                    && blocks.length == chunk.getHeight() * 256) {
                FILLS.put(chunk, new Fill(palette, blocks, postprocess == null ? new int[0] : postprocess));
            }
            try {
                advance(d, chunk, ChunkStatus.TERRAIN);
            } finally {
                FILLS.remove(chunk);
            }
            return token;
        } catch (Throwable t) {
            failed("proto", t);
            d.live.remove(key);
            return 0;
        }
    }

    /** Runs FEATURES on live chunk x z (its eight neighbours are live too). */
    public static void decorate(String dimension, int x, int z) {
        // Horizon's generation threads, attached to the JVM, have no context class loader.
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        HorizonDataFixer.check();
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return;
        Live live = d.live.get(ChunkPos.pack(x, z));
        if (live == null) return;
        Dimension outer = LOCATING.get();
        LOCATING.set(d);
        try {
            advance(d, live.chunk(), ChunkStatus.FEATURES);
        } catch (Throwable t) {
            failed("features", t);
        } finally {
            if (outer == null) LOCATING.remove(); else LOCATING.set(outer);
        }
    }

    /** Live chunk x z as vanilla saves a chunk (NBT, full status), let go; null if it has none. */
    public static byte[] finish(String dimension, int x, int z, long token) {
        // Horizon's generation threads, attached to the JVM, have no context class loader.
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return null;
        long key = ChunkPos.pack(x, z);
        Live live = d.live.get(key);
        if (live == null || live.token() != token) return null;
        d.live.remove(key, live);
        try {
            CompoundTag tag = save(d, live.chunk());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            NbtIo.write(tag, new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (Throwable t) {
            failed("finish", t);
            return null;
        }
    }

    /**
     * Live chunk x z as {@link #finish} gives it, kept live (Horizon saves a proto it drops
     * unfinished: its neighbours' features wrote into it), with {@code horizon:decorated}
     * when its own features ran. Null if it isn't held.
     */
    public static byte[] snapshot(String dimension, int x, int z, long token) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return null;
        Live live = d.live.get(ChunkPos.pack(x, z));
        if (live == null || live.token() != token) return null;
        try {
            CompoundTag tag;
            synchronized (live.chunk()) {
                tag = save(d, live.chunk());
            }
            tag.putBoolean("horizon:decorated", live.chunk().getPersistedStatus().isOrAfter(ChunkStatus.FEATURES));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            NbtIo.write(tag, new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (Throwable t) {
            failed("snapshot", t);
            return null;
        }
    }

    /**
     * Puts a saved proto's state back onto live chunk x z, made again to TERRAIN: its blocks
     * (palette and an index a block from the chunk's bottom, as worldgenProtoFilled's), the
     * block entities and entities placed in it (SNBT), and FEATURES if it was decorated, so
     * what its neighbours' features wrote survives its unloading. Blocks count from fromY.
     */
    public static void restore(String dimension, int x, int z, long token, String[] palette, int fromY, int[] blocks,
                               String[] blockEntities, String[] entities, boolean decorated) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return;
        Live live = d.live.get(ChunkPos.pack(x, z));
        if (live == null || live.token() != token) return;
        ProtoChunk chunk = live.chunk();
        try {
            synchronized (chunk) {
                BlockState[] states = states(palette);
                int minY = chunk.getMinY();
                LevelChunkSection[] sections = chunk.getSections();
                {
                    for (int i = 0; i < blocks.length; i++) {
                        int y = fromY + (i >> 8);
                        if (y < minY || y >= minY + chunk.getHeight()) continue;
                        LevelChunkSection section = sections[chunk.getSectionIndex(y)];
                        BlockState state = states[blocks[i]];
                        if (section.getBlockState(i & 15, y & 15, (i >> 4) & 15) != state) {
                            section.setBlockState(i & 15, y & 15, (i >> 4) & 15, state, false);
                        }
                    }
                }
                for (BlockPos at : new ArrayList<>(chunk.getBlockEntitiesPos())) chunk.removeBlockEntity(at);
                for (String snbt : blockEntities) {
                    chunk.setBlockEntityNbt(net.minecraft.nbt.TagParser.parseCompoundFully(snbt));
                }
                chunk.getEntities().clear();
                for (String snbt : entities) chunk.addEntity(net.minecraft.nbt.TagParser.parseCompoundFully(snbt));
                var kinds = chunk.getPersistedStatus().heightmapsAfter();
                net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk, kinds);
                if (decorated && chunk.getPersistedStatus().isBefore(ChunkStatus.FEATURES)) {
                    chunk.setPersistedStatus(ChunkStatus.FEATURES);
                }
            }
        } catch (Throwable t) {
            failed("restore", t);
        }
    }

    /** Horizon let live chunk x z go unfinished. */
    public static void forget(String dimension, int x, int z, long token) {
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return;
        long key = ChunkPos.pack(x, z);
        Live live = d.live.get(key);
        if (live != null && live.token() == token) d.live.remove(key, live);
    }

    // ---- Biomes where nothing is generated yet ------------------------------------------

    // ---- Far terrain -------------------------------------------------------------------------

    /**
     * The top blocks the mods' surface rules give far columns (Horizon's far terrain, whose
     * land is Horizon's noise, the same as the mods' generator's when it is vanilla's):
     * the material rule at each x y z triple (the land's top block, water up to
     * {@code waterHeights} over it: HorizonSurface.tops), with the mods' noise biomes. A state per triple
     * (BlockStateParser's form) or null where the rules leave the block; null if the mods
     * don't generate the dimension with a noise generator.
     */
    public static String[] lodTops(String dimension, int[] xyz, int[] waterHeights) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null || !(d.generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise)) return null;
        try {
            var randomState = d.level.getChunkSource().randomState();
            var rule = noise.generatorSettings().value().materialRule().value();
            var generation = new net.minecraft.world.level.levelgen.WorldGenerationContext(d.generator, d.level);
            var samplers = randomState.samplersWithContext(
                    net.minecraft.world.level.levelgen.densityfunction.SamplerContext.builder().enableCaches().build());
            var resolver = d.level.uncachedBiomeResolver();
            java.util.function.Function<BlockPos, Holder<Biome>> biomes =
                    pos -> resolver.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
            BlockState[] tops = HorizonSurface.tops(randomState.surfaceSystem(), rule, randomState, generation, biomes,
                    samplers, xyz, waterHeights);
            if (tops == null) return null;
            String[] out = new String[tops.length];
            for (int i = 0; i < tops.length; i++) {
                out[i] = tops[i] == null ? null : net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(tops[i]);
            }
            return out;
        } catch (Throwable t) {
            failed("far terrain's tops", t);
            return null;
        }
    }

    /**
     * The blocks the dimension's surface rules can give, and its default block (stone): what
     * a far column's land top is made of (above it are the features: trees, plants). Null if
     * the mods don't generate it with a noise generator.
     */
    public static String[] lodMaterials(String dimension) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null || !(d.generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise)) return null;
        var settings = noise.generatorSettings().value();
        Set<BlockState> states = HorizonSurface.materials(settings.materialRule().value());
        states.add(settings.defaultBlock());
        // Land only: a rule's water, lava or air isn't a column's top.
        states.removeIf(state -> state.isAir() || !state.getFluidState().isEmpty());
        return states.stream().map(net.minecraft.commands.arguments.blocks.BlockStateParser::serialize).toArray(String[]::new);
    }

    /**
     * Far terrain's noise for a dimension the mods generate: "vanilla" when its land is
     * vanilla's noise (Horizon's own stands for it), "" when the mods' noise was written into
     * {@code dir} as a data pack (HorizonNoiseExport), else why it couldn't be.
     */
    public static String lodNoise(String dimension, String dir) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return "the mods don't generate it";
        if (vanillaNoise(dimension, d.generator)) return "vanilla";
        String failed = HorizonNoiseExport.export(d.level.registryAccess(), d.generator, java.nio.file.Path.of(dir), d.level.getSeed(), d.level.getChunkSource().randomState());
        return failed == null ? "" : failed;
    }

    /**
     * Whether the dimension's land is vanilla's noise: a noise generator with the dimension's
     * vanilla settings, and no mod's data pack with noise settings, density functions or noises
     * (Tectonic replaces the overworld's; any such pack counts).
     */
    private static boolean vanillaNoise(String dimension, ChunkGenerator generator) {
        if (!(generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise)) return false;
        var vanilla = switch (dimension) {
            case "minecraft:overworld" -> net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD;
            case "minecraft:the_nether" -> net.minecraft.world.level.levelgen.NoiseGeneratorSettings.NETHER;
            case "minecraft:the_end" -> net.minecraft.world.level.levelgen.NoiseGeneratorSettings.END;
            default -> null;
        };
        if (vanilla == null || !noise.generatorSettings().is(vanilla)) return false;
        var resources = HorizonData.loaded.resources();
        for (String folder : new String[] {"worldgen/noise_settings", "worldgen/density_function", "worldgen/noise"}) {
            for (var stack : resources.listResourceStacks(folder, id -> true).values()) {
                for (var resource : stack) {
                    if (!"vanilla".equals(resource.sourcePackId())) return false;
                }
            }
        }
        return true;
    }

    /**
     * Far columns of a dimension a mod's own generator makes (not a noise generator: One
     * Block's void): ChunkGenerator.getBaseColumn at each x z pair, as runs from the bottom,
     * "fromY toY state" (toY exclusive) separated by ';', "" for an empty column. Null if the
     * mods don't generate the dimension.
     */
    public static String[] lodColumns(String dimension, int[] xz) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return null;
        try {
            var randomState = d.level.getChunkSource().randomState();
            String[] out = new String[xz.length / 2];
            for (int i = 0; i < out.length; i++) {
                var column = d.generator.getBaseColumn(xz[2 * i], xz[2 * i + 1], d.level, randomState);
                StringBuilder runs = new StringBuilder();
                BlockState run = null;
                int from = 0;
                int minY = d.level.getMinY(), maxY = d.level.getMinY() + d.level.getHeight();
                for (int y = minY; y <= maxY; y++) {
                    BlockState state = y < maxY ? column.getBlock(y) : null;
                    if (state != null && state.isAir()) state = null;
                    if (state != run) {
                        if (run != null) {
                            if (runs.length() > 0) runs.append(';');
                            runs.append(from).append(' ').append(y).append(' ')
                                    .append(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(run));
                        }
                        run = state;
                        from = y;
                    }
                }
                out[i] = runs.toString();
            }
            return out;
        } catch (Throwable t) {
            failed("far terrain's columns", t);
            return null;
        }
    }

    /**
     * The mods' biomes at quart positions (x y z triples) of a dimension they generate:
     * BiomeSource.getNoiseBiome, for Horizon's far terrain and maps. Null if they don't.
     */
    public static String[] biomes(String dimension, int[] quarts) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return null;
        var resolver = d.level.uncachedBiomeResolver();
        String[] out = new String[quarts.length / 3];
        for (int i = 0; i < out.length; i++) {
            Holder<Biome> biome = resolver.getNoiseBiome(quarts[3 * i], quarts[3 * i + 1], quarts[3 * i + 2]);
            out[i] = biome.unwrapKey().map(k -> k.identifier().toString()).orElse(null);
        }
        return out;
    }

    /** The biomes a dimension the mods generate can have (BiomeSource.possibleBiomes); null if they don't. */
    public static String[] possibleBiomes(String dimension) {
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return null;
        return d.generator.getBiomeSource().possibleBiomes().stream()
                .flatMap(h -> h.unwrapKey().stream()).map(k -> k.identifier().toString()).sorted()
                .toArray(String[]::new);
    }

    /**
     * /locate biome in a dimension the mods generate: BiomeSource.findClosestBiome3d with
     * the mods' source, for any of {@code names}. {name, x, y, z} or an empty array (none);
     * null if the mods don't generate it.
     */
    public static String[] findBiome(String dimension, String[] names, int x, int y, int z, int radius,
                                     int horizontal, int vertical) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return null;
        Set<String> wanted = Set.of(names);
        var found = d.generator.getBiomeSource().findClosestBiome3d(new BlockPos(x, y, z), radius, horizontal, vertical,
                h -> h.unwrapKey().map(k -> wanted.contains(k.identifier().toString())).orElse(false),
                d.level.getChunkSource().randomState(), d.level);
        if (found == null) return new String[0];
        BlockPos at = found.getFirst();
        String name = found.getSecond().unwrapKey().map(k -> k.identifier().toString()).orElse("");
        return new String[] {name, Integer.toString(at.getX()), Integer.toString(at.getY()), Integer.toString(at.getZ())};
    }

    /**
     * /locate structure in a dimension the mods generate: ChunkGenerator.findNearestMapStructure
     * with the mods' generator (their structures, placed by their biomes) for {@code selector},
     * an id or a #tag. {name, x, y, z}, an empty array (none), or null if the mods don't
     * generate the dimension or have no such structure.
     */
    public static String[] locateStructure(String dimension, String selector, int x, int y, int z, int radius) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return null;
        var structures = d.level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        net.minecraft.core.HolderSet<net.minecraft.world.level.levelgen.structure.Structure> set;
        if (selector.startsWith("#")) {
            var tag = net.minecraft.tags.TagKey.create(Registries.STRUCTURE,
                    net.minecraft.resources.Identifier.parse(selector.substring(1)));
            var named = structures.get(tag);
            if (named.isEmpty()) return null;
            set = named.get();
        } else {
            var holder = structures.get(ResourceKey.create(Registries.STRUCTURE,
                    net.minecraft.resources.Identifier.parse(selector)));
            if (holder.isEmpty()) return null;
            set = net.minecraft.core.HolderSet.direct(holder.get());
        }
        LOCATING.set(d);
        try {
            var found = d.generator.findNearestMapStructure(d.level, set, new BlockPos(x, y, z), radius, false);
            if (found == null) return new String[0];
            BlockPos at = found.getFirst();
            String name = found.getSecond().unwrapKey().map(k -> k.identifier().toString()).orElse(selector);
            return new String[] {name, Integer.toString(at.getX()), Integer.toString(at.getY()), Integer.toString(at.getZ())};
        } catch (Throwable t) {
            failed("locate", t);
            return null;
        } finally {
            LOCATING.remove();
        }
    }

    /**
     * The dimension a locate or a generation step runs in on this thread: the level's chunks
     * come from the generation then (a feature asking the level's StructureManager whether
     * it stands in a village reads the level's chunks, not the region's).
     */
    private static final ThreadLocal<Dimension> LOCATING = new ThreadLocal<>();

    /**
     * A chunk a locate reads (StructureCheck wants a candidate's structure starts:
     * level.getChunk(x, z, STRUCTURE_STARTS)): the generation's own, at that status, while a
     * locate runs on this thread in {@code level}; null otherwise.
     */
    public static ChunkAccess locatingChunk(ServerLevel level, int x, int z, ChunkStatus status) {
        Dimension d = LOCATING.get();
        if (d == null || d.level != level) return null;
        // As far as the generation's lower statuses go: what a structure lookup reads.
        ChunkStatus at = status == null || status.isAfter(ChunkStatus.STRUCTURE_REFERENCES)
                ? ChunkStatus.STRUCTURE_REFERENCES : status;
        return neighbour(d, x, z, at);
    }

    // ---- Stand-ins --------------------------------------------------------------------------

    /** Stand-in chunks of levels the mods generate, by chunk, until Horizon loads them. */
    private static final Map<ServerLevel, Map<Long, net.minecraft.world.level.chunk.LevelChunk>> STAND_INS =
            new java.util.IdentityHashMap<>();
    /** Stand-ins kept a level (each a generated chunk); past it they start over. */
    private static final int STAND_INS_KEPT = 256;

    /**
     * Chunk x z of a level, which Horizon hasn't loaded, for a mod's {@code getChunk(x, z)}
     * that loads (vanilla's ServerChunkCache blocks, reads or generates it there and then): The
     * Sift's rift finds its way out in The Sift's level, nobody being there yet, and its way
     * back in the Overworld's, where the player left. Horizon's save of it when it has one
     * (HorizonChunkLoads.saved); else, in a level the mods generate, generated here as Horizon
     * would have them generate it (to FEATURES, kept in the cache Horizon's own generation of
     * that chunk takes it from: both are the same chunk). Horizon is asked to load it
     * (HorizonChunkLoads.future); block reads of the level fall back on the stand-in
     * (standInBlock) until it has, and writes wait for Horizon's, as any write into an unloaded
     * chunk. Null where neither can be had (a vanilla level's chunk never generated).
     */
    public static net.minecraft.world.level.chunk.LevelChunk standIn(ServerLevel level, int x, int z) {
        long key = ChunkPos.pack(x, z);
        synchronized (STAND_INS) {
            var kept = STAND_INS.get(level);
            if (kept != null && kept.containsKey(key)) return kept.get(key);
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        try {
            var chunk = HorizonChunkLoads.saved(level, x, z);
            if (chunk == null) chunk = generatedStandIn(level, x, z);
            if (chunk == null) return null;
            synchronized (STAND_INS) {
                var kept = STAND_INS.computeIfAbsent(level, l -> new java.util.HashMap<>());
                if (kept.size() >= STAND_INS_KEPT) kept.clear();
                kept.put(key, chunk);
            }
            HorizonChunkLoads.future(level, x, z, ChunkStatus.FULL, true);
            return chunk;
        } catch (Throwable t) {
            failed("stand-in chunk", t);
            return null;
        } finally {
            Thread.currentThread().setContextClassLoader(loader);
        }
    }

    /** Chunk x z of a level the mods generate, generated for a stand-in; null in another level. */
    private static net.minecraft.world.level.chunk.LevelChunk generatedStandIn(ServerLevel level, int x, int z) {
        String dimension = level.dimension().identifier().toString();
        Dimension d = DIMENSIONS.get(dimension);
        // A mod's level nobody has been in: its generation starts here, as Horizon's opening
        // of the level would start it (vanilla's levels start with the server).
        if (d == null && serverReady && !HorizonServer.vanillaLevel(level.dimension().identifier())
                && start(dimension, HorizonServer.seed(), readyStructures, null)) {
            d = DIMENSIONS.get(dimension);
        }
        if (d == null || d.level != level) return null;
        Dimension outer = LOCATING.get();
        LOCATING.set(d);
        try {
            Live live = d.live.get(ChunkPos.pack(x, z));
            // A chunk Horizon is generating right now stays at its step (its worker holds it).
            ProtoChunk proto = live != null ? live.chunk() : neighbour(d, x, z, ChunkStatus.FEATURES);
            return new net.minecraft.world.level.chunk.LevelChunk(level, proto, null);
        } finally {
            if (outer == null) LOCATING.remove(); else LOCATING.set(outer);
        }
    }

    /** The block at {@code pos} of a stand-in chunk (standIn), or null when there is none. */
    public static BlockState standInBlock(ServerLevel level, BlockPos pos) {
        if (STAND_INS.isEmpty()) return null;
        net.minecraft.world.level.chunk.LevelChunk chunk;
        synchronized (STAND_INS) {
            var kept = STAND_INS.get(level);
            chunk = kept == null ? null : kept.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        }
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    /** The stand-in of chunk x z, if one was made (heights, getChunk without loading). */
    public static net.minecraft.world.level.chunk.LevelChunk standInNow(ServerLevel level, int x, int z) {
        if (STAND_INS.isEmpty()) return null;
        synchronized (STAND_INS) {
            var kept = STAND_INS.get(level);
            return kept == null ? null : kept.get(ChunkPos.pack(x, z));
        }
    }

    // ---- Steps ------------------------------------------------------------------------------

    /**
     * Whether TERRAIN's and BIOMES' work runs on the Horizon worker that asked for the chunk
     * instead of hopping to vanilla's background executor (HORIZON_JAVA_TERRAIN=inline or
     * executor; by default inline from 4 cores). Horizon's workers are behind the server and
     * window threads and as many as the cores but one; the executor's threads are as many
     * again at normal priority, so on a desktop the hop doubled the threads generating and
     * took time from ticks and frames. On 2 cores Horizon has 1 worker and the executor is the
     * second core's work, so the hop stays.
     */
    private static final boolean INLINE = switch (String.valueOf(System.getenv("HORIZON_JAVA_TERRAIN"))) {
        case "inline" -> true;
        case "executor" -> false;
        default -> Runtime.getRuntime().availableProcessors() >= 4;
    };
    /** Set while a Horizon worker runs a step (advance). */
    private static final ThreadLocal<Boolean> ON_WORKER = ThreadLocal.withInitial(() -> false);

    /** The executor a generation step's async part runs on (the generator mixins' hop). */
    public static java.util.concurrent.Executor executor(net.minecraft.TracingExecutor executor, String name) {
        return INLINE && ON_WORKER.get() ? Runnable::run : executor.forName(name);
    }

    private static ProtoChunk fresh(Dimension d, int x, int z) {
        return new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, d.level, d.containers, null);
    }

    /**
     * Runs vanilla's steps on {@code chunk} up to {@code target}, one at a time under the
     * chunk's lock: a step reads its neighbours at lower statuses only, so two threads
     * advancing neighbours never wait on each other in a circle.
     */
    private static void advance(Dimension d, ProtoChunk chunk, ChunkStatus target) {
        // Mods' server configs before any feature is placed (Lost Cities reads its profile there):
        // Horizon generates the spawn's chunks before its server's first tick and SERVER_STARTING,
        // so the configs' defaults are loaded now, as a server start would. (UMP, 2026-11-11)
        if (target.isOrAfter(ChunkStatus.FEATURES)) HorizonServer.defaultServerConfigs();
        for (ChunkStatus status : STEPS) {
            if (status.isAfter(target)) break;
            if (chunk.getPersistedStatus().isOrAfter(status)) continue;
            // The neighbours this step reads are brought to their statuses first, holding no lock: a
            // thread never waits for a neighbour while holding a chunk (the per-chunk locks of before
            // deadlocked that way, ump/poc/hang/threads.txt), and generation runs on every worker at once
            // (one lock per dimension, UMP's first fix, made the mods' worldgen single-threaded: 90+ ms of
            // the server tick with 100 mods). Steps before FEATURES write only their own chunk: its lock.
            // FEATURES writes into its eight neighbours, so two of them at once would race: one lock per
            // dimension for it, taken after its neighbours are ready. (UMP, 2026-11-11)
            ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
            long started = HorizonProfile.start();
            var cache = around(d, chunk, step);
            Object lock = status == ChunkStatus.FEATURES ? d : chunk;
            synchronized (lock) {
                if (chunk.getPersistedStatus().isOrAfter(status)) continue;
                boolean outer = ON_WORKER.get();
                ON_WORKER.set(true);
                try {
                    step.apply(d.context, cache, chunk).join();
                } finally {
                    ON_WORKER.set(outer);
                }
                if (!chunk.getPersistedStatus().isOrAfter(status)) chunk.setPersistedStatus(status);
            }
            HorizonProfile.add("worldgen " + status.getName(), started);
        }
    }

    /** The chunks a step reads around {@code center}, each at the status the step needs at its distance. */
    private static StaticCache2D<GenerationChunkHolder> around(Dimension d, ProtoChunk center, ChunkStep step) {
        ChunkPos pos = center.getPos();
        var needs = step.directDependencies();
        int radius = Math.max(needs.size() - 1, 0);
        return StaticCache2D.create(pos.x(), pos.z(), radius, (x, z) -> {
            if (x == pos.x() && z == pos.z()) return new Held(center);
            int distance = Math.max(Math.abs(x - pos.x()), Math.abs(z - pos.z()));
            return new Held(neighbour(d, x, z, needs.get(distance)));
        });
    }

    /** Chunk x z at {@code status} at least: Horizon's if it holds it, else one of the cache's. */
    private static ProtoChunk neighbour(Dimension d, int x, int z, ChunkStatus status) {
        long key = ChunkPos.pack(x, z);
        Live live = d.live.get(key);
        ProtoChunk chunk = live != null ? live.chunk() : d.partial.get(key);
        if (chunk == null) chunk = d.keep(key, fresh(d, x, z));
        advance(d, chunk, status);
        return chunk;
    }

    /** A chunk holder that only has its chunk, at every status up to the chunk's. */
    private static final class Held extends GenerationChunkHolder {
        private static java.lang.reflect.Field futures;

        Held(ProtoChunk chunk) {
            super(chunk.getPos());
            try {
                if (futures == null) {
                    var f = GenerationChunkHolder.class.getDeclaredField("futures");
                    f.setAccessible(true);
                    futures = f;
                }
                @SuppressWarnings("unchecked")
                var array = (AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>>) futures.get(this);
                var done = CompletableFuture.completedFuture(ChunkResult.<ChunkAccess>of(chunk));
                ChunkStatus status = chunk.getPersistedStatus();
                for (ChunkStatus s : ChunkStatus.getStatusList()) {
                    if (s.isAfter(status)) break;
                    array.set(s.getIndex(), done);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        protected void addSaveDependency(CompletableFuture<?> sync) {}

        @Override
        public int getTicketLevel() {
            return 33;
        }

        @Override
        public int getQueueLevel() {
            return 33;
        }
    }

    // ---- The chunk as vanilla saves it ----------------------------------------------------

    /**
     * SerializableChunkData's form of a generated chunk (what Horizon reads of a saved world):
     * its sections' block states and biomes, block entities, entities (structures' mobs) and
     * structure references, as a full chunk. Light, heightmaps and ticks are Horizon's.
     */
    private static CompoundTag save(Dimension d, ProtoChunk chunk) {
        CompoundTag tag = new CompoundTag();
        ChunkPos pos = chunk.getPos();
        tag.putInt("xPos", pos.x());
        tag.putInt("yPos", chunk.getMinSectionY());
        tag.putInt("zPos", pos.z());
        tag.putString("Status", "minecraft:full");
        ListTag sections = new ListTag();
        LevelChunkSection[] all = chunk.getSections();
        for (int i = 0; i < all.length; i++) {
            CompoundTag section = new CompoundTag();
            section.store("block_states", d.containers.blockStatesContainerCodec(), all[i].getStates());
            section.store("biomes", d.containers.biomeContainerCodec(), all[i].getBiomes());
            section.putByte("Y", (byte) (chunk.getMinSectionY() + i));
            sections.add(section);
        }
        tag.put("sections", sections);
        ListTag blockEntities = new ListTag();
        for (BlockPos at : chunk.getBlockEntitiesPos()) {
            CompoundTag be = chunk.getBlockEntityNbtForSaving(at, d.level.registryAccess());
            if (be != null) blockEntities.add(be);
        }
        tag.put("block_entities", blockEntities);
        ListTag entities = new ListTag();
        entities.addAll(chunk.getEntities());
        tag.put("entities", entities);
        CompoundTag references = new CompoundTag();
        var structures = d.level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        chunk.getAllReferences().forEach((structure, starts) -> {
            var id = structures.getKey(structure);
            if (id != null && !starts.isEmpty()) references.put(id.toString(), new LongArrayTag(starts.toLongArray()));
        });
        CompoundTag structureData = new CompoundTag();
        structureData.put("References", references);
        tag.put("structures", structureData);
        // What the features scheduled (a lake's water, a spring's lava, leaves' shapes) and
        // what they and the aquifers left to update (PostProcessing), as SerializableChunkData
        // saves them: Horizon runs them once the chunk is live, so fluids flow.
        var ticks = chunk.getTicksForSerialization(0L);
        tag.store("block_ticks", net.minecraft.world.ticks.SavedTick.codec(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.byNameCodec()).listOf(), ticks.blocks());
        tag.store("fluid_ticks", net.minecraft.world.ticks.SavedTick.codec(
                net.minecraft.core.registries.BuiltInRegistries.FLUID.byNameCodec()).listOf(), ticks.fluids());
        ListTag post = new ListTag();
        for (var marks : chunk.getPostProcessing()) {
            ListTag section = new ListTag();
            if (marks != null) {
                for (int i = 0; i < marks.size(); i++) section.add(net.minecraft.nbt.ShortTag.valueOf(marks.getShort(i)));
            }
            post.add(section);
        }
        tag.put("PostProcessing", post);
        return tag;
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private static void set(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void failed(String what, Throwable t) {
        if (FAILURES.merge(what + t, 1, Integer::sum) <= 3) {
            Natives.log("Java mods' worldgen: " + what + " failed: " + t);
            log(t);
        }
    }

    private static void log(Throwable t) {
        // CompletionException and ReportedException only wrap: the root says what went wrong.
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        if (t instanceof net.minecraft.ReportedException reported) {
            for (String line : reported.getReport().getDetails().split("\n")) {
                if (line.contains("Feature") || line.contains("Location") || line.contains("Biome")) Natives.log("  " + line.trim());
            }
        }
        StackTraceElement[] frames = cause.getStackTrace();
        if (cause != t) Natives.log("  caused by " + cause);
        for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
    }
}
