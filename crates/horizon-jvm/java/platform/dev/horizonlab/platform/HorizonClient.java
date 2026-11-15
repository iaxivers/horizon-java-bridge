package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.io.File;
import java.lang.reflect.Field;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Horizon's headless Minecraft client: no window, no GPU. Mods' client code (their client
 * entrypoints, screens) runs against it; what a screen draws is captured for Horizon to draw.
 * Made before the client entrypoints, with what they reach (options, key bindings...).
 */
public final class HorizonClient {
    private HorizonClient() {}

    private static Minecraft minecraft;

    /** Called by FabricBoot on the client side, before the client entrypoints (finish after). */
    public static void boot(String gameDir) throws ReflectiveOperationException {
        Thread.currentThread().setContextClassLoader(HorizonClient.class.getClassLoader());
        Minecraft mc = (Minecraft) Natives.allocate(Minecraft.class);
        set(Minecraft.class, null, "instance", mc);
        minecraft = mc;
        File dir = new File(gameDir);
        set(Minecraft.class, mc, "gameDirectory", dir);
        // Running (isRunning): mods' own threads loop while it is (Xaero's World Map's MapRunner).
        set(Minecraft.class, mc, "running", true);
        // What Minecraft's constructor sets before Fabric's client entrypoints run, which
        // mods' client initializers read. Its task queue (Minecraft.execute: a mod's client
        // receiver, or initializer, hands work to the client's thread), which the server
        // thread runs: tasks().
        set(net.minecraft.util.thread.BlockableEventLoop.class, mc, "pendingRunnables",
                new java.util.concurrent.ConcurrentLinkedQueue<Runnable>());
        // The signed-in user (JEI keys its config folders by the profile id): an offline one,
        // as the window's player is to the mirror.
        String name = "Player";
        set(Minecraft.class, mc, "user", new net.minecraft.client.User(name,
                net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name), "", java.util.Optional.empty(), java.util.Optional.empty()));
        // The resource pack list, made by Minecraft's constructor before the client entrypoints:
        // a mod's initializer adds its pack source to it (Simple Voice Chat's: it was null).
        set(Minecraft.class, mc, "resourcePackRepository", new net.minecraft.server.packs.repository.PackRepository());
        HorizonServer.client = true;
    }

    /**
     * After the client entrypoints, as Minecraft's constructor goes on after Fabric's hook at
     * its head: the options (with the key bindings mods registered).
     */
    /**
     * The game's start, around Fabric's entrypoints (Minecraft's constructor at its first
     * Thread.currentThread(): EntrypointPatch): the mods' injections there, before (FabricBoot,
     * just before the main entrypoints) or after (after the client ones).
     */
    public static void gameStart(boolean after) {
        HorizonHooks.scanNow();
        // The mixins' field initializers on the client, which its constructor would run first
        // (allocated without it): Spell Engine's key list was null in every key handling.
        // (UMP, 2026-11-11)
        if (!after) HorizonHooks.initFields(minecraft);
        HorizonHooks.fireClient(minecraft, after ? HorizonHooks.START_AFTER : HorizonHooks.START_BEFORE);
    }

    /** Minecraft's constructor at its options, after the registries' freeze (FabricBoot). */
    public static void optionsStart() {
        // Minecraft's constructor makes its block colours before its options and the end of its
        // start, where mods register tints (WorldWeaver's applyBlockTints: BetterEnd, BetterNether
        // got null). The models' load later keeps this one and its registrations. (UMP, 2026-11-11)
        try {
            if (minecraft.getBlockColors() == null) {
                set(Minecraft.class, minecraft, "blockColors", net.minecraft.client.color.block.BlockColors.createDefault());
            }
        } catch (Throwable t) {
            Natives.log("Horizon's client: no block colours at its start: " + t);
        }
        HorizonHooks.fireClient(minecraft, HorizonHooks.START_OPTIONS);
        HorizonHooks.fireClient(minecraft, HorizonHooks.START_END);
    }

    private static net.minecraft.client.main.GameConfig gameConfig;

    /**
     * The game's start-up configuration, which Minecraft's constructor takes (mods' injections
     * there read its folders: MaLiLib's config folder): the game folder's, made without its
     * constructor (no user, window or quick play here).
     */
    static synchronized net.minecraft.client.main.GameConfig gameConfig() {
        if (gameConfig != null) return gameConfig;
        try {
            var config = (net.minecraft.client.main.GameConfig) Natives.allocate(net.minecraft.client.main.GameConfig.class);
            File dir = minecraft.gameDirectory;
            set(net.minecraft.client.main.GameConfig.class, config, "location", new net.minecraft.client.main.GameConfig.FolderData(
                    dir, new File(dir, "resourcepacks"), new File(dir, "assets"), null));
            gameConfig = config;
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's client: no game config for the mods: " + e);
        }
        return gameConfig;
    }

    public static void finish() throws ReflectiveOperationException {
        Minecraft mc = minecraft;
        HorizonDeviceMods.startScan();
        // options.txt is brought up to date by the game's data fixer, as the server's saves are.
        set(Minecraft.class, mc, "fixerUpper", net.minecraft.util.datafix.DataFixers.getDataFixer());
        // The GPU device first, as Minecraft's constructor makes its window and device before
        // its options: options.txt's load asks the device (its anisotropy, mods' options read
        // RenderSystem.getDevice(): "Can't getDevice() before it was initialized").
        HorizonGpu.install();
        // Its frame rate limiter, which options.txt's load sets (maxFps): a value holder here.
        set(Minecraft.class, mc, "framerateLimitTracker",
                Natives.allocate(com.mojang.blaze3d.platform.FramerateLimitTracker.class));
        // The client's codecs' types (ClientBootstrap: Minecraft's main runs it before the game
        // starts): mods' resource reloads parse atlas sources and item models with them (a
        // config library's GUI atlas: "Unknown element id: minecraft:directory").
        try {
            net.minecraft.client.ClientBootstrap.bootstrap();
        } catch (Throwable t) {
            Natives.log("Horizon's client: the client's bootstrap stopped at " + t);
        }
        // Its window (sized below), and while the options load, options that answer what their
        // load asks the client (the GUI scale's range: isEnforceUnicode, the window's scale), as
        // Minecraft's constructor has its window before its options. Before, every start logged
        // "Failed to load options" (Options.forceUnicodeFont() on null).
        set(Minecraft.class, mc, "window", Natives.allocate(com.mojang.blaze3d.platform.Window.class));
        try {
            var early = (net.minecraft.client.Options) Natives.allocate(net.minecraft.client.Options.class);
            set(net.minecraft.client.Options.class, early, "forceUnicodeFont",
                    net.minecraft.client.OptionInstance.createBoolean("options.forceUnicodeFont", false));
            set(Minecraft.class, mc, "options", early);
        } catch (Throwable t) {
            Natives.log("Horizon's client: no options for the options' load: " + t);
        }
        set(Minecraft.class, mc, "options", new net.minecraft.client.Options(mc, mc.gameDirectory));
        // Key names in text (Component.keybind: Create's "Release Right Button to Confirm"), as
        // Minecraft's constructor sets them: the key bindings' names, not their ids.
        net.minecraft.network.chat.contents.KeybindResolver.setKeyResolver(KeyMapping::createNameSupplier);
        set(Minecraft.class, mc, "font", font());
        // A mouse, which screens focus their widgets for.
        set(Minecraft.class, mc, "lastInputType", net.minecraft.client.InputType.MOUSE);
        // A narrator that says nothing (Narrator.EMPTY).
        var narrator = (net.minecraft.client.GameNarrator) Natives.allocate(net.minecraft.client.GameNarrator.class);
        set(net.minecraft.client.GameNarrator.class, narrator, "minecraft", mc);
        set(net.minecraft.client.GameNarrator.class, narrator, "narrator", com.mojang.text2speech.Narrator.EMPTY);
        set(Minecraft.class, mc, "narrator", narrator);
        set(Minecraft.class, mc, "atlasManager", atlases());
        // Its textures: named, never uploaded (TextureManagerMixin, HorizonTextures).
        var textures = Natives.allocate(net.minecraft.client.renderer.texture.TextureManager.class);
        // Its maps, empty (mods' accessors read them: JourneyMap's getTex).
        set(net.minecraft.client.renderer.texture.TextureManager.class, textures, "byPath", new java.util.HashMap<>());
        set(net.minecraft.client.renderer.texture.TextureManager.class, textures, "tickableTextures", new java.util.HashSet<>());
        set(Minecraft.class, mc, "textureManager", textures);
        // Its skins: each profile's default (SkinManagerMixin).
        var skins = Natives.allocate(net.minecraft.client.resources.SkinManager.class);
        // Its downloader (FrozenLib's capes): downloads nothing (SkinDownloaderMixin).
        set(net.minecraft.client.resources.SkinManager.class, skins, "skinTextureDownloader",
                Natives.allocate(net.minecraft.client.renderer.texture.SkinTextureDownloader.class));
        set(Minecraft.class, mc, "skinManager", skins);
        // The in-game GUI's object (screens reach its HUD), made without its constructor.
        var gui = (net.minecraft.client.gui.Gui) Natives.allocate(net.minecraft.client.gui.Gui.class);
        var hud = (net.minecraft.client.gui.Hud) Natives.allocate(net.minecraft.client.gui.Hud.class);
        // What the HUD's parts mods draw over read (HorizonClient.hud): the client, a random
        // (the hearts' jitter).
        set(net.minecraft.client.gui.Hud.class, hud, "minecraft", mc);
        set(net.minecraft.client.gui.Hud.class, hud, "random", net.minecraft.util.RandomSource.create());
        // What its field initializers and constructor set that mods' parts of it read: the held
        // item's name's last item (Immersive Hotbar's tooltip read it: null), the vignette, the
        // title times, the spectator menu.
        set(net.minecraft.client.gui.Hud.class, hud, "lastToolHighlight", net.minecraft.world.item.ItemStack.EMPTY);
        set(net.minecraft.client.gui.Hud.class, hud, "vignetteBrightness", 1.0F);
        hud.resetTitleTimes();
        try {
            set(net.minecraft.client.gui.Hud.class, hud, "waypointStyles", new net.minecraft.client.resources.WaypointStyleManager());
            set(net.minecraft.client.gui.Hud.class, hud, "spectatorGui", new net.minecraft.client.gui.components.spectator.SpectatorGui(mc));
        } catch (Throwable t) {
            Natives.log("Horizon's client: the HUD's spectator menu or waypoint styles couldn't be made: " + t);
        }
        // Its contextual bar (experience, locator, a mount's jump) as its constructor makes it:
        // vanilla's hotbar run inside a mod's wrap of it (Create's) picks one each frame. (The
        // ContextualInfo enum is private: by name.)
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Enum> info = (Class<Enum>) Class.forName("net.minecraft.client.gui.Hud$ContextualInfo", true,
                    net.minecraft.client.gui.Hud.class.getClassLoader());
            @SuppressWarnings("unchecked")
            java.util.function.Function<String, Object> value = name -> Enum.valueOf(info, name);
            set(net.minecraft.client.gui.Hud.class, hud, "contextualInfoBar", com.mojang.datafixers.util.Pair.of(
                    value.apply("EMPTY"), net.minecraft.client.gui.contextualbar.ContextualBar.EMPTY));
            java.util.Map<Object, java.util.function.Supplier<net.minecraft.client.gui.contextualbar.ContextualBar>> bars =
                    new java.util.LinkedHashMap<>();
            bars.put(value.apply("EMPTY"), () -> net.minecraft.client.gui.contextualbar.ContextualBar.EMPTY);
            bars.put(value.apply("EXPERIENCE"), () -> new net.minecraft.client.gui.contextualbar.ExperienceBar(mc));
            bars.put(value.apply("LOCATOR"), () -> new net.minecraft.client.gui.contextualbar.LocatorBar(mc));
            bars.put(value.apply("JUMPABLE_VEHICLE"), () -> new net.minecraft.client.gui.contextualbar.JumpableVehicleBar(mc));
            set(net.minecraft.client.gui.Hud.class, hud, "contextualInfoBars", com.google.common.collect.ImmutableMap.copyOf(bars));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Horizon's client: the HUD has no contextual bars: " + e);
        }
        // Its overlays HUD mods ask about (Jade hides under an open tab list or a boss bar):
        // none showing.
        set(net.minecraft.client.gui.Hud.class, hud, "tabList",
                Natives.allocate(net.minecraft.client.gui.components.PlayerTabOverlay.class));
        var bosses = Natives.allocate(net.minecraft.client.gui.components.BossHealthOverlay.class);
        set(net.minecraft.client.gui.components.BossHealthOverlay.class, bosses, "events", new java.util.LinkedHashMap<>());
        set(net.minecraft.client.gui.Hud.class, hud, "bossOverlay", bosses);
        // The debug overlay (F3), which mods ask whether it shows (JourneyMap hides under it):
        // reading the client's debug entries below.
        var debug = Natives.allocate(net.minecraft.client.gui.components.DebugScreenOverlay.class);
        set(net.minecraft.client.gui.components.DebugScreenOverlay.class, debug, "minecraft", mc);
        set(net.minecraft.client.gui.Hud.class, hud, "debugOverlay", debug);
        // The chat (mods' screens read its recent lines: JourneyMap's map; add messages to it),
        // empty and never drawn.
        try {
            Class<?> chatClass = net.minecraft.client.gui.components.ChatComponent.class;
            var chat = Natives.allocate(chatClass);
            set(chatClass, chat, "minecraft", mc);
            set(chatClass, chat, "recentChat", new net.minecraft.util.ArrayListDeque<String>(100));
            set(chatClass, chat, "allMessages", new java.util.ArrayList<>());
            set(chatClass, chat, "trimmedMessages", new java.util.ArrayList<>());
            set(chatClass, chat, "messageDeletionQueue", new java.util.ArrayList<>());
            set(chatClass, chat, "visibleMessageFilter", (java.util.function.Predicate<Object>) m -> true);
            set(net.minecraft.client.gui.Hud.class, hud, "chat", chat);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Horizon's client: no chat component: " + e);
        }
        // Mods' fields on the HUD (Enderscape's hearts' variables), from their mixins' initializers.
        HorizonHooks.initFields(hud);
        set(net.minecraft.client.gui.Gui.class, gui, "hud", hud);
        // Its client (mods' injections into its frame read gui's minecraft: Xaero's).
        set(net.minecraft.client.gui.Gui.class, gui, "minecraft", mc);
        set(Minecraft.class, mc, "gui", gui);
        // A toast manager, so mods adding toasts don't fail (Pufferfish's Skills' message handler
        // threw on null); their toasts aren't drawn yet.
        try {
            set(net.minecraft.client.gui.Gui.class, gui, "toastManager",
                    new net.minecraft.client.gui.components.toasts.ToastManager(mc, mc.options));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Horizon's client: no toast manager: " + e);
        }
        // Screens click through the game mode (handleContainerInput): GameModeClientMixin.
        var gameMode = Natives.allocate(net.minecraft.client.multiplayer.MultiPlayerGameMode.class);
        // What its constructor sets (no block being broken: recording mods read destroyBlockPos).
        set(net.minecraft.client.multiplayer.MultiPlayerGameMode.class, gameMode, "destroyBlockPos", new net.minecraft.core.BlockPos(-1, -1, -1));
        set(net.minecraft.client.multiplayer.MultiPlayerGameMode.class, gameMode, "destroyingItem", net.minecraft.world.item.ItemStack.EMPTY);
        // Its Minecraft, as its constructor keeps it (ensureHasSentCarriedItem reads the player:
        // TACZ's gun draw calls it).
        set(net.minecraft.client.multiplayer.MultiPlayerGameMode.class, gameMode, "minecraft", mc);
        set(net.minecraft.client.multiplayer.MultiPlayerGameMode.class, gameMode, "localPlayerMode", net.minecraft.world.level.GameType.DEFAULT_MODE);
        set(Minecraft.class, mc, "gameMode", gameMode);
        // The server's resource packs the client took (none: Horizon's game takes them):
        // recording mods list them in their snapshots.
        try {
            var packs = Natives.allocate(net.minecraft.client.resources.server.ServerPackManager.class);
            set(net.minecraft.client.resources.server.ServerPackManager.class, packs, "packs", new java.util.ArrayList<>());
            var source = Natives.allocate(net.minecraft.client.resources.server.DownloadedPackSource.class);
            set(net.minecraft.client.resources.server.DownloadedPackSource.class, source, "manager", packs);
            set(Minecraft.class, mc, "downloadedPackSource", source);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Horizon's client: no server pack source: " + e);
        }
        // Text boxes' typing area, for a window it has none of (TextInputManagerMixin).
        set(Minecraft.class, mc, "textInputManager", Natives.allocate(com.mojang.blaze3d.platform.TextInputManager.class));
        // Buttons click with a sound: a manager without an engine (SoundManagerMixin).
        set(Minecraft.class, mc, "soundManager", Natives.allocate(net.minecraft.client.sounds.SoundManager.class));
        // A window of Horizon's GUI size (mods' screens lay out by getWindow()'s scaled size and
        // scale: Xaero's settings), with no SDL window under it.
        // The debug screen's entries (the crosshair's part asks whether the axes show): the
        // game folder's, none shown.
        try {
            set(Minecraft.class, mc, "debugEntries", new net.minecraft.client.gui.components.debug.DebugScreenEntryList(
                    mc.gameDirectory, net.minecraft.util.datafix.DataFixers.getDataFixer()));
        } catch (Throwable t) {
            Natives.log("Horizon's client: no debug screen entries: " + t);
        }
        // A game renderer that is only its main camera (CameraMixin: the player's eyes), which
        // HUD mods aim with (Jade).
        var renderer = Natives.allocate(net.minecraft.client.renderer.GameRenderer.class);
        set(net.minecraft.client.renderer.GameRenderer.class, renderer, "mainCamera",
                Natives.allocate(net.minecraft.client.Camera.class));
        // Its render state (mods read the light map's and the options' state from it).
        try {
            set(net.minecraft.client.renderer.GameRenderer.class, renderer, "gameRenderState",
                    new net.minecraft.client.renderer.state.GameRenderState());
        } catch (Throwable t) {
            Natives.log("Horizon's client: no game render state: " + t);
        }
        set(Minecraft.class, mc, "gameRenderer", renderer);
        // Its level extractor, which mods ask to redraw (LevelExtractorMixin: nothing to redraw).
        set(Minecraft.class, mc, "levelExtractor", Natives.allocate(net.minecraft.client.renderer.extract.LevelExtractor.class));
        windowSize();
        // Its mouse and keyboard handlers (mods' screens call them: JourneyMap's onButton).
        mouseAt(0, 0);
        registerCommands();
        // The language manager (mods' translation hooks ask it which language is chosen:
        // Server Translations API's Language.inject), with no resource reloads behind it.
        try {
            String code = mc.options.languageCode == null ? "en_us" : mc.options.languageCode;
            set(Minecraft.class, mc, "languageManager",
                    new net.minecraft.client.resources.language.LanguageManager(mc, code, lang -> { }));
        } catch (Throwable t) {
            Natives.log("Horizon's client: no language manager: " + t);
        }
        language(mc.options.languageCode);
        // A particle engine that hands mods' particles to Horizon's (HorizonParticles).
        set(Minecraft.class, mc, "particleEngine", new HorizonParticles());
        // Item and block models, until the client's own load (models(), with a GPU; never
        // without one): a model manager with none baked and a resolver that keeps items for
        // Horizon to draw (ItemModelResolverMixin), so the HUD's hotbar, the hands' tick and
        // mods asking for them don't fail on null.
        standInModels();
        Natives.log("Horizon's headless client is up");
    }

    /**
     * The client's language (LanguageManager's ClientLanguage, from the resource manager the
     * headless client lacks): vanilla's English, then every mod's {@code assets/<ns>/lang/}
     * English and the chosen language over it. Screens' text, which their code resolves here
     * (a mod's "string.naturescompass.selectBiome"), and the server's getString() read it.
     */
    private static void language(String code) {
        java.util.Map<String, String> storage = new java.util.HashMap<>();
        java.util.List<String> codes = code == null || code.equals("en_us") ? java.util.List.of("en_us") : java.util.List.of("en_us", code);
        for (String lang : codes) {
            try (var in = Minecraft.class.getClassLoader().getResourceAsStream("assets/minecraft/lang/" + lang + ".json")) {
                if (in != null) net.minecraft.locale.Language.loadFromJson(in, storage::put);
            } catch (Exception e) {
                Natives.log("Horizon's client: vanilla's " + lang + " could not be read: " + e);
            }
            for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                for (java.nio.file.Path root : mod.getRootPaths()) {
                    java.nio.file.Path assets = root.resolve("assets");
                    if (!java.nio.file.Files.isDirectory(assets)) continue;
                    try (var namespaces = java.nio.file.Files.list(assets)) {
                        for (java.nio.file.Path ns : (Iterable<java.nio.file.Path>) namespaces::iterator) {
                            java.nio.file.Path file = ns.resolve("lang/" + lang + ".json");
                            if (!java.nio.file.Files.isRegularFile(file)) {
                                legacyLang(ns, lang, storage);
                                continue;
                            }
                            try (var in = java.nio.file.Files.newInputStream(file)) {
                                net.minecraft.locale.Language.loadFromJson(in, storage::put);
                            } catch (Exception e) {
                                Natives.log("Horizon's client: " + mod.getMetadata().getId() + "'s " + file + " could not be read: " + e);
                            }
                        }
                    } catch (java.io.IOException e) {
                        // A mod whose assets can't be listed has no translations for us.
                    }
                }
            }
        }
        try {
            var make = net.minecraft.client.resources.language.ClientLanguage.class.getDeclaredConstructor(java.util.Map.class, boolean.class);
            make.setAccessible(true);
            net.minecraft.locale.Language.inject(make.newInstance(storage, false));
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's client: no language with mods' translations: " + e);
        }
    }

    /**
     * A mod's legacy {@code lang/xx_YY.lang} ({@code key=value} lines, the pre-1.13 format),
     * when it has no JSON for the language: ReplayMod keeps its translations so, made into
     * JSON at runtime by a resource pack of its own that the headless client never opens.
     */
    private static void legacyLang(java.nio.file.Path ns, String lang, java.util.Map<String, String> storage) {
        int at = lang.indexOf('_');
        String code = at < 0 ? lang : lang.substring(0, at) + "_" + lang.substring(at + 1).toUpperCase(java.util.Locale.ROOT);
        for (String name : new String[] {code, lang}) {
            java.nio.file.Path file = ns.resolve("lang/" + name + ".lang");
            if (!java.nio.file.Files.isRegularFile(file)) continue;
            try {
                for (String line : java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                    String l = line.startsWith("\uFEFF") ? line.substring(1) : line;
                    int eq = l.indexOf('=');
                    if (l.isBlank() || l.trim().startsWith("#") || eq < 0) continue;
                    storage.put(l.substring(0, eq), l.substring(eq + 1));
                }
            } catch (java.io.IOException e) {
                Natives.log("Horizon's client: " + file + " could not be read: " + e);
            }
            return;
        }
    }

    // ---- Mods' settings screens ---------------------------------------------------------------

    /**
     * {@code /modconfig <mod>}: the mod's settings screen, as Mod Menu's list opens it (its
     * "modmenu" entrypoint's ConfigScreenFactory, with no parent screen), for the player who
     * runs it. Horizon's Mods page lists the same screens (configMods, the "modconfig" input).
     */
    private static void registerCommands() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) ->
                dispatcher.register(net.minecraft.commands.Commands.literal("modconfig")
                        .then(net.minecraft.commands.Commands.argument("mod", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(configScreens().keySet(), b))
                                .executes(c -> openConfig(c.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "mod"))))));
    }

    /** Mods whose "modmenu" entrypoint failed (told once). */
    private static final java.util.Set<String> BROKEN_MENUS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The mods with a settings screen, by id: what makes it from a parent screen. */
    static java.util.Map<String, java.util.function.UnaryOperator<net.minecraft.client.gui.screens.Screen>> configScreens() {
        java.util.Map<String, java.util.function.UnaryOperator<net.minecraft.client.gui.screens.Screen>> out = new java.util.TreeMap<>();
        // Mod Menu's API when Mod Menu isn't installed: mods' "modmenu" entrypoints load then.
        HorizonModMenuApi.ensure();
        java.lang.reflect.Method create, own, provided;
        Class<?> api;
        try {
            ClassLoader loader = HorizonClient.class.getClassLoader();
            create = Class.forName("com.terraformersmc.modmenu.api.ConfigScreenFactory", true, loader)
                    .getMethod("create", net.minecraft.client.gui.screens.Screen.class);
            // The interface's methods, not the implementation's: a mod's ModMenuApi class may
            // not be public (its methods then can't be called through its own class).
            api = Class.forName("com.terraformersmc.modmenu.api.ModMenuApi", true, loader);
            own = api.getMethod("getModConfigScreenFactory");
            provided = api.getMethod("getProvidedConfigScreenFactories");
        } catch (ReflectiveOperationException | LinkageError e) {
            return out; // No Mod Menu: its entrypoints can't load.
        }
        java.util.Map<String, java.util.function.UnaryOperator<net.minecraft.client.gui.screens.Screen>> others = new java.util.TreeMap<>();
        for (var container : net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class)) {
            String id = container.getProvider().getMetadata().getId();
            try {
                Object entry = container.getEntrypoint();
                if (!api.isInstance(entry)) {
                    // Mod Menu skips it too (Compressed Blocks' is an empty class): no screen.
                    if (BROKEN_MENUS.add(id)) Natives.log("Horizon's client: " + id + "'s \"modmenu\" entrypoint isn't a ModMenuApi (Mod Menu skips it too): no settings screen");
                    continue;
                }
                Object factory = own.invoke(entry);
                if (factory != null) out.put(id, parent -> make(create, factory, parent));
                // Screens a mod gives others (Forge Config API Port's, for mods with its
                // configs), as Mod Menu: a mod's own screen wins over one given for it.
                if (provided.invoke(entry) instanceof java.util.Map<?, ?> given) {
                    given.forEach((mod, f) -> {
                        if (f != null) others.putIfAbsent(String.valueOf(mod), parent -> make(create, f, parent));
                    });
                }
            } catch (Throwable t) {
                Throwable root = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                if (BROKEN_MENUS.add(id)) Natives.log("Horizon's client: " + id + "'s settings screen can't be made: " + root);
            }
        }
        others.forEach(out::putIfAbsent);
        return out;
    }

    /**
     * The mods with a settings screen, as {@code [[id, name], ...]} (by name): what players'
     * Mods pages list (events::config_mods). The screens are made when asked for, not here.
     */
    public static String configMods() {
        Thread.currentThread().setContextClassLoader(HorizonClient.class.getClassLoader());
        var list = new com.google.gson.JsonArray();
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        java.util.List<String[]> mods = new java.util.ArrayList<>();
        try {
            for (String id : configScreens().keySet()) {
                String name = loader.getModContainer(id).map(c -> c.getMetadata().getName()).orElse(id);
                mods.add(new String[] {id, name});
            }
        } catch (Throwable t) {
            Natives.log("Horizon's client: the mods' settings screens can't be listed: " + t);
        }
        mods.sort(java.util.Comparator.comparing(m -> m[1].toLowerCase(java.util.Locale.ROOT)));
        for (String[] m : mods) {
            var pair = new com.google.gson.JsonArray();
            pair.add(m[0]);
            pair.add(m[1]);
            list.add(pair);
        }
        return list.toString();
    }

    private static net.minecraft.client.gui.screens.Screen make(java.lang.reflect.Method create, Object factory,
                                                               net.minecraft.client.gui.screens.Screen parent) {
        try {
            return (net.minecraft.client.gui.screens.Screen) create.invoke(factory, parent);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
    }

    private static int openConfig(net.minecraft.commands.CommandSourceStack source, String mod) {
        var player = source.getPlayer();
        var factory = configScreens().get(mod);
        if (player == null || factory == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(player == null
                    ? "Only a player can open a mod's settings"
                    : "No settings screen for " + mod + " (Mod Menu installed? " + String.join(", ", configScreens().keySet()) + ")"));
            return 0;
        }
        try {
            as(player, () -> setScreen(factory.apply(new HorizonModsPage())));
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: " + mod + "'s settings screen failed: " + root);
            HorizonFailures.report("its settings screen", root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
            source.sendFailure(net.minecraft.network.chat.Component.literal(mod + "'s settings screen failed: " + root));
            return 0;
        }
        return 1;
    }

    /** Whether this JVM's client is Horizon's headless one. */
    public static boolean headless() {
        return minecraft != null;
    }

    public static Minecraft minecraft() {
        return minecraft;
    }

    /** Fields set by name, looked up once (entities' snapshots set some for each entity). */
    private static final java.util.Map<Class<?>, java.util.Map<String, Field>> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

    static void set(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = FIELDS.computeIfAbsent(owner, o -> new java.util.concurrent.ConcurrentHashMap<>()).get(name);
        if (field == null) {
            field = owner.getDeclaredField(name);
            field.setAccessible(true);
            FIELDS.get(owner).put(name, field);
        }
        field.set(target, value);
    }

    /** A field read by name (looked up once, as {@link #set}). */
    static Object get(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        Field field = FIELDS.computeIfAbsent(owner, o -> new java.util.concurrent.ConcurrentHashMap<>()).get(name);
        if (field == null) {
            field = owner.getDeclaredField(name);
            field.setAccessible(true);
            FIELDS.get(owner).put(name, field);
        }
        return field.get(target);
    }

    /**
     * The atlases screens' graphics are made with (GuiGraphicsExtractor takes the GUI sprites'):
     * empty ones, without a GPU. Horizon draws sprites from its own atlas, by name.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.minecraft.client.resources.model.sprite.AtlasManager atlases() throws ReflectiveOperationException {
        var manager = (net.minecraft.client.resources.model.sprite.AtlasManager)
                Natives.allocate(net.minecraft.client.resources.model.sprite.AtlasManager.class);
        Class<?> entryClass = Class.forName("net.minecraft.client.resources.model.sprite.AtlasManager$AtlasEntry");
        Class<?> configClass = Class.forName("net.minecraft.client.resources.model.sprite.AtlasManager$AtlasConfig");
        var make = entryClass.getDeclaredConstructor(net.minecraft.client.renderer.texture.TextureAtlas.class, configClass);
        make.setAccessible(true);
        java.util.Map byId = new java.util.HashMap();
        for (var id : new net.minecraft.resources.Identifier[] {net.minecraft.data.AtlasIds.GUI}) {
            var atlas = (net.minecraft.client.renderer.texture.TextureAtlas)
                    Natives.allocate(net.minecraft.client.renderer.texture.TextureAtlas.class);
            // Its texture's name (sprites of the GUI atlas are textures/gui/sprites/: HorizonSprites).
            set(net.minecraft.client.renderer.texture.TextureAtlas.class, atlas, "location",
                    net.minecraft.resources.Identifier.withDefaultNamespace("textures/atlas/gui.png"));
            byId.put(id, make.newInstance(atlas, null));
        }
        Class<?> owner = net.minecraft.client.resources.model.sprite.AtlasManager.class;
        set(owner, manager, "atlasById", byId);
        set(owner, manager, "atlasByTexture", new java.util.HashMap<>());
        set(owner, manager, "spriteLookup", new java.util.HashMap<>());
        return manager;
    }

    /** A sprite that is only its name (and the whole of its texture's UVs). */
    static net.minecraft.client.renderer.texture.TextureAtlasSprite sprite(net.minecraft.resources.Identifier atlas,
                                                                         net.minecraft.resources.Identifier name) {
        try {
            var contents = Natives.allocate(net.minecraft.client.renderer.texture.SpriteContents.class);
            set(net.minecraft.client.renderer.texture.SpriteContents.class, contents, "name", name);
            set(net.minecraft.client.renderer.texture.SpriteContents.class, contents, "width", 16);
            set(net.minecraft.client.renderer.texture.SpriteContents.class, contents, "height", 16);
            set(net.minecraft.client.renderer.texture.SpriteContents.class, contents, "additionalMetadata", java.util.List.of());
            var sprite = Natives.allocate(net.minecraft.client.renderer.texture.TextureAtlasSprite.class);
            Class<?> owner = net.minecraft.client.renderer.texture.TextureAtlasSprite.class;
            set(owner, sprite, "atlasLocation", atlas);
            set(owner, sprite, "contents", contents);
            set(owner, sprite, "u1", 1f);
            set(owner, sprite, "v1", 1f);
            return (net.minecraft.client.renderer.texture.TextureAtlasSprite) sprite;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // ---- Text widths ------------------------------------------------------------------------

    /**
     * The font screens measure text with: vanilla's default font's advances (read from its
     * ascii.png as BitmapProvider reads them), 6 for what it lacks. Horizon draws the text.
     */
    static net.minecraft.client.gui.Font font() {
        float[] advances = asciiAdvances();
        net.minecraft.client.gui.font.glyphs.BakedGlyph[] glyphs = new net.minecraft.client.gui.font.glyphs.BakedGlyph[256];
        net.minecraft.client.gui.font.glyphs.BakedGlyph wide = glyph(6);
        for (int i = 0; i < 256; i++) glyphs[i] = glyph(advances[i]);
        net.minecraft.client.gui.GlyphSource source = new net.minecraft.client.gui.GlyphSource() {
            @Override
            public net.minecraft.client.gui.font.glyphs.BakedGlyph getGlyph(int codePoint) {
                return codePoint >= 0 && codePoint < 256 ? glyphs[codePoint] : wide;
            }

            @Override
            public net.minecraft.client.gui.font.glyphs.BakedGlyph getRandomGlyph(net.minecraft.util.RandomSource random, int width) {
                return wide;
            }
        };
        // Once the client's real fonts are loaded on its GPU (fonts()), glyphs are theirs: text a
        // mod draws with the GPU (Xaero's compass letters) shows.
        return new net.minecraft.client.gui.Font(new net.minecraft.client.gui.Font.Provider() {
            @Override
            public net.minecraft.client.gui.GlyphSource glyphs(net.minecraft.network.chat.FontDescription description) {
                var real = realFonts;
                return real != null ? real.glyphs(description) : source;
            }

            @Override
            public net.minecraft.client.gui.font.glyphs.EffectGlyph effect() {
                var real = realFonts;
                return real != null ? real.effect() : (x0, y0, x1, y1, depth, color, shadow, offset) -> null;
            }
        });
    }

    private static net.minecraft.client.gui.font.glyphs.BakedGlyph glyph(float advance) {
        com.mojang.blaze3d.font.GlyphInfo info = com.mojang.blaze3d.font.GlyphInfo.simple(advance);
        return new net.minecraft.client.gui.font.glyphs.BakedGlyph() {
            @Override
            public com.mojang.blaze3d.font.GlyphInfo info() {
                return info;
            }

            @Override
            public net.minecraft.client.gui.font.TextRenderable.Styled createGlyph(float x, float y, int color, int shadow,
                                                                                net.minecraft.network.chat.Style style, float bold, float offset) {
                return null;
            }
        };
    }

    /** The real fonts' glyphs (a FontManager's), when the headless client has a GPU. */
    private static volatile net.minecraft.client.gui.Font.Provider realFonts;

    /**
     * The client's fonts as vanilla loads them (FontManager over the client resources: ascii.png,
     * unifont...), their glyphs stitched on the headless GPU; the client's Font answers from them.
     */
    private static void fonts(net.minecraft.client.resources.model.sprite.AtlasManager atlases,
                              net.minecraft.client.renderer.PlayerSkinRenderCache skins) {
        long started = System.nanoTime();
        try {
            var fonts = new net.minecraft.client.gui.font.FontManager(minecraft.getTextureManager(), atlases, skins);
            java.util.concurrent.Executor here = Runnable::run;
            net.minecraft.server.packs.resources.SimpleReloadInstance.of(minecraft.getResourceManager(), java.util.List.of(fonts),
                    here, here, java.util.concurrent.CompletableFuture.completedFuture(net.minecraft.util.Unit.INSTANCE)).done().join();
            realFonts = (net.minecraft.client.gui.Font.Provider) field(net.minecraft.client.gui.Font.class, fonts.createFont(), "provider");
            Natives.log("Horizon's client: fonts loaded in " + (System.nanoTime() - started) / 1_000_000 + " ms");
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the fonts couldn't be loaded: " + root);
        }
    }

    /** Each character's advance in ascii.png's 16 by 16 grid: its last drawn column, plus one. */
    private static float[] asciiAdvances() {
        float[] out = new float[256];
        java.util.Arrays.fill(out, 6);
        try (var in = Minecraft.class.getClassLoader().getResourceAsStream("assets/minecraft/textures/font/ascii.png")) {
            if (in == null) return out;
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(in);
            int cell = image.getWidth() / 16;
            for (int c = 0; c < 256; c++) {
                int cx = (c % 16) * cell, cy = (c / 16) * cell;
                int last = -1;
                for (int x = cell - 1; x >= 0 && last < 0; x--) {
                    for (int y = 0; y < cell; y++) {
                        if ((image.getRGB(cx + x, cy + y) >>> 24) != 0) {
                            last = x;
                            break;
                        }
                    }
                }
                if (last >= 0) out[c] = (last + 1) * 8f / cell + 1;
            }
        } catch (Exception e) {
            Natives.log("Horizon's client: ascii.png could not be read: " + e);
        }
        out[' '] = 4;
        return out;
    }

    // ---- Mods' screens ----------------------------------------------------------------------

    /** The size Horizon's GUI has (scaled pixels), which screens lay out in. */
    static int width = 427, height = 240;
    /**
     * The window's GUI scale and framebuffer size (0: the GUI's size at the scale), as the
     * player's game last said (a size input): pictures in picture are drawn at that scale, and
     * mods' full-screen targets are the framebuffer's size (Xaero's World Map).
     */
    static int scale = 3, pixelWidth, pixelHeight;

    /** Each player's window, as their game last told it: {GUI width, height, scale, framebuffer width, height}. */
    private static final java.util.Map<java.util.UUID, int[]> WINDOWS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The window now: {GUI width, height, scale, framebuffer width, height}. */
    private static int[] windowNow() {
        return new int[] {width, height, scale, pixelWidth, pixelHeight};
    }

    /** Player {@code id}'s GUI size (the window now when their game hasn't said). */
    static int[] guiSize(java.util.UUID id) {
        int[] w = id == null ? null : WINDOWS.get(id);
        return w == null ? new int[] {width, height} : new int[] {w[0], w[1]};
    }

    /**
     * A size input ({@code {"kind":"size", width, height, scale, px_w, px_h}}; an older game sends
     * the GUI's size only: scale 3): player {@code id}'s window, the headless one's now.
     */
    static void sizeInput(java.util.UUID id, com.google.gson.JsonObject in) {
        int w = in.get("width").getAsInt(), h = in.get("height").getAsInt();
        int s = in.has("scale") ? Math.max(1, in.get("scale").getAsInt()) : 3;
        int pw = in.has("px_w") ? in.get("px_w").getAsInt() : w * s;
        int ph = in.has("px_h") ? in.get("px_h").getAsInt() : h * s;
        int[] window = {w, h, s, pw, ph};
        if (id != null) WINDOWS.put(id, window);
        applyWindow(window, true);
    }

    /** Sets the headless window to {@code window}; {@code resize}: the main target too. */
    private static void applyWindow(int[] window, boolean resize) {
        width = window[0];
        height = window[1];
        scale = window[2];
        pixelWidth = window[3];
        pixelHeight = window[4];
        windowSize();
        if (!resize) return;
        // The main render target follows the window, as Minecraft.resizeDisplay resizes it
        // (only on a size input: switching players' windows keeps it, unseen anyway).
        try {
            var renderer = minecraft == null ? null : minecraft.gameRenderer;
            if (renderer instanceof net.minecraft.client.renderer.GameRenderer game) {
                var target = (com.mojang.blaze3d.pipeline.RenderTarget) get(net.minecraft.client.renderer.GameRenderer.class, game, "mainRenderTarget");
                int pw = pixelWidth > 0 ? pixelWidth : width * scale, ph = pixelHeight > 0 ? pixelHeight : height * scale;
                if (target != null && (target.width != pw || target.height != ph)) target.resize(pw, ph);
            }
        } catch (Throwable t) {
            Natives.log("Horizon's client: the main target couldn't follow the window: " + t);
        }
    }

    /** The headless window's size: Horizon's GUI size at the window's scale and framebuffer. */
    static void windowSize() {
        try {
            var window = minecraft.getWindow();
            if (window == null) return;
            Class<?> owner = com.mojang.blaze3d.platform.Window.class;
            int pw = pixelWidth > 0 ? pixelWidth : width * scale, ph = pixelHeight > 0 ? pixelHeight : height * scale;
            for (String f : new String[] {"width", "framebufferWidth", "windowedWidth"}) set(owner, window, f, pw);
            for (String f : new String[] {"height", "framebufferHeight", "windowedHeight"}) set(owner, window, f, ph);
            set(owner, window, "guiScaledWidth", width);
            set(owner, window, "guiScaledHeight", height);
            set(owner, window, "guiScale", scale);
            set(owner, window, "focused", true);
            set(owner, window, "monitorManager", Natives.allocate(HorizonMonitors.class));
            // The render state's window (the GUI renderer's scale, pictures in picture), as the
            // game renderer's extract sets it each frame.
            if (minecraft.gameRenderer instanceof net.minecraft.client.renderer.GameRenderer game && game.gameRenderState() != null) {
                var state = game.gameRenderState().windowRenderState;
                state.width = pw;
                state.height = ph;
                state.guiScale = scale;
                var gui = get(net.minecraft.client.renderer.GameRenderer.class, game, "guiRenderer");
                if (gui != null) {
                    set(net.minecraft.client.gui.render.GuiRenderer.class, gui, "cachedGuiScale", scale);
                    var projection = get(net.minecraft.client.gui.render.GuiRenderer.class, gui, "guiProjection");
                    if (projection instanceof net.minecraft.client.renderer.Projection p) p.setupOrtho(1000.0F, 11000.0F, width, height, true);
                }
            }
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's client: the window's size could not be set: " + e);
        }
    }

    /** Sets whether the mouse is grabbed (the window active too, once): what it was. */
    private static boolean grabMouse(boolean grabbed) {
        try {
            if (minecraft.mouseHandler == null) mouseAt(0, 0);
            boolean was = minecraft.mouseHandler.isMouseGrabbed();
            set(net.minecraft.client.MouseHandler.class, minecraft.mouseHandler, "mouseGrabbed", grabbed);
            if (!minecraft.isWindowActive()) set(com.mojang.blaze3d.platform.Window.class, minecraft.getWindow(), "focused", true);
            return was;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The mouse, at a GUI position (screens that read it themselves rather than from their
     * arguments: REI's recipe view), in the window's pixels (its scale is 3).
     */
    static void mouseAt(double guiX, double guiY) {
        try {
            if (minecraft.mouseHandler == null) {
                var mouse = Natives.allocate(net.minecraft.client.MouseHandler.class);
                set(net.minecraft.client.MouseHandler.class, mouse, "minecraft", minecraft);
                set(Minecraft.class, minecraft, "mouseHandler", mouse);
            }
            // Its keyboard handler too: text boxes focused by a click resubmit its IME preedit
            // (none here).
            if (minecraft.keyboardHandler == null) {
                var keyboard = Natives.allocate(net.minecraft.client.KeyboardHandler.class);
                set(net.minecraft.client.KeyboardHandler.class, keyboard, "minecraft", minecraft);
                set(Minecraft.class, minecraft, "keyboardHandler", keyboard);
            }
            set(net.minecraft.client.MouseHandler.class, minecraft.mouseHandler, "xpos", guiX * 3);
            set(net.minecraft.client.MouseHandler.class, minecraft.mouseHandler, "ypos", guiY * 3);
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's client: no mouse: " + e);
        }
    }

    private static final java.util.Map<java.util.UUID, net.minecraft.client.gui.screens.Screen> SCREENS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, net.minecraft.client.player.LocalPlayer> PLAYERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The client's own player for a server player (Minecraft.player, which screens read: the
     * recipe book, the open menu): made without its constructor, on the server player's
     * inventory and menus. Each player's screen runs with theirs as Minecraft.player.
     */
    static net.minecraft.client.player.LocalPlayer localPlayer(net.minecraft.server.level.ServerPlayer player)
            throws ReflectiveOperationException {
        var local = PLAYERS.get(player.getUUID());
        if (local == null) {
            local = (net.minecraft.client.player.LocalPlayer) Natives.allocate(net.minecraft.client.player.LocalPlayer.class);
            set(net.minecraft.client.player.LocalPlayer.class, local, "recipeBook", recipeBook(player));
            set(net.minecraft.client.player.LocalPlayer.class, local, "minecraft", minecraft);
            // Its connection: Minecraft.getConnection() is the player's (Puzzles Lib's join
            // reads getConnection().getConnection()).
            var listener = connection(player.level());
            if (listener != null) set(net.minecraft.client.player.LocalPlayer.class, local, "connection", listener);
            // Who it is (a screen checks a chunk's owner against it: Chunk Loaders): the
            // server player's id, UUID and profile.
            set(net.minecraft.world.entity.Entity.class, local, "uuid", player.getUUID());
            set(net.minecraft.world.entity.Entity.class, local, "stringUUID", player.getStringUUID());
            set(net.minecraft.world.entity.Entity.class, local, "id", player.getId());
            set(net.minecraft.world.entity.player.Player.class, local, "gameProfile", player.getGameProfile());
            // Its keys (isShiftKeyDown: a collision context's descending): none pressed.
            set(net.minecraft.client.player.LocalPlayer.class, local, "input", new net.minecraft.client.player.ClientInput());
            // Its first-person hands and items, as its constructor makes them (26.3:
            // firstPersonHandsAndItems(); Create's client tick reads them every tick).
            set(net.minecraft.client.player.LocalPlayer.class, local, "firstPersonHandsAndItems",
                    new net.minecraft.client.player.FirstPersonHandsAndItems());
            // What its chat may do (ChatScreen asks: JourneyMap's map has one): anything.
            set(net.minecraft.client.player.LocalPlayer.class, local, "chatAbilities",
                    net.minecraft.client.multiplayer.chat.ChatAbilities.NO_RESTRICTIONS);
            // Its own connection (Minecraft.getConnection: mods' per-client state).
            var own = connection(player);
            if (own != null) set(net.minecraft.client.player.LocalPlayer.class, local, "connection", own);
            set(net.minecraft.world.entity.player.Player.class, local, "inventory", player.getInventory());
            // Its profile (its scoreboard name: the team whose name tag rules an entity
            // renderer asks): the server player's.
            set(net.minecraft.world.entity.player.Player.class, local, "gameProfile", player.getGameProfile());
            set(net.minecraft.world.entity.player.Player.class, local, "inventoryMenu", player.inventoryMenu);
            // Its level, which tooltips ask (enabled features): the server player's.
            set(net.minecraft.world.entity.Entity.class, local, "level", player.level());
            // Its synced data (health: isAlive, which a screen's tick asks): the server player's.
            set(net.minecraft.world.entity.Entity.class, local, "entityData", player.getEntityData());
            // A builder of synced data for its class, as its constructor makes one: mods that add
            // synced data to every entity class from the builder (Create's AllSynchedDatas: its
            // accessors by exact class, the toolbox HUD's) then know LocalPlayer's (the same ids
            // as the server player's, whose data it reads). Before, Create's HUD read a null
            // accessor every frame.
            new net.minecraft.network.syncher.SynchedEntityData.Builder(local);
            // What it is in (water over its eyes: the HUD's air bubbles): the server player's.
            set(net.minecraft.world.entity.Entity.class, local, "fluidInteraction",
                    field(net.minecraft.world.entity.Entity.class, player, "fluidInteraction"));
            // Its worn and off-hand items (getItemInHand(OFF_HAND), an item's client use): the
            // server player's.
            set(net.minecraft.world.entity.LivingEntity.class, local, "equipment",
                    field(net.minecraft.world.entity.LivingEntity.class, player, "equipment"));
            // Its attributes (max health, armour: the HUD's hearts) and effects: the server player's.
            for (String f : new String[] {"attributes", "activeEffects"}) {
                set(net.minecraft.world.entity.LivingEntity.class, local, f, field(net.minecraft.world.entity.LivingEntity.class, player, f));
            }
            // What mods' mixins add to entities and players, set in constructors this player
            // never ran (field initializers are merged into them, and constructor-end
            // injections), in their order: the server player's mod objects where the client's
            // has none (Trinkets' equipment attachment, which Traveler's Backpack's keys and HUD
            // read); empty collections; mod objects from their factory methods (Player
            // Animation Lib's `animationManager = createAnimationStack()`, which fills its
            // animation map) or a constructor taking the player (Spell Engine's cast
            // controller); then the constructors' end (Better Combat's and Spell Engine's
            // animation stacks, read from that map).
            HorizonServer.emptyCollections(local, net.minecraft.client.player.LocalPlayer.class);
            // Its first-person hands (firstPersonHandsAndItems(), made in its constructor):
            // Create's client tick reads the held items there each tick (the Extendo Grip).
            set(net.minecraft.client.player.LocalPlayer.class, local, "firstPersonHandsAndItems",
                    new net.minecraft.client.player.FirstPersonHandsAndItems());
            // Its class's synced data, as its constructor's SynchedEntityData.Builder makes it
            // (the data itself is the server player's, above). Mods that add data to players
            // make their accessors per entity class there: Create's AllSynchedDatas looks a
            // player's up by getClass(), and found none for LocalPlayer (a null accessor in
            // SynchedEntityData.get: its toolbox HUD, every frame).
            try {
                new net.minecraft.network.syncher.SynchedEntityData.Builder(local);
            } catch (Throwable t) {
                Natives.log("Horizon's client: the client player's synced data couldn't be made: " + t);
            }
            // In a client level while they run, as a client player's constructors are (Player
            // Animation Lib prepares its layers only on the client side).
            var client = clientLevel(player.level());
            if (client != null) set(net.minecraft.world.entity.Entity.class, local, "level", client);
            try {
                // The initializers of mixins on the client player's own classes (LocalPlayer,
                // AbstractClientPlayer: the server player has none of theirs), first, as its
                // constructor runs them: TACZ's gun state objects take each other and the player.
                for (Class<?> c = local.getClass(); c != net.minecraft.world.entity.player.Player.class; c = c.getSuperclass()) {
                    HorizonMixinInit.run(local, c);
                }
                makeModFields(local, true);
                copyModFields(player, local);
                makeModFields(local, false);
                HorizonHooks.constructed(local, minecraft, client, player.getGameProfile(), player.level());
            } finally {
                set(net.minecraft.world.entity.Entity.class, local, "level", player.level());
            }
            // In its level, as a client player is once added to it (JourneyMap maps only for a
            // player whose levelCallback isn't NULL, which the server player's copy is).
            set(net.minecraft.world.entity.Entity.class, local, "levelCallback", IN_LEVEL);
            PLAYERS.put(player.getUUID(), local);
        }
        SERVER_PLAYERS.put(local, player);
        // Its game mode as the client's game mode has it (Farmer's Delight's comfort overlay
        // reads gameMode.getPlayerMode()): the server player's. (Fields: setLocalMode would
        // also tell the player's abilities.)
        if (minecraft.gameMode != null) {
            set(net.minecraft.client.multiplayer.MultiPlayerGameMode.class, minecraft.gameMode, "localPlayerMode", player.gameMode());
            set(net.minecraft.client.multiplayer.MultiPlayerGameMode.class, minecraft.gameMode, "previousLocalPlayerMode", player.gameMode());
        }
        // Its food (what HUD mods read: AppleSkin): the server player's.
        set(net.minecraft.world.entity.player.Player.class, local, "foodData", player.getFoodData());
        // The client's menu: its own copy where its screen has one, else the server's.
        var client = CLIENT_MENUS.get(player.getUUID());
        local.containerMenu = client != null && player.containerMenu != null
                && client.containerId == player.containerMenu.containerId ? client : player.containerMenu;
        // Its permissions (tooltips' operator warnings): the server player's.
        set(net.minecraft.client.player.LocalPlayer.class, local, "permissions", player.permissions());
        // Where it is and looks (Nature's Compass sends blockPosition() with its search): the
        // server player's, set as fields (setPosRaw reads the old position, which a player made
        // without its constructor has none of).
        Class<?> entity = net.minecraft.world.entity.Entity.class;
        set(entity, local, "position", player.position());
        set(entity, local, "blockPosition", player.blockPosition());
        set(entity, local, "chunkPosition", player.chunkPosition());
        set(entity, local, "bb", player.getBoundingBox());
        // Its eyes' height and size (getEyePosition: the camera, Jade's ray; at 0 the ray
        // starts at the feet, inside the block stood on, and reads the one under it).
        set(entity, local, "eyeHeight", player.getEyeHeight());
        set(entity, local, "dimensions", field(entity, player, "dimensions"));
        local.xo = local.xOld = player.getX();
        local.yo = local.yOld = player.getY();
        local.zo = local.zOld = player.getZ();
        set(entity, local, "yRot", player.getYRot());
        set(entity, local, "xRot", player.getXRot());
        local.yRotO = player.getYRot();
        local.xRotO = player.getXRot();
        local.yHeadRot = player.getYHeadRot();
        local.yBodyRot = player.yBodyRot;
        // Its keys (LocalPlayer.isShiftKeyDown reads them): the sneak key as the server
        // player has it.
        if (local.input == null) local.input = new net.minecraft.client.player.ClientInput();
        local.input.keyPresses = new net.minecraft.world.entity.player.Input(
                false, false, false, false, false, player.isShiftKeyDown(), false);
        return local;
    }

    /**
     * The client's recipe book (what ClientboundRecipeBookAddPacket fills): every recipe the
     * server shows in recipe books, as Horizon's player unlocks recipes on its side.
     */
    static net.minecraft.client.ClientRecipeBook recipeBook(net.minecraft.server.level.ServerPlayer player) {
        var book = new net.minecraft.client.ClientRecipeBook();
        var recipes = player.level().getServer().getRecipeManager();
        for (var holder : recipes.getRecipes()) {
            // A display without a category (a mod's recipe type that answers null) couldn't
            // cross vanilla's network codec, so a real client never sees it: skipped, or the
            // whole book fails to build.
            recipes.listDisplaysForRecipe(holder.id(), entry -> {
                if (entry.category() != null) book.add(entry);
            });
        }
        book.rebuildCollections();
        searchTrees(book, player.level());
        return book;
    }

    /** A connection that is only its search trees (the recipe book's search): never sends. */
    private static net.minecraft.client.multiplayer.ClientPacketListener searchConnection;

    public static synchronized net.minecraft.client.multiplayer.ClientPacketListener searchConnection() {
        return searchConnection;
    }

    /** A mod's own type (vanilla's and the JDK's fields the client sets itself). */
    private static boolean modType(Class<?> type) {
        String name = type.getName();
        return !type.isPrimitive() && !type.isArray() && !name.startsWith("net.minecraft.") && !name.startsWith("com.mojang.")
                && !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("org.joml.")
                && !name.startsWith("it.unimi.");
    }

    /**
     * The server player's fields where the client player's are empty (Entity, LivingEntity,
     * Avatar, Player): the player is the server's; its client copy shares what it lacks
     * (Trinkets' attachment, the arm's swing state Better Combat reads).
     */
    private static void copyModFields(net.minecraft.server.level.ServerPlayer player, net.minecraft.client.player.LocalPlayer local) {
        for (Class<?> c = net.minecraft.world.entity.player.Player.class; c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(local) == null && f.get(player) != null) f.set(local, f.get(player));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // A field it can't reach stays empty.
                }
            }
        }
    }

    /**
     * The client player's empty mod-typed fields, up its classes: from a no-argument method of
     * its classes that makes one ("create..."), or a constructor of the field's class taking
     * the player.
     */
    private static void makeModFields(net.minecraft.client.player.LocalPlayer local, boolean byFactory) {
        java.util.List<java.lang.reflect.Method> factories = new java.util.ArrayList<>();
        for (Class<?> c = local.getClass(); c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && !java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getName().toLowerCase(java.util.Locale.ROOT).contains("create") && modType(m.getReturnType())) {
                    factories.add(m);
                }
            }
        }
        for (Class<?> c = local.getClass(); c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !modType(f.getType()) || f.getType().isInterface()) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(local) != null) continue;
                    Object made = null;
                    for (java.lang.reflect.Method m : byFactory ? factories : java.util.List.<java.lang.reflect.Method>of()) {
                        if (m.getReturnType() != f.getType()) continue;
                        m.setAccessible(true);
                        made = m.invoke(local);
                        break;
                    }
                    if (made == null && !byFactory) {
                        for (var make : f.getType().getDeclaredConstructors()) {
                            Class<?>[] params = make.getParameterTypes();
                            if (params.length == 1 && params[0].isInstance(local)) {
                                make.setAccessible(true);
                                made = make.newInstance(local);
                                break;
                            }
                        }
                    }
                    if (made != null) f.set(local, made);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    // Left empty.
                }
            }
        }
    }

    /** Recipe search, over the recipe book every player's client has (they all know all). */
    private static synchronized void searchTrees(net.minecraft.client.ClientRecipeBook book,
                                                 net.minecraft.world.level.Level level) {
        var connection = connection(level);
        if (connection == null || connection.searchTrees() != null) return;
        try {
            var trees = new net.minecraft.client.multiplayer.SessionSearchTrees();
            trees.updateRecipes(book, level);
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, connection, "searchTrees", trees);
        } catch (Throwable t) {
            Natives.log("Horizon's client: no recipe search: " + t);
        }
    }

    /**
     * A client's network connection as single player's is: over memory to a server in this
     * process. Its channel is closed (nothing is written on it; sends go nowhere, as with no
     * channel) but answers its address as vanilla's memory channel does: a LocalAddress (Simple
     * Voice Chat connects its voice to the address of the game's connection: 127.0.0.1 here).
     */
    static net.minecraft.network.Connection memoryConnection(net.minecraft.network.Connection connection) {
        var channel = new io.netty.channel.embedded.EmbeddedChannel() {
            @Override
            protected java.net.SocketAddress remoteAddress0() {
                return new io.netty.channel.local.LocalAddress("horizon-server");
            }

            @Override
            protected java.net.SocketAddress localAddress0() {
                return new io.netty.channel.local.LocalAddress("horizon-client");
            }
        };
        channel.remoteAddress();
        channel.localAddress();
        channel.close();
        try {
            set(net.minecraft.network.Connection.class, connection, "channel", channel);
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's client: its connection has no address: " + e);
        }
        return connection;
    }

    /**
     * The client's connection (one for every player): one that never sends, with the server's
     * registries and features (what a client gets as it joins). Mods reach them through it:
     * through the client level's connection (REI's displays, once there is a level), else the
     * game mode's.
     */
    static synchronized net.minecraft.client.multiplayer.ClientPacketListener connection(net.minecraft.world.level.Level level) {
        if (searchConnection != null) return searchConnection;
        try {
            var connection = (net.minecraft.client.multiplayer.ClientPacketListener)
                    Natives.allocate(net.minecraft.client.multiplayer.ClientPacketListener.class);
            var server = HorizonServer.server();
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, connection, "registryAccess",
                    server != null ? server.registryAccess() : level.registryAccess());
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, connection, "enabledFeatures",
                    level.enabledFeatures());
            // Its network connection, with no channel (mods ask it: getConnection(), isMemoryConnection):
            // what is sent on it goes nowhere (ClientCommonPacketListenerMixin).
            set(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class, connection, "connection",
                    Natives.allocate(net.minecraft.network.Connection.class));
            // Its Minecraft (ClientCommonPacketListenerImpl.minecraft): mods' injections into its
            // handlers read the client through it (Architectury's join event: minecraft.player).
            set(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class, connection, "minecraft", minecraft);
            // Its world clocks (a client level's clockManager(): mods' environment attributes,
            // Xaero's sun brightness).
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, connection, "clockManager",
                    new net.minecraft.client.ClientClockManager());
            // Its waypoints (the HUD's locator bar asks hasWaypoints: Create's wrapped hotbar
            // runs vanilla's contextual info): none, as before the server tracks any.
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, connection, "waypointManager",
                    new net.minecraft.client.waypoints.ClientWaypointManager());
            players(connection);
            // Its scoreboard (ClientLevel.getScoreboard() is the connection's): an empty one, as
            // a client's is before the server sends any (Cardinal Components' client tick).
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, connection, "scoreboard",
                    new net.minecraft.world.scores.Scoreboard());
            // Its network connection: a mirror one, as the players' are (connected, not
            // connecting: MirrorConnectionMixin), over memory (memoryConnection).
            set(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class, connection, "connection",
                    HorizonServer.clientConnection());
            if (minecraft.gameMode != null) {
                set(net.minecraft.client.multiplayer.MultiPlayerGameMode.class, minecraft.gameMode, "connection", connection);
            }
            searchConnection = connection;
        } catch (Throwable t) {
            Natives.log("Horizon's client: no client connection: " + t);
        }
        return searchConnection;
    }

    /** Each player's client's chunks: the client level they are of, and their positions. */
    private static final java.util.Map<java.util.UUID, Object[]> CLIENT_CHUNKS = new java.util.concurrent.ConcurrentHashMap<>();
    /** At most this many chunks are made known to a player's client a tick (copies are made). */
    private static final int CHUNKS_A_TICK = 24;

    /**
     * A player's client's chunks, as the server sends them: those of Horizon's within the
     * login's radius around the player, nearest first, are loaded (ClientChunkEvents.CHUNK_LOAD,
     * a few a tick), and those beyond it unloaded (CHUNK_UNLOAD), as a client's chunk cache does.
     */
    private static void clientChunks(net.minecraft.server.level.ServerPlayer player) {
        if (!(minecraft.level instanceof net.minecraft.client.multiplayer.ClientLevel level)
                || !(player.level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        Object[] state = CLIENT_CHUNKS.get(player.getUUID());
        @SuppressWarnings("unchecked")
        java.util.Map<Long, net.minecraft.world.level.chunk.LevelChunk> have = state == null ? null
                : (java.util.Map<Long, net.minecraft.world.level.chunk.LevelChunk>) state[1];
        if (state == null || state[0] != level) {
            // Another dimension: the last one's chunks go.
            if (state != null) unloadAll((net.minecraft.client.multiplayer.ClientLevel) state[0], have);
            have = new java.util.HashMap<>();
            CLIENT_CHUNKS.put(player.getUUID(), new Object[] {level, have});
        }
        int cx = player.chunkPosition().x(), cz = player.chunkPosition().z(), radius = LOGIN_RADIUS;
        // Those out of reach go first.
        var it = have.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            int x = net.minecraft.world.level.ChunkPos.getX(entry.getKey()), z = net.minecraft.world.level.ChunkPos.getZ(entry.getKey());
            if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) > radius + 1) {
                it.remove();
                var chunk = entry.getValue();
                HorizonServer.fireEach("ClientChunkEvents.CHUNK_UNLOAD",
                        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_UNLOAD, l -> l.onChunkUnload(level, chunk));
            }
        }
        int made = 0;
        for (int ring = 0; ring <= radius && made < CHUNKS_A_TICK; ring++) {
            for (int dx = -ring; dx <= ring && made < CHUNKS_A_TICK; dx++) {
                for (int dz = -ring; dz <= ring && made < CHUNKS_A_TICK; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    long key = net.minecraft.world.level.ChunkPos.pack(cx + dx, cz + dz);
                    if (have.containsKey(key)) continue;
                    var chunk = HorizonChunks.chunk(server, cx + dx, cz + dz, level);
                    if (chunk == null) continue;
                    have.put(key, chunk);
                    made++;
                    HorizonServer.fireEach("ClientChunkEvents.CHUNK_LOAD",
                            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_LOAD, l -> l.onChunkLoad(level, chunk));
                }
            }
        }
    }

    private static void unloadAll(net.minecraft.client.multiplayer.ClientLevel level,
                                  java.util.Map<Long, net.minecraft.world.level.chunk.LevelChunk> have) {
        for (var chunk : have.values()) {
            HorizonServer.fireEach("ClientChunkEvents.CHUNK_UNLOAD",
                    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_UNLOAD, l -> l.onChunkUnload(level, chunk));
        }
        have.clear();
    }

    /** A connection's player list (getPlayerInfo: JourneyMap's skins), empty. */
    static void players(net.minecraft.client.multiplayer.ClientPacketListener connection) throws ReflectiveOperationException {
        Class<?> owner = net.minecraft.client.multiplayer.ClientPacketListener.class;
        set(owner, connection, "playerInfoMap", new java.util.concurrent.ConcurrentHashMap<>());
        set(owner, connection, "listedPlayers", new java.util.LinkedHashSet<>());
    }

    /** Runs {@code run} with the player's own client player as Minecraft.player. */
    /**
     * The server's services, with profiles of the players on it first: the server's resolver
     * asks Mojang, which knows no offline (or Horizon) player, so a client screen naming one
     * (Chunk Loaders' owner) would show no name. A client knows the players it sees.
     */
    private static net.minecraft.server.Services clientServices(net.minecraft.server.MinecraftServer server) {
        var services = server.services();
        var resolver = services.profileResolver();
        var players = new net.minecraft.server.players.ProfileResolver() {
            @Override
            public java.util.Optional<com.mojang.authlib.GameProfile> fetchByName(String name) {
                for (var online : HorizonServer.players()) {
                    if (online.getGameProfile().name().equalsIgnoreCase(name)) return java.util.Optional.of(online.getGameProfile());
                }
                return resolver == null ? java.util.Optional.empty() : resolver.fetchByName(name);
            }

            @Override
            public java.util.Optional<com.mojang.authlib.GameProfile> fetchById(java.util.UUID id) {
                var online = HorizonServer.player(id);
                if (online != null) return java.util.Optional.of(online.getGameProfile());
                // One that was on (UserNameToIdResolver: usercache.json's names).
                var cached = services.nameToIdCache() == null ? null : services.nameToIdCache().get(id).orElse(null);
                if (cached != null) return java.util.Optional.of(new com.mojang.authlib.GameProfile(cached.id(), cached.name()));
                return resolver == null ? java.util.Optional.empty() : resolver.fetchById(id);
            }
        };
        return new net.minecraft.server.Services(services.sessionService(), services.servicesKeySet(),
                services.profileRepository(), services.nameToIdCache(), players);
    }

    private static java.lang.reflect.Field attachments;
    private static boolean noAttachments;

    /**
     * Fabric's data attachments (a map Fabric API mixes into Entity: Carry On's carry data,
     * which the server sets), the server player's on a client copy of it, as a client would
     * have them synced. Again each time: the server makes its map when it first attaches.
     */
    static void shareAttachments(net.minecraft.world.entity.Entity from, net.minecraft.world.entity.Entity to) {
        if (noAttachments) return;
        try {
            if (attachments == null) {
                attachments = net.minecraft.world.entity.Entity.class.getDeclaredField("dataAttachments");
                attachments.setAccessible(true);
            }
            Object map = attachments.get(from);
            if (map != null) attachments.set(to, map);
        } catch (ReflectiveOperationException | RuntimeException e) {
            noAttachments = true;
        }
    }

    /** The player whose client ran last (as()): a mod thread's client messages are theirs. */
    private static volatile net.minecraft.server.level.ServerPlayer lastPlayer;

    static net.minecraft.server.level.ServerPlayer lastPlayer() {
        return lastPlayer;
    }

    static void as(net.minecraft.server.level.ServerPlayer player, ThrowingRunnable run) throws Throwable {
        lastPlayer = player;
        var before = minecraft.player;
        var screenBefore = minecraft.gui.screen();
        var local = localPlayer(player);
        shareAttachments(player, local);
        set(Minecraft.class, minecraft, "player", local);
        // Mods' client state is theirs (HorizonClientState: REI's search box, overlay...).
        HorizonClientState.load(player.getUUID());
        // The services (profiles by id: Chunk Loaders' owner names): the mirrored server's.
        if (minecraft.services() == null && player.level().getServer() != null) {
            set(Minecraft.class, minecraft, "services", clientServices(player.level().getServer()));
        }
        // Their client stands in their dimension's client level (Minecraft.level: what their
        // screens and client code read, Chunk Loaders' grid its loaders), as a client in a world.
        var levelBefore = minecraft.level;
        var localLevel = local.level();
        var level = clientLevel(player.level());
        minecraft.level = level;
        set(net.minecraft.world.entity.Entity.class, local, "level", level);
        // Their connection's level too (ClientPacketListener.getLevel: a connection with no
        // level is one still logging in, and Simple Voice Chat sent nothing on it).
        if (local.connection != null && level != null) {
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, local.connection, "level", level);
        }
        // Their open screen is the GUI's (Minecraft.gui.screen()), which mods' client code looks at (a message
        // for the screen that is open: Tom's Storage's terminal's item list).
        var screen = SCREENS.get(player.getUUID());
        if (screen == null) screen = OVERLAYS.get(player.getUUID());
        if (screen != null) set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", screen);
        // Their window has the focus and, with no screen open, the mouse (MouseHandler.grabMouse
        // as a screen closes): mods check both before acting on input (TACZ's guns fire only
        // "in game": no screen, mouse grabbed, window active).
        boolean grabbedBefore = grabMouse(minecraft.gui.screen() == null);
        // Their window (GUI size and scale, framebuffer): mods' size maths and pictures in
        // picture follow the player whose client runs.
        int[] windowBefore = windowNow(), window = WINDOWS.get(player.getUUID());
        boolean otherWindow = window != null && !java.util.Arrays.equals(window, windowBefore);
        if (otherWindow) applyWindow(window, false);
        try {
            run.run();
        } finally {
            if (otherWindow) applyWindow(windowBefore, false);
            // Back to the outer run's player's state (as() within another player's as()).
            if (before != null) HorizonClientState.load(before.getUUID());
            set(Minecraft.class, minecraft, "player", before);
            set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", screenBefore);
            grabMouse(grabbedBefore);
            minecraft.level = levelBefore;
            set(net.minecraft.world.entity.Entity.class, local, "level", localLevel);
            if (before == null) rest();
        }
    }

    /**
     * Between players' runs the client is its own player's, as a real client always is, when
     * this process's game has a player and a device mod runs here (HorizonDeviceMods): such a
     * mod's own threads read Minecraft.player and .level at any time (Simple Voice Chat's
     * speakers play only while there is a player and a level, and place voices by it).
     */
    static void rest() {
        var own = HorizonDeviceMods.own;
        if (own == null || minecraft == null || HorizonDeviceMods.mods().isEmpty()) return;
        var player = HorizonServer.player(own);
        if (player == null) return;
        try {
            var local = localPlayer(player);
            var level = clientLevel(player.level());
            if (local == null || level == null) return;
            set(Minecraft.class, minecraft, "player", local);
            minecraft.level = level;
            set(net.minecraft.world.entity.Entity.class, local, "level", level);
            if (local.connection != null) {
                set(net.minecraft.client.multiplayer.ClientPacketListener.class, local.connection, "level", level);
            }
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's client: its own player couldn't stay its client's: " + e);
        }
    }

    /**
     * A player left the server: their client's state goes (their screens, overlay, mods' kept
     * state), so a player joining later with their name starts afresh.
     */
    static void left(java.util.UUID id) {
        HorizonPacketTap.left(id);
        WINDOWS.remove(id);
        JOINED.remove(id);
        HorizonDeviceMods.RUN_THEIR_OWN.remove(id);
        HorizonLoop.forget(id);
        CLIENT_CHUNKS.remove(id);
        HorizonClientState.forget(id);
        var overlay = OVERLAYS.remove(id);
        OVERLAY_SENT.remove(id);
        OVERLAY_MOUSE.remove(id);
        DRAWN.remove(id);
        if (overlay != null) {
            try {
                overlay.removed();
            } catch (Throwable t) {
                // Gone anyway.
            }
        }
        if (SCREENS.containsKey(id)) closeScreen(id);
    }

    interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final java.util.Map<java.util.UUID, net.minecraft.server.level.ServerPlayer> OWNERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A mod's menu opened for {@code player}: its own screen (MenuScreens' constructor for the
     * menu's type, which its mod registered in its client code), made on the server's menu
     * itself (the screen reads what the menu holds as it is). False when it has none.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean openScreen(net.minecraft.server.level.ServerPlayer player,
                                     net.minecraft.world.inventory.AbstractContainerMenu menu,
                                     net.minecraft.network.chat.Component title) {
        if (minecraft == null || minecraft.font == null) return false;
        try {
            Field field = net.minecraft.client.gui.screens.MenuScreens.class.getDeclaredField("SCREENS");
            field.setAccessible(true);
            Object constructor = ((java.util.Map) field.get(null)).get(menu.getType());
            if (constructor == null) return false;
            var create = constructor.getClass().getMethod("create",
                    net.minecraft.world.inventory.AbstractContainerMenu.class,
                    net.minecraft.world.entity.player.Inventory.class, net.minecraft.network.chat.Component.class);
            create.setAccessible(true);
            // The client's own menu where its type makes one (MenuType.create, as a client does on
            // the open-screen packet), kept as the server's is (syncClientMenu): a mod whose
            // menu keeps state of its own on each side (Tom's Storage's terminal's item list)
            // needs both. Else (a Fabric extended menu, whose client constructor reads the
            // client's level) the screen shows the server's menu itself.
            net.minecraft.world.inventory.AbstractContainerMenu[] made = {null};
            as(player, () -> made[0] = clientMenu(menu, player));
            var shown = made[0] != null ? made[0] : menu;
            if (made[0] != null) CLIENT_MENUS.put(player.getUUID(), made[0]);
            else CLIENT_MENUS.remove(player.getUUID());
            var screen = (net.minecraft.client.gui.screens.Screen) create.invoke(constructor, shown, player.getInventory(), title);
            // A new screen's first frame always goes, even one drawn as the last screen's was (the
            // same pot opened again, nothing moved): its window is a new one in Horizon's game,
            // which shows its stand-in until a frame for that window comes.
            SENT.remove(player.getUUID());
            UNDER.remove(player.getUUID());
            // Vanilla's order (Minecraft.setScreen): the GUI's screen is the new one, added, then
            // laid out (mods make their overlays for it there: Fabric's screen init events).
            as(player, () -> {
                set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", screen);
                screen.added();
                screen.init(width, height);
            });
            SCREENS.put(player.getUUID(), screen);
            OWNERS.put(player.getUUID(), player);
            return true;
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the screen of " + net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(menu.getType())
                    + " could not open: " + root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
            return false;
        }
    }

    public static void closeScreen(java.util.UUID player) {
        UNDER.remove(player);
        CLIENT_ONLY.remove(player);
        CLIENT_MENUS.remove(player);
        SCREENS.remove(player);
        OWNERS.remove(player);
        SENT.remove(player);
        MOUSE.remove(player);
        DRAWN.remove(player);
    }

    /** Players whose open screen is a client-only one (no menu under it: Gui.setScreen's). */
    private static final java.util.Set<java.util.UUID> CLIENT_ONLY = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The menu's screen under a player's client-only screen (JEI's recipes opened from the
     * pot's screen) and the client menu it showed: back when the client-only screen closes,
     * to its parent or to none, while the menu is still open.
     */
    private record Under(net.minecraft.client.gui.screens.Screen screen,
                         net.minecraft.world.inventory.AbstractContainerMenu clientMenu) {}
    private static final java.util.Map<java.util.UUID, Under> UNDER = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Gui.setScreen in the headless client (GuiMixin): a mod's client code opens a screen of its
     * own, with no menu (Nature's Compass's biome list, a config screen, a guide book), or
     * closes it (null). It is the screen of the player whose client runs now (Minecraft.player:
     * their item's use, a message to their client, their screen's input). Horizon's window shows
     * it as a menu's (Natives.clientScreen, then frames).
     */
    public static void setScreen(net.minecraft.client.gui.screens.Screen screen) {
        // A device client's screen (its player's own game shows it: HorizonDeviceClient).
        if (HorizonDeviceClient.on()) {
            HorizonDeviceClient.setScreen(screen);
            return;
        }
        var player = serverPlayer(minecraft.player);
        if (player == null) {
            if (screen != null) {
                Natives.log("Horizon's client: " + screen.getClass().getName()
                        + " was opened with no player's client running; not shown");
            }
            return;
        }
        java.util.UUID id = player.getUUID();
        // The screen under Horizon's inventory, as a screen's parent (REI's recipe view, closed:
        // back to the screen it opened over): the client-only screen closes, and the player's
        // game goes back to its inventory.
        if (screen instanceof HorizonOverlayScreen) screen = null;
        // A mod's settings screen going back to its parent (Done): Horizon's Mods page.
        if (screen instanceof HorizonModsPage) screen = null;
        var current = SCREENS.get(id);
        if (screen == current) return;
        boolean clientOnly = CLIENT_ONLY.contains(id);
        if (current != null && !clientOnly && screen == null) {
            // A menu's screen closing itself (a Done button): the menu closes, as
            // LocalPlayer.closeContainer has the server do; the window follows.
            player.closeContainer();
            return;
        }
        try {
            if (current != null && clientOnly) HorizonScreenEvents.removed(current);
            // Back to the menu's own screen from a client-only one over it (JEI's recipes
            // closing to their parent, or closed with none) while the menu is open: the menu's
            // window shows it again. Before, the menu's screen was gone (Horizon's stand-in
            // showed, its one row of slots) and its window no longer heard of.
            Under under = UNDER.get(id);
            if (clientOnly && under != null && (screen == null || screen == under.screen())
                    && player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
                UNDER.remove(id);
                CLIENT_ONLY.remove(id);
                if (under.clientMenu() != null) CLIENT_MENUS.put(id, under.clientMenu());
                SCREENS.put(id, under.screen());
                SENT.remove(id);
                // Set again as vanilla's setScreen sets a parent: added, laid out (the recipe
                // viewer's overlay made for it again).
                set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", under.screen());
                under.screen().added();
                under.screen().init(width, height);
                Natives.clientScreen(id.toString(), false);
                return;
            }
            if (screen == null) {
                closeScreen(id);
                Natives.clientScreen(id.toString(), false);
                return;
            }
            // Over a menu's screen (a button that opens a mod's settings), the menu stays open
            // on the server, as vanilla's does until the client says otherwise; its screen is
            // kept for when this one closes.
            if (current != null && !clientOnly) UNDER.put(id, new Under(current, CLIENT_MENUS.get(id)));
            CLIENT_MENUS.remove(id);
            SCREENS.put(id, screen);
            OWNERS.put(id, player);
            CLIENT_ONLY.add(id);
            SENT.remove(id);
            // Vanilla's order: the GUI's screen is the new one, added, then laid out in the
            // GUI's size. (as() puts the GUI's screen back when the caller's run ends.)
            set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", screen);
            screen.added();
            screen.init(width, height);
            Natives.clientScreen(id.toString(), true);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: " + (screen == null ? "a screen could not close" : screen.getClass().getName()
                    + " could not open") + ": " + root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
            if (screen != null && SCREENS.get(id) == screen) {
                closeScreen(id);
                Natives.clientScreen(id.toString(), false);
            }
        }
    }

    /** A client-only screen shut from outside it (the window closed it): no screen. */
    private static void shut(java.util.UUID id, net.minecraft.server.level.ServerPlayer player) throws Throwable {
        if (!CLIENT_ONLY.contains(id)) return;
        as(player, () -> setScreen(null));
    }

    /** A client player's own message (LocalPlayerMessagesMixin): to its player's game. */
    public static boolean clientMessage(net.minecraft.client.player.LocalPlayer local,
                                        net.minecraft.network.chat.Component message, boolean overlay) {
        var player = SERVER_PLAYERS.get(local);
        if (player == null) return HorizonDeviceClient.message(local, message, overlay);
        String json = HorizonServer.chatJson(message);
        if (overlay) Natives.overlay(player.getStringUUID(), json);
        else Natives.tell(player.getStringUUID(), json);
        return true;
    }

    /**
     * The client half of using a mod's block (MultiPlayerGameMode.performUseItemOn, after the
     * server's): Fabric's UseBlockCallback, then the block's useItemOn and, passed to an empty
     * hand, useWithoutItem, on the player's client player with the level answering
     * isClientSide() true. What it opens is the player's screen; what it would change in the
     * world is the client's prediction, dropped (HorizonServer.setBlock): the server's is
     * made. The item's own useOn is left out (a placement predicted on the shared inventory).
     */
    public static void useBlock(net.minecraft.server.level.ServerPlayer player,
                                net.minecraft.world.InteractionHand hand,
                                net.minecraft.world.phys.BlockHitResult hit) {
        if (minecraft == null || minecraft.font == null) return;
        try {
            as(player, () -> {
                var local = minecraft.player;
                var level = local.level();
                HorizonServer.clientHalf = Thread.currentThread();
                try {
                    var event = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT;
                    Object[] listeners = HorizonServer.listeners(event);
                    for (Object listener : listeners != null ? listeners : new Object[] {event.invoker()}) {
                        var r = ((net.fabricmc.fabric.api.event.player.UseBlockCallback) listener).interact(local, level, hand, hit);
                        if (r != net.minecraft.world.InteractionResult.PASS) return;
                    }
                    var state = level.getBlockState(hit.getBlockPos());
                    var stack = local.getItemInHand(hand);
                    boolean secondary = local.isSecondaryUseActive() && !(local.getMainHandItem().isEmpty() && local.getOffhandItem().isEmpty());
                    if (secondary) return;
                    var r = state.useItemOn(stack, level, local, hand, hit);
                    if (r.consumesAction()) return;
                    if (r instanceof net.minecraft.world.InteractionResult.TryEmptyHandInteraction
                            && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                        state.useWithoutItem(level, local, hit);
                    }
                } finally {
                    HorizonServer.clientHalf = null;
                }
            });
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the client side of using a block failed: " + root);
            HorizonFailures.report("using a block", root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
        }
    }

    /**
     * The client half of a block click that comes before the server's, in vanilla's order: a
     * vanilla client runs MultiPlayerGameMode.useItemOn first, and the mods' injections at its
     * head and before its prediction (startPrediction) may end the click there, on the client,
     * with no packet to the server: a mod item's selection made by right-clicking blocks (a
     * machine arm's points, a chain's first end), a value box set, glue's corner. They send
     * their own packets for the server's half. Then those inside performUseItemOn (the
     * prediction, before the packet). Vanilla's own bodies aren't run here: the server's are,
     * and the inventory is shared. Fabric API's own are left out (its UseBlockCallback's client
     * half runs in useBlock). The result when a mod ended the click on the client; else null.
     */
    public static net.minecraft.world.InteractionResult useBlockFirst(net.minecraft.server.level.ServerPlayer player,
                                                                      net.minecraft.world.InteractionHand hand,
                                                                      net.minecraft.world.phys.BlockHitResult hit) {
        if (minecraft == null || minecraft.font == null || minecraft.gameMode == null) return null;
        // The use key reads down for the click's next client ticks (Create's value boxes open
        // their settings while it is held).
        USE_HELD.put(player.getUUID(), USE_TICKS);
        var gameModeClass = net.minecraft.client.multiplayer.MultiPlayerGameMode.class;
        if (!HorizonHooks.hasMerged(gameModeClass, "useItemOn", "HEAD")
                && !HorizonHooks.hasMerged(gameModeClass, "useItemOn", PREDICTION)
                && !HorizonHooks.hasMergedInside(gameModeClass, "performUseItemOn")) return null;
        net.minecraft.world.InteractionResult[] result = {null};
        try {
            inWorld(player, () -> withKeys(player.getUUID(), () -> {
                var local = minecraft.player;
                if (local == null) return;
                HorizonServer.clientHalf = Thread.currentThread();
                try {
                    var gameMode = minecraft.gameMode;
                    for (String at : new String[] {"HEAD", PREDICTION}) {
                        var fired = HorizonHooks.fireMergedSkipping(gameMode, gameModeClass, "useItemOn", at, true, local, hand, hit);
                        if (fired.cancelled()) {
                            result[0] = fired.returnValue() instanceof net.minecraft.world.InteractionResult r
                                    ? r : net.minecraft.world.InteractionResult.PASS;
                            if (DEBUG_USE) Natives.log("use: the client took the click at " + at + " of useItemOn: " + result[0]);
                            return;
                        }
                    }
                    // The prediction (performUseItemOn): its result is the client's; the packet
                    // goes to the server whatever it is, so the server's half follows.
                    var fired = HorizonHooks.fireMergedInside(gameMode, gameModeClass, "performUseItemOn", true,
                            local, hand, hit, hit.getBlockPos(), local.getItemInHand(hand));
                    if (DEBUG_USE && fired.cancelled()) Natives.log("use: the client's prediction: " + fired.returnValue());
                    tasks();
                } finally {
                    HorizonServer.clientHalf = null;
                }
            }));
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the client side of a block click failed: " + root);
            HorizonFailures.report("using a block", root);
        }
        return result[0];
    }

    /** MultiPlayerGameMode.useItemOn's call that predicts the click and sends its packet. */
    private static final String PREDICTION = "INVOKE Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction";

    private static final boolean DEBUG_USE = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("use");

    /**
     * The client half of using an item on nothing (MultiPlayerGameMode.useItem, after the
     * server's): Fabric's UseItemCallback, then the item's own use, on the player's client
     * player, with mirrored levels answering isClientSide() true (LevelMixin). What it opens
     * (Gui.setScreen) is the player's screen.
     */
    public static net.minecraft.world.InteractionResult useItem(net.minecraft.server.level.ServerPlayer player,
                                                                net.minecraft.world.InteractionHand hand) {
        if (minecraft == null || minecraft.font == null) return null;
        net.minecraft.world.InteractionResult[] result = {net.minecraft.world.InteractionResult.PASS};
        try {
            as(player, () -> {
                var local = minecraft.player;
                var level = local.level();
                HorizonServer.clientHalf = Thread.currentThread();
                try {
                    var event = net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT;
                    Object[] listeners = HorizonServer.listeners(event);
                    for (Object listener : listeners != null ? listeners : new Object[] {event.invoker()}) {
                        var r = ((net.fabricmc.fabric.api.event.player.UseItemCallback) listener).interact(local, level, hand);
                        if (r != net.minecraft.world.InteractionResult.PASS) {
                            result[0] = r;
                            return;
                        }
                    }
                    result[0] = local.getItemInHand(hand).use(level, local, hand);
                } finally {
                    HorizonServer.clientHalf = null;
                }
            });
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the client side of using "
                    + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(player.getItemInHand(hand).getItem())
                    + " failed: " + root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
        }
        return result[0];
    }

    /**
     * The client half of a player's click on an entity (Minecraft.startAttack, which the attack
     * key calls): the mods' injections at its head run on the player's client, looking at what
     * the player looks at, its attack strength Horizon's swing ({@code ticks} since it). A mod
     * that cancels it takes the click: its own attack follows, as its client sends it (its
     * payloads reach the server's half; the hits land on the mirror's entities, Horizon's mobs'
     * shadows among them). Whether one took it.
     */
    /** Each player's client ticks left with the attack key down (a click's press). */
    private static final java.util.Map<java.util.UUID, Integer> ATTACK_HELD = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Vanilla's use key, down while the player's right click lasts: their game sends a use
     * every 4 ticks while it is held (rightClickDelay), so it is down for 6 ticks after each
     * (Create's value boxes open their settings when the click is held).
     */
    private static final java.util.Map<java.util.UUID, Integer> USE_HELD = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int USE_TICKS = 6;
    /** Players whose use button is down, as their game told (bindMouse: when mods take every button). */
    private static final java.util.Set<java.util.UUID> USE_DOWN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final int CLICK_TICKS = Integer.getInteger("horizon.clickTicks", 2);

    public static boolean startAttack(net.minecraft.server.level.ServerPlayer player, int ticks) {
        if (minecraft == null || minecraft.font == null) return false;
        if (!HorizonHooks.hasMerged(Minecraft.class, "startAttack", "HEAD")) return false;
        boolean[] taken = {false};
        try {
            inWorld(player, () -> withKeys(player.getUUID(), () -> {
                HorizonServer.clientHalf = Thread.currentThread();
                try {
                    if (minecraft.player != null) HorizonCombat.setStrength(minecraft.player, ticks);
                    taken[0] = HorizonHooks.fireMerged(minecraft, Minecraft.class, "startAttack", "HEAD").cancelled();
                    // The click's press: the attack key reads down for its next client ticks.
                    if (taken[0]) ATTACK_HELD.put(player.getUUID(), CLICK_TICKS);
                } finally {
                    HorizonServer.clientHalf = null;
                }
            }));
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the client side of an attack failed: " + root);
            HorizonFailures.report("an attack", root);
        }
        return taken[0];
    }

    // ---- The client's tick ------------------------------------------------------------------

    /**
     * One tick of the headless client, as Minecraft.runTick runs one per frame and
     * Minecraft.tick one per game tick: Fabric's ClientTickEvents (START, END) and mods'
     * injections into runTick and tick (HorizonHooks.fireClient: Xaero's lib finishes loading at
     * runTick's renderFrame). The client has no level and no player of its own (Horizon's window
     * plays), as a client at its title screen: mods' tick code checks for both. Before the
     * server's tick, so the first comes before SERVER_STARTING, as a client's frames come before
     * its integrated server starts.
     */
    public static void clientTick() {
        if (minecraft == null || minecraft.font == null) return;
        Thread.currentThread().setContextClassLoader(HorizonClient.class.getClassLoader());
        renderThread();
        // The game renderer's GPU parts, made on the render thread (the client's) once.
        if (!gpuParts) {
            gpuParts = true;
            if (HorizonGpu.installed()) guiRenderer(minecraft.gameRenderer);
        }
        tasks();
        // ClientLifecycleEvents.CLIENT_STARTED: Minecraft's first frame, before any world (Jade
        // finishes registering its tooltip providers there).
        if (!started) {
            started = true;
            reloadClientResources();
            // The GPU's shaders: the GUI pipelines' fallback cache over the client's resources
            // (vanilla's and the mods' shaders), as Minecraft's start makes it.
            if (HorizonGpu.installed() && minecraft.getResourceManager() != null) models();
            HorizonServer.fireEach("ClientLifecycleEvents.CLIENT_STARTED",
                    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED, l -> l.onClientStarted(minecraft));
            reloadAddedListeners();
        }
        finishModels();
        // Its loading is done once the models are (Minecraft.isGameLoadFinished: MiniHUD draws
        // nothing before).
        if (pendingModels == null && !minecraft.isGameLoadFinished()) {
            try {
                set(Minecraft.class, minecraft, "gameLoadFinished", true);
            } catch (ReflectiveOperationException e) {
                Natives.log("Horizon's client: its loading can't be marked done: " + e);
            }
        }
        // Each player's game is a client of its own: its tick runs as that player
        // (Minecraft.player, the open screen), with its own keys (bindings). With no player, a
        // client at its title screen.
        var players = HorizonServer.players();
        // While the client's models load, it stays at its title screen, as a vanilla client
        // loads its resources before it joins a world: mods that read the models when their
        // player joins (Diorama's block colours, built once) get them.
        if (players.isEmpty() || pendingModels != null) {
            rest(null);
            tickOnce();
            return;
        }
        // In a world: each player's client in turn, standing in their level and looking at what
        // they look at (the one client a vanilla game has), ticked, then its HUD drawn.
        java.util.Set<Object> tickedLevels = new java.util.HashSet<>();
        for (var player : players) {
            try {
                inWorld(player, () -> withKeys(player.getUUID(), () -> {
                    joined(player);
                    // What the server sent them, through their client connection (recording mods).
                    HorizonPacketTap.pump(player);
                    levelRendererReady();
                    // Once, with a player (mods' item models read it: Create's sand paper).
                    if (modelsLoaded) HorizonItemModels.exportOnce();
                    long t = HorizonProfile.start();
                    tickOnce();
                    // The camera's tick (GameRenderer.tick's): its field of view's modifier.
                    tickView(player.getUUID());
                    // The client level's own block entities of mod blocks (ClientLevel's tick of
                    // them), once a tick per level, as the first of its players.
                    if (minecraft.level != null) {
                        HorizonClientBlockEntities.near(minecraft.level, minecraft.player);
                        if (tickedLevels.add(minecraft.level)) HorizonClientBlockEntities.tick(minecraft.level);
                    }
                    ATTACK_HELD.computeIfPresent(player.getUUID(), (u, n) -> n > 1 ? n - 1 : null);
                    USE_HELD.computeIfPresent(player.getUUID(), (u, n) -> n > 1 ? n - 1 : null);
                    // Their client's ticking sounds (the sound engine's tick).
                    HorizonSounds.tick(player.getUUID());
                    t = HorizonProfile.add("client: tick", t);
                    // The chunks mods asked for on their own threads (JourneyMap's mapping).
                    HorizonChunks.serveWanted(64);
                    // Their client's chunks come and go (Fabric's ClientChunkEvents: Diorama maps
                    // the chunks it is told of).
                    clientChunks(player);
                    // A frame: the mods' work at its start and end (GameRenderer.extract), its HUD
                    // between.
                    HorizonHooks.fireFrame(minecraft.gameRenderer, true);
                    t = HorizonProfile.add("client: frame start", t);
                    sendHud(player);
                    // The camera's field of view in the frame (Camera.update), as the mods make it.
                    sendView(player.getUUID());
                    t = HorizonProfile.add("client: HUD", t);
                    HorizonHooks.fireFrame(minecraft.gameRenderer, false);
                    // Then the frame is drawn (GameRenderer.render): the mods' work at its head.
                    HorizonHooks.fireRender(minecraft.gameRenderer);
                    HorizonGpu.endFrame();
                    HorizonProfile.add("client: frame end", t);
                }));
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                if (HUD_FAILURES.merge("tick", 1, Integer::sum) <= 3) {
                    Natives.log("Horizon's client: a player's client tick failed: " + root);
                    HorizonFailures.report("the client's tick", root);
                    for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
                }
            }
        }
        HorizonClientBlockEntities.endTick();
        HorizonSounds.endTick();
        rest(players.get(0));
    }

    /**
     * Between ticks the client stands as the first player's (Minecraft.player, level, screen),
     * as a game's client always has one: mods' own threads read it (Xaero's World Map's map
     * runner finds its session through Minecraft.player). Ticks and frames still run as each
     * player in turn (as()).
     */
    private static void rest(net.minecraft.server.level.ServerPlayer player) {
        try {
            if (player == null) {
                if (minecraft.player != null) {
                    set(Minecraft.class, minecraft, "player", null);
                    minecraft.level = null;
                    set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", null);
                }
                return;
            }
            var local = localPlayer(player);
            var level = clientLevel(player.level());
            set(Minecraft.class, minecraft, "player", local);
            minecraft.level = level;
            set(net.minecraft.world.entity.Entity.class, local, "level", level);
            var screen = SCREENS.get(player.getUUID());
            if (screen == null) screen = OVERLAYS.get(player.getUUID());
            set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", screen);
        } catch (Throwable t) {
            Natives.log("Horizon's client: the client couldn't rest as a player: " + t);
        }
    }

    private static boolean started;

    /**
     * The client's block and item models, as Minecraft's resource reload makes them, now that
     * there is a GPU to stitch their atlases on: block colours (BlockColors.createDefault, with
     * the mods' through Fabric), the atlases (a real AtlasManager: blocks, items...) and a
     * ModelManager reloaded over the client resources (vanilla's and the mods' models). Mods
     * read them (Xaero's minimap: blocks' models and textures for its colours). The headless
     * GUI atlas stays (screens' sprites are Horizon's): the others join its atlas manager.
     */
    private static void models() {
        // A pack whose baked models don't fit still gets a model manager: its models are baked
        // as mods first ask for them (Xaero's World Map's frame hook read getModelManager() and
        // failed on null when the models were skipped).
        HorizonLazyModels.on = !modelsFit();
        long started = System.nanoTime();
        // The client's codecs' types (ClientBootstrap, Minecraft's main runs it): without
        // SpriteSources' an atlas's sources parse to nothing and every atlas held only the
        // missing texture (every model drew it); item models' and tints' too. Its last step
        // (Vulkan's feature sets) may fail here: the ones before it are in by then.
        try {
            net.minecraft.client.ClientBootstrap.bootstrap();
        } catch (Throwable t) {
            Natives.log("Horizon's client: the client's bootstrap stopped at " + t);
        }
        try {
            // The start's block colours (with the tints mods registered there), else new ones.
            var colors = minecraft.getBlockColors() != null ? minecraft.getBlockColors()
                    : net.minecraft.client.color.block.BlockColors.createDefault();
            set(Minecraft.class, minecraft, "blockColors", colors);
            var atlases = new net.minecraft.client.resources.model.sprite.AtlasManager(minecraft.getTextureManager(), 0);
            var skins = (net.minecraft.client.renderer.PlayerSkinRenderCache) Natives.allocate(net.minecraft.client.renderer.PlayerSkinRenderCache.class);
            // Minecraft's constructor makes its model manager at once, its models filled by the
            // reload: mods' client start reads it (TACZ's item renderers keep
            // getEntityModels(), a supplier over it). Loaded or not: modelsLoaded(). The one the
            // client was made with (standInModels) is the one reloaded.
            var models = minecraft.getModelManager();
            if (models == null) {
                models = new net.minecraft.client.resources.model.ModelManager(colors, atlases, skins);
                set(Minecraft.class, minecraft, "modelManager", models);
            } else {
                Class<?> manager = net.minecraft.client.resources.model.ModelManager.class;
                set(manager, models, "blockColors", colors);
                set(manager, models, "atlasManager", atlases);
                set(manager, models, "playerSkinRenderCache", skins);
            }
            // The real atlases beside the headless GUI one, from the start: mods ask the
            // client's atlas manager for them as they stitch (Xaero's, for the blocks').
            Class<?> owner = net.minecraft.client.resources.model.sprite.AtlasManager.class;
            var headless = minecraft.getAtlasManager();
            @SuppressWarnings("unchecked")
            var byId = (java.util.Map<Object, Object>) field(owner, headless, "atlasById");
            @SuppressWarnings("unchecked")
            var byTexture = (java.util.Map<Object, Object>) field(owner, headless, "atlasByTexture");
            @SuppressWarnings("unchecked")
            var realById = (java.util.Map<Object, Object>) field(owner, atlases, "atlasById");
            @SuppressWarnings("unchecked")
            var realByTexture = (java.util.Map<Object, Object>) field(owner, atlases, "atlasByTexture");
            realById.forEach((id, entry) -> {
                if (!id.equals(net.minecraft.data.AtlasIds.GUI)) byId.put(id, entry);
            });
            realByTexture.forEach((id, entry) -> {
                if (!id.equals(net.minecraft.client.renderer.Sheets.GUI_SHEET)) byTexture.put(id, entry);
            });
            // With the colour maps biome tints read (grass, foliage: GrassColor.pixels...).
            java.util.List<net.minecraft.server.packs.resources.PreparableReloadListener> listeners = new java.util.ArrayList<>(java.util.List.of(
                    atlases, models,
                    new net.minecraft.client.resources.GrassColorReloadListener(),
                    new net.minecraft.client.resources.FoliageColorReloadListener(),
                    new net.minecraft.client.resources.DryFoliageColorReloadListener()));
            // The mods' listeners that come after the models (Trinkets' client manager, whose
            // preparation the models' reload waits for: without it the reload never ended),
            // in the same reload as vanilla runs them; one that fails is left out alone.
            for (var listener : AFTER_MODELS) listeners.add(failingAlone(listener));
            // Prepared on the game's background workers, applied on the client's thread (the
            // server's: Minecraft.execute, which tasks() runs each tick), where the atlases
            // upload to the GPU (never a pool thread: "Rendersystem called from wrong thread",
            // no models and a broken GUI atlas).
            var reload = net.minecraft.server.packs.resources.SimpleReloadInstance.of(minecraft.getResourceManager(),
                    listeners, net.minecraft.util.Util.backgroundExecutor(), minecraft,
                    java.util.concurrent.CompletableFuture.completedFuture(net.minecraft.util.Unit.INSTANCE));
            // It runs on while the server ticks (finishModels each client tick): one that
            // waits for something it never gets (the 107-jar pack's) mustn't hold the server.
            pendingModels = new Object[] {reload, models, atlases, started, skins};
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the models couldn't be loaded: " + root);
            for (var f : root.getStackTrace()) Natives.log("    at " + f);
        }
    }

    /** Heap a block state's baked models take in the mirror (the 148-jar pack: ~700 MB at 577k). */
    private static final long MODEL_BYTES_PER_STATE = 1200;

    /**
     * Whether the mirror bakes every model at once: Horizon draws with its own, so this copy is
     * only for the mods that read them, and a big pack's (connected textures on every state)
     * doesn't fit beside the rest in a default heap: the heap ran out mid-bake and worldgen with
     * it. When not, they're baked on first use (HorizonLazyModels). HORIZON_JAVA_MODELS=on bakes
     * all at once anyway, off always on first use.
     */
    private static boolean modelsFit() {
        String mode = System.getenv("HORIZON_JAVA_MODELS");
        if ("on".equals(mode)) return true;
        if ("off".equals(mode)) {
            Natives.log("Horizon's client: the mods' models are baked on first use (HORIZON_JAVA_MODELS=off)");
            return false;
        }
        long states = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size();
        long need = states * MODEL_BYTES_PER_STATE;
        long live = 0;
        for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            // The old generation and survivors (Eden is mostly garbage): a little over what lives.
            if (pool.getType() != java.lang.management.MemoryType.HEAP || pool.getName().contains("Eden")) continue;
            var usage = pool.getUsage();
            if (usage != null) live += usage.getUsed();
        }
        long max = Runtime.getRuntime().maxMemory();
        if (live + need <= max * 85 / 100) return true;
        Natives.log("Horizon's client: the mods' models are baked on first use: " + states + " block states need ~"
                + need / 1_000_000 + " MB of Java heap, " + (max - live) / 1_000_000 + " MB is free of "
                + max / 1_000_000 + " (only the states mods read are baked; "
                + "HORIZON_JVM_OPTIONS=-Xmx<size> gives Java more, HORIZON_JAVA_MODELS=on bakes them all at once)");
        return false;
    }

    /** The mods' client reload listeners that come after the models (reloadClientResources). */
    private static final java.util.List<net.minecraft.server.packs.resources.PreparableReloadListener> AFTER_MODELS =
            new java.util.ArrayList<>();

    /** A mod's reload listener whose failure (after its preparation) is logged, not the reload's. */
    private static net.minecraft.server.packs.resources.PreparableReloadListener failingAlone(
            net.minecraft.server.packs.resources.PreparableReloadListener listener) {
        return (net.minecraft.server.packs.resources.PreparableReloadListener) java.lang.reflect.Proxy.newProxyInstance(
                HorizonClient.class.getClassLoader(),
                new Class<?>[] {net.minecraft.server.packs.resources.PreparableReloadListener.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(listener, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        if (!method.getName().equals("reload")) throw e.getCause();
                        // Failed at once: the others mustn't wait for it at the barrier.
                        Natives.log("Horizon's client: a mod's resource reload (" + listener.getClass().getName() + ") failed: " + e.getCause());
                        return ((net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier) args[2])
                                .wait(net.minecraft.util.Unit.INSTANCE).thenAccept(unit -> { });
                    }
                    if (result instanceof java.util.concurrent.CompletableFuture<?> future) {
                        return future.exceptionally(t -> {
                            Throwable root = t;
                            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                            Natives.log("Horizon's client: a mod's resource reload (" + listener.getClass().getName() + ") failed: " + root);
                            return null;
                        });
                    }
                    return result;
                });
    }

    /** Whether the client's models finished loading (its model manager is there from the start). */
    static boolean modelsLoaded() {
        return modelsLoaded;
    }

    /** The models' reload under way: reload, model manager, atlas manager, start time, skins. */
    private static Object[] pendingModels;

    /**
     * The client's model manager and item model resolver from its start, as Minecraft's
     * constructor makes them before its first resource reload: the manager holds no models
     * until the reload (models(): with a GPU), the resolver has no manager and keeps the items
     * it is asked for (ItemModelResolverMixin), as the entity renderers' does. Without them
     * the HUD's hotbar, the hands' tick and mods' client start failed on null (and with no GPU
     * they stayed null).
     */
    private static void standInModels() {
        try {
            if (minecraft.getModelManager() == null) {
                set(Minecraft.class, minecraft, "modelManager",
                        new net.minecraft.client.resources.model.ModelManager(null, null, null));
            }
            if (minecraft.getItemModelResolver() == null) {
                set(Minecraft.class, minecraft, "itemModelResolver", new net.minecraft.client.renderer.item.ItemModelResolver(null));
            }
        } catch (Throwable t) {
            Natives.log("Horizon's client: no stand-in models: " + t);
        }
    }

    /** The models' reload, once it ended: the client's model manager, atlases and fonts from it. */
    private static void finishModels() {
        if (pendingModels == null) return;
        var reload = (net.minecraft.server.packs.resources.ReloadInstance) pendingModels[0];
        long started = (long) pendingModels[3];
        if (!reload.done().isDone()) {
            if (System.nanoTime() - started > 120_000_000_000L) {
                pendingModels = null;
                Natives.log("Horizon's client: the models' reload didn't end in 2 minutes; the mods get no baked models");
            }
            return;
        }
        var models = (net.minecraft.client.resources.model.ModelManager) pendingModels[1];
        var atlases = (net.minecraft.client.resources.model.sprite.AtlasManager) pendingModels[2];
        var skins = (net.minecraft.client.renderer.PlayerSkinRenderCache) pendingModels[4];
        pendingModels = null;
        try {
            reload.done().join();
            set(Minecraft.class, minecraft, "modelManager", models);
            // The client's item models over them (Iceberg's 3D item in Legendary Tooltips:
            // getItemModelResolver().updateForTopItem). With a model manager the resolver is
            // vanilla's (ItemModelResolverMixin keeps items only for resolvers without one).
            set(Minecraft.class, minecraft, "itemModelResolver", new net.minecraft.client.renderer.item.ItemModelResolver(models));
            Class<?> owner = net.minecraft.client.resources.model.sprite.AtlasManager.class;
            set(owner, minecraft.getAtlasManager(), "spriteLookup", field(owner, atlases, "spriteLookup"));
            fonts(atlases, skins);
            featureRenderer(models, atlases);
            // The end of the client's resource reload (Minecraft.onResourceLoadFinished, which the
            // headless client never runs): the mods' injections there (Flywheel's in Create:
            // its backend chosen, its renderer caches reset).
            HorizonHooks.fireMerged(minecraft, Minecraft.class, "onResourceLoadFinished", "HEAD");
            HorizonHooks.fireMerged(minecraft, Minecraft.class, "onResourceLoadFinished", "RETURN");
            modelsLoaded = true;
            HorizonEntityRender.modelsLoaded();
            HorizonFluids.export();
            Natives.log("Horizon's client: block and item models loaded in " + (System.nanoTime() - started) / 1_000_000 + " ms"
                    + (HorizonLazyModels.on ? " (baked on first use)" : ""));
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the models couldn't be loaded: " + root);
            for (var f : root.getStackTrace()) Natives.log("    at " + f);
        }
    }
    private static boolean gpuParts;
    /** The models' reload ended with the models in place. */
    private static volatile boolean modelsLoaded;

    /** Whether the client's models are loaded (the model manager holds them). */
    static boolean modelsReady() {
        return modelsLoaded;
    }
    /** The client levels whose level renderer was told of them (levelRendererReady). */
    private static final java.util.Set<net.minecraft.client.multiplayer.ClientLevel> RENDERED_LEVELS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * The level renderer's reload for the client's level (LevelExtractor.allChanged, which a
     * client runs as its level renderer gets a level and after each resource reload), once per
     * client level once the models are in. Horizon draws the world, so allChanged's body stays
     * cancelled (LevelExtractorMixin); the mods' injections at its end run here, with the
     * extractor's level set. Flywheel (inside Create) sets up its model helpers there
     * (ModelRenderHelper): without it every Create block entity renderer threw a
     * NullPointerException and its shafts, cogs and kinetic items drew nothing. Flywheel's
     * backend stays off (it draws with OpenGL; the headless client's GPU is Horizon's), so
     * Create draws its parts through its block entity renderers, which the bridge records.
     */
    /**
     * Whether a client level's renderer reload ran (levelRendererReady): mods' block entity
     * renderers draw from then on (Flywheel's helpers, the client's baked models).
     */
    static boolean levelRendered() {
        return !RENDERED_LEVELS.isEmpty();
    }

    private static void levelRendererReady() {
        if (!modelsLoaded || !(minecraft.level instanceof net.minecraft.client.multiplayer.ClientLevel level)
                || !RENDERED_LEVELS.add(level)) return;
        var extractor = minecraft.levelExtractor;
        if (extractor == null) return;
        try {
            Class<?> owner = net.minecraft.client.renderer.extract.LevelExtractor.class;
            set(owner, extractor, "minecraft", minecraft);
            set(owner, extractor, "level", level);
            HorizonHooks.fireMerged(extractor, owner, "allChanged", "RETURN");
        } catch (Throwable t) {
            Natives.log("Horizon's client: the level renderer's reload failed: " + t);
        }
    }

    /** A client player's place in its level: moves and removals are the server's business. */
    private static final net.minecraft.world.level.entity.EntityInLevelCallback IN_LEVEL =
            new net.minecraft.world.level.entity.EntityInLevelCallback() {
                @Override
                public void onMove() {}

                @Override
                public void onRemove(net.minecraft.world.entity.Entity.RemovalReason reason) {}
            };

    /**
     * The game renderer's feature dispatcher (its render buffers, the real models and atlases,
     * the client's font), which mods drawing their own passes submit through: VoxelMap's
     * SubmitPass (prepareFrame, renderAllFeatures) draws its whole HUD with it.
     */
    private static void featureRenderer(net.minecraft.client.resources.model.ModelManager models,
                                        net.minecraft.client.resources.model.sprite.AtlasManager atlases) {
        try {
            var gameRenderer = minecraft.gameRenderer;
            Class<?> owner = net.minecraft.client.renderer.GameRenderer.class;
            var buffers = new net.minecraft.client.renderer.RenderBuffers(1);
            set(owner, gameRenderer, "renderBuffers", buffers);
            var dispatcher = new net.minecraft.client.renderer.feature.FeatureRenderDispatcher(
                    buffers, models, atlases, minecraft.font, gameRenderer.gameRenderState());
            set(owner, gameRenderer, "featureRenderDispatcher", dispatcher);
            // The GUI renderer's too (mods drawing oversized items read it there).
            Object gui = field(owner, gameRenderer, "guiRenderer");
            if (gui != null) set(net.minecraft.client.gui.render.GuiRenderer.class, gui, "featureRenderDispatcher", dispatcher);
        } catch (Throwable t) {
            Natives.log("Horizon's client: no feature render dispatcher: " + t);
            for (var f : t.getStackTrace()) Natives.log("    at " + f);
        }
    }

    /**
     * The game renderer's GUI renderer, as far as mods drawing with the GPU read it: its GUI
     * projection and that projection's matrix buffer (Xaero's minimap draws its picture in
     * picture with them). Nothing renders through it: HorizonGraphics records the GUI.
     */
    private static void guiRenderer(Object gameRenderer) {
        try {
            var gui = Natives.allocate(net.minecraft.client.gui.render.GuiRenderer.class);
            // GuiRenderer.draw's: the GUI's size, z from 1000 to 11000 (Xaero sets its size).
            var projection = new net.minecraft.client.renderer.Projection();
            projection.setupOrtho(1000.0F, 11000.0F, width, height, true);
            set(net.minecraft.client.gui.render.GuiRenderer.class, gui, "guiProjection", projection);
            set(net.minecraft.client.gui.render.GuiRenderer.class, gui, "guiProjectionMatrixBuffer",
                    new net.minecraft.client.renderer.ProjectionMatrixBuffer("gui"));
            // Its oversized items' renderers and GUI scale (Pufferfish's Skills draws its icons
            // through them: OversizedItemRenderer.prepare, a picture in picture each).
            set(net.minecraft.client.gui.render.GuiRenderer.class, gui, "oversizedItemRenderers", new java.util.HashMap<>());
            int scale = Math.max(1, minecraft.getWindow().getGuiScale());
            var window = ((net.minecraft.client.renderer.GameRenderer) gameRenderer).gameRenderState().windowRenderState;
            if (window.guiScale == 0) window.guiScale = scale;
            set(net.minecraft.client.gui.render.GuiRenderer.class, gui, "cachedGuiScale", window.guiScale);
            set(net.minecraft.client.renderer.GameRenderer.class, gameRenderer, "guiRenderer", gui);
        } catch (Throwable t) {
            Natives.log("Horizon's client: no GUI renderer: " + t);
        }
        // The rest of what GameRenderer's constructor makes that GPU drawing reads: the lights'
        // buffer, the overlay and light map textures, no fog, a main render target (the GUI's
        // size at the window's scale: drawing aimed at the screen lands there, unseen).
        Class<?> owner = net.minecraft.client.renderer.GameRenderer.class;
        ThrowingRunnable[] parts = {
            () -> {
                // The GUI's lights from the start (vanilla's frame sets them; Iceberg's 3D item
                // draws in them, its pipeline's Lighting unbound otherwise: no draw).
                var lighting = new com.mojang.blaze3d.platform.Lighting();
                lighting.setupFor(com.mojang.blaze3d.platform.Lighting.Entry.ITEMS_3D);
                set(owner, gameRenderer, "lighting", lighting);
            },
            () -> set(owner, gameRenderer, "overlayTexture", new net.minecraft.client.renderer.texture.OverlayTexture()),
            () -> set(owner, gameRenderer, "lightmap", new net.minecraft.client.renderer.Lightmap()),
            () -> {
                var fog = new net.minecraft.client.renderer.fog.FogRenderer();
                set(owner, gameRenderer, "fogRenderer", fog);
                com.mojang.blaze3d.systems.RenderSystem.setShaderFog(fog.getBuffer(net.minecraft.client.renderer.fog.FogRenderer.FogMode.NONE));
            },
            () -> set(owner, gameRenderer, "mainRenderTarget", new com.mojang.blaze3d.pipeline.TextureTarget("main", width * 3, height * 3,
                    com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM, com.mojang.renderpearl.api.GpuFormat.D32_FLOAT)),
            // The Globals uniform (GameRenderer.render updates it each frame): pipelines on the
            // GLOBALS layout (item_cutout: Iceberg's 3D item) skipped their draws without it.
            () -> {
                var globals = new net.minecraft.client.renderer.GlobalSettingsUniform();
                globals.update(width * 3, height * 3, 1.0, 0L, 0.0F, 0, net.minecraft.world.phys.Vec3.ZERO, false);
                set(owner, gameRenderer, "globalSettingsUniform", globals);
            },
        };
        for (var part : parts) {
            try {
                part.run();
            } catch (Throwable t) {
                Natives.log("Horizon's client: a part of the game renderer couldn't be made: " + t);
            }
        }
    }

    /** Players whose client has joined (joined()). */
    private static final java.util.Set<java.util.UUID> JOINED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Each player's client connection (LocalPlayer.connection): mods keep per-client state on it. */
    private static final java.util.Map<java.util.UUID, net.minecraft.client.multiplayer.ClientPacketListener> CONNECTIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A player's own client connection: made as the shared one is (the server's registries, never
     * sends), with its own clocks, and, on a dedicated server, the server it is connected to
     * (ServerData: mods name their per-server data by it). Xaero's minimap keeps its session on
     * the connection: one minimap per player.
     */
    static net.minecraft.client.multiplayer.ClientPacketListener connection(net.minecraft.server.level.ServerPlayer player) {
        return CONNECTIONS.computeIfAbsent(player.getUUID(), uuid -> {
            var shared = connection(player.level());
            if (shared == null) return null;
            try {
                // A new connection as the shared one is made (its fields, not mods' state).
                var copy = (net.minecraft.client.multiplayer.ClientPacketListener)
                        Natives.allocate(net.minecraft.client.multiplayer.ClientPacketListener.class);
                Class<?> listener = net.minecraft.client.multiplayer.ClientPacketListener.class;
                for (String name : new String[] {"registryAccess", "enabledFeatures", "searchTrees"}) {
                    set(listener, copy, name, field(listener, shared, name));
                }
                set(net.minecraft.client.multiplayer.ClientPacketListener.class, copy, "clockManager",
                        new net.minecraft.client.ClientClockManager());
                set(net.minecraft.client.multiplayer.ClientPacketListener.class, copy, "waypointManager",
                        new net.minecraft.client.waypoints.ClientWaypointManager());
                // Its Minecraft and network connection, as the shared one has (Architectury's and
                // Puzzles Lib's join read minecraft.player and getConnection().getConnection()),
                // and a scoreboard of its own (Cardinal Components' client tick reads
                // getConnection().scoreboard()).
                Class<?> common = net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class;
                set(common, copy, "minecraft", minecraft);
                set(common, copy, "connection", field(common, shared, "connection"));
                set(net.minecraft.client.multiplayer.ClientPacketListener.class, copy, "scoreboard",
                        new net.minecraft.world.scores.Scoreboard());
                players(copy);
                if (!(HorizonServer.server() instanceof net.minecraft.client.server.IntegratedServer)) {
                    set(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class, copy, "serverData",
                            new net.minecraft.client.multiplayer.ServerData("Horizon", "horizon",
                                    net.minecraft.client.multiplayer.ServerData.Type.OTHER));
                }
                // This game's own client (connected to another's server): that server.
                HorizonDeviceClient.ownConnection(copy);
                return copy;
            } catch (Throwable t) {
                Natives.log("Horizon's client: no connection of its own for " + player.getName().getString() + ": " + t);
                return shared;
            }
        });
    }

    /**
     * Fabric's play networking addon of a client connection made without its constructor
     * (whose injection makes it): made as that does, without its start (the session's global
     * receivers are the global registry's, which HorizonLoop asks itself), and without the
     * channel registration it would send (Horizon's connection has no channel).
     */
    static net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon fabricAddon(
            net.minecraft.client.multiplayer.ClientPacketListener connection) {
        if (connection == null) return null;
        try {
            var existing = net.fabricmc.fabric.impl.networking.client.ClientNetworkingImpl.getAddon(connection);
            if (existing != null) return existing;
            Field slot = null;
            for (Field f : net.minecraft.client.multiplayer.ClientPacketListener.class.getDeclaredFields()) {
                if (f.getType() == net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon.class) slot = f;
            }
            if (slot == null) return null;
            var addon = (net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon)
                    Natives.allocate(net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon.class);
            for (Class<?> c = addon.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Class<?> t = f.getType();
                    Object value = null;
                    if (t == java.util.Map.class) value = new java.util.HashMap<>();
                    else if (t == java.util.Set.class) value = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
                    else if (t == java.util.concurrent.locks.ReadWriteLock.class) value = new java.util.concurrent.locks.ReentrantReadWriteLock();
                    else if (t == java.util.concurrent.atomic.AtomicBoolean.class) value = new java.util.concurrent.atomic.AtomicBoolean();
                    else if (t == org.slf4j.Logger.class) value = org.slf4j.LoggerFactory.getLogger("ClientPlayNetworkAddon (Horizon)");
                    else if (t == net.minecraft.network.Connection.class) value = connection.getConnection();
                    else if (t == Minecraft.class) value = minecraft;
                    else if (t.isInstance(connection) && f.getName().equals("listener")) value = connection;
                    else if (f.getName().equals("receiver")) value = net.fabricmc.fabric.impl.networking.client.ClientNetworkingImpl.PLAY;
                    if (value != null && f.get(addon) == null) f.set(addon, value);
                }
            }
            slot.setAccessible(true);
            slot.set(connection, addon);
            return addon;
        } catch (Throwable t) {
            Natives.log("Horizon's client: no Fabric networking addon for the client's connection: " + t);
            return null;
        }
    }

    /** A receiver a mod registered on this player's client connection (in game), or null. */
    static Object connectionReceiver(net.minecraft.server.level.ServerPlayer player, net.minecraft.resources.Identifier channel) {
        var connection = CONNECTIONS.get(player.getUUID());
        if (connection == null) return null;
        try {
            var addon = net.fabricmc.fabric.impl.networking.client.ClientNetworkingImpl.getAddon(connection);
            return addon == null ? null : addon.getHandler(channel);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A player's client joining (ClientPacketListener.handleLogin, then the level's spawn:
     * handleSetSpawn), once its client is in a world: the mods' injections into those
     * methods run on its connection, wherever in them they inject (Xaero's minimap starts its
     * session at the login and names the world by the spawn).
     */
    /**
     * The player's client joins before its first message from the server is read, as a vanilla
     * client handles its login before the packets after it (HorizonLoop's deliveries): a mod
     * clearing its client data at the join (Pufferfish's Skills' categories) cleared what the
     * server had sent at its own join, delivered the tick before.
     */
    static void joinBeforeMessages(net.minecraft.server.level.ServerPlayer player) {
        joined(player);
    }

    private static void joined(net.minecraft.server.level.ServerPlayer player) {
        if (minecraft.level == null || !JOINED.add(player.getUUID())) return;
        // Their game starts at vanilla's field of view (its scale told again if a mod changes it).
        VIEW_SENT.remove(player.getUUID());
        FOV_MODIFIERS.remove(player.getUUID());
        // Mods that failed before a player was there: told now (HorizonFailures).
        HorizonFailures.flush();
        // The client's server is the mirror, in this process (as single player's is, and as
        // its connections are memory ones): mods name their per-world data by it (Xaero's
        // minimap: "waypoints" of the world folder).
        try {
            // Not a game's own client connected to another's server: it has none, as a vanilla
            // client there (Xaero's minimap names its data by the server's address then).
            if (HorizonServer.server() instanceof net.minecraft.client.server.IntegratedServer integrated
                    && !HorizonDeviceClient.own() && minecraft.getSingleplayerServer() == null) {
                set(Minecraft.class, minecraft, "singleplayerServer", integrated);
            }
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's client: no single-player server: " + e);
        }
        var connection = minecraft.player != null ? minecraft.player.connection : null;
        if (connection == null) return;
        // The login's chunk radius (handleLogin sets it on a real client): mods map that far
        // (JourneyMap: getEffectiveRenderDistance, 1 chunk without it).
        minecraft.options.setServerRenderDistance(LOGIN_RADIUS);
        fireConnection(connection, net.minecraft.network.protocol.game.ClientboundLoginPacket.class, loginPacket(player), "handleLogin");
        try {
            var spawn = new net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket(player.level().getRespawnData());
            fireConnection(connection, net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket.class, spawn, "handleSetSpawn");
        } catch (Throwable t) {
            Natives.log("Horizon's client: no spawn for the client's join: " + t);
        }
        // Fabric's own (its networking's injection, skipped in fireConnection): the connection's INIT and
        // JOIN (Puzzles Lib's client join, and mods listening there). The connection gets
        // Fabric's play addon first, as its constructor's injection makes it: receivers mods
        // register in game, at INIT among others, go to it ("Cannot register receiver while
        // not in game": ShulkerBoxTooltip).
        fabricAddon(connection);
        var sender = HorizonLoop.clientSender();
        HorizonServer.fireEach("ClientPlayConnectionEvents.INIT",
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.INIT, l -> l.onPlayInit(connection, minecraft));
        HorizonServer.fireEach("ClientPlayConnectionEvents.JOIN",
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN, l -> l.onPlayReady(connection, sender, minecraft));
        recipesArrive(player, connection);
        // A screen the player opened before their client joined (Horizon's world plays while
        // the client loads its models) is laid out again, as one opened now would be: a vanilla
        // client has no screen open before it joins, and mods make their parts for a screen as
        // it is laid out (a recipe viewer's overlay, which it wouldn't make with no world).
        var open = minecraft.gui.screen();
        if (open != null) {
            try {
                open.init(width, height);
            } catch (Throwable t) {
                Natives.log("Horizon's client: the open screen couldn't be laid out again at the join: " + t);
            }
        }
    }

    /**
     * What a joining client does with the server's recipes (PlayerList.placeNewPlayer sends them
     * after the login): Fabric's recipe sync gives the client the server's recipes
     * (ClientRecipeSynchronizedEvent: every serializer, the client being the server's own), then
     * the recipe packet: the connection's recipes set as handleUpdateRecipes sets them, and the
     * mods' injections into it run (recipe viewers start there). The server's tags too, as the
     * configuration phase sends them (handleUpdateTags' injections).
     */
    private static void recipesArrive(net.minecraft.server.level.ServerPlayer player, net.minecraft.client.multiplayer.ClientPacketListener connection) {
        var server = HorizonServer.server();
        if (server == null) return;
        try {
            var tags = new net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket(
                    net.minecraft.tags.TagNetworkSerialization.serializeTagsToNetwork(server.registries()));
            fireConnection(connection, net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket.class, tags, "handleUpdateTags");
        } catch (Throwable t) {
            Natives.log("Horizon's client: no tags for the client's join: " + t);
        }
        try {
            var manager = server.getRecipeManager();
            var recipes = net.fabricmc.fabric.impl.recipe.sync.SynchronizedRecipesImpl.of(manager.getRecipes());
            HorizonServer.fireEach("ClientRecipeSynchronizedEvent",
                    net.fabricmc.fabric.api.client.recipe.v1.sync.ClientRecipeSynchronizedEvent.EVENT, l -> l.onRecipesSynchronized(minecraft, recipes));
            var packet = new net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket(
                    manager.getSynchronizedItemProperties(), manager.getSynchronizedStonecutterRecipes());
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, connection, "recipes",
                    new net.minecraft.client.multiplayer.ClientRecipeContainer(packet.itemSets(), packet.stonecutterRecipes()));
            long started = System.nanoTime();
            fireConnection(connection, net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket.class, packet, "handleUpdateRecipes");
            Natives.log("Horizon's client: " + player.getName().getString() + "'s client got the server's recipes ("
                    + manager.getRecipes().size() + ", " + (System.nanoTime() - started) / 1_000_000 + " ms)");
        } catch (Throwable t) {
            Natives.log("Horizon's client: no recipes for the client's join: " + t);
            HorizonFailures.report("the client's join", t);
        }
    }

    /** The chunk radius of the login packet: Horizon's view distance. */
    private static final int LOGIN_RADIUS = 12;

    /** The login packet the server sends the player (PlayerList.placeNewPlayer's), or null. */
    private static net.minecraft.network.protocol.game.ClientboundLoginPacket loginPacket(net.minecraft.server.level.ServerPlayer player) {
        try {
            var server = HorizonServer.server();
            var level = player.level();
            return new net.minecraft.network.protocol.game.ClientboundLoginPacket(player.getId(), level.getLevelData().isHardcore(),
                    server.levelKeys(), server.getMaxPlayers(), LOGIN_RADIUS, LOGIN_RADIUS, false, true, false,
                    player.createCommonSpawnInfo(level), false, false);
        } catch (Throwable t) {
            Natives.log("Horizon's client: no login packet for the client's join: " + t);
            return null;
        }
    }

    /**
     * The mods' injections into one of the connection's packet handlers, given the packet, in
     * the order the handler's body reaches them: its HEAD's first, its RETURN's last.
     */
    private static void fireConnection(net.minecraft.client.multiplayer.ClientPacketListener connection, Class<?> packet,
                                       Object value, String method) {
        var methods = new java.util.ArrayList<>(java.util.List.of(net.minecraft.client.multiplayer.ClientPacketListener.class.getDeclaredMethods()));
        methods.sort(java.util.Comparator.comparingInt(HorizonHooks::injectOrder));
        for (var m : methods) {
            var merged = m.getAnnotation(org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
            Class<?>[] params = m.getParameterTypes();
            // (Fabric's own needs the connection's networking add-on, which the headless
            // client has none of.)
            if (merged == null || merged.mixin().startsWith("dev.horizonlab.platform.") || merged.mixin().startsWith("net.fabricmc.")
                    || params.length != 2 || params[0] != packet
                    || !org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class.isAssignableFrom(params[1])
                    || !HorizonDeviceMods.runs(m)) {
                continue;
            }
            try {
                m.setAccessible(true);
                m.invoke(connection, value, new org.spongepowered.asm.mixin.injection.callback.CallbackInfo(method, true));
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                Natives.log("Horizon's client: " + merged.mixin() + " failed at the client's " + method + ": " + cause);
                HorizonFailures.report("the client's join", cause);
            }
        }
    }

    /**
     * The client's thread is the render thread (RenderSystem.assertOnRenderThread): the
     * headless client runs on the thread that ticks it (the server's).
     */
    private static void renderThread() {
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return;
        try {
            set(com.mojang.blaze3d.systems.RenderSystem.class, null, "renderThread", Thread.currentThread());
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's client: no render thread: " + e);
        }
    }

    /**
     * The client's resource reload (Minecraft's constructor starts it, before CLIENT_STARTED),
     * for the mods' own reload listeners only (Fabric's ResourceLoader, CLIENT_RESOURCES: Jade's
     * themes): over vanilla's jar and every mod's assets, run here and now, one listener at a
     * time. Vanilla's own listeners (textures, models, sounds) have nothing to load into.
     */
    @SuppressWarnings("unchecked")
    private static void reloadClientResources() {
        var type = net.minecraft.server.packs.PackType.CLIENT_RESOURCES;
        java.util.List<net.minecraft.server.packs.PackResources> packs = new java.util.ArrayList<>();
        try {
            Class<?> source = net.minecraft.client.resources.ClientPackSource.class;
            var metadata = (net.minecraft.server.packs.resources.ResourceMetadata) field(source, null, "BUILT_IN_METADATA");
            var info = (net.minecraft.server.packs.PackLocationInfo) field(source, null, "VANILLA_PACK_INFO");
            packs.add(new net.minecraft.server.packs.VanillaPackResourcesBuilder().setMetadata(metadata)
                    .exposeNamespace("minecraft", "realms").applyDevelopmentConfig().pushJarResources().build(info).fullResources());
        } catch (Throwable t) {
            Natives.log("Horizon's client: no vanilla resources for the mods' reload: " + t);
        }
        java.util.Collection<net.minecraft.server.packs.resources.PreparableReloadListener> listeners;
        try {
            packs.addAll(net.fabricmc.fabric.impl.resource.pack.ModPackResourcesUtil.getModResourcePacks(
                    net.fabricmc.loader.api.FabricLoader.getInstance(), type, null));
            var loader = net.fabricmc.fabric.impl.resource.ResourceLoaderImpl.get(type);
            listeners = ((java.util.Map<Object, net.minecraft.server.packs.resources.PreparableReloadListener>)
                    field(net.fabricmc.fabric.impl.resource.ResourceLoaderImpl.class, loader, "addedReloaders")).values();
        } catch (Throwable t) {
            Natives.log("Horizon's client: the mods' resources could not be loaded: " + t);
            return;
        }
        // The resource pack list (Minecraft.getResourcePackRepository: Xaero's World Map hashes the
        // selected ones): none of the player's chosen, as a new game's (Horizon draws with its
        // own). The mods' injections at its first reload in Minecraft's constructor add their
        // own sources to it (TACZ's gun packs, with the guns' models, textures and animations):
        // their packs go over vanilla's and the mods' jars, as a required pack at the top does.
        try {
            var repository = minecraft.getResourcePackRepository();
            if (repository == null) repository = new net.minecraft.server.packs.repository.PackRepository();
            repository.reload();
            set(Minecraft.class, minecraft, "resourcePackRepository", repository);
            HorizonHooks.fireClient(minecraft, HorizonHooks.START_PACKS);
            repository.reload();
            int added = 0;
            for (var pack : repository.getAvailablePacks()) {
                try {
                    pack.open().forEach(packs::add);
                    added++;
                } catch (Throwable t) {
                    Natives.log("Horizon's client: a mod's resource pack " + pack.getId() + " couldn't be opened: " + t);
                }
            }
            if (added > 0) Natives.log("Horizon's client: " + added + " resource pack(s) the mods added: " + repository.getAvailableIds());
        } catch (Throwable t) {
            Natives.log("Horizon's client: no resource pack repository: " + t);
        }
        var manager = new net.minecraft.server.packs.resources.MultiPackResourceManager(type, packs);
        // The client's resource manager (Minecraft.getResourceManager: Xaero reads its radar
        // icons' definitions there), over the same packs.
        try {
            var reloadable = new net.minecraft.server.packs.resources.ReloadableResourceManager(type);
            set(net.minecraft.server.packs.resources.ReloadableResourceManager.class, reloadable, "resources", manager);
            set(Minecraft.class, minecraft, "resourceManager", reloadable);
        } catch (Throwable t) {
            Natives.log("Horizon's client: no client resource manager: " + t);
        }
        clientResources = manager;
        // The GPU's shaders: the GUI pipelines' fallback cache over the client's resources
        // (vanilla's and the mods' shaders), as Minecraft's start makes it before its first
        // reload: mods' reloads stitch atlases with it ("Fallback pipeline cache not yet set").
        if (HorizonGpu.installed()) {
            try {
                net.minecraft.client.renderer.GameRenderer.preloadUiShader(minecraft.getResourceManager());
            } catch (Throwable t) {
                Natives.log("Horizon's client: the GPU's shaders couldn't be loaded: " + t);
            }
        }
        java.util.concurrent.Executor here = Runnable::run;
        int ran = 0;
        int skipped = 0;
        for (var listener : new java.util.ArrayList<>(listeners)) {
            RELOADED.add(listener);
            if (afterModels(listener)) {
                // Run with the models' reload instead (models()), after them as Fabric orders
                // it: Trinkets' model discovery waits for its manager's preparation.
                AFTER_MODELS.add(listener);
                skipped++;
                continue;
            }
            try {
                net.minecraft.server.packs.resources.SimpleReloadInstance.of(manager, java.util.List.of(listener), here, here,
                        java.util.concurrent.CompletableFuture.completedFuture(net.minecraft.util.Unit.INSTANCE)).done().join();
                ran++;
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                if (HorizonGpu.installed()) {
                    // Failed on its own: it may want vanilla's listeners in the same reload (the
                    // atlases' stitch shared with every listener: Continuity's connected
                    // textures read it). Run again with the models' reload, as the game's
                    // reload holds them all.
                    Natives.log("Horizon's client: a mod's resource reload (" + listener.getClass().getName()
                            + ") failed alone (" + root + "); it runs again with the models");
                    AFTER_MODELS.add(listener);
                    skipped++;
                } else {
                    Natives.log("Horizon's client: a mod's resource reload (" + listener.getClass().getName() + ") failed: " + root);
                }
            }
        }
        Natives.log("Horizon's client: " + ran + " of the mods' resource reload listeners ran over " + packs.size()
                + " packs" + (skipped > 0 ? "; " + skipped + " that read baked models or textures run with the models" : ""));
    }

    /** The client resources the mods' reload ran over (reloadClientResources), for listeners added later. */
    private static net.minecraft.server.packs.resources.ResourceManager clientResources;
    /** The mods' client reload listeners that ran (or wait for the models) already. */
    private static final java.util.Set<Object> RELOADED = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * The mods' client reload listeners registered after the client's reload ran, at
     * CLIENT_STARTED (JEI registers its GUI colours' there), run once over the same resources:
     * in a real client the next reload (or the first, still running) would give them theirs.
     */
    @SuppressWarnings("unchecked")
    private static void reloadAddedListeners() {
        if (clientResources == null) return;
        java.util.List<net.minecraft.server.packs.resources.PreparableReloadListener> added = new java.util.ArrayList<>();
        try {
            var loader = net.fabricmc.fabric.impl.resource.ResourceLoaderImpl.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
            for (var listener : ((java.util.Map<Object, net.minecraft.server.packs.resources.PreparableReloadListener>)
                    field(net.fabricmc.fabric.impl.resource.ResourceLoaderImpl.class, loader, "addedReloaders")).values()) {
                if (RELOADED.add(listener) && !afterModels(listener)) added.add(listener);
            }
        } catch (Throwable t) {
            return;
        }
        java.util.concurrent.Executor here = Runnable::run;
        for (var listener : added) {
            try {
                net.minecraft.server.packs.resources.SimpleReloadInstance.of(clientResources, java.util.List.of(listener), here, here,
                        java.util.concurrent.CompletableFuture.completedFuture(net.minecraft.util.Unit.INSTANCE)).done().join();
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                Natives.log("Horizon's client: a mod's resource reload (" + listener.getClass().getName() + ") failed: " + root);
            }
        }
        if (!added.isEmpty()) Natives.log("Horizon's client: " + added.size() + " reload listener(s) the mods added at their start ran");
    }

    /**
     * Whether a mod's reload listener runs after vanilla's models or textures (its Fabric
     * dependencies): it reads the baked models or atlases (Traveler's Backpack's star model,
     * from the ModelManager), which the headless client never bakes; Horizon draws them.
     */
    /** Vanilla's client reload listeners whose results a mod's listener ordered after them reads. */
    private static final java.util.Set<String> MODEL_LISTENERS = java.util.Set.of(
            "minecraft:models", "minecraft:textures", "minecraft:atlas", "minecraft:block_entity_render_dispatcher",
            "minecraft:entity_render_dispatcher");

    private static boolean afterModels(Object listener) {
        // Fabric's resource loader v1: an ordering after vanilla's atlases or models
        // (ResourceLoader.addListenerOrdering(ResourceReloaderKeys.Client.ATLAS, id): Continuity).
        try {
            var loader = net.fabricmc.fabric.impl.resource.ResourceLoaderImpl.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
            Class<?> impl = net.fabricmc.fabric.impl.resource.ResourceLoaderImpl.class;
            @SuppressWarnings("unchecked")
            var added = (java.util.Map<Object, Object>) field(impl, loader, "addedReloaders");
            Object id = null;
            for (var e : added.entrySet()) if (e.getValue() == listener) id = e.getKey();
            if (id != null) {
                for (Object order : (java.util.Collection<?>) field(impl, loader, "reloadersOrdering")) {
                    var first = order.getClass().getDeclaredMethod("first");
                    var second = order.getClass().getDeclaredMethod("second");
                    first.setAccessible(true);
                    second.setAccessible(true);
                    if (id.equals(second.invoke(order)) && MODEL_LISTENERS.contains(String.valueOf(first.invoke(order)))) return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No v1 ordering to read: the v0 dependencies below.
        }
        try {
            var deps = listener.getClass().getMethod("getFabricDependencies").invoke(listener);
            if (!(deps instanceof java.util.Collection<?> list)) return false;
            for (Object dep : list) {
                String id = String.valueOf(dep);
                if (id.equals("minecraft:models") || id.equals("minecraft:textures")) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No Fabric dependencies: it runs.
        }
        return false;
    }

    private static Field attackStrengthTicker;

    /** The counters LocalPlayer.tick moves each client tick (the rest is the server's player). */
    private static void clientPlayerTick(net.minecraft.client.player.LocalPlayer local) {
        local.tickCount++;
        try {
            if (attackStrengthTicker == null) {
                Field f = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("attackStrengthTicker");
                f.setAccessible(true);
                attackStrengthTicker = f;
            }
            attackStrengthTicker.setInt(local, attackStrengthTicker.getInt(local) + 1);
        } catch (ReflectiveOperationException e) {
            // Left as it is.
        }
    }

    static void tickOnce() {
        HorizonHooks.fireClient(minecraft, "runTick");
        HorizonHooks.fireClient(minecraft, "tick");
        // Minecraft.handleKeybinds' attack each tick (continueAttack: the attack key held with
        // no screen up), as far as mods hook its head (a weapon's swing carried on).
        if (minecraft.player != null && HorizonHooks.hasMerged(Minecraft.class, "continueAttack", "HEAD")) {
            boolean held = minecraft.gui.screen() == null && minecraft.options.keyAttack.isDown();
            HorizonHooks.fireMerged(minecraft, Minecraft.class, "continueAttack", "HEAD", held);
        }
        // Once a frame in the game (the headless client's frames are its ticks, getDeltaTracker
        // whole ticks): Iceberg's render tick (Legendary Tooltips' turning 3D item, its targets'
        // pool given back).
        HorizonHooks.fireClient(minecraft, "renderFrame");
        // The key bindings' handling in the tick (Minecraft.handleKeybinds, while playing):
        // Simple Voice Chat reads its keys at its head.
        if (minecraft.player != null && minecraft.gui.screen() == null) HorizonHooks.fireClient(minecraft, "handleKeybinds");
        HorizonServer.fireEach("ClientTickEvents.START_CLIENT_TICK",
                net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK, l -> l.onStartTick(minecraft));
        // The client player's tick (the client level ticks it: Player.tick), as far as mods hook
        // it at its head and tail (Xaero's minimap follows its world there); the player itself
        // is the server's.
        var local = minecraft.player;
        if (local != null) {
            // Its age and attack strength move as LocalPlayer.tick moves them (Entity.baseTick's
            // tickCount++, Player.tick's attackStrengthTicker++): mods time their client's
            // swings by them (a weapon's upswing that ends N ticks after the click).
            clientPlayerTick(local);
            // Its hands' items and heights (LocalPlayer.tick ticks them; mods read them).
            var hands = local.firstPersonHandsAndItems();
            if (hands != null) {
                try {
                    hands.tick(local);
                } catch (Throwable t) {
                    if (HUD_FAILURES.merge("hands", 1, Integer::sum) == 1) Natives.log("Horizon's client: the hands' tick failed: " + t);
                }
            }
            // LocalPlayer.tick's head and end around it, the client mixins' (TACZ releases its
            // guns' client state lock and follows the server's draw and reload there).
            HorizonHooks.fireMerged(local, net.minecraft.client.player.LocalPlayer.class, "tick", "HEAD");
            HorizonHooks.fire(local, net.minecraft.world.entity.player.Player.class, "tick", "()V", "HEAD", new Object[0], null, true);
            HorizonHooks.fire(local, net.minecraft.world.entity.player.Player.class, "tick", "()V", "RETURN", new Object[0], null, true);
            HorizonHooks.fire(local, net.minecraft.world.entity.player.Player.class, "tick", "()V", "TAIL", new Object[0], null, true);
            HorizonHooks.fireMerged(local, net.minecraft.client.player.LocalPlayer.class, "tick", "RETURN");
        }
        HorizonServer.fireEach("ClientTickEvents.END_CLIENT_TICK",
                net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK, l -> l.onEndTick(minecraft));
    }

    // ---- Mods' key bindings ---------------------------------------------------------------------

    /** Each player's keys: which of the mods' KeyMappings are down, and their clicks not yet consumed. */
    private record Keys(java.util.Set<KeyMapping> down, java.util.Map<KeyMapping, Integer> clicks) {}

    private static final java.util.Map<java.util.UUID, Keys> KEYS = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.List<KeyMapping> modKeys = java.util.List.of();
    private static int modKeysOf = -1;
    private static java.util.Set<String> vanillaKeys;

    /**
     * The mods' own KeyMappings: every one made (KeyMapping.ALL, whether through Fabric's
     * KeyMappingHelper or a plain {@code new KeyMapping}), minus vanilla's (those Options makes:
     * its KeyMapping fields), which Horizon handles itself. Read again when ALL grows.
     */
    @SuppressWarnings("unchecked")
    static synchronized java.util.List<KeyMapping> modKeys() {
        try {
            var all = (java.util.Map<String, KeyMapping>) field(KeyMapping.class, null, "ALL");
            if (all.size() == modKeysOf) return modKeys;
            if (vanillaKeys == null) vanillaKeys = vanillaKeyNames();
            var list = new java.util.ArrayList<KeyMapping>();
            for (KeyMapping mapping : new java.util.ArrayList<>(all.values())) {
                if (!vanillaKeys.contains(mapping.getName())) list.add(mapping);
            }
            java.util.Collections.sort(list);
            modKeys = java.util.List.copyOf(list);
            modKeysOf = all.size();
            var names = new StringBuilder();
            for (KeyMapping mapping : modKeys) names.append(names.isEmpty() ? "" : ", ").append(mapping.getName());
            Natives.log("Horizon's client: " + modKeys.size() + " mod key binding(s)" + (modKeys.isEmpty() ? "" : ": " + names));
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (modKeysOf != -2) Natives.log("Horizon's client: the mods' key bindings can't be read: " + e);
            modKeysOf = -2;
        }
        return modKeys;
    }

    /** Vanilla's mappings: the ones Options holds in its own fields (keyUp, keyHotbarSlots...). */
    private static java.util.Set<String> vanillaKeyNames() throws ReflectiveOperationException {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (minecraft == null || minecraft.options == null) return names;
        for (Field f : net.minecraft.client.Options.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getName().equals("keyMappings")) continue;
            f.setAccessible(true);
            if (f.getType() == KeyMapping.class || KeyMapping.class.isAssignableFrom(f.getType())) {
                if (f.get(minecraft.options) instanceof KeyMapping k) names.add(k.getName());
            } else if (f.getType() == KeyMapping[].class && f.get(minecraft.options) instanceof KeyMapping[] keys) {
                for (KeyMapping k : keys) if (k != null) names.add(k.getName());
            }
        }
        return names;
    }

    /**
     * The mods' key bindings for the player's Controls page, as JSON: {@code [{name, category,
     * key, default, label, categoryLabel}]} (keys as options.txt names them, labels in the
     * client's language), in vanilla's order (KeyMapping.compareTo: category, then name).
     */
    public static String keyMappings() {
        Thread.currentThread().setContextClassLoader(HorizonClient.class.getClassLoader());
        var out = new com.google.gson.JsonArray();
        for (KeyMapping mapping : modKeys()) {
            var o = new com.google.gson.JsonObject();
            o.addProperty("name", mapping.getName());
            var category = mapping.getCategory();
            o.addProperty("category", category.id().toString());
            var key = key(mapping);
            o.addProperty("key", key == null ? "key.keyboard.unknown" : key.getName());
            o.addProperty("default", mapping.getDefaultKey().getName());
            o.addProperty("label", net.minecraft.network.chat.Component.translatable(mapping.getName()).getString());
            o.addProperty("categoryLabel", category.label().getString());
            out.add(o);
        }
        return out.toString();
    }

    /**
     * The keyboard keys (26.3 scancodes) the mods' KeyMappings are set to, as a JSON array; -1
     * too when a mod handles every key itself ({@link #keyPressHandlers}: MaLiLib's hotkeys).
     */
    public static String boundKeys() {
        Thread.currentThread().setContextClassLoader(HorizonClient.class.getClassLoader());
        var out = new com.google.gson.JsonArray();
        java.util.Set<Integer> seen = new java.util.TreeSet<>();
        if (!keyPressHandlers().isEmpty() || !sdlKeyHandlers.isEmpty()) out.add(-1);
        // -200: mods take the mouse wheel while playing (MouseHandler.onScroll's injections).
        if (!scrollHandlers().isEmpty()) out.add(-200);
        // Mouse buttons as -100 - button: the mods' KeyMappings set to one (TACZ's shoot and aim
        // on the left and right buttons), and every button when mods handle them themselves
        // (MouseHandler.onButton's injections).
        if (!mouseButtonHandlers().isEmpty()) {
            // (SDL's buttons, as 26.3's mouse keys are: left 1, middle 2, right 3, back 4, forward 5.)
            for (int b = 1; b <= 5; b++) if (seen.add(-100 - b)) out.add(-100 - b);
        }
        for (KeyMapping mapping : modKeys()) {
            var key = key(mapping);
            if (key == null || key.getValue() < 0) continue;
            if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD && seen.add(key.getValue())) {
                out.add(key.getValue());
            } else if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE && seen.add(-100 - key.getValue())) {
                out.add(-100 - key.getValue());
            }
        }
        return out.toString();
    }

    private static com.mojang.blaze3d.platform.InputConstants.Key key(KeyMapping mapping) {
        try {
            return (com.mojang.blaze3d.platform.InputConstants.Key) field(KeyMapping.class, mapping, "key");
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * A key of a player's game, pressed or released while playing (KeyboardHandler.keyPress:
     * KeyMapping.set, and click on a press), kept for that player's client tick.
     */
    static void bind(java.util.UUID player, int scancode, boolean down, java.util.List<String> mappings) {
        bind(player, com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD.getOrCreate(scancode), down, mappings);
    }

    /** A mouse button of a player's game while playing (MouseHandler.onButton: KeyMapping.set and click). */
    static void bindMouse(java.util.UUID player, int button, boolean down, java.util.List<String> mappings) {
        bind(player, com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(button), down, mappings);
    }

    /** The mapping names of a bind input ({@code "mappings"}: the player's game's own bindings), or null. */
    private static java.util.List<String> mappings(com.google.gson.JsonObject in) {
        if (!in.has("mappings") || !in.get("mappings").isJsonArray()) return null;
        var names = new java.util.ArrayList<String>();
        for (var e : in.getAsJsonArray("mappings")) names.add(e.getAsString());
        return names;
    }

    /**
     * {@code mappings}: the mods' mappings the player's game binds to this key (its Controls,
     * which the player may have changed), set in that player's keys only; null: those whose
     * key here is the one pressed (a game that sent no names).
     */
    private static void bind(java.util.UUID player, com.mojang.blaze3d.platform.InputConstants.Key pressed, boolean down,
            java.util.List<String> names) {
        Keys keys = KEYS.computeIfAbsent(player, p -> new Keys(
                java.util.concurrent.ConcurrentHashMap.newKeySet(), new java.util.concurrent.ConcurrentHashMap<>()));
        // Vanilla's use key, when its button's own presses come (mods take every button: the
        // click's real length, not the uses it repeats).
        if (minecraft != null && minecraft.options != null && pressed.equals(key(minecraft.options.keyUse))) {
            if (down) {
                USE_DOWN.add(player);
            } else {
                USE_DOWN.remove(player);
                USE_HELD.remove(player);
            }
        }
        for (KeyMapping mapping : modKeys()) {
            if (names != null ? !names.contains(mapping.getName()) : !pressed.equals(key(mapping))) continue;
            if (DEBUG_INPUT) Natives.log("bind " + pressed.getName() + (down ? " down" : " up") + ": " + mapping.getName());
            if (down) {
                keys.down().add(mapping);
                keys.clicks().merge(mapping, 1, Integer::sum);
            } else {
                keys.down().remove(mapping);
            }
        }
    }

    private static java.util.List<java.lang.reflect.Method> mouseButtonHandlers;

    /** The mods' handlers merged into MouseHandler.onButton, at whatever point (TACZ's input events). */
    static synchronized java.util.List<java.lang.reflect.Method> mouseButtonHandlers() {
        if (mouseButtonHandlers != null) return mouseButtonHandlers;
        try {
            mouseButtonHandlers = HorizonHooks.injected(net.minecraft.client.MouseHandler.class, "onButton",
                    long.class, net.minecraft.client.input.MouseButtonInfo.class, int.class);
        } catch (Throwable t) {
            Natives.log("Horizon's client: the mods' mouse handlers can't be read: " + t);
            mouseButtonHandlers = java.util.List.of();
        }
        if (!mouseButtonHandlers.isEmpty()) {
            Natives.log("Horizon's client: " + mouseButtonHandlers.size() + " mod mouse handler(s) get every button while playing");
        }
        return mouseButtonHandlers;
    }

    /**
     * A mouse button of a player's game while playing, as MouseHandler.onButton gets it: the
     * mods' injections there (press: action 1, release: 0), as that player's client.
     */
    static void mousePress(java.util.UUID id, int button, int mods, boolean down) {
        var handlers = mouseButtonHandlers();
        var player = HorizonServer.player(id);
        if (handlers.isEmpty() || player == null || minecraft == null) return;
        long window = HorizonScreenEvents.windowHandle(minecraft);
        try {
            as(player, () -> withKeys(player.getUUID(), () -> fireKey(handlers, minecraft.mouseHandler, "onButton", window,
                    new net.minecraft.client.input.MouseButtonInfo(button, mods), down ? 1 : 0)));
        } catch (Throwable t) {
            Natives.log("Horizon's client: a mouse button failed: " + t);
        }
    }

    private static java.util.List<java.lang.reflect.Method> scrollHandlers;

    /** The mods' handlers merged into MouseHandler.onScroll, at whatever point (Create's schematics, trains, elevators). */
    static synchronized java.util.List<java.lang.reflect.Method> scrollHandlers() {
        if (scrollHandlers != null) return scrollHandlers;
        try {
            scrollHandlers = HorizonHooks.injected(net.minecraft.client.MouseHandler.class, "onScroll", true,
                    long.class, double.class, double.class);
        } catch (Throwable t) {
            Natives.log("Horizon's client: the mods' scroll handlers can't be read: " + t);
            scrollHandlers = java.util.List.of();
        }
        if (!scrollHandlers.isEmpty() || DEBUG_INPUT) {
            Natives.log("Horizon's client: " + scrollHandlers.size() + " mod scroll handler(s) get the mouse wheel while playing");
        }
        return scrollHandlers;
    }

    /**
     * The mouse wheel of a player's game while playing (no screen), as MouseHandler.onScroll
     * gets it: the mods' injections there, as that player's client, before vanilla moves the
     * hotbar. The player's game moved its hotbar at once (no wait for the mods); when a mod
     * takes the scroll (cancels: Create's schematic tools, a train's throttle), the hotbar is
     * put back to {@code slot}, where it was.
     */
    static void scroll(java.util.UUID id, double x, double y, int slot) {
        var handlers = scrollHandlers();
        var player = HorizonServer.player(id);
        if (handlers.isEmpty() || player == null || minecraft == null) return;
        long window = HorizonScreenEvents.windowHandle(minecraft);
        boolean[] taken = {false};
        // The slot the wheel turned from is the one in hand as the mods hear it.
        if (slot >= 0 && slot < 9) player.getInventory().setSelectedSlot(slot);
        try {
            inWorld(player, () -> withKeys(id, () -> {
                // As vanilla scales it (discrete steps by default), which handlers take as a local.
                boolean discrete = minecraft.options.discreteMouseScroll().get();
                double sensitivity = minecraft.options.mouseWheelSensitivity().get();
                double scaledY = (discrete ? Math.signum(y) : y) * sensitivity;
                var info = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("onScroll", true);
                for (var handler : handlers) {
                    Class<?>[] params = handler.getParameterTypes();
                    Object[] call = new Object[params.length];
                    call[0] = window;
                    call[1] = x;
                    call[2] = y;
                    call[3] = info;
                    for (int i = 4; i < params.length; i++) {
                        if (params[i] == double.class) call[i] = scaledY;
                        else if (params[i] == int.class) call[i] = (int) Math.signum(scaledY);
                        else if (params[i] == float.class) call[i] = (float) scaledY;
                        else if (params[i] == boolean.class) call[i] = discrete;
                        else if (params[i] == long.class) call[i] = window;
                    }
                    try {
                        handler.invoke(java.lang.reflect.Modifier.isStatic(handler.getModifiers()) ? null : minecraft.mouseHandler, call);
                    } catch (ReflectiveOperationException e) {
                        HorizonFailures.report("the mouse wheel", e.getCause() != null ? e.getCause() : e, handler);
                    }
                    if (info.isCancelled()) {
                        taken[0] = true;
                        break;
                    }
                }
                tasks();
            }));
        } catch (Throwable t) {
            Natives.log("Horizon's client: the mouse wheel failed: " + t);
        }
        if (DEBUG_INPUT) Natives.log("scroll " + y + " to " + handlers.size() + " mod handler(s)" + (taken[0] ? ", taken" : ""));
        if (taken[0] && slot >= 0 && slot < 9) {
            HorizonPackets.send(player, new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(slot));
            player.getInventory().setSelectedSlot(slot);
        }
    }

    private static java.util.List<java.lang.reflect.Method> keyPressHandlers;
    private static java.util.List<java.lang.reflect.Method> sdlKeyHandlers;
    private static Object sdlEvents;

    /**
     * The mods' handlers merged into KeyboardHandler.keyPress, at whatever point they inject
     * (MaLiLib's, after its FramerateLimitTracker call: every hotkey of Litematica, MiniHUD,
     * Tweakeroo; Litematica's menu on M), and into SDLEventHandler.handleKeyEvent, which comes
     * before it (MaLiLib keeps its pressed keys there). Fabric's and Horizon's own left out.
     */
    static synchronized java.util.List<java.lang.reflect.Method> keyPressHandlers() {
        if (keyPressHandlers != null) return keyPressHandlers;
        try {
            keyPressHandlers = HorizonHooks.injected(net.minecraft.client.KeyboardHandler.class, "keyPress",
                    long.class, int.class, net.minecraft.client.input.KeyEvent.class);
            sdlKeyHandlers = HorizonHooks.injected(com.mojang.blaze3d.platform.SDLEventHandler.class, "handleKeyEvent",
                    org.lwjgl.sdl.SDL_Event.class);
        } catch (Throwable t) {
            Natives.log("Horizon's client: the mods' key handlers can't be read: " + t);
            keyPressHandlers = java.util.List.of();
            sdlKeyHandlers = java.util.List.of();
        }
        if (!keyPressHandlers.isEmpty() || !sdlKeyHandlers.isEmpty()) {
            Natives.log("Horizon's client: " + keyPressHandlers.size() + " mod key handler(s) and " + sdlKeyHandlers.size()
                    + " SDL key handler(s) get every key while playing");
        }
        return keyPressHandlers;
    }

    /**
     * A key of a player's game while playing, as the window's SDL event reaches the mods: their
     * injections at SDLEventHandler.handleKeyEvent's head (an SDL_Event of the key), then theirs in
     * KeyboardHandler.keyPress (press: action 1, release: 0), run as that player's client. A
     * hotkey may open a screen (Gui.setScreen: a client-only screen in their window).
     */
    static void keyPress(java.util.UUID id, int key, int keycode, int mods, boolean down) {
        var player = HorizonServer.player(id);
        if ((keyPressHandlers().isEmpty() && sdlKeyHandlers.isEmpty()) || player == null || minecraft == null) return;
        try {
            as(player, () -> firePress(key, keycode, mods, down));
        } catch (Throwable t) {
            Natives.log("Horizon's client: a key press failed: " + t);
        }
    }

    /** The mods' key handlers for a press or release, as the client player now (keyPress). */
    static void firePress(int key, int keycode, int mods, boolean down) throws Throwable {
        var handlers = keyPressHandlers();
        var sdl = sdlKeyHandlers;
        if (handlers.isEmpty() && sdl.isEmpty()) return;
        var event = new net.minecraft.client.input.KeyEvent(key, keycode, mods);
        long window = HorizonScreenEvents.windowHandle(minecraft);
        boolean taken = false;
        if (!sdl.isEmpty()) {
            if (sdlEvents == null) {
                sdlEvents = Natives.allocate(com.mojang.blaze3d.platform.SDLEventHandler.class);
                set(com.mojang.blaze3d.platform.SDLEventHandler.class, sdlEvents, "minecraft", minecraft);
            }
            var sdlEvent = org.lwjgl.sdl.SDL_Event.calloc();
            try {
                // SDL_EVENT_KEY_DOWN (0x300), SDL_EVENT_KEY_UP (0x301).
                sdlEvent.type(down ? 0x300 : 0x301);
                sdlEvent.key().type(down ? 0x300 : 0x301).scancode(key).key(keycode).mod((short) mods)
                        .down(down).repeat(false);
                taken = fireKey(sdl, sdlEvents, "handleKeyEvent", sdlEvent);
            } finally {
                sdlEvent.free();
            }
        }
        if (!taken) taken = fireKey(handlers, minecraft.keyboardHandler, "keyPress", window, down ? 1 : 0, event);
        if (DEBUG_INPUT) Natives.log("key " + key + (down ? " down" : " up") + " to " + sdl.size() + "+"
                + handlers.size() + " mod key handler(s)" + (taken ? ", taken" : "") + "; screen now "
                + minecraft.gui.screen());
    }

    /** Calls handlers (the method's arguments, then a CallbackInfo): whether one cancelled. */
    private static boolean fireKey(java.util.List<java.lang.reflect.Method> handlers, Object self, String method, Object... args) {
        var info = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo(method, true);
        Object[] call = java.util.Arrays.copyOf(args, args.length + 1);
        call[args.length] = info;
        for (var handler : handlers) {
            if (!HorizonDeviceMods.runs(handler)) continue;
            try {
                handler.invoke(java.lang.reflect.Modifier.isStatic(handler.getModifiers()) ? null : self, call);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                HorizonFailures.report("a key press", cause, handler);
                if (DEBUG_INPUT) Natives.log("key handler " + handler.getName() + " failed: " + cause);
            }
            if (info.isCancelled()) return true;
        }
        return false;
    }

    /**
     * Runs with the player's keys on the mods' KeyMappings (isDown, clicks; KeyMappings are the
     * client's, one set for every player here), then keeps the clicks the mods didn't consume.
     */
    static void withKeys(java.util.UUID player, ThrowingRunnable run) throws Throwable {
        Keys keys = KEYS.get(player);
        var mappings = modKeys();
        Field clickCount = KeyMapping.class.getDeclaredField("clickCount");
        clickCount.setAccessible(true);
        for (KeyMapping mapping : mappings) {
            mapping.setDown(keys != null && keys.down().contains(mapping));
            clickCount.setInt(mapping, keys == null ? 0 : keys.clicks().getOrDefault(mapping, 0));
        }
        // Vanilla's attack key: down while the player's click lasts (startAttack); its use key
        // while their right click does.
        KeyMapping attack = minecraft.options != null ? minecraft.options.keyAttack : null;
        Integer held = ATTACK_HELD.get(player);
        if (attack != null) attack.setDown(held != null && held > 0);
        KeyMapping use = minecraft.options != null ? minecraft.options.keyUse : null;
        Integer using = USE_HELD.get(player);
        if (use != null) use.setDown(USE_DOWN.contains(player) || using != null && using > 0);
        try {
            run.run();
        } finally {
            if (attack != null) attack.setDown(false);
            if (use != null) use.setDown(false);
            for (KeyMapping mapping : mappings) {
                int left = clickCount.getInt(mapping);
                if (keys != null) {
                    if (left > 0) keys.clicks().put(mapping, left);
                    else keys.clicks().remove(mapping);
                }
                mapping.setDown(false);
                clickCount.setInt(mapping, 0);
            }
        }
    }

    // ---- Mods' field of view (the camera's, which zoom mods change) ---------------------------

    /** Each player's camera FOV modifiers (Camera.oldFovModifier, fovModifier), swapped onto the one camera. */
    private static final java.util.Map<java.util.UUID, float[]> FOV_MODIFIERS = new java.util.concurrent.ConcurrentHashMap<>();
    /** The FOV scale each player's game was last told ({@link #sendView}). */
    private static final java.util.Map<java.util.UUID, Float> VIEW_SENT = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean cameraReady;
    private static java.lang.reflect.Method calculateFov;
    private static Field fovModifier, oldFovModifier, cameraFov;

    private static net.minecraft.client.Camera camera() {
        try {
            if (minecraft == null || minecraft.gameRenderer == null) return null;
            var camera = (net.minecraft.client.Camera) field(net.minecraft.client.renderer.GameRenderer.class, minecraft.gameRenderer, "mainCamera");
            if (camera == null) return null;
            if (!cameraReady) {
                cameraReady = true;
                // Made without its constructor: its client (tickFov and calculateFov read the
                // options) and modifiers at rest.
                set(net.minecraft.client.Camera.class, camera, "minecraft", minecraft);
                calculateFov = net.minecraft.client.Camera.class.getDeclaredMethod("calculateFov", float.class);
                calculateFov.setAccessible(true);
                fovModifier = net.minecraft.client.Camera.class.getDeclaredField("fovModifier");
                oldFovModifier = net.minecraft.client.Camera.class.getDeclaredField("oldFovModifier");
                cameraFov = net.minecraft.client.Camera.class.getDeclaredField("fov");
                fovModifier.setAccessible(true);
                oldFovModifier.setAccessible(true);
                cameraFov.setAccessible(true);
                var hooks = HorizonHooks.hooking(net.minecraft.client.Camera.class,
                        java.util.Set.of("tick", "tickFov", "calculateFov", "getFov", "modifyFovBasedOnDeathOrFluid", "calculateHudFov"));
                Natives.log("Horizon's client: " + hooks.size() + " mod hook(s) on the camera's field of view"
                        + (hooks.isEmpty() ? "" : ": " + String.join(", ", hooks)));
            }
            return calculateFov == null ? null : camera;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (calculateFov == null && cameraReady) return null;
            Natives.log("Horizon's client: no camera field of view: " + e);
            cameraReady = true;
            calculateFov = null;
            return null;
        }
    }

    /** The camera's tick (Camera.tick, tickFov) for the player's client: their modifiers on it. */
    private static void tickView(java.util.UUID player) {
        var camera = camera();
        if (camera == null) return;
        float[] m = FOV_MODIFIERS.computeIfAbsent(player, p -> new float[] {1f, 1f});
        try {
            oldFovModifier.setFloat(camera, m[0]);
            fovModifier.setFloat(camera, m[1]);
            camera.tick();
            m[0] = oldFovModifier.getFloat(camera);
            m[1] = fovModifier.getFloat(camera);
        } catch (Throwable t) {
            if (HUD_FAILURES.merge("camera tick", 1, Integer::sum) == 1) Natives.log("Horizon's client: the camera's tick failed: " + t);
        }
    }

    /**
     * The player's field of view as the mods make it, in the frame: Camera.calculateFov (the
     * mods' hooks on it: Zoomify's zoom), written to the camera's fov as update does, then
     * getFov (theirs on it). Over vanilla's own (options' FOV times the modifier, which
     * Horizon applies itself; death and fluids are inert here), the scale Horizon's camera
     * takes: sent to the player's game when it changes.
     */
    private static void sendView(java.util.UUID player) {
        var camera = camera();
        if (camera == null) return;
        float[] m = FOV_MODIFIERS.computeIfAbsent(player, p -> new float[] {1f, 1f});
        float scale = 1f;
        try {
            oldFovModifier.setFloat(camera, m[0]);
            fovModifier.setFloat(camera, m[1]);
            float modded = (float) calculateFov.invoke(camera, 1f);
            cameraFov.setFloat(camera, modded);
            modded = camera.getFov();
            float base = minecraft.options.fov().get() * m[1];
            if (base > 0 && Float.isFinite(modded) && modded > 0) scale = modded / base;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException e && e.getCause() != null ? e.getCause() : t;
            if (HUD_FAILURES.merge("camera fov", 1, Integer::sum) == 1) {
                Natives.log("Horizon's client: the camera's field of view failed: " + cause);
                HorizonFailures.report("the camera's field of view", cause);
            }
        }
        Float sent = VIEW_SENT.get(player);
        if (sent == null ? Math.abs(scale - 1f) < 1e-4f : Math.abs(scale - sent) < 1e-4f) return;
        VIEW_SENT.put(player, scale);
        if (DEBUG_INPUT) Natives.log("view " + player + ": fov scale " + scale);
        Natives.view(player.toString(), scale);
    }

    // ---- The client in a world: its level, what it looks at, its HUD --------------------------

    /** Each dimension's client level: the mirror's blocks, seen from the client side. */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
            net.minecraft.client.multiplayer.ClientLevel> CLIENT_LEVELS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A client level for a mirrored level (Minecraft.level, which HUD mods read: Jade's block
     * under the crosshair): made without its constructor, as the mirror's levels are, so its
     * blocks, fluids, light and block entities are Horizon's (LevelMixin), isClientSide true.
     * No chunks, entities or renderer of its own.
     */
    static net.minecraft.client.multiplayer.ClientLevel clientLevel(net.minecraft.world.level.Level server) {
        return CLIENT_LEVELS.computeIfAbsent(server.dimension(), key -> {
            var level = HorizonServer.mirror(net.minecraft.client.multiplayer.ClientLevel.class);
            var access = (dev.horizonlab.platform.mixin.LevelAccess) level;
            access.horizon$setDimension(key);
            access.horizon$setClientSide(true);
            access.horizon$setRandom(net.minecraft.util.RandomSource.create());
            access.horizon$setSoundSeedGenerator(net.minecraft.util.RandomSource.create());
            access.horizon$setBlockEntityTickers(new java.util.ArrayList<>());
            access.horizon$setPendingBlockEntityTickers(new java.util.ArrayList<>());
            access.horizon$setRegistryAccess(server.registryAccess());
            access.horizon$setDimensionType(server.dimensionTypeRegistration());
            access.horizon$setDamageSources(server.damageSources());
            // Its client data (ClientboundLoginPacket's: hardcore, difficulty, flat), from the
            // server's; Horizon's world time is in the server's.
            var serverData = server.getLevelData();
            var clientData = new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(
                    server.getDifficulty(), serverData.isHardcore(), false);
            clientData.setGameTime(server.getGameTime());
            // Its spawn (ClientboundSetDefaultSpawnPositionPacket's: JourneyMap's UIState).
            clientData.setSpawn(server.getRespawnData());
            access.horizon$setLevelData(clientData);
            Class<?> owner = net.minecraft.client.multiplayer.ClientLevel.class;
            try {
                set(owner, level, "minecraft", minecraft);
                // Its connection (mods read the client's registries through it: REI).
                set(owner, level, "connection", connection(server));
                // Its chunks' storage (an empty chunk beyond Horizon's): the server level's.
                set(net.minecraft.world.level.Level.class, level, "palettedContainerFactory", server.palettedContainerFactory());
                set(owner, level, "clientLevelData", clientData);
                set(owner, level, "players", new java.util.ArrayList<>());
                set(owner, level, "dragonParts", new java.util.ArrayList<>());
                set(owner, level, "worldBorder", new net.minecraft.world.level.border.WorldBorder());
                set(owner, level, "environmentAttributes", server.environmentAttributes());
                set(owner, level, "seaLevel", server.getSeaLevel());
                // Its tick rate (ClientboundTickingStatePacket's): the server's (potions'
                // tooltips read it for their durations; JEI's search reads every tooltip).
                set(owner, level, "tickRateManager", server.tickRateManager());
                // Its chunk source (mods ask it for the light engine: Xaero's World Map), its
                // light Horizon's as the level's own (LevelMixin).
                try {
                    var cache = new net.minecraft.client.multiplayer.ClientChunkCache(level, 12);
                    set(net.minecraft.client.multiplayer.ClientChunkCache.class, cache, "lightEngine", HorizonServer.light(level));
                    set(owner, level, "chunkSource", cache);
                } catch (Throwable t) {
                    Natives.log("Horizon's client: a client level has no chunk source: " + t);
                }
                // Its biomes (biome tints: grass, foliage, water), from its chunks (Horizon's),
                // zoomed with the world's seed as a client's are.
                long seed = server instanceof net.minecraft.server.level.ServerLevel serverLevel ? serverLevel.getSeed() : 0L;
                set(net.minecraft.world.level.Level.class, level, "biomeManager",
                        new net.minecraft.world.level.biome.BiomeManager(level, net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(seed)));
            } catch (ReflectiveOperationException e) {
                Natives.log("Horizon's client: a client level is missing a part: " + e);
            }
            HorizonServer.emptyCollections(level, owner);
            // What mods' injections at the end of its constructors give a level (Chunk
            // Loaders' per-level loaders, the client's copy its screen's grid reads).
            try {
                HorizonHooks.constructed(level);
            } catch (Throwable t) {
                Natives.log("Horizon's client: a client level's constructor injections failed: " + t);
            }
            return level;
        });
    }

    /** A client level's entities (ClientLevelMixin): its mirrored level's. */
    public static net.minecraft.world.level.entity.LevelEntityGetter<net.minecraft.world.entity.Entity> entities(
            net.minecraft.client.multiplayer.ClientLevel level) {
        var all = HorizonServer.entities(HorizonServer.level(level.dimension().identifier().toString()));
        // Not the client's own player's server copy: the client has its own player (LocalPlayer).
        var self = serverPlayer(minecraft.player);
        java.util.function.Predicate<net.minecraft.world.entity.Entity> other = e -> e != self;
        return new net.minecraft.world.level.entity.LevelEntityGetter<>() {
            @Override
            public net.minecraft.world.entity.Entity get(int id) {
                var e = all.get(id);
                return other.test(e) ? e : null;
            }

            @Override
            public net.minecraft.world.entity.Entity get(java.util.UUID id) {
                var e = all.get(id);
                return other.test(e) ? e : null;
            }

            @Override
            public Iterable<net.minecraft.world.entity.Entity> getAll() {
                java.util.List<net.minecraft.world.entity.Entity> out = new java.util.ArrayList<>();
                for (var e : all.getAll()) if (other.test(e)) out.add(e);
                return out;
            }

            @Override
            public <U extends net.minecraft.world.entity.Entity> void get(
                    net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, U> test,
                    net.minecraft.util.AbortableIterationConsumer<U> consumer) {
                all.get(test, e -> other.test(e) ? consumer.accept(e) : net.minecraft.util.Continuation.CONTINUE);
            }

            @Override
            public void get(net.minecraft.world.phys.AABB box, java.util.function.Consumer<net.minecraft.world.entity.Entity> consumer) {
                all.get(box, e -> {
                    if (other.test(e)) consumer.accept(e);
                });
            }

            @Override
            public <U extends net.minecraft.world.entity.Entity> void get(
                    net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, U> test,
                    net.minecraft.world.phys.AABB box, net.minecraft.util.AbortableIterationConsumer<U> consumer) {
                all.get(test, box, e -> other.test(e) ? consumer.accept(e) : net.minecraft.util.Continuation.CONTINUE);
            }
        };
    }

    /**
     * Runs {@code run} as the player's client in a world (as(): their client level), with
     * Minecraft.hitResult what they look at (Minecraft.pick: a block, or a mod's entity nearer).
     */
    static void inWorld(net.minecraft.server.level.ServerPlayer player, ThrowingRunnable run) throws Throwable {
        as(player, () -> {
            var hitBefore = minecraft.hitResult;
            var pickedBefore = minecraft.crosshairPickEntity;
            minecraft.hitResult = pick(player);
            minecraft.crosshairPickEntity = minecraft.hitResult instanceof net.minecraft.world.phys.EntityHitResult e ? e.getEntity() : null;
            try {
                run.run();
            } finally {
                minecraft.hitResult = hitBefore;
                minecraft.crosshairPickEntity = pickedBefore;
            }
        });
    }

    /** What the player looks at (GameRenderer.pick): their block, or a nearer entity of the mirror. */
    static net.minecraft.world.phys.HitResult pick(net.minecraft.server.level.ServerPlayer player) {
        double blockRange = player.blockInteractionRange(), entityRange = player.entityInteractionRange();
        var block = player.pick(blockRange, 1f, false);
        var eye = player.getEyePosition();
        var view = player.getViewVector(1f);
        double range = Math.max(blockRange, entityRange);
        double reach = block.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                ? block.getLocation().distanceToSqr(eye) : range * range;
        var box = player.getBoundingBox().expandTowards(view.scale(range)).inflate(1);
        var entity = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(player, eye, eye.add(view.scale(range)), box,
                e -> e != player && !e.isSpectator() && e.isPickable(), Math.min(reach, entityRange * entityRange));
        return entity != null ? entity : block;
    }

    /** HORIZON_JAVA_DEBUG=hud: each HUD frame's size is logged. */
    private static final boolean DEBUG_HUD = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("hud");
    private static final java.util.Map<String, Integer> HUD_FAILURES = new java.util.concurrent.ConcurrentHashMap<>();
    /** The last HUD sent to each player. */
    private static final java.util.Map<java.util.UUID, String> HUD_SENT = new java.util.concurrent.ConcurrentHashMap<>();
    /** Hud's parts under extractPlayerHealth (the hearts, food, armour, air: AppleSkin's). */
    private static final java.util.Set<String> HEALTH_PARTS = java.util.Set.of(
            "extractPlayerHealth", "extractHearts", "extractHeart", "extractFood", "extractArmor", "extractAirBubbles");

    /** The player's HUD as their client's mods draw it, to them when it changed. */
    private static void sendHud(net.minecraft.server.level.ServerPlayer player) {
        String frame = hud(player).toString();
        if (frame.equals(HUD_SENT.get(player.getUUID()))) return;
        HUD_SENT.put(player.getUUID(), frame);
        Natives.hudFrame(player.getUUID().toString(), frame);
    }

    /**
     * What mods draw on the HUD (Gui's HUD extraction, with vanilla's own parts left out: they are
     * Horizon's): Fabric's HUD elements of mods (Jade's tooltip), then the vanilla parts mods
     * inject into, run with only the mods' drawing kept (AppleSkin's food overlay over the food
     * bar), then mods' handlers at the head and end of the whole HUD.
     */
    static com.google.gson.JsonArray hud(net.minecraft.server.level.ServerPlayer player) {
        return hud(player.getUUID(), player.gameMode().isSurvival());
    }

    /** The HUD of the client player now (its images as {@code id}'s game has them). */
    static com.google.gson.JsonArray hud(java.util.UUID id, boolean hurtable) {
        int[] gui = guiSize(id);
        HorizonGraphics graphics = new HorizonGraphics(gui[0], gui[1]);
        graphics.modsOnly = true;
        graphics.imagesSent = imagesSent(id);
        graphics.maxImage = maxImage(id);
        var delta = net.minecraft.client.DeltaTracker.ONE;
        fabricHudElements(graphics, delta);
        // The end of the in-game GUI's frame: the mods' overlays there (MaLiLib's: MiniHUD).
        HorizonHooks.fireGui(minecraft.gui, graphics);
        Object hud;
        try {
            hud = field(net.minecraft.client.gui.Gui.class, minecraft.gui, "hud");
        } catch (ReflectiveOperationException e) {
            return graphics.ops();
        }
        // Mods' fields on the HUD whose initializer had nothing to give when it was made
        // (Enderscape's variables come from its client entrypoint, which runs later).
        HorizonHooks.initFields(hud);
        var hooked = HorizonHooks.hudMethods();
        // Hearts and food show where the player can be hurt (Hud.extractHotbarAndDecorations).
        if (hurtable && !java.util.Collections.disjoint(hooked, HEALTH_PARTS)) {
            hudPart("extractPlayerHealth", () -> call(hud, "extractPlayerHealth", graphics));
        }
        for (String method : hooked) {
            if (HEALTH_PARTS.contains(method) || method.equals("extractRenderState")) continue;
            hudPart(method, () -> {
                for (var m : net.minecraft.client.gui.Hud.class.getDeclaredMethods()) {
                    if (!m.getName().equals(method) || m.isSynthetic()) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1 && params[0] == net.minecraft.client.gui.GuiGraphicsExtractor.class) {
                        m.setAccessible(true);
                        m.invoke(hud, graphics);
                    } else if (params.length == 2 && params[0] == net.minecraft.client.gui.GuiGraphicsExtractor.class
                            && params[1] == net.minecraft.client.DeltaTracker.class) {
                        m.setAccessible(true);
                        m.invoke(hud, graphics, delta);
                    }
                }
            });
        }
        if (hooked.contains("extractRenderState")) {
            hudPart("extractRenderState", () -> HorizonHooks.fireHud(hud, "extractRenderState", graphics, delta));
        }
        if (DEBUG_HUD) {
            StringBuilder seq = new StringBuilder("hud ops:");
            for (var op : graphics.ops()) {
                var o = op.getAsJsonObject();
                seq.append(' ').append(o.has("op") ? o.get("op").getAsString() : "?");
                if (o.has("texture")) seq.append('(').append(o.get("texture").getAsString()).append(')');
                if (o.has("width")) seq.append('[').append(o.get("width")).append('x').append(o.get("height")).append(']');
            }
            Natives.log(seq.toString());
            Natives.log("hud: " + graphics.ops().size() + " ops kept, " + graphics.dropped + " vanilla ones left out; parts "
                    + hooked + "; looking at " + (minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult b
                    ? b.getBlockPos().toShortString() + " " + minecraft.level.getBlockState(b.getBlockPos())
                    : String.valueOf(minecraft.hitResult)) + "; wearing " + (minecraft.player == null ? "-"
                    : minecraft.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)));
        }
        return graphics.ops();
    }

    private static void call(Object target, String name, Object... args) throws Throwable {
        for (var m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                m.invoke(target, args);
                return;
            }
        }
    }

    /** One part of the HUD; one failing (each frame) is told a few times and the rest still draw. */
    private static void hudPart(String part, ThrowingRunnable run) {
        // A part that failed 3 frames running is left out: one that needs the GPU (Xaero's
        // minimap: RenderSystem.getDevice()) fails every frame.
        Integer failed = HUD_FAILURES.get(part);
        if (failed != null && failed >= 3) return;
        try {
            run.run();
            if (failed != null) HUD_FAILURES.remove(part);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            int count = HUD_FAILURES.merge(part, 1, Integer::sum);
            if (count == 1 && HUD_LOGGED.add(part)) {
                Natives.log("Horizon's client: the HUD's " + part + " failed: " + root);
                HorizonFailures.report("the HUD", root);
                StackTraceElement[] frames = root.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 10); f++) Natives.log("    at " + frames[f]);
            } else if (count == 3) {
                Natives.log("Horizon's client: the HUD's " + part + " failed 3 frames running; left out");
            }
        }
    }

    private static final java.util.Set<String> HUD_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Fabric's HUD elements that mods added (HudElementRegistry: addFirst, attachElement*,
     * addLast, replaceElement), in the HUD's order; vanilla's own elements are left out.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void fabricHudElements(HorizonGraphics graphics, net.minecraft.client.DeltaTracker delta) {
        java.util.List<Object> roots = new java.util.ArrayList<>();
        Class<?> impl;
        try {
            impl = Class.forName("net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl");
            Object first = field(impl, null, "FIRST");
            var vanilla = (java.util.List<Object>) field(impl, null, "VANILLA_ELEMENT_IDS");
            var byId = (java.util.Map<Object, Object>) field(impl, null, "ROOT_ELEMENTS");
            Object last = field(impl, null, "LAST");
            roots.add(first);
            for (Object id : vanilla) if (byId.get(id) != null) roots.add(byId.get(id));
            for (Object root : byId.values()) if (!roots.contains(root)) roots.add(root);
            if (!roots.contains(last)) roots.add(last);
        } catch (Throwable t) {
            if (HUD_FAILURES.merge("fabric", 1, Integer::sum) == 1) Natives.log("Horizon's client: no Fabric HUD elements: " + t);
            return;
        }
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement none = (g, d) -> {};
        for (Object root : roots) {
            if (root == null) continue;
            java.util.List<Object> layers;
            try {
                var accessor = root.getClass().getDeclaredMethod("layers");
                accessor.setAccessible(true);
                layers = (java.util.List<Object>) accessor.invoke(root);
            } catch (ReflectiveOperationException e) {
                continue;
            }
            for (Object o : new java.util.ArrayList<>(layers)) {
                var layer = (net.fabricmc.fabric.impl.client.rendering.hud.HudLayer) o;
                if (layer.isRemoved()) continue;
                var element = layer.element(none);
                if (element == none || !HorizonDeviceMods.runs(element.getClass())) continue;
                int before = graphics.ops().size();
                hudPart(String.valueOf(layer.id()), () -> element.extractRenderState(graphics, delta));
                if (DEBUG_HUD) Natives.log("hud element " + layer.id() + ": " + (graphics.ops().size() - before) + " ops");
            }
        }
    }

    /** What each player's game has of mods' dynamic textures (name to version). */
    private static final java.util.Map<java.util.UUID, java.util.Map<String, Integer>> IMAGES_SENT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The largest image player {@code id}'s game takes: this process's own game's (in memory)
     * more than a network player's.
     */
    static int maxImage(java.util.UUID id) {
        return id != null && id.equals(HorizonDeviceMods.own) ? HorizonGraphics.LOCAL_MAX_IMAGE : HorizonGraphics.REMOTE_MAX_IMAGE;
    }

    static java.util.Map<String, Integer> imagesSent(java.util.UUID player) {
        return IMAGES_SENT.computeIfAbsent(player, p -> new java.util.concurrent.ConcurrentHashMap<>());
    }

    /** One frame of a player's mod screen, as Horizon draws it (HorizonGraphics' operations). */
    /** When each screen was last drawn (System.nanoTime), for {@link #deltaTicks}. */
    private static final java.util.Map<Object, Long> EXTRACTED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * The ticks since {@code screen} was last drawn, as vanilla's Gui passes a screen
     * (DeltaTracker.getGameTimeDeltaTicks: a frame's time, in ticks). Screens animate by it:
     * Cloth Config's lists scroll toward their target by it, and never moved with 0.
     */
    static float deltaTicks(Object screen) {
        long now = System.nanoTime();
        Long last = EXTRACTED.put(screen, now);
        if (last == null) return 1f;
        return (float) Math.min(5.0, (now - last) / 50_000_000.0);
    }

    public static String frame(java.util.UUID player, int mouseX, int mouseY) {
        var screen = SCREENS.get(player);
        if (screen == null) return null;
        int[] gui = guiSize(player);
        HorizonGraphics graphics = new HorizonGraphics(gui[0], gui[1]);
        graphics.imagesSent = imagesSent(player);
        graphics.maxImage = maxImage(player);
        try {
            as(OWNERS.get(player), () -> {
                mouseAt(mouseX, mouseY);
                HorizonScreenEvents.extract(screen, graphics, mouseX, mouseY, deltaTicks(screen));
            });
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: a screen failed to draw: " + root);
            HorizonFailures.report("drawing a screen", root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
        }
        var ops = graphics.ops();
        if (DEBUG_INPUT && ops.isEmpty()) Natives.log("overlay: " + graphics.dropped + " operations dropped as vanilla's");
        if (typing(screen)) {
            var op = new com.google.gson.JsonObject();
            op.addProperty("op", "typing");
            ops.add(op);
        }
        return ops.toString();
    }

    /**
     * Whether a text box of the screen has the keyboard (its focused widget, or a recipe book's
     * search): then it takes every key, the inventory key's letter too, as vanilla's do.
     */
    static boolean typing(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.gui.components.events.GuiEventListener at = screen;
        while (at instanceof net.minecraft.client.gui.components.events.ContainerEventHandler parent
                && parent.getFocused() != null) {
            at = parent.getFocused();
        }
        if (at instanceof net.minecraft.client.gui.components.EditBox box && box.isFocused()) return true;
        // A text field a mod draws beside the screen (a recipe viewer's search box, its own
        // EditBox or a widget of its own): focused, as its focus chain or EditBox says.
        if (at != screen && at.isFocused() && textInput(at.getClass())) return true;
        if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                && (HorizonTextFields.focused() || HorizonScreenEvents.modTakesTyping(screen))) return true;
        if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen<?>) {
            try {
                var book = field(net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen.class, screen, "recipeBookComponent");
                var search = field(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class, book, "searchBox");
                return book instanceof net.minecraft.client.gui.screens.recipebook.RecipeBookComponent<?> component
                        && component.isVisible()
                        && search instanceof net.minecraft.client.gui.components.EditBox box && box.isFocused();
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
        return false;
    }

    private static final java.util.Map<Class<?>, Boolean> TEXT_INPUTS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A widget that takes typed characters itself: an EditBox, or a mod's class with its own
     * charTyped (vanilla's own widgets other than EditBox don't type).
     */
    static boolean textInput(Class<?> type) {
        return TEXT_INPUTS.computeIfAbsent(type, t -> {
            if (net.minecraft.client.gui.components.EditBox.class.isAssignableFrom(t)) return true;
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) {
                if (c.getName().startsWith("net.minecraft.")) return false;
                for (var m : c.getDeclaredMethods()) {
                    if (m.getName().equals("charTyped") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == net.minecraft.client.input.CharacterEvent.class) {
                        return !net.minecraft.client.gui.components.events.ContainerEventHandler.class.isAssignableFrom(t);
                    }
                }
            }
            return false;
        });
    }

    static Object field(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    // ---- Frames to Horizon, input from it -------------------------------------------------------

    /** HORIZON_JAVA_DEBUG=screens: each input to a mod's screen is logged. */
    private static final boolean DEBUG_INPUT = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("screens");
    /** HORIZON_JAVA_DEBUG=frames: each new screen frame's textures and text, in one line (regress checks read it). */
    private static final boolean DEBUG_FRAMES = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("frames");

    /** A frame in one line: the screen, its ops, the textures it blits and its text (text@y), in drawing order. */
    private static void logFrame(Object screen, String frame) {
        var ops = com.google.gson.JsonParser.parseString(frame).getAsJsonArray();
        var textures = new java.util.TreeSet<String>();
        var texts = new java.util.ArrayList<String>();
        for (var e : ops) {
            var o = e.getAsJsonObject();
            if (o.has("texture")) textures.add(o.get("texture").getAsString());
            String at = o.has("y") ? "@" + Math.round(o.get("y").getAsDouble()) : "";
            if (o.has("text")) texts.add(o.get("text").getAsString() + at);
            else if (o.has("json")) {
                StringBuilder text = new StringBuilder();
                plainText(com.google.gson.JsonParser.parseString(o.get("json").getAsString()), text);
                texts.add(text + at);
            }
        }
        Natives.log("frame of " + (screen == null ? "?" : screen.getClass().getName()) + ": " + ops.size()
                + " ops; textures " + textures + "; text " + String.join(" | ", texts));
    }

    /** The "text" values of a component's JSON, in order. */
    private static void plainText(com.google.gson.JsonElement json, StringBuilder out) {
        if (json.isJsonPrimitive()) out.append(json.getAsString());
        else if (json.isJsonArray()) json.getAsJsonArray().forEach(e -> plainText(e, out));
        else if (json.isJsonObject()) {
            var o = json.getAsJsonObject();
            if (o.has("text")) out.append(o.get("text").getAsString());
            if (o.has("extra")) plainText(o.get("extra"), out);
        }
    }

    /** HORIZON_JAVA_DEBUG=screentime: how long each screen frame and input takes. */
    private static final boolean DEBUG_TIME = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("screentime");

    /** Each player's mouse over their screen, in GUI pixels. */
    private static final java.util.Map<java.util.UUID, double[]> MOUSE = new java.util.concurrent.ConcurrentHashMap<>();
    /** When each player's screen was last drawn (System.nanoTime). */
    private static final java.util.Map<java.util.UUID, Long> DRAWN = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * A mouse move draws the screen again only when it was drawn longer ago than this; else the
     * tick's frame shows the hover. A fast mouse sent a move every frame, and each was drawn at
     * once with the settings files looked over (Cloth Config's screens lagged the server).
     */
    private static final long MOVE_FRAME_NANOS = 40_000_000L;
    /** The last frame sent to each player. */
    private static final java.util.Map<java.util.UUID, String> SENT = new java.util.concurrent.ConcurrentHashMap<>();
    /** The player whose input runs now (their screen's clicks are theirs). */
    private static net.minecraft.server.level.ServerPlayer acting;

    /** Each open mod screen's frame, to its player when it changed (the server's tick end). */
    public static void sendFrames() {
        tasks();
        for (var id : new java.util.ArrayList<>(SCREENS.keySet())) {
            tickScreen(id);
            send(id);
        }
        for (var id : new java.util.ArrayList<>(OVERLAYS.keySet())) {
            if (SCREENS.containsKey(id)) continue;
            var screen = OVERLAYS.get(id);
            var player = HorizonServer.player(id);
            if (screen == null || player == null) continue;
            try {
                as(player, () -> HorizonScreenEvents.tick(screen));
            } catch (Throwable t) {
                if (TICK_FAILED.add(screen)) Natives.log("Horizon's client: the inventory overlay's tick failed: " + t);
            }
            sendOverlay(id);
        }
    }

    // ---- Mods' overlays on Horizon's inventory (REI's item list) -----------------------------

    /** The inventory overlay's "window": input and frames of the screen under Horizon's inventory. */
    static final int OVERLAY_WINDOW = -3;
    /**
     * Each player's inventory screen while Horizon's inventory (or a container of its own) is
     * open: a container screen in the headless client (HorizonOverlayScreen), which mods draw their overlays on
     * (REI's item list, its search box). Only what mods draw goes to Horizon (modsOnly): the
     * inventory itself is Horizon's.
     */
    private static final java.util.Map<java.util.UUID, net.minecraft.client.gui.screens.Screen> OVERLAYS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, String> OVERLAY_SENT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, double[]> OVERLAY_MOUSE = new java.util.concurrent.ConcurrentHashMap<>();

    private static String overlayFrame(java.util.UUID id) {
        var screen = OVERLAYS.get(id);
        var player = HorizonServer.player(id);
        if (screen == null || player == null) return null;
        double[] mouse = OVERLAY_MOUSE.getOrDefault(id, new double[] {-1, -1});
        int[] gui = guiSize(id);
        HorizonGraphics graphics = new HorizonGraphics(gui[0], gui[1]);
        graphics.modsOnly = true;
        graphics.imagesSent = imagesSent(id);
        graphics.maxImage = maxImage(id);
        try {
            as(player, () -> {
                mouseAt(mouse[0], mouse[1]);
                HorizonScreenEvents.extract(screen, graphics, (int) mouse[0], (int) mouse[1], deltaTicks(screen));
            });
        } catch (Throwable t) {
            if (TICK_FAILED.add(String.valueOf(t))) {
                Natives.log("Horizon's client: the inventory overlay failed to draw: " + t);
                HorizonFailures.report("drawing over the inventory", t);
                if (DEBUG_INPUT) for (StackTraceElement frame : t.getStackTrace()) Natives.log("    at " + frame);
            }
        }
        var ops = graphics.ops();
        if (DEBUG_INPUT && ops.isEmpty()) Natives.log("overlay: " + graphics.dropped + " operations dropped as vanilla's");
        if (typing(screen)) {
            var op = new com.google.gson.JsonObject();
            op.addProperty("op", "typing");
            ops.add(op);
        }
        return ops.toString();
    }

    private static void sendOverlay(java.util.UUID id) {
        String frame = overlayFrame(id);
        if (frame == null || frame.equals(OVERLAY_SENT.get(id))) return;
        OVERLAY_SENT.put(id, frame);
        if (DEBUG_INPUT) Natives.log("overlay frame: " + frame.length() + " chars");
        Natives.overlayFrame(id.toString(), frame);
    }

    /**
     * Input to a player's inventory overlay (window -3): {@code open} and {@code close} as
     * Horizon's inventory opens and closes, and the mouse and keys as a screen's (a key over one
     * of Horizon's slots names it: the screen's slot there is hovered).
     */
    private static void overlayInput(java.util.UUID id, com.google.gson.JsonObject in) {
        var player = HorizonServer.player(id);
        if (player == null) return;
        String kind = in.get("kind").getAsString();
        if (DEBUG_INPUT) Natives.log("overlay input " + in);
        try {
            switch (kind) {
                case "open" -> {
                    if (in.has("width")) sizeInput(id, in);
                    if (OVERLAYS.containsKey(id)) return;
                    as(player, () -> {
                        var screen = new HorizonOverlayScreen(player.inventoryMenu, player.getInventory());
                        OVERLAYS.put(id, screen);
                        set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", screen);
                        screen.added();
                        screen.init(width, height);
                    });
                    OVERLAY_SENT.remove(id);
                }
                case "close" -> {
                    var screen = OVERLAYS.remove(id);
                    OVERLAY_SENT.remove(id);
                    if (screen != null) as(player, () -> HorizonScreenEvents.removed(screen));
                    // An empty frame: nothing left over the next inventory before it draws.
                    Natives.overlayFrame(id.toString(), "[]");
                }
                default -> {
                    var screen = OVERLAYS.get(id);
                    if (screen == null) return;
                    double[] mouse = OVERLAY_MOUSE.getOrDefault(id, new double[] {-1, -1});
                    double x = in.has("x") ? in.get("x").getAsDouble() : mouse[0];
                    double y = in.has("y") ? in.get("y").getAsDouble() : mouse[1];
                    OVERLAY_MOUSE.put(id, new double[] {x, y});
                    mouseAt(x, y);
                    int button = in.has("button") ? in.get("button").getAsInt() : 0;
                    int mods = in.has("mods") ? in.get("mods").getAsInt() : 0;
                    var event = new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(button, mods));
                    if (kind.equals("press") || kind.equals("release") || kind.equals("scroll")) overlayFrame(id);
                    acting = player;
                    try {
                        as(player, () -> {
                            switch (kind) {
                                case "size" -> {
                                    sizeInput(id, in);
                                    screen.resize(width, height);
                                }
                                case "move" -> screen.mouseMoved(x, y);
                                // (horizon: a press Horizon's inventory takes itself, on its slots.)
                                case "press" -> HorizonScreenEvents.click(minecraft, screen, event, false,
                                        !(in.has("horizon") && in.get("horizon").getAsBoolean()));
                                case "release" -> HorizonScreenEvents.release(minecraft, screen, event);
                                case "drag" -> HorizonScreenEvents.drag(minecraft, screen, event,
                                        in.has("dx") ? in.get("dx").getAsDouble() : 0, in.has("dy") ? in.get("dy").getAsDouble() : 0);
                                case "scroll" -> HorizonScreenEvents.scroll(minecraft, screen, x, y, in.get("h").getAsDouble(), in.get("v").getAsDouble());
                                case "key" -> {
                                    int key = in.get("key").getAsInt();
                                    // Over one of Horizon's slots: the screen's slot there is the
                                    // hovered one, as the mouse over it makes it in vanilla.
                                    if (in.has("slot") && screen instanceof HorizonOverlayScreen overlay) overlay.hover(in.get("slot").getAsInt());
                                    HorizonScreenEvents.key(minecraft, screen, new net.minecraft.client.input.KeyEvent(key,
                                            in.has("keycode") ? in.get("keycode").getAsInt() : key, mods));
                                }
                                case "char" -> HorizonScreenEvents.character(minecraft, screen, new net.minecraft.client.input.CharacterEvent(in.get("char").getAsInt()));
                                default -> {}
                            }
                        });
                    } finally {
                        acting = null;
                    }
                    if (kind.equals("move") || kind.equals("drag")) {
                        // As a screen's: no settings files looked over; the tick draws it.
                        if (kind.equals("drag")) HorizonServer.afterScreenInput();
                        Long drawn = DRAWN.get(id);
                        long now = System.nanoTime();
                        if (drawn == null || now - drawn >= MOVE_FRAME_NANOS) {
                            DRAWN.put(id, now);
                            sendOverlay(id);
                        }
                        return;
                    }
                    HorizonServer.afterScreenInput();
                    HorizonClientConfig.afterInput(id);
                    sendOverlay(id);
                }
            }
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the inventory overlay's " + kind + " failed: " + root);
            HorizonFailures.report("the inventory overlay", root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
        }
    }

    private static final java.util.Set<Object> TICK_FAILED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Screen.tick, as the client's tick runs it (a container screen's containerTick). */
    private static void tickScreen(java.util.UUID id) {
        var screen = SCREENS.get(id);
        var player = OWNERS.get(id);
        if (screen == null || player == null) return;
        try {
            syncClientMenu(id);
            // With the player's keys down, as a screen's tick reads them (KeyMapping.isDown).
            as(player, () -> withKeys(player.getUUID(), () -> HorizonScreenEvents.tick(screen)));
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            // Once a screen: a failing tick fails each tick.
            if (TICK_FAILED.add(screen)) {
                Natives.log("Horizon's client: a screen's tick failed: " + root);
                HorizonFailures.report("a screen's tick", root);
                for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
            }
        }
    }

    private static java.lang.reflect.Method runAllTasks;

    /**
     * The client's thread is the server thread (where Horizon runs every screen and message):
     * what is handed to it there runs at once, and what other threads hand it runs here, each
     * tick, as the client's frame would run it.
     */
    static void tasks() {
        try {
            if (minecraft.getRunningThread() == null) {
                set(Minecraft.class, minecraft, "gameThread", Thread.currentThread());
            }
            // Its packet processor on that thread (mods' own packets' handlers check they run
            // there: PacketUtils.ensureRunningOnSameThread), the packets queued from others run here.
            if (minecraft.packetProcessor() == null) {
                set(Minecraft.class, minecraft, "packetProcessor", new net.minecraft.network.PacketProcessor(minecraft.getRunningThread()));
            }
            minecraft.packetProcessor().processQueuedPackets();
            if (runAllTasks == null) {
                runAllTasks = net.minecraft.util.thread.BlockableEventLoop.class.getDeclaredMethod("runAllTasks");
                runAllTasks.setAccessible(true);
            }
            runAllTasks.invoke(minecraft);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: a client task failed: " + root);
            HorizonFailures.report("a client task", root);
        }
    }

    private static void send(java.util.UUID id) {
        DRAWN.put(id, System.nanoTime());
        syncClientMenu(id);
        double[] mouse = MOUSE.getOrDefault(id, new double[] {-1, -1});
        long start = System.nanoTime();
        String frame = frame(id, (int) mouse[0], (int) mouse[1]);
        if (DEBUG_TIME) Natives.log("screen frame: " + (System.nanoTime() - start) / 1000 + " us, "
                + (frame == null ? 0 : frame.length()) + " chars" + (frame != null && frame.equals(SENT.get(id)) ? " (same)" : ""));
        if (frame == null || frame.equals(SENT.get(id))) return;
        SENT.put(id, frame);
        if (DEBUG_FRAMES) logFrame(SCREENS.get(id), frame);
        if (DEBUG_INPUT) Natives.log("screen frame " + (frame.length() > 4000 ? frame.substring(0, 4000) + "..." : frame));
        Natives.screenFrame(id.toString(), frame);
    }

    /**
     * Input to a player's mod screen, from Horizon's window (JSON): {@code size} (its GUI
     * width and height), {@code move}, {@code press}, {@code release}, {@code drag}, {@code
     * scroll}, {@code key} and {@code char}, with the mouse in GUI pixels.
     */
    public static void input(String uuid, String json) {
        Thread.currentThread().setContextClassLoader(HorizonClient.class.getClassLoader());
        java.util.UUID id = java.util.UUID.fromString(uuid);
        var in = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        String kind = in.get("kind").getAsString();
        if (kind.equals("bind") && in.has("scroll")) {
            scroll(id, in.has("x") ? in.get("x").getAsDouble() : 0, in.get("scroll").getAsDouble(),
                    in.has("slot") ? in.get("slot").getAsInt() : -1);
            return;
        }
        if (kind.equals("bind") && in.has("mouse")) {
            int button = in.get("mouse").getAsInt();
            boolean down = in.get("down").getAsBoolean();
            bindMouse(id, button, down, mappings(in));
            mousePress(id, button, in.has("mods") ? in.get("mods").getAsInt() : 0, down);
            return;
        }
        if (kind.equals("bind")) {
            int key = in.get("key").getAsInt();
            boolean down = in.get("down").getAsBoolean();
            bind(id, key, down, mappings(in));
            keyPress(id, key, in.has("keycode") ? in.get("keycode").getAsInt() : key,
                    in.has("mods") ? in.get("mods").getAsInt() : 0, down);
            return;
        }
        // A mod's settings screen, from the player's Mods page (as /modconfig opens it).
        if (kind.equals("modconfig")) {
            var player = HorizonServer.player(id);
            var factory = configScreens().get(in.get("mod").getAsString());
            if (DEBUG_INPUT) Natives.log("settings screen of " + in.get("mod").getAsString() + " for " + player + ": " + (factory != null));
            if (player == null || factory == null) return;
            try {
                as(player, () -> setScreen(factory.apply(new HorizonModsPage())));
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                Natives.log("Horizon's client: " + in.get("mod").getAsString() + "'s settings screen failed: " + root);
                HorizonFailures.report("its settings screen", root);
                for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
            }
            return;
        }
        // The window's HUD size (window -2: no screen), which the HUD lays out in.
        if (in.has("window") && in.get("window").getAsInt() == -2) {
            if ("size".equals(in.get("kind").getAsString())) sizeInput(id, in);
            return;
        }
        if (in.has("window") && in.get("window").getAsInt() == OVERLAY_WINDOW) {
            overlayInput(id, in);
            return;
        }
        var screen = SCREENS.get(id);
        var player = OWNERS.get(id);
        if (screen == null || player == null) return;
        if (DEBUG_INPUT) Natives.log("screen input " + json + " to " + screen.getClass().getName());
        double x = in.has("x") ? in.get("x").getAsDouble() : MOUSE.getOrDefault(id, new double[2])[0];
        double y = in.has("y") ? in.get("y").getAsDouble() : MOUSE.getOrDefault(id, new double[2])[1];
        int button = in.has("button") ? in.get("button").getAsInt() : 0;
        int mods = in.has("mods") ? in.get("mods").getAsInt() : 0;
        double[] last = MOUSE.get(id);
        MOUSE.put(id, new double[] {x, y});
        // A click where the mouse wasn't at the last frame: a frame there first, as the window
        // draws before it clicks (widgets that keep their hover from drawing, SuperMartijn's,
        // take the click only over what they drew as hovered).
        boolean moved = last == null || last[0] != x || last[1] != y;
        if (moved && (kind.equals("press") || kind.equals("release") || kind.equals("scroll"))) {
            frame(id, (int) x, (int) y);
        }
        var event = new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(button, mods));
        try {
            acting = player;
            as(player, () -> {
                switch (kind) {
                    case "size" -> {
                        sizeInput(id, in);
                        screen.resize(width, height);
                    }
                    case "move" -> screen.mouseMoved(x, y);
                    case "press" -> {
                        HorizonScreenEvents.click(minecraft, screen, event, in.has("double") && in.get("double").getAsBoolean());
                    }
                    case "release" -> HorizonScreenEvents.release(minecraft, screen, event);
                    case "drag" -> HorizonScreenEvents.drag(minecraft, screen, event,
                            in.has("dx") ? in.get("dx").getAsDouble() : 0, in.has("dy") ? in.get("dy").getAsDouble() : 0);
                    case "scroll" -> HorizonScreenEvents.scroll(minecraft, screen, x, y, in.get("h").getAsDouble(), in.get("v").getAsDouble());
                    case "key" -> {
                        int key = in.get("key").getAsInt();
                        HorizonScreenEvents.key(minecraft, screen, new net.minecraft.client.input.KeyEvent(
                                key, in.has("keycode") ? in.get("keycode").getAsInt() : key, mods));
                        // Escape (41) on a client-only screen: Screen.keyPressed closes it
                        // (onClose: setScreen(null), or its parent); one that took the key and
                        // stayed, though it closes on Escape, is closed, so the window never
                        // keeps a screen it can't leave.
                        if (key == 41 && CLIENT_ONLY.contains(id) && SCREENS.get(id) == screen && screen.shouldCloseOnEsc()) {
                            setScreen(null);
                        }
                    }
                    // The window left a client-only screen (it closed it itself).
                    case "close" -> shut(id, player);
                    case "char" -> HorizonScreenEvents.character(minecraft, screen, new net.minecraft.client.input.CharacterEvent(in.get("char").getAsInt()));
                    default -> {}
                }
            });
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: a screen's " + kind + " failed: " + root);
            HorizonFailures.report("a screen", root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
        } finally {
            acting = null;
        }
        long handled = System.nanoTime();
        if (kind.equals("move") || kind.equals("drag")) {
            // A move saves nothing and moves no item, a drag saves nothing (the window sends
            // one per mouse event: hundreds a second): no settings files looked over, drawn
            // now only if the last frame is old (else the tick's frame shows it).
            if (kind.equals("drag")) HorizonServer.afterScreenInput();
            Long drawn = DRAWN.get(id);
            if (drawn == null || handled - drawn >= MOVE_FRAME_NANOS) send(id);
            return;
        }
        HorizonServer.afterScreenInput();
        // Settings a player other than the owner saved go to their own folder (HorizonClientConfig).
        HorizonClientConfig.afterInput(id);
        if (DEBUG_TIME) Natives.log("screen input " + kind + ": after it " + (System.nanoTime() - handled) / 1000 + " us");
        send(id);
    }

    /** MultiPlayerGameMode.handleContainerInput from a mod's screen: the server's menu clicked. */
    public static void containerInput(int containerId, int slot, int button, net.minecraft.world.inventory.ContainerInput input) {
        var player = acting;
        if (player == null || player.containerMenu == null || player.containerMenu.containerId != containerId) return;
        var menu = player.containerMenu;
        if (!menu.isValidSlotIndex(slot) && slot != -999 && slot != -1) return;
        if (menu.stillValid(player)) {
            menu.clicked(slot, button, input, player);
            menu.broadcastChanges();
        } else {
            player.closeContainer();
        }
    }

    /** Each player's client menu, where the screen has one of its own (openScreen). */
    private static final java.util.Map<java.util.UUID, net.minecraft.world.inventory.AbstractContainerMenu> CLIENT_MENUS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The client's menu for a server's, made by its type; null when it can't be made apart. */
    private static net.minecraft.world.inventory.AbstractContainerMenu clientMenu(
            net.minecraft.world.inventory.AbstractContainerMenu menu, net.minecraft.server.level.ServerPlayer player) {
        var type = menu.getType();
        // Fabric's extended menus: made from their opening data, written and read as the
        // network would (openingData), in the client's level (CC: Tweaked's computer keeps
        // its terminal on the client's menu only).
        Object[] opening = OPENING.remove(player.getUUID());
        boolean extended = type instanceof net.fabricmc.fabric.api.menu.v1.ExtendedMenuType<?, ?>;
        if (extended && (opening == null || (int) opening[0] != menu.containerId)) return null;
        if (!extended && type.getClass() != net.minecraft.world.inventory.MenuType.class) return null;
        try {
            var made = extended
                    ? extendedMenu((net.fabricmc.fabric.api.menu.v1.ExtendedMenuType) type, menu.containerId, player, opening)
                    : type.create(menu.containerId, player.getInventory());
            if (made == null || made.slots.size() != menu.slots.size()) return null;
            syncClientMenu(menu, made);
            return made;
        } catch (Throwable t) {
            if (extended) Natives.log("Horizon's client: the client's menu of " + net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(type)
                    + " couldn't be made (its screen shows the server's): " + t);
            return null;
        }
    }

    /** Each player's last extended menu's opening: {containerId, codec, data}. */
    private static final java.util.Map<java.util.UUID, Object[]> OPENING = new java.util.concurrent.ConcurrentHashMap<>();

    /** Fabric's extended menu opening sent to a player (HorizonServer.payload): kept for its client menu. */
    public static void openingData(net.minecraft.server.level.ServerPlayer player, int containerId, Object codec, Object data) {
        OPENING.put(player.getUUID(), new Object[]{containerId, codec, data});
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.minecraft.world.inventory.AbstractContainerMenu extendedMenu(
            net.fabricmc.fabric.api.menu.v1.ExtendedMenuType type, int containerId,
            net.minecraft.server.level.ServerPlayer player, Object[] opening) {
        var codec = (net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Object>) opening[1];
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess());
        codec.encode(buf, opening[2]);
        Object data = codec.decode(buf);
        return type.create(containerId, player.getInventory(), data);
    }

    /**
     * The server's menu as its client copy shows it (the slots, the data slots and the carried
     * stack, as its synchronizer's packets carry them). The player's own inventory is one object
     * on both sides here: its slots are left as they are.
     */
    @SuppressWarnings("unchecked")
    private static void syncClientMenu(net.minecraft.world.inventory.AbstractContainerMenu server,
                                       net.minecraft.world.inventory.AbstractContainerMenu client) {
        int state = server.getStateId();
        for (int i = 0; i < server.slots.size() && i < client.slots.size(); i++) {
            var mine = client.slots.get(i);
            if (mine.container instanceof net.minecraft.world.entity.player.Inventory) continue;
            var want = server.slots.get(i).getItem();
            if (!net.minecraft.world.item.ItemStack.matches(mine.getItem(), want)) client.setItem(i, state, want.copy());
        }
        try {
            Field field = net.minecraft.world.inventory.AbstractContainerMenu.class.getDeclaredField("dataSlots");
            field.setAccessible(true);
            var theirs = (java.util.List<net.minecraft.world.inventory.DataSlot>) field.get(server);
            var ours = (java.util.List<net.minecraft.world.inventory.DataSlot>) field.get(client);
            for (int i = 0; i < theirs.size() && i < ours.size(); i++) {
                int value = theirs.get(i).get();
                if (ours.get(i).get() != value) client.setData(i, value);
            }
        } catch (ReflectiveOperationException e) {
            // No data slots to copy.
        }
        if (!net.minecraft.world.item.ItemStack.matches(client.getCarried(), server.getCarried())) {
            client.setCarried(server.getCarried().copy());
        }
    }

    /** A player's client menu brought up to the server's (before each frame). */
    private static void syncClientMenu(java.util.UUID id) {
        var client = CLIENT_MENUS.get(id);
        var player = OWNERS.get(id);
        if (client == null || player == null) return;
        var server = player.containerMenu;
        if (server == null || server.containerId != client.containerId) return;
        try {
            as(player, () -> syncClientMenu(server, client));
        } catch (Throwable t) {
            Natives.log("Horizon's client: a client menu could not follow its server's: " + t);
        }
    }

    /** Each client player's server player (its game mode, for one). */
    private static final java.util.Map<Object, net.minecraft.server.level.ServerPlayer> SERVER_PLAYERS =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /** A client player's server player (who a mod's client message is from). */
    public static net.minecraft.server.level.ServerPlayer serverPlayer(Object local) {
        return local == null ? null : SERVER_PLAYERS.get(local);
    }

    /** AbstractClientPlayer.gameMode, which reads the tab list's entry: the server player's. */
    public static net.minecraft.world.level.GameType gameMode(Object local) {
        var player = SERVER_PLAYERS.get(local);
        return player == null ? net.minecraft.world.level.GameType.SURVIVAL : player.gameMode();
    }

    /**
     * MultiPlayerGameMode.handlePlaceRecipe, as ServerGamePacketListenerImpl.handlePlaceRecipe
     * answers it: the recipe book's click fills the menu's grid, or shows the recipe as a ghost
     * when the ingredients are missing (ClientboundPlaceGhostRecipePacket, straight to the screen).
     */
    public static void placeRecipe(int containerId, net.minecraft.world.item.crafting.display.RecipeDisplayId id,
                                   boolean useMaxItems) {
        var player = acting;
        if (player == null || player.isSpectator() || player.containerMenu == null
                || player.containerMenu.containerId != containerId) return;
        if (!player.containerMenu.stillValid(player)) {
            player.closeContainer();
            return;
        }
        var info = player.level().getServer().getRecipeManager().getRecipeFromDisplay(id);
        if (info == null) return;
        var holder = info.parent();
        if (!(player.containerMenu instanceof net.minecraft.world.inventory.RecipeBookMenu menu)) return;
        if (holder.value().placementInfo().isImpossibleToPlace()) return;
        var after = menu.handlePlacement(useMaxItems, player.isCreative(), holder, player.level(), player.getInventory());
        menu.broadcastChanges();
        if (after == net.minecraft.world.inventory.RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE
                && SCREENS.get(player.getUUID()) instanceof net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener screen) {
            screen.fillGhostRecipe(info.display().display());
        }
    }

    /** MultiPlayerGameMode.handleInventoryButtonClick: the menu's button (a stonecutter's recipe). */
    public static void buttonClick(int containerId, int button) {
        var player = acting;
        if (player == null || player.containerMenu == null || player.containerMenu.containerId != containerId) return;
        if (player.containerMenu.clickMenuButton(player, button)) player.containerMenu.broadcastChanges();
    }
}
