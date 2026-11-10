# The Java mod bridge (fork `iaxivers/horizon-java-bridge`, branch `java-bridge`)

Fabric Java mods run inside Horizon: a JVM in the game's process runs Fabric Loader with the real
26.3 jar, mods and all. Horizon stays the game: its world, its server, its rendering. The Java
side is a **mirror**: a `MinecraftServer` and levels made without their constructors, whose
blocks are Horizon's, so mods' code runs against Horizon's world. Mods' content (blocks, items,
assets) is converted to Horizon content mods once. Their screens are drawn by Horizon from what
the mod's own client code draws headless.

Goal: big Fabric packs (SkyFactory-sized). Read this page, then work; test headless before
saying something works.

## Where things are

| Piece | Files |
|---|---|
| JVM, JNI natives, Rust→Java calls | `crates/horizon-jvm/src/lib.rs` (natives: `native_*`), `events.rs` (every call into Java: `server_tick`, `use_block`, `menu_click`, `screen_input`, `random_ticks`, `block_entities`, `game_time`, `export_*`), `content.rs` (content build + cache, `FORMAT`), `libs.rs` (Fabric's libraries) |
| Boot | `java/dev/horizonlab/bridge/Boot.java`, `fabric/FabricBoot.java` (Knot, entrypoints, headless client boot), `Natives.java` (Java→Rust) |
| Server mirror | `java/platform/.../HorizonServer.java`: set-up (`setUp`, `level`), the tick (`tick`: players, events, hooks, menus, block entities, frames), menus shown in Horizon's window (`syncMenus`, `SHOWN`), payloads (`payload`), `syncInventories` (end of every call) |
| Horizon's items for mods' blocks | `HorizonItems.java` (shadows, block callbacks, held items, changes back); Rust `java_server/items.rs` (`java_items_json`, `java_items_changed`, `JAVA_HELD_ITEMS`, hoppers) |
| Mod block entities | `HorizonBlockEntities.java`: `LIVE` (mods' own, ticked in the order they were made, saved in their chunks' `block_entities`: `chunkData`, `chunkLoaded`, `changedChunks`; Rust `java_server/saves.rs`; the old `horizon-java/block_entities/` read once as `LEGACY`), `COPIES` (Horizon's vanilla ones as lasting copies, refreshed each tick, written back on change), Fabric load/unload events |
| Ticks, light, data, hooks | `HorizonTicks.java`, `HorizonLight.java`, `HorizonData.java` (data packs, recipes), `HorizonHooks.java` (mods' injections fired by name), `HorizonMixinTable.java` (every patch of every kind, where it runs, which actions go to the mirror), `HorizonTickets.java` (mods' chunk tickets → Horizon's kept chunks), `HorizonChunkLoads.java` (chunks loaded through vanilla's chunk system: pregenerators) |
| Headless client | `HorizonClient.java`: an allocated `Minecraft` (options, font, gui, window, language with mods' translations, sound/text-input managers, game mode), a `LocalPlayer` per player, screens (`openScreen`, client menus, `frame`, `input`, `tickScreen`), client-only screens (`setScreen` via `GuiMixin`, `CLIENT_ONLY`), the client half of item use (`useItem`), the client's tick (`clientTick`), `/modconfig`, `as(player, …)` swaps `minecraft.player` and `gui.screen` |
| Screen drawing | `HorizonGraphics.java` records `GuiGraphicsExtractor` calls as JSON ops (blit, sprite, fill, gradient, text, item, decorations, tooltips, pose, `typing`) |
| Mods' messages | `HorizonLoop.java`: server→client payloads queued and delivered after `syncMenus` (`flush`); client→server run at once. Mixins: `PlayNetworkingMixin` (server side), `ClientPlayNetworkingMixin` |
| Mixins | `java/platform/horizon-platform.mixins.json` (`client` list: headless-client only) |
| Mods' entities | `HorizonEntities.java` (live per level, ticked as `tickNonPassenger`, saved in `horizon-java/entities/`, attack/interact), `HorizonEntityRender.java` (the mod's renderer submits into a recording collector: part trees once, poses + matrix + texture per tick), `HorizonSpawner.java` (natural spawning, the levels' biomes), `HorizonPackets.java` (sounds and particles to Horizon's players), mixins `ServerChunkCacheMixin` (a chunk source holding nothing), `PathNavigationRegionMixin`, `BlockScanMixin`, `ItemModelResolverMixin` + `ItemStackRenderStateMixin` (held items), `EntitySelectorMixin`; Rust: `game/java_entities.rs` (draw, aim), `java_server/entities.rs` (`send_java_entities`, `java_entity_attack`/`interact`, `routed_hit`), `java_server/commands.rs` (`/summon`) |
| GPU (mods that draw with the GPU) | `crates/horizon-jvm/src/gpu/` (`mod.rs`: renderpearl's device on wgpu, natives on `dev.horizonlab.bridge.Gpu`; `spirv.rs`: combined samplers split for naga), `HorizonGpu.java` (the renderpearl backend: textures, buffers, transient memory, encoder, passes, pipelines), `HorizonGraphics` (pictures in picture, GPU textures as HUD images), `HorizonClient.guiRenderer` (the game renderer's GPU parts) |
| Mods' worldgen | `HorizonWorldgen.java` (also the biome queries: `biomes`, `findBiome`), `HorizonChunks.java` (chunk copies for code that reads chunks: saplings), `HorizonTints.java` (mods' block tints for the mesher) (vanilla's chunk steps on the mirror's level, chunks back as vanilla saves them), mixin `WorldGenRegionMixin`; Rust: `game/java_worldgen.rs` (`JavaTerrain`, wraps `VanillaTerrain` in `world_loading::make_streaming`), mods' biomes in `biome.rs` (`mod_biomes`) and the biome registry (`lan/data.rs`) |
| Content conversion | `crates/horizon-port-pack` (`oracle.rs`, `carry.rs`), `HorizonContent.java` (facts, creative tabs), `crates/horizon-client/src/mod_content.rs` (content root) |
| Recording, replays | `HorizonPacketTap.java` (the window's player's packets through a vanilla client pipeline), mixin `TapConnectionMixin`; Rust: `lan/packet_tap.rs` (kept from `lan/conn.rs`), `events::client_packets`/`wants_packets`/`client_closed`; Horizon's replay viewer `lan/replay.rs` (`--replay FILE.mcpr`); `HorizonClientCommands.java` (mods' Fabric client commands) |
| Voice chat, device mods, the player's own client | `HorizonDeviceMods.java` (which mods use the player's sound devices; where each mod's client code runs, per player), `HorizonDeviceClient.java` (their client half in a game connected to another's server: the mods the server lacks, or every mod), `crates/horizon-jvm/src/device.rs`, `game/java_device.rs`; test `tools/java_bridge/voice_test.sh` |
| Game side (Rust) | `crates/horizon-client/src/game/java_mods.rs` (the `java` state; `bridge.rs`: start, content, creative), `java_server.rs` (the types Horizon's files name) + feature-only `java_server/` by area: `tick.rs` (the tick, `apply_java_actions`, `with_java_world`, saves), `blocks.rs` (`JavaBlocks`, random ticks, fluids, neighbours, breaks, level writes, `JAVA_STATES`), `players.rs` (hurts, effects, falls, jumps, teleports, deaths, chat), `menus.rs` (`java_open_menu`, clicks, screens' input, `JAVA_WINDOWS`), `commands.rs`, `items.rs` (items, hoppers, pickups, drops), `entities.rs` (snapshots, attacks, rides, `routed_hit`); `java_screen.rs` (draws frames, sends input, key maps; `Screen::JavaMod` and `CLIENT_WINDOW` for client-only screens; plain, so a plain client draws a bridge server's screens; the server's sends in feature-only `java_screen/host.rs`) |
| Plain build (no `java-mods`) | `game/java_hooks/{blocks,players,screens,entities,worldgen,lan,remote}.rs`: every hook Horizon's files call, answering "none"; `java_mods/off.rs`, `java_server/off.rs`: the state. Horizon's own files carry no `cfg(feature = "java-mods")`: the bridge's files are feature-only (`#![cfg]`: `java_device`, `java_entities`, `java_profile`, `java_worldgen`, `creatures/java.rs`, `mounts/java.rs`, `world_loading/java.rs`) or plain hook glue (`<file>/java.rs` in the game) |
| NeoForge (prototype) | `java/dev/horizonlab/bridge/neo/NeoBoot.java`, `src/neoforge.rs`, `java_mods.rs` (`start_neoforge`, `HORIZON_NEOFORGE`), `java/neoforge/` (fixture, prototype platform), `tools/java_bridge/neoforge/`: see [java-bridge-neoforge.md](java-bridge-neoforge.md) |

## How a tick flows

1. Horizon's server tick → `java_server/tick.rs` (`tick_java_server`) → `events::game_time`, `events::server_tick` →
   `HorizonServer.tick`: the headless client's tick first (`HorizonClient.clientTick`), players mirrored, Fabric events fired, mods' hooks, levels' ticks,
   block entities (`HorizonBlockEntities.tick`), open menus (`stillValid`, `broadcastChanges`),
   screens (`HorizonClient.sendFrames`: tasks, screen ticks, frames), `syncInventories`
   (Horizon's block entities written back, menus shown, mods' messages delivered).
2. Java sends results back as actions (`Natives.*` → `events::Action`), applied in
   `java_server/tick.rs` (`apply_java_actions`). Blocks are not actions: a mod's
   `Level.setBlock(pos, state, flags)` is made in Horizon's world inside the Java call
   (`Natives.setBlockNow` → `world::set_block` → the lent game's `java_set_block_now`:
   Horizon's `set_block` with the mod's flags, neighbour and shape updates, Horizon's block
   entity replaced), then the Java side follows every block it changed (its section copies,
   `HorizonServer.follow`) and runs the mods' part of `LevelChunk.setBlockState`
   (`HorizonServer.settle`: block entities made or dropped, `onPlace`,
   `affectNeighborsAfterRemoval`, the mods' blocks' neighbour updates), so the mod's next
   read sees it all. Only a write Horizon can't make now (another dimension, a chunk not
   loaded, no world lent) is still queued (`Action::SetBlock`, kept in `WRITTEN` until made).
3. Horizon's world changes of Java mod blocks reach Java through `world::SET_HOOK` →
   `events::block_entities` (block entities exist from placement); those a mod's write made
   are run inside the write instead (`Written.sets`, `Written.neighbours`).
4. Mods' entities: `HorizonEntities.tick` in the server tick; after it `events::entities`
   gives a JSON snapshot (`HorizonEntityRender.snapshot`), which `send_java_entities` sends
   each connection (its level, within 160 blocks) on `horizon:java_entities` with the model
   trees it lacks. The client keeps two ticks and lerps positions, matrices and poses.
   Attack/Interact packets with ids from `0x7fff0000` go to Java (`Player.attack`,
   `Player.interactOn`). A command naming a mod's entity type goes to the mods' server
   (vanilla's command); one with `@e`/`@n` runs there first, quietly, with selectors finding
   no players (`runForEntities`), then in Horizon. Sound and particle packets mods send
   mirrored players are encoded with vanilla's codec and sent on as they are
   (`Natives.packet` → `Action::Packet` → `C::Unknown`).

5. Mods' worldgen: a dimension whose generation mods change (their biomes in its biome
   source, their features in its biomes: `HorizonWorldgen.modded`) is generated on the Java
   side. Horizon's streaming workers call `events::worldgen_proto` (STRUCTURE_STARTS ..
   TERRAIN), `worldgen_decorate` (FEATURES, the 3x3 region held) and `worldgen_finish` (the
   chunk as vanilla's save NBT, read by `save::decode`); vanilla's own `ChunkStep.apply` runs
   each step over a `StaticCache2D` of `Held` holders (the step's neighbours: Horizon's live
   protos, else a cache of lower-status chunks). Far terrain (LOD), `/locate` and the spawn
   search stay on Horizon's `VanillaTerrain`. Other dimensions are Horizon's as before.

## Client-only screens and the client's tick

- `Gui.setScreen` (26.3's `Minecraft.setScreen`) is cancelled in the headless client
  (`GuiMixin`): `HorizonClient.setScreen` makes it the screen of the player whose client runs
  now (`minecraft.player` inside `as()`), calls `added` and `init`, and sends
  `Natives.clientScreen(uuid, true)`. Rust puts `(conn, CLIENT_WINDOW = -1)` in `JAVA_WINDOWS`,
  so frames and input go as a menu screen's; the client opens `Screen::JavaMod` (blurred
  world, the frame drawn, every key to the screen, Escape too). `setScreen(null)` →
  `clientScreen(false)` → `{"window":-1,"close":true}` → back to playing. `setScreen(null)`
  over a menu's screen closes the menu (`closeContainer`).
- The client half of `Item.use`: after `HorizonServer.useItem`'s server half (and its
  `syncInventories`, which delivers what the server sent the client), a mod item's
  `UseItemCallback` listeners and `ItemStack.use` run on the player's `LocalPlayer` with
  `HorizonServer.clientHalf` set, for which `LevelMixin` answers `isClientSide()` true.
- `/modconfig <mod>` opens a mod's settings screen from its Mod Menu entrypoint
  (`ConfigScreenFactory.create(null)`): needs Mod Menu in the mods folder.
- `HorizonClient.clientTick` (each server tick, before it, so the first precedes
  SERVER_STARTING): client tasks, the mods' injections into `Minecraft.runTick` and
  `Minecraft.tick` (`HorizonHooks.fireClient`: `@Inject` at any point, `@ModifyArg` of a
  boolean, from client mixin configs too), then `ClientTickEvents` START and END. The client
  has no level and no player of its own (like a client at its title screen).

## The client's world, textures, styled text

- Every `as(player, …)` stands the player's client in their dimension's client level
  (`minecraft.level`, `LocalPlayer.level`), screens and messages included (Chunk Loaders'
  grid reads `ClientUtils.getWorld()`). Client levels run the mods' constructor injections
  (`HorizonHooks.constructed`), get chunks as copies of Horizon's (`LevelMixin` on
  `Level.getChunk`, `HorizonChunks`; an `EmptyLevelChunk` where Horizon has none), the server
  level's `palettedContainerFactory`, and answer `enabledFeatures`, `recipeAccess`,
  `getScoreboard` from the server level (`ClientLevelMixin`); `sendPacketToServer` drops.
- The `LocalPlayer` carries the server player's id, UUID, profile, eye height and size (Jade's
  ray started at the feet before), and its old positions.
- The headless client has services (the mirrored server's: profiles), a skin manager giving
  each player's real skin (`SkinManagerMixin` → `HorizonSkins`, below), a texture manager of named views
  (`TextureManagerMixin`, `HorizonTextures`: `getTexture(id)` knows the PNG; `register`
  keeps mods' textures by name), and atlas sprites (`TextureAtlasMixin`, `HorizonSprites`):
  each sprite is made from its PNG and `.mcmeta` (nine-slice), its texture found through the
  atlas's sources (`assets/*/atlases/gui.json`: "single", "directory"), and owns UV square
  k..k+1 so drawn quads name it.
- `HorizonGraphics.Capture` turns textured quads into blits: a named view's texture (with its
  UVs), else an atlas sprite by UV square; `addBlitToCurrentLayer` too (vanilla's nine-slices
  of headless sprites). `blitSprite(Identifier)` of a mod sprite outside gui/sprites with
  nine-slice/tile metadata goes through vanilla's drawing (Horizon has no metadata for it).
  Sprite ops carry their resolved `texture`.
- Dynamic textures (`DynamicTextureMixin`: Chunk Loaders' chunk maps): a named view
  `horizon:dynamic/N`; its pixels go once per version per player as an `{"op":"image"}` before
  the first blit that draws it (`HorizonTextures.imageOp`, `imagesSent`, cleared on join).
  The game stores images as frames arrive (`take_java_images`; a frame can be replaced before
  it's drawn) in 128 atlas slots of 64x64 (`horizon/java{i}`, bigger images shrunk).
- Text runs keep their styles: a `FormattedCharSequence` with any styled run becomes a
  component (`json`), drawn styled by Horizon (Jade's blue italic mod name).
- A click where the mouse wasn't at the last frame draws a frame there first (widgets that
  keep their hover from drawing).
- Sprites with nine-slice or tile metadata are always split in Java (vanilla's drawing into
  pieces of the headless sprite): Horizon's frame drawing stretches a `sprite` op whole.
  Headless sprites asked for with no atlas get the GUI atlas's location (vanilla's nine-slice
  blits it: a null one threw).
- An atlas blitted with a sprite's own UVs (`blit(atlas, …, u0, u1, …)`: corelib's
  `submitSprite`, its screens' backgrounds) resolves to the sprite by UV square too.
- Text added to the render state itself (`GuiRenderState.addText(GuiTextRenderState)`:
  corelib's `submitText`, Chunk Loaders' owner panel) is captured as a text op in its pose.
- Scissors: `scissor`/`unscissor` ops nest (intersected, through their pose) and the game
  clips what is drawn under them (`clip_since`): Tech Reborn's energy bars draw the whole bar
  and scissor it to the level.
- A mod's own payload sent as a raw packet (`player.connection.send(new
  ClientboundCustomPayloadPacket(...))`: corelib's channels) goes to the mod's client code like
  a Fabric one (`PacketListenerMixin.modPayload`; vanilla's and Fabric's own stay dropped).
  What a client handler schedules (`Minecraft.schedule`: corelib queues its packets) runs
  right after it, still inside `as(player)`: in their client level. Before, Chunk Loaders'
  client copy never got its loaders, so no cell could be toggled.
- The client's services resolve profiles of the players on the server (then usercache.json)
  before Mojang: Chunk Loaders' owner name.
- Jar-in-jar mods with textures (`META-INF/jars/`: RebornCore in Tech Reborn) are carried as
  mods of their own by the content build (`content.rs`, `nested_jars`; FORMAT 10), so their GUI
  textures join the atlas. Before, Tech Reborn's screens drew only vanilla's inventory.
- **The headless client never ran `ClientBootstrap.bootstrap()`** (Minecraft's main does):
  without `SpriteSources`' types every atlas source parsed to nothing, so the real atlases
  held only the missing texture and every baked model drew it. `models()` runs it first.
- `models()`' reload ran its main-thread steps on whichever thread finished a preparation:
  now and then a pool thread ("Rendersystem called from wrong thread": no models, a broken
  GUI atlas, screens failing with "Texture view does not exist"). They're queued and run on
  the client's thread.
- Block entity renderers that submit a block model (`submitBlockModel`, vanilla's and Fabric's
  with a mesh, whose default drops the mesh): its quads become meshes per sprite, UVs made the
  sprite's own (`blockModel`; a mesh quad's sprite found by UV in its atlas), textures
  `ns:textures/<path>.png`. Mods' `textures/block/` join Horizon's GUI atlas for them.
- Blocks with `RenderShape.INVISIBLE` (drawn by their block entity only) are exported
  (`invisible`) and carried with empty models (`<ns>:block/horizon_invisible/*`, children of
  the originals: particle kept; content FORMAT 11), or Horizon drew the model still.
- The client connection has its `minecraft` (Architectury's join event read
  `this.minecraft.player`: NPE at the client's join).
- **A mod's failure is told in chat** (`HorizonFailures.report`): "[Java mods] <mod> failed in
  <where>: <exception>: <message> (the game goes on; the log has more)", to every player, once
  per mod and place, eight in a session at most (then one "more failed" line); failures before
  anyone joined are told at the first join. The mod is the first stack frame in a mod's jar
  (past the game, Fabric, Horizon), or the mixin of a failing handler (`MixinMerged`). Called
  where the bridge catches mods' failures: events (`HorizonServer.fire`), hooks
  (`HorizonHooks`: HUD, client frame and methods, injections), and the client's screens,
  overlay, HUD, ticks, tasks, joins, settings screens and the recipe viewer. Fixture:
  `fixture_mod.sh failmod` (its ticks throw now and then); regress `failmsg`.
- `HORIZON_JAVA_DEBUG=loop` logs messages between the halves; `=screens` also logs each sent
  screen frame (first 4000 chars) and the inventory overlay's input; `=hud` logs the block
  looked at and its state.

## Players, joins and settings (multiplayer)

- `tools/java_bridge/lan_test.sh`: a host with MODS opened to LAN and a guest joining it (its
  own scratch folder), steps with `$H`/`$G`, screenshots of both. Guests get mod screens, HUD
  mods (Jade shows what each player looks at) and REI over their inventory.
- One headless client runs every player's client in turn (`as`), so a mod's static state is
  shared: the host types in a recipe viewer's search box and the guest's shows it.
  `HorizonClientState` swaps listed fields per player as `as` changes player; a player new to
  the client starts from the field's empty value, which mods fill lazily. Since
  `bridge-generic-ui` the list holds no recipe viewer's fields (REI's were there: code tied to
  REI's internals); only the map mods' remain (their own chat's). Add a mod's fields there when its client state
  leaks between players. A player leaving drops theirs (`HorizonClient.left`: state, overlay,
  screen, join).
- Client joins run per player (`joined(uuid)`, the first tick each player's client is in the
  world): the mods' injections into `ClientPacketListener.handleLogin` (only Horizon's own
  platform mixins and Fabric's are skipped: a mod under `dev.horizonlab.` was skipped before),
  then Fabric's `ClientPlayConnectionEvents.INIT` and `JOIN` (their injection is Fabric
  networking's, which the headless client lacks). Puzzles Lib's join reads
  `minecraft.getConnection().getConnection()`: each `LocalPlayer` has the client connection,
  which has a `Connection` with no channel; raw packets sent on it go to the mods' server code
  when they are custom payloads (`ClientCommonPacketListenerMixin`), else are dropped.
  Fixture: `fixture_mod.sh joinmod` (says in chat which hooks ran, per player).
- Mods' settings in Horizon's menus: the server sends each player's game the Java mods with a
  settings screen (`horizon:java_config_mods`, `HorizonClient.configMods`, read once); Options
  → Mods lists them under "Java mods"; a click closes the options (a single-player world is
  paused under them, and frames come from the server's tick) and asks the server
  (`{"kind":"modconfig"}`), which opens it as a client-only screen. The screens come from
  mods' "modmenu" entrypoints: without Mod Menu, `HorizonModMenuApi` makes its API's
  interfaces (ModMenuApi, ConfigScreenFactory, UpdateChecker, default methods answering
  "none") with ASM and puts them on the class path first, so those entrypoints load (tested:
  Jade, AppleSkin, Farmer's Delight, Cloth Config, REI via Architectury). As Mod Menu: the
  screens one mod gives others (`getProvidedConfigScreenFactories`: Forge Config API Port's)
  are listed under those mods' ids, a mod's own screen winning; the methods are called through
  the interface (an implementation class may not be public); an entrypoint that isn't a
  `ModMenuApi` (Compressed Blocks 3.1.0's is an empty class: it has no settings) is skipped
  with one line. A screen's parent is `HorizonModsPage` (some screens read it; none was
  given before): shown again on Done, it closes the screen and the window goes back to
  Options → Mods (`JavaOverlay.mods_page`). Regress `modsettings2` (fixture `configmod`;
  `fixture_mod.sh` compiles a fixture's `compile-only/` APIs beside the jar, not into it).
- Settings per player (`HorizonClientConfig`): the objects holding mods' settings listed in
  `holders()` are swapped with the rest of a player's client state; a player new to the client
  gets a deep copy (the mod's own objects copied; the game's, the JDK's and libraries' shared).
  On disk, the first player the client serves (the host) owns `config/`: after another
  player's screen input, files changed under `config/` move to
  `config/horizon-players/<uuid>/` and the owner's are put back. Since `bridge-generic-ui`
  `holders()` names no recipe viewer or HUD mod: cloth-config's AutoConfig holders, Jade's
  JsonConfigs, AppleSkin's `ModConfig.INSTANCE` and REI's config manager were reached by
  reflection into those mods and are gone. Their settings are one for every player of a
  server again (a guest's change shows for the host too), and a guest's kept files are no
  longer read back at their next join (`loadKept` called AutoConfig's `load()`). REI's
  per-player searches went too (its search executor wrapped through a mixin, its display
  index rebuilt by reflection): REI searches on its own threads as in any client, with the
  one set of settings. Regress `rei-lan`, `jei-lan`, `config-rejoin` show it. The way back for
  every mod at once is one headless client per player (a class loader each), not a list of
  mods' fields.
- `HorizonClientState` gives a player new to the client a fresh empty collection for a
  collection field (null before): a mod's own reload clears it and throws on null.
- Dedicated servers: `tools/java_bridge/server_test.sh` starts `horizon-client --dedicated
  --nogui` with MODS in its `mods-java/` (console through a FIFO: `sv "op Player"`) and a game
  that joins it; steps with `$G`, screenshots of the player's game. Its players get mod screens
  (Tech Reborn's machines), HUD mods (Jade with TR's energy) and REI over their inventory and
  over mod screens: the dedicated server runs the same headless client as a LAN host.
- Dedicated servers with Java mods hung at "Preparing level" after the upstream merge: with
  nobody online a standalone server streams no center (radius -1), but the spawn search
  waited for the mods' generator to decide (the first chunk made decides it) and for the
  origin chunk to be lit, both by moving the center. It now asks for those chunks alone
  (`request_spawn_chunk`, as upstream's spiral does: `streaming.extra` + `rank_full_task`).
  Then a player joining was held two minutes (`release_held_joins` waits for the spawn's 3x3,
  again by the center): `lan_update_streaming` keeps the spawn's 3x3 as an area while players
  are held. Regress `server` (server=True cases run `server_test.sh`: a dedicated server and a
  joining player; checks read the server's log). Tested there: Jade, AppleSkin, Xaero's
  minimap and its settings (Y), Nature's Compass's biome screen, Options → Mods' Java mods.
- **The client's join gets the server's recipes** as a vanilla client does
  (`HorizonClient.recipesArrive`, after `handleLogin` and Fabric's `JOIN`): the server's tags
  (`handleUpdateTags`' injections), Fabric's recipe sync (`ClientRecipeSynchronizedEvent`
  with the server's recipes), then the recipe packet `PlayerList.placeNewPlayer` sends
  (`ClientboundUpdateRecipesPacket`: the connection's `recipes` set as `handleUpdateRecipes`
  sets them, and the mods' injections into it run). Recipe viewers start there: REI's reload
  (its `handleUpdateRecipes` HEAD), JEI's runtime (its RETURN). REI's server then sends its
  displays through its own payload (`[REI Server Display Sync] Received server's request to
  set N recipes`), which reaches REI's client as any mod's payload does.
- **A screen opened before the client joined is laid out again at the join** (`joined`: its
  `init`). Horizon's world plays while the headless client still loads its models, so a pot
  could be open before the client joined; REI made no overlay for it then (no world). A
  vanilla client has no screen open before it joins. Still intermittent: REI's list beside that
  pot is sometimes empty, also when opened again later (1 of 2 runs passed; REI's search ran
  during its join reload and isn't redone: the old code forced a search, by name). Regress
  `reiscreen`. A screen opened after REI's reload (the inventory, the pot later in a session)
  has its list.
- **A packet handler's injections run in the order its body reaches them**
  (`fireConnection`, `HorizonHooks.injectOrder`): HEAD first, RETURN/TAIL last. REI starts its
  reload at `handleUpdateRecipes`' head and Architectury's recipe event (REI's END stage) fires
  from its return; in the class's method order the END could come first and REI's reload
  never ended.
- **The headless client's connection is connected** (`MirrorConnectionMixin`): its memory
  connection to the server in this process, as single player's is (`isConnected` true, the
  address `local`; raw sends, flushes and ticks go nowhere). JEI keeps a client's recipes only
  for the connection they came from; before, a JEI mixin answered its connection id.

## Mods' overlay on Horizon's inventory (recipe viewers)

Nothing here names a recipe viewer (`bridge-generic-ui`): REI and JEI work over Horizon's
inventory through what a vanilla client does for any mod. Tested with REI 26.3.824 and JEI
31.17.0.108 (regress `rei`, `jei`, `pot-jei`, `reiscreen`).

- **A client message sent off the loop's thread is queued** (`HorizonLoop.toServer`): mods run
  code on threads of their own (REI's reload threads: Advanced Loot Info asks the server for
  its loot data there; no player's client runs on that thread). It goes to `INBOX` as the
  player whose client ran last (`HorizonClient.lastPlayer`) and runs at the next `flush`, on
  the loop's thread; code that waits on that thread for a mod's thread `pump()`s them.
- Mod container screens open in vanilla's order (`openScreen`: the GUI's screen set, `added`,
  `init`), so mods make their overlays for them there (Fabric's screen init events): REI's
  list draws beside a Tech Reborn machine, JEI's beside the cooking pot.
- **With the headless GPU, atlases are real GPU textures** (render attachments, filled with
  the missing texture). `HorizonGpu.Texture.atlas` marks those made by `TextureAtlas`; Capture
  treats their quads as sprites by UV square, never as GPU images.
- While Horizon's own inventory (or a container screen of its own) is open with no mod screen
  in its place, the game sends window `-3` input: `open` (GUI size), mouse moves, `close`. Java
  keeps a `HorizonOverlayScreen` per player (an `AbstractContainerScreen` of the player's
  inventory menu that draws nothing itself) and draws it `modsOnly` each frame (`sendOverlay`,
  only when changed) to `horizon:java_overlay`. The game draws it over the inventory.
- **Input as a vanilla screen gets it.** Presses outside the inventory's window with nothing
  carried go to the overlay (and their drags and release), as does the wheel there. Presses
  the inventory takes itself (its slots) go to the overlay too, with `horizon: true`: the mods
  see them (their wraps, Fabric's events: a search box lets go of the keyboard), the screen's
  own handler doesn't run. Keys: every key the inventory screen doesn't use itself (Escape,
  the inventory key, drop, the hotbar's and off hand's swaps: `inventory_screen_key`) goes to
  the overlay; over one of Horizon's slots it names the slot (the inventory menu's index:
  `menu_slot_index`), and `HorizonOverlayScreen.hover` makes it the screen's hovered slot, as
  the mouse over it does in vanilla. So R and U over a slot open REI's or JEI's recipes and
  uses through their own key handling (REI's keys are its own config's, not KeyMappings: a
  list of the mods' KeyMappings would have missed them). A client-only screen opened from
  there: Escape goes back to the inventory (`JavaOverlay.back`).
- **While a mod's text box has the keyboard, every key but Escape goes to it** (the frame's
  `typing`, `HorizonClient.typing`): a focused EditBox in the screen's focus chain, the last
  EditBox focused anywhere (`EditBoxFocusMixin`, `HorizonTextFields`: JEI's search box isn't
  one of the screen's widgets), or a screen whose last typed character a mod took before the
  screen saw it (`HorizonScreenEvents.modTakesTyping`, until the next click: REI's search box
  is a widget of its own). So in REI's box the very first character, when it is one of the
  inventory screen's keys (E, Q, F, a digit), still acts on the inventory; the rest type.
- **The mods' wraps of the screen's handlers run** (`HorizonScreenEvents`,
  `HorizonHooks.wrapCall`): vanilla's MouseHandler/KeyboardHandler call `Screen.mouseClicked`,
  `mouseReleased`, `mouseDragged`, `mouseScrolled` and `keyPressed`; mods wrap those calls
  with MixinExtras' `@WrapOperation` (Architectury fires its `ClientScreenInputEvent` there:
  REI takes its clicks, keys and scrolls through it). `wrapCall` finds the wrap handlers
  merged into the vanilla class (by their mixin's annotation: method and target) and chains
  them, the innermost calling the screen inside Fabric's screen events (JEI takes its input
  there). Characters: the mods' injections at `KeyboardHandler.charTyped`'s head and tail,
  then Fabric's char events (Architectury's `KEY_TYPED` rides on them).
- **Frames and ticks**: `HorizonScreenEvents.extract`/`tick`/`removed` fire Fabric's
  `beforeExtract`/`afterExtract`, `beforeTick`/`afterTick` and `remove` (Gui's mixin fires them
  in a real client): JEI updates what the mouse is over before each frame.
- **A mod's own extractor records too.** Mods draw through a `GuiGraphicsExtractor` of their
  own built on the one they were given (REI's `compat.GuiGraphics.of`: a subclass made from
  its fields, on the same render state). `PlatformPlugin` (the platform mixin config's plugin)
  starts every `GuiGraphicsExtractor` method `HorizonGraphics` records, and `pose()`, with a
  hand-off to the `HorizonGraphics` whose render state the extractor draws on
  (`HorizonGraphicsWrappers.forward`), so any such extractor records into Horizon's frame.
  `GuiGraphicsMixin` does the same for the private `innerBlit` mods widen.
- **Mods' `CLIENT_STARTED`**: JEI registers its `CLIENT_STARTED` listener from its injection
  into `Minecraft.<init>` at the first `ResourceLoadStateTracker.startReload`; the headless
  Minecraft is allocated, so `HorizonHooks.startPoint` fires injections there with the
  constructor's end (`START_END`, before `CLIENT_STARTED`). Client reload listeners mods
  register at `CLIENT_STARTED` (JEI's GUI colours) run right after it (`reloadAddedListeners`).
- The server sends an empty overlay frame as a player joins: the game only talks to `-3` once
  it knows the server has Java mods.
- `modsOnly`'s stack walk skips Horizon's own frames and `GuiGraphicsExtractor`'s, so a mod's
  draw through its own extractor counts as the mod's.
- The headless Minecraft has a `KeyboardHandler` and a `MouseHandler` (allocated): mods' wraps
  and injections are theirs.
- `/recipes` and `/uses` are gone (they called REI's and JEI's APIs), as are
  `HORIZON_JAVA_DEBUG=rei` and `=jei`. R and U over a slot, or a viewer's own keys, are the way.

## Client HUD mods

- Once players exist, `HorizonClient.clientTick` runs each player's client in turn inside
  `inWorld(player, …)`: `as()`, plus `minecraft.level` = the dimension's client level
  (`clientLevel`: a `ClientLevel` made without its constructor and registered as a mirror, so
  `LevelMixin` gives Horizon's blocks; block entities and writes go to the server level via
  `HorizonServer.serverSide`; entities via `ClientLevelMixin`, without the player's own server
  copy), `minecraft.hitResult` (`pick`: block, or a nearer mirrored entity), the `LocalPlayer`
  standing where the server player stands. Then the client tick, then `sendHud`.
- `hud(player)` draws into a `HorizonGraphics` with `modsOnly`: an op is kept when, walking out
  from the draw call, a mod's frame (a mod class, or a non-Fabric mixin handler merged into a
  vanilla class: `MixinMerged`) comes before Horizon's driver. Drawn: Fabric's HUD elements
  that mods added (`HudElementRegistryImpl` roots, vanilla layers skipped), the vanilla Hud
  parts mods inject into (`HorizonHooks.hudMethods`; `extractPlayerHealth` for the hearts and
  food, in survival/adventure), and handlers on `Hud.extractRenderState` (`fireHud`).
- Frames go `Natives.hudFrame` → `Action::HudFrame` → `horizon:java_hud` (only when changed);
  the client draws them over its HUD (`draw_java_hud`, under titles and chat) and sends its HUD
  size on `horizon:java_screen_input` as window `-2`.
- `HorizonGraphics` also captures GUI elements mods add to the render state themselves
  (`GuiRenderState.addGuiElement`: Jade's float rectangles) by building their vertices:
  untextured quads become fills and gradients; textured ones are dropped (no texture name).
- What the headless client gained for HUD mods: a game renderer whose main camera answers
  from the player (`CameraMixin`, `MinecraftCameraMixin`: camera entity, delta tracker), a
  `GameRenderState`, debug entries, Hud's tab list and boss overlay (none showing), the
  `LocalPlayer`'s attributes, effects, food, fluid state and input (shared with or made for
  its server player), `ClientLifecycleEvents.CLIENT_STARTED`, and the mods' client resource
  reload (`reloadClientResources`: Fabric's CLIENT_RESOURCES reloaders over vanilla's jar and
  the mods' packs; Jade's themes).
- Mods' top-level `textures/*.png` and `textures/hud/` join Horizon's GUI atlas (AppleSkin's
  `icons.png`).
- `HORIZON_JAVA_DEBUG=hud` logs each HUD frame's size and each Fabric HUD element's ops.

## Mods' hotkeys, client-only screens over menus, renderer mods (bridge-client-12)

- **Every key while playing, for mods that handle keys themselves.** MaLiLib (Litematica,
  MiniHUD, Tweakeroo) keeps its pressed keys in `SDLEventHandler.handleKeyEvent` and runs its
  hotkeys from `KeyboardHandler.keyPress` (an INVOKE injection after
  `FramerateLimitTracker.onInputReceived`), not through `KeyMapping`s. When a mod has such a
  handler (`HorizonHooks.injected`: @Inject handlers merged into a method, found by their
  mixin's annotation, any point), `boundKeys` adds `-1` and the window sends every key press
  and release while playing (`java_bind_key`: scancode, keycode, modifiers). Java fires the
  SDL handlers with a real `SDL_Event` (`SDL_Event.calloc`, KEY_DOWN 0x300 / KEY_UP 0x301),
  then the `keyPress` ones (action 1/0), as that player's client (`HorizonClient.keyPress`).
  Litematica's M opens its main menu (a client-only screen); regress `litematica`.
- **A client-only screen over a menu's screen** (JEI's uses screen from the cooking pot's)
  keeps the menu's screen (`HorizonClient.UNDER`) and Rust keeps the menu's window
  (`JAVA_UNDER`); closing it (to its parent, or to none) while the menu is open puts both back,
  and the menu's screen sends a frame again. Before, the pot came back as Horizon's one-row
  stand-in. A new menu screen always sends its first frame (`SENT` cleared in `openScreen`):
  the same pot opened again draws the same frame, for a new window. Regress `pot` (opened 20
  times), `pot-jei`. `horizon-ctl state` has `java_screen`: the window whose Java screen is
  drawn (null: Horizon's own screen or its stand-in).
- **Renderer mods are left out** (`horizon_jvm::renderers`: sodium, iris, canvas, nvidium,
  vulkanmod, and what depends on them): Fabric gets the other jars as `fabric.addMods` with an
  empty mods folder, and players are told in gray "[Java mods] Sodium isn't needed in Horizon:
  Horizon has its own renderer (left out)" (`HorizonFailures.notNeeded`, at join). Regress
  `sodium`.
- **Java's log4j writes no file.** The JVM starts with the bridge's `log4j2-horizon.xml`
  (`-Dlog4j2.configurationFile`, console only); once Knot runs, `HorizonLog` replaces the
  console with an appender into Horizon's log (`[java] [logger/LEVEL] message`, INFO and up,
  stack traces cut to 12 frames). Mojang's config wrote `logs/latest.log` in the working
  folder: on a dedicated server Horizon's own log, whose lines were overwritten.
  Regress `server-log`.

## Mods' key bindings in Controls, mods' field of view (bridge-modkeys-fov)

- **Mods' key bindings are Horizon's.** `HorizonClient.modKeys()` is every `KeyMapping` made
  (`KeyMapping.ALL`: Fabric's `KeyMappingHelper` ones and plain `new KeyMapping`s alike) minus
  vanilla's (the ones `Options` holds in its own fields), sorted as vanilla's list is; logged
  once each time it grows (`N mod key binding(s): ...`). `keyMappings()` sends them
  (`{name, category, key, default, label, categoryLabel}`, labels in the headless language) on
  `horizon:java_key_mappings` beside `horizon:java_keys` (`send_java_keys`; own client:
  `own_client_greet`). The game keeps them in `settings.keys` (`KeyBinds::set_mods`):
  Controls → Key Binds lists them after vanilla's (in a vanilla category when the mod uses one,
  else under the mod's own category heading), rebindable and resettable with conflict marks
  across both lists; options.txt keeps them as `key_<name>:<key>` after vanilla's
  (`OptionsFile::mod_keys`, kept whether or not the mod is there). A key or button press
  while playing sends `"mappings": [names]` with its `bind` input: the mods' mappings the
  player's Controls bind to it; Java sets those in that player's `Keys` only (no write to the
  shared `KeyMapping.key`). A game that got no mappings sends none and Java matches by its own
  keys as before. Regress `keys`, `keys-rebind` (G→H in the Controls UI, saved, G then
  inert), `keys-server`.
- **Mods' field of view on Horizon's camera.** 26.3 keeps FOV in `Camera` (`tick` →
  private `tickFov`, private `calculateFov(float)`, `update` writes `fov`, `getFov()`); the
  headless camera (allocated) gets its `minecraft` once and each player's
  `oldFovModifier`/`fovModifier` swapped on: `camera.tick()` after the client tick
  (`tickView`), then in the frame `calculateFov(1f)` (the mods' hooks run: Zoomify's
  `@ModifyReturnValue`), written to `fov`, `getFov()` (hooks there too) over `options.fov ×
  fovModifier` (death and fluids are inert headless) = the scale sent on change
  (`Natives.view` → `Action::View` → `horizon:java_view`; own client: local). The game eases
  it over the tick (`JavaView`) and multiplies `fov_degrees` after the native mods' FOV, so
  `zoom_magnification`, far terrain's zoom aim and `horizon-ctl state`'s new `player.fov` see
  it; turning slows by the same scale while it is below 1 (the mods' `turnPlayer` hooks don't
  run headless). The hooks on the camera's FOV methods are logged once (`N mod hook(s) on the
  camera's field of view: <mixin>.<handler> (@Kind on method)`, `HorizonHooks.hooking`).
  Mods that change `AbstractClientPlayer.getFieldOfViewModifier` instead cancel out (the
  headless camera has no camera entity: modifier 1). Regress `fov` (fixture `fovmod`).

## Cloth Config's screens, client waypoints, Litematica and Chat Heads (bridge-client-13)

- **Mods with a `-` in their id were never carried** ("mod id \"cloth-config\" is not
  [a-z0-9_]"): their assets never reached Horizon, so Cloth Config's arrows and +/- icons
  (`cloth-config2:textures/gui/cloth_config.png`) were blank. The content mod's id now takes
  `_` (`cloth_config`) and owns the original id as a namespace; a namespace holding only
  textures/lang/sounds that no JSON names (`cloth-config2`) is the mod's too
  (`horizon-port-pack` `carry.rs`; content FORMAT 15).
- **Screens get the frame's delta ticks** (`HorizonClient.deltaTicks`: time since that
  screen's last frame, in ticks), as vanilla's Gui passes `DeltaTracker.getGameTimeDeltaTicks()`.
  It was 0: Cloth's lists (and anything animating by it) never moved toward their scroll target.
- **Mouse moves no longer lag the server.** Every move (one per rendered frame the GUI pixel
  changed: hundreds a second on a fast PC) drew a whole frame (Cloth's demo: 4-18 ms, 48 KB)
  and walked `config/` (`HorizonClientConfig.afterInput`). Now moves and drags skip the
  settings walk and draw at most every 40 ms (the tick's frame shows the rest); the window
  sends moves at most every 20 ms, the last position always (`java_screen.rs`, `move_due`).
  Same for the inventory overlay (REI/JEI).
- `HORIZON_JAVA_DEBUG=frames`: each new screen frame in one line (screen class, textures,
  `text@y` in drawing order): regress checks read it. `=screentime`: each frame's and input's
  time. Regress `cloth` (opens, icons, a list expanded, scrolled, searched, 150 fast moves).
- **Client waypoints**: every client connection (host, LAN guest, dedicated server's player)
  has a `ClientWaypointManager` (client-12/gpu-12). The testers' "ClientWaypointManager
  .hasWaypoints()" NPE predates that. Regress `join`, `join-lan`, `join-server` (the joinmod
  fixture reads it at JOIN and from a HUD element; they fail without it).
- **Litematica**: its menus (M) and its HUD (tool mode, placement) work. Its schematic overlay
  in the world does not: Litematica draws it inside vanilla's level renderer
  (`MixinLevelRenderer`: injections into `LevelRenderer.render`/`addMainPass` with its own
  chunk sections, GPU buffer slices and the camera's matrices; MaLiLib's `renderWorldLast`
  likewise), which Horizon never runs. A generic path would fire mods' world-render hooks per
  player with a recording backend and send the geometry (world coordinates, sprites) to the
  player's game to draw in its world pass: gpu work, not started.
- **Chat Heads** (MPL-2.0) loads and chat works with it (a tester's "does not display
  messages" is the heads). No heads yet: Chat Heads changes vanilla's client chat
  (`ChatListener` → `ChatComponent`), which Horizon replaces with its own. Two halves, both
  generic: (1) every message a player's game shows also goes through their headless client's
  `ClientPacketListener.handleSystemChat`/`handlePlayerChat`, the component that reaches
  `ChatComponent.addMessage` sent in its place (Chat Heads' "Before Name" puts a player-head
  object component in it, which Horizon's text already draws); (2) its default "Before Line"
  draws the head from `ChatComponent`'s own drawing: only vanilla's chat drawn by Java (when a
  mod injects into it) would show it. Not started.
## Recording and replays: ReplayMod, Flashback (bridge-replay-1)

How they work in Minecraft, and what of it Horizon has:
- **ReplayMod** (GPL-3.0) records at the client's network connection: at the login's end
  (`ClientHandshakePacketListenerImpl.handleLoginFinished`, its injection) it adds handlers
  before and after the netty pipeline's decoder (the raw bytes and the decoded packets), saves
  each packet with its time to `replay_recordings/<date>.mcpr` (a zip: `metaData.json`,
  `recording.tmcpr`: int time, int length, body), adds the recording player's own moves from
  its client tick, and saves when the channel goes inactive. It replays by feeding the file to
  a new client connection (an EmbeddedChannel) with vanilla's renderer, its camera entity and
  its jGui screens (the replay viewer and editor open from vanilla's title screen).
- **Flashback** (All Rights Reserved: nothing of it copied) records every packet handed to a
  client listener (`Connection.genericsFtw`, its injection) after a snapshot it writes from the
  vanilla client's own world (`ClientChunkCache.storage`, entities, player infos, tab list,
  boss bars, scoreboard, light engine), started by `/flashback start` (a Fabric client command)
  or its pause-screen buttons. It replays on its own integrated server (`ReplayServer`) with
  vanilla's renderer and an ImGui editor.
- Horizon draws the world itself; the headless client has no renderer, no camera and no title
  screen, and its client levels are mirrors of the server's (no client world of its own). So
  **their replay viewers can't run in Horizon**. Recording needs the packets, which Horizon's
  server sent only to the player's game.

What runs now:
- **The client's connection reads its packets** (`HorizonPacketTap`). When a mod's mixin is
  merged into `Connection` or the login's listener (`wanted`, at start), Rust keeps the bodies
  its server sends the window's own player (memory connection only: single player, a LAN
  host; `lan/packet_tap.rs` from `conn.rs`'s login end and `PlayerSender`) and passes them
  each tick before the Java tick (`events::client_packets`). Java runs them, in order, through
  a real vanilla client pipeline on an `EmbeddedChannel` (`Connection.configureSerialization`,
  `configurePacketHandler`), past the splitter (one packet each; memory packets aren't held to
  2 MiB), inside the owner's `as()` after their join. Phases switch where vanilla's listeners
  switch them (after the login's end and the configuration's end, the terminal packets); play
  decodes with the server's registries. The connection hands each packet to a listener of the
  phase's interface (a Proxy, `TapConnectionMixin`'s redirect in `channelRead0`) whose
  handlers fire the mods' injections into the real listener's handler, HEAD and TAIL/RETURN
  (read from the merged class: ReplayMod applies its configs from its own launcher), and at
  the configuration end's `setupInboundProtocol` INVOKE (ReplayMod records the end there) —
  never vanilla's bodies. The join's login and spawn and payloads are fired elsewhere and stay
  out. The connection never sends (`send` cancelled; a mod's payload goes to the mods' server),
  a decode fault is logged (never a disconnect), and it ends as the integrated world closes
  (`events::client_closed` from `integrated.rs`: `disconnect`, channelInactive, Fabric's
  `DISCONNECT`, then up to 30 s for the threads mods start, running client tasks meanwhile).
  `HORIZON_JAVA_DEBUG=tap` logs each packet handed over.
- **ReplayMod records**: "[Replay Mod] Recording started" in chat, RECORDING on the HUD, the
  `.mcpr` saved as the world closes (a 50 s test: 14k packets, 2.7 MB, protocol 777). Its
  config end (`replaymod:enabled_packs_data`, FinishConfiguration) and its own player
  (AddEntity, moves) are in it.
- **Horizon plays `.mcpr` recordings** (`lan/replay.rs`, `--replay FILE`; the launcher's game
  arguments): a server end of a memory pipe the window joins (`connect_local`): its own login
  end, the recorded configuration, then the play packets at their times. The viewer is put in
  spectator mode, 4 blocks behind and 2 above the recorder's first position; left out: the
  recorder's health, death, screens, game mode, abilities, later teleports, disconnects,
  reconfiguration, transfers, and Horizon's own `horizon:` payloads (the Java HUD's frames).
  Chat lines (or `/` commands) control it: `pause`, `play`, `speed <x>`, `skip <seconds>`.
  Only recordings of protocol 777 (26.3) are played. A replay can't go back (no rewind) and
  has no camera paths or video export (ReplayMod's editor).
- **Flashback**: loads; `/flashback start` runs (client commands), but its snapshot needs the
  vanilla client's own world, so it fails there (`Collection.size()` of a null). Players are
  told at join (`renderers::PARTIAL`): it can't record or replay in Horizon yet.
- Generic fixes on the way: **Fabric client commands** (`HorizonClientCommands`: registered
  once, their roots in the server's command names and tree — an argument type of a mod's own
  becomes one greedy argument — run on the player's client with its ClientSuggestionProvider);
  **client chat lines** (`ChatComponent.addClientSystemMessage`) go to the player's chat;
  **legacy `.lang` files** (`key=value`, ReplayMod's) read by the headless client's language
  and carried as JSON (`carry.rs`, content FORMAT 15); `Minecraft.getGameProfile` is the
  current player's; the game mode's constructor defaults (`destroyBlockPos`) and an empty
  server-pack source; `HorizonHooks.fireMergedSkipping` (Fabric's own left out) and INVOKE
  points in `fireMerged` (`"INVOKE <target>"`).
- Not done: the replay viewer opened from inside the game (a single-player world can't
  transfer: vanilla refuses it); the recording's own player has the mirror's entity id
  (`0x7fff....`), so Horizon's packets about the player (id 1) don't apply to that body in a
  replay; ReplayMod's red recording dot (its jGui texture) isn't drawn; guests' and dedicated
  servers' players aren't recorded (the window's own player only, as one client records itself).
- Test: `regress.py replaymod` (records a scene, quits, plays it with `replay_view.sh`),
  `flashback`. `replay_view.sh FILE` watches any recording headless.
## Voice chat: mods on the player's own sound devices (bridge-voice-1)

Simple Voice Chat works between players (2026-11-04: a LAN host and a guest hear each other,
each on its own devices; `tools/java_bridge/voice_test.sh`). No code names the mod: what
counts is what a mod's client code does.
- **Device mods** (`HorizonDeviceMods`): a mod whose classes call `alcCaptureOpenDevice`,
  `alcOpenDevice` or use `javax/sound/sampled/TargetDataLine` (found by a scan of its classes
  at the client's start, beside it) opens the player's microphone or speakers, so its client
  half must run on that player's machine. A server's headless client runs every player's
  client, and such mods keep one client a process (SVC's `ClientManager`): a guest's join
  replaced the host's voice client, and it would have opened the host's microphone for them.
- **On a server**, device mods' client code runs only for the process's own player (Rust tells
  `HorizonLoop.ownPlayer`: the single-player world's owner; none on a dedicated server).
  For other players it is left out: client events (`fireEach` of `Client*` events), the
  client's tick/frame/keybind injections (`fireClient`, `fireFrame`), connection injections,
  key handlers, Fabric HUD elements (`HorizonDeviceMods.runs`, by the listener's or mixin's
  mod: `HorizonFailures.modOf`). Their messages on the mod's channels (namespace = mod id) go
  to that player's game when it said, as it joined, that it runs them
  (`horizon:java_device_mods`; `HorizonLoop.toClient` encodes the payload with its codec and
  sends it as a custom payload, `HorizonPackets.customPayload`); what the game sends back on
  those channels reaches the mod's server receivers as that player's (`lan_actions` → 
  `device::from_game` → `HorizonLoop.fromGame`).
- **In a game connected to another's server** (a guest, a dedicated server's player), its own
  JVM is idle (no world of its own); `HorizonDeviceClient` makes it that player's client for the
  device mods only (`HorizonDeviceMods.runs`: only they run there): a `LocalPlayer`, a
  `ClientLevel` holding the player and the others where Horizon's game sees them (each tick,
  `game/java_device.rs` → `device::tick`), a connection whose channel answers the server's
  address (SVC sends its voice to it), Fabric's INIT and JOIN, the client's tick with the
  player's keys, frames and tasks. It joins 40 ticks after the player is in the world (the
  server has mirrored them by then). Its HUD is drawn over the game's (`draw_java_hud`), its
  screens are the game's client-only screens (`Screen::JavaMod`, window -1: input goes to the
  JVM, not the server, while it is the device client's), its chat lines go to the game's
  chat. It leaves with the game (`Minecraft.disconnect`'s injections: SVC closes its voice).
- **The host's own client between runs**: SVC's speaker threads play only while
  `Minecraft.player` and `.level` are set, at any time. With a device mod and an own player,
  `as()` leaves the client its own player's (`HorizonClient.rest`) instead of none.
- Generic fixes it took: the headless `Minecraft` has its `PackRepository` before the client
  entrypoints (SVC adds its pack source there: its client init threw); client connections have
  a level (`ClientPacketListener.getLevel`: SVC sends nothing on a connection without one) and a
  closed memory channel answering `LocalAddress` (SVC: `127.0.0.1`); the answer to a client's
  message arrives after the client code that sent it (`HorizonLoop.reading`: SVC made its client
  right after asking for its secret, and the secret, delivered inside the ask, found none);
  `Minecraft.handleKeybinds` injections fire each tick (SVC's keys: V opens its menu); the
  camera answers `upVector`/`leftVector` (SVC's positional audio); `ChatComponent
  .addClientSystemMessage` goes to the player (`ClientChatMixin`); failures and device mods are
  found for jars with `+` in their name (`modOf` decoded `+` as a space: most mods were
  blamed on no one); Java mods' GUI textures in any folder of theirs join the GUI atlas
  (`java_gui_folder`: SVC's `textures/icons/`).
- Tested: `voice_test.sh` (PulseAudio null sinks, one per game's speaker and microphone; a tone
  into one game's microphone, recorded from the other's speakers): the host heard the guest's
  440 Hz in 34 of 39 half-seconds, the guest the host's 660 Hz in 38 of 39 (the gaps: two games
  and llvmpipe on 2 cores). V opens SVC's menu on both (the guest's from its own JVM).
- Not yet: SVC's icons over players' heads (`AvatarRendererMixin`: Horizon draws the world);
  the host's voice playback sometimes stutters while the server thread swaps players
  (`as()`); on a dedicated server SVC binds its configured UDP port (24454: open it) but
  ignores `voice_host` (it asks Fabric's side, which is the client's here). SVC's licence:
  All Rights Reserved (its jar runs as it is; nothing of it is in the repo).

## Each game runs its own mods' client (`HORIZON_JAVA_OWN_CLIENT`, bridge-generic-2; by default for mods the server lacks, bridge-own-client)

The generic fix for per-player client state (Ludovic's condition 2 took the per-mod lists
away): as in vanilla, a player's mods' client half runs in **their own game**, not in a server's
one headless client shared by every player. It generalises the device mods' path (voice chat,
above) from some mods to all of them.
- **By default, per join (bridge-own-client, 2026-11-06)**: a Horizon server whose Java mods run
  tells each joining game their ids (`horizon:java_server_mods`, `device::SERVER_MODS_CHANNEL`,
  sent with the greeting in `send_java_keys`; `HorizonDeviceMods.ids`). After `JOIN_AFTER` (40
  ticks) the game's `device::join(host, port, uuid, name, server_mods)` decides
  (`HorizonDeviceClient.join`, `HorizonDeviceMods.missing`): the mods this game has and the
  server doesn't (Fabric, Fabric API, the game and Horizon never count) run here.
  - **No list arrived** (a vanilla or Paper server, a Horizon server without Java mods or whose
    Java failed): every mod's client half runs here, as with the switch (`*`): log line `every
    mod's client half runs here for this player, on host:port (the server runs no Java mods)`.
  - **The server runs some, not all** (`+`): the own client runs only those mods and what they
    depend on (`HorizonDeviceMods.ownRuns`, `withDependencies`: `runs()` answers false for the
    rest), log line `X run here for this player (the server doesn't run them)`. Its HUD is drawn
    over the server's (the device HUD layer), its keys are added to the server's (a key goes to
    both: `own_client_also`), its settings screens are added to Options → Mods (opened here), the
    HUD's size goes to both; the inventory overlay stays the server's.
  - **Nothing missing**: the device mods' client as before (voice chat), or nothing.
  The game tells the server on `RUNS_OWN_CHANNEL` (`*,ids`, `+,ids` or device ids); the server
  keeps a set per player (`HorizonDeviceMods.RUN_THEIR_OWN`: `runsTheirOwn(uuid, mods)`, log
  `Player's own game runs X's client half`), leaves those mods' client code out for that player
  and sends their channels to that game (`deviceChannel(channel, player)`).
- **Joining needs the mod list to match only for mods that change the world**: a carried Java
  mod with no block, item or data (Xaero's, Jade, a HUD) is `side = "client"` (`carry.rs`
  `changes_world`; content FORMAT 17), so a server without it accepts the player (it was
  refused before: "Not on this server: xaerominimap").
- The own client has no single-player server (`HorizonClient.joined`: Xaero then names its data
  by the server's address, as a vanilla client) and the world spawn the server told the game
  (`spawn` in the tick's state → `HorizonServer.ownSpawn` → the mirror's `effectiveRespawnData`;
  Xaero's `handleSetSpawn` read null before).
- `HORIZON_JAVA_OWN_CLIENT=1` on both sides keeps the old switch: every mod's client half in each
  game whatever the server runs, the server's messages waiting for a game's client (regress
  `maps-lan` and `rei-lan` set it).
- **In a game connected to another's server** (LAN guest, a dedicated server's player;
  `game/java_device.rs` `tick_own_client`, `HorizonDeviceClient` `own*`,
  `HorizonServer.ownClientTick`): its JVM keeps a **mirror of what it sees**: a server's mirror
  (`setUp(true)`: mods name per-server data by the server, not by a world folder) with its one
  player where its game has it (position, look, mode, health, food, sneak, inventory when it
  changes) and levels over the game's own world (the chunks the server sent: `with_java_world`
  lends them, as a server lends its levels). No server event, tick, block entity or menu runs
  there: only `HorizonClient.clientTick` and `sendFrames` for that player, the host's own code,
  so joins, HUD, the inventory overlay, client-only screens, key bindings and `/modconfig`
  screens work as for a host's own player. Their frames (actions `HudFrame`, `OverlayFrame`,
  `ScreenFrame`/`ClientScreen` of window -1) are shown here; the input of windows -1, -2, -3
  and `bind`/`modconfig` goes to this JVM (`own_client_takes`), a server menu's window still to
  the server (its screen is made where the menu is). The server's HUD, overlay, keys and
  settings list for this game are ignored (`own_java_client`). Its client connection answers
  the server's address and server entry (`ownConnection`: `getCurrentServer`, the remote
  address). Mods' messages: the client's go to the server over this game's connection; the
  server's arrive as custom payloads and are delivered to the mods' client receivers.
- **On the server**: a player whose game said so (`RUNS_OWN_CHANNEL`, as device mods do) gets
  none of the headless client's mod code (`HorizonDeviceMods.runs`: their ticks, HUD, events,
  hooks); every mod channel but `minecraft:`/`horizon:` goes to their game. What the mods send a
  player before their game started its client (it starts with the game's JVM, after the join)
  **waits** and goes in order once it says so (`HorizonLoop.WAITING`): REI's display sync at the
  join had reached the host's own REI as the guest, replacing the host's displays.
- Works (headless, LAN, 2 cores): JourneyMap per player (`maps-lan`: each minimap maps its own
  player's world from its own game's chunks, the guest's map data and waypoints in its own
  folder under the server, `journeymap/data/mp/localhost_<world>/`); REI per player
  (`rei-lan`: the guest's craftable filter is its own, the host's stays off; the guest's list is
  its own REI's).
- Not yet: the client halves the server starts (a mod item's client use, `startAttack`, menu
  screens and their overlays) still run on the server's headless client, now without the mods'
  client code for such a player (Better Combat's swings and recipe viewers over a mod's menu
  for a guest with the switch on); a player whose game has no Java mods gets no mod client at
  all with the switch on. JourneyMap + VoxelMap together: VoxelMap's client entrypoint fails
  (both register `worldinfo:world_id`, as in the real game). With the server running some mods,
  the own client's inventory overlay (a recipe viewer the server lacks) isn't shown, and its
  mouse-button bindings go to the server only.
- Test: `regress.py --jars <journeymap, REI + Architectury + Cloth + FD, fabric-api> maps-lan
  rei-lan`; by default: `maps-client-only` (a Horizon server with no Java mods, the player with
  Xaero), `maps-client-vanilla` (vanilla's server jar from `.local`, offline: `server_test.sh`
  `VANILLA_SERVER=1`), `maps-client-some` (the server runs Jade, the player Jade + Xaero).
  `server_test.sh` takes `SERVER_MODS`/`PLAYER_MODS` folders (regress: `server_mods=[...]`,
  `log="player"` to check the player's log).

## Mods that change vanilla's behaviour (the mixin table, routed actions)

Most of vanilla's code never runs on the Java side: Horizon does it in Rust. A mod's Mixin into
it does nothing unless Horizon fires it or hands the action to the mirror.

- **The table.** At start-up `HorizonMixinTable` reads every mod's Mixin configs (common,
  server and client) and records every patch: `@Inject` at any point, `@Redirect`,
  `@Modify*`, `@Overwrite`, MixinExtras' `@WrapOperation`/`@WrapWithCondition`/
  `@ModifyExpressionValue`/`@ModifyReturnValue`/`@WrapMethod`. Each patched method gets a side
  (`where`): `java` (the mirror or headless client runs it), `fired` (Horizon fires its
  HEAD/TAIL/RETURN injections), `routed` (the action goes to the mirror when patched), `rust`
  (Horizon's; the patch does nothing). The log has a line of sums (`Mixin table: …`) and one
  line per method a mod patches that Rust does (`Mixin table: Rust does Biome.shouldSnow …`);
  the whole table is `horizon-java/mixin-table.tsv` in the game folder.
- **Routed actions** (a mod patches their inside, Fabric API not counted: Horizon fires its
  events itself). Decided per block/item class, cached, logged once (`… goes to the mods`):
  - Breaking a block (`HorizonServer.breakBlock`, Rust `java_break`): a patch of
    `ServerPlayerGameMode.destroyBlock` past its head/end, or of the block's or tool's
    `playerWillDestroy`/`playerDestroy`/`dropResources`/`getDrops`/`spawnAfterBreak`/
    `mineBlock`: vanilla's whole `destroyBlock` runs on the mirror (removal → Horizon's world,
    drops and experience orbs → Horizon's entities, tool wear → slots); Rust does nothing
    more. Otherwise Horizon breaks it after firing `destroyBlock`'s HEAD injections and
    Fabric's BEFORE, and its RETURN/TAIL ones after (`afterBreak`).
  - Starting to dig: Fabric's `AttackBlockCallback` (`attackBlock`, from the START action).
  - Random ticks (`routedRandomTicks`): a vanilla block whose class chain's `randomTick`
    (`growCrops`, `getGrowthSpeed`, `advanceTree`…) is patched, and growing plants (crops,
    stems, saplings, cane, cactus, berries, bamboo, kelp…) when `BlockStateBase.randomTick`
    is (Serene Seasons). horizon-world's `RANDOM_TICK_HOOK` now returns true to take the tick;
    Java runs `BlockState.randomTick` at the end of the level's block tick.
  - Using an item on a block (`useRouted`, `GameModeMixin`): a patch of `useItemOn` past its
    head, the block's `useItemOn`/`useWithoutItem`, the item's `useOn`/`place`, bone meal's
    `growCrop`, `performBonemeal`: vanilla's body runs instead of PASS to Horizon.
  - Item pickup (`itemTouch`) and experience orbs (`orbTouch`, Clumps): an `ItemEntity` /
    `ExperienceOrb` made for the call, `playerTouch` on the mirrored player (the orb's count,
    the player's experience given first), the result back (count left, delay, experience;
    slots). Server-thread/LAN players only (`guest_pickups`, `guest_orbs`); in-frame not yet.
  - An `ExperienceOrb` a mod's code spawns becomes Horizon's orb (`dropItem` of
    `#experience_orb`, the value as the count).
- `HORIZON_JAVA_DEBUG=ticks` logs each random tick the mods run and what it made.
- Second round (`HorizonVanillaActions`, from the table of a 91-jar pack: the most patched
  vanilla actions left were `Player.attack`, item use, crafting results, crop growth):
  - Horizon's own mobs, hit or used by a player (`vanilla_entity`, from `java_entity_attack`
    and `java_entity_interact` for ids below the bridge's): a copy made from the mob's NBT
    (`mob_tree_nbt`) gets Fabric's `AttackEntityCallback`/`UseEntityCallback` and the mods'
    heads of `Player.attack`/`interactOn`. Bits back: taken, swing, removed (the mods
    removed the copy: Horizon removes its mob, no drops). A vanilla mob a mod's code adds
    (`addFreshEntity`) becomes Horizon's (`Natives.spawnEntity` → `Action::SpawnEntity` →
    `load_mob`): Carry On picks a cow up and puts it down. Middle patches of `Player.attack`
    (Better Combat) stay Rust's: the entity is Horizon's.
  - An item used in the air (`GameModeMixin.useItem`): a vanilla item whose class's use (or
    `releaseUsing`, `finishUsingItem`) a mod patches inside goes to vanilla's use on the
    mirror (`useRouted`); the heads of `ItemStack.use` and of `use` on the item's classes are
    fired (`fireUse`), a cancel being the result.
  - A crafting result taken (`menu::CraftedTake`, from `Menu.on_take`; guests in
    `guest_menu_op`, in-frame beside `award_crafted`): vanilla's `ResultSlot.onTake` on a copy
    of the grid (one of each ingredient, so remainders stay in the copy) and the result
    (Architectury's, Balm's, Modonomicon's crafted hooks). What the mods give is sent to the
    guest at once (`LanHub::send_inventory_slots`: the click's answer would take it as known).
  - Crop and sapling growth: covered by the first round (random ticks, bone meal through
    `useRouted`: BCLib and Puzzles Lib patch `BoneMealItem`, Balm `CropBlock`/`StemBlock`).
- Third round:
  - A player's attack (`HorizonCombat`, Rust `java_attack_mob` from `guest_attack`), when a
    mod patches `Player.attack`'s inside (Farmer's Delight, Spell Engine) or the head of the
    client's `Minecraft.startAttack`: copies of the target and the mobs within 8 blocks (NBT, Horizon's ids)
    stand in the mirror's level for the call (`HorizonServer.entities` lists them);
    vanilla's `Player.attack` runs with the patches; `CombatCaptureMixin` catches what
    reaches each copy's `hurtServer` and `knockback` (vanilla's bodies don't run on a copy),
    and Horizon hurts and pushes its own mobs with it (`Game::routed_hit`). The weapon's wear
    and exhaustion are the mirror's. First the click's client half
    (`HorizonClient.startAttack`, 2026-11-04 generic-entities): the mods' injections at the
    head of `Minecraft.startAttack` run on the player's headless client, looking at what the
    player looks at; one that cancels takes the click (its own swing follows from its client,
    whose request reaches its server half and hits the mirror's entities: Horizon's mobs'
    shadows), and the copies' hit is left out (an empty answer: the game swings, hits nothing
    itself). The attack key reads down for the click's next 2 client ticks, and the client
    tick fires the mods' head of `continueAttack` (vanilla's `handleKeybinds`). A mod's
    attack handed to a mirrored connection's `handleAttack` attacks directly
    (`GameListenerMixin`: the mirror has no packet processor). No code of any one mod.
  - A hotbar pick (`SetCarriedItem`): the heads of `Inventory.setSelectedSlot` (Carry On while
    carrying); a cancel keeps the old slot and sends it (`SetHeldSlot`).
  - Getting onto a vehicle (after the guest's interact boarded one): the heads of
    `Entity.startRiding` with a copy of it (a mob from its NBT, a boat or cart by type); a
    cancel takes the guest off (`stop_riding`).
- Fourth round:
  - Getting off (a guest sneaking in a boat, cart, mount; `guest_vehicle_tick`): before the
    seat logic, the heads of `LivingEntity.stopRiding`, `Entity.stopRiding`,
    `Player/Entity.removeVehicle` run with the player sitting on a copy of its vehicle
    (`HorizonVanillaActions.dismount`, the `vehicle` field set by reflection); a cancel keeps
    it seated (its shift is dropped for the tick). Once off, their RETURN/TAIL run.
  - A sweep's box queries: the mirror level's (`HorizonServer.entities`' `get(box, ...)`) list
    the visiting copies too, so a mod's swing finds every mob in it, not just the cursor's.
  - A player's fall damage (`fall_damage`, when a mod patches `causeFallDamage` or
    `calculateFallDamage`: Moving Elevators, Mythic Upgrades, Balm's and Puzzles Lib's
    events): the server sets `PlayerMotion::route_fall` for a guest's moves; a landing that
    would hurt leaves `FallEvent::Routed` instead (the statistic stays Rust's), and after the
    moves the game asks `HorizonVanillaActions.fall`: vanilla's `causeFallDamage` on the
    mirrored player, `calculateFallDamage` answered with Horizon's safe distance and damage
    multiplier attributes (`FallCaptureMixin`: the mirror has no effects or attributes), the
    damage reaching `hurtServer` caught. Horizon deals it with vanilla's sounds
    (`MotionWorld::routed_fall_damage`). A mace's or wind charge's impulse cuts the
    distance first (as `cause_living_fall_damage` does), and a native mod block's fallOn
    (`mod_block_fall`, `FallEvent::Routed`'s `contact`) changes the damage after the mods.
  - A player's new effect (`Game::add_player_effect`: potions, food, totems, `/effect`): the
    heads of `LivingEntity.addEffect` with the instance; a cancel drops it. Beacons,
    conduits, raids' Hero of the Village, the ominous bottle's Bad Omen, the Warden's
    darkness and the turtle helmet's water breathing (each tick) ask too
    (`Game::add_effect_asking_mods`), only when a mod hooks `addEffect` (routed action
    `effects`). A trial spawner's Bad Omen → Trial Omen asks for the Trial Omen
    (`java_add_effect_to`, the guest's UUID); a refusal still takes the Bad Omen.
  - Round 6, the next "Rust does" lines by count: a player's jump (`LivingEntity` /
    `Player.jumpFromGround`, 4 mods: Puzzles Lib's jump event, Mythic Upgrades, Spell Engine,
    Wizards) and a teleport (`ServerPlayer.teleport`, 5: Balm, GlitchCore, Polymer,
    FrozenLib) fire their heads and ends on the mirror (routed actions `jumps`, `teleports`).
    Round 7: a head that cancels undoes them. A jump: the heads run before the guest's moves
    (`HorizonVanillaActions.jumpStart`, one per jump the moves make); a cancelled one isn't
    made on the server (no statistic, exhaustion or velocity) and the guest is teleported
    back to where it took off; the ends (`jumpEnd`) only for the jumps made. A teleport: the
    head (`teleportStart`) before Horizon moves the guest, in its level (`lan_actions`'
    swap-out: the guest keeps its place and look), to another level by command
    (`run_level_teleports`) or through a portal (`start_server_trip`; the portal asks again
    next tick, as vanilla's does); the ends (`teleportEnd`) once it is there, with the new
    level's `TeleportTransition`. Not routed: the host's own player in-frame (no LAN hub).
  - Round 7: advancements on the mirror (routed action `advancements`, when a mod patches
    `PlayerAdvancements.award` anywhere: Balm's and Puzzles Lib's events inject inside it).
    Before Horizon grants a criterion (`trigger_criteria`, `/advancement grant`), vanilla's
    whole `award` runs on the mirrored player's advancements (`HorizonVanillaActions.award`):
    the mirror's own advancement (the server data's criteria and requirements, rewards
    empty and no chat announcement: Horizon gives those), its progress set first to the
    criteria Horizon has. `award` returning false with the criterion still ungranted is a
    refusal: Horizon doesn't grant it. Not done: `tickEffects`, `openMenu` (Horizon's own
    menus have no Java `MenuProvider`), `revoke`.
  - Precipitation (`ServerLevel.tickPrecipitation`), when a mod patches the biomes' weather
    (`Biome.shouldSnow`, `shouldFreeze`, `getPrecipitationAt`...: Serene Seasons): the game
    keeps each column's top (`GameLevel::precipitation` → `java_precipitation`) and, at the
    end of the level's block tick, asks the mirror's biome (`HorizonVanillaActions
    .precipitation`), then runs its own tick with those answers
    (`Behaviours::precipitation_tick_with`). Snow lies in the plains in a Serene Seasons
    winter; the sky still rains in Horizon's window (the client's biome weather is Horizon's).
  - Performance mods (Lithium, FerriteCore, ModernFix...: `HorizonMixinTable.PERFORMANCE`)
    never make an action the mirror's: Lithium overwrites `Biome.getTemperature`.
- **Hoppers and Java mods' blocks** (bridge-mixins-8). A Java mod's block entity lives only in
  Java (`HorizonBlockEntities.LIVE`): Horizon's world has none there, so Horizon's hoppers
  didn't see a cooking pot, a mod's chest or machine at all. Now `hopper_eject` and
  `suck_from_container` (`machines.rs`), finding no Horizon container, ask Java
  (`java_hopper_insert`/`java_hopper_extract` → `events::hopper_insert`/`hopper_extract` →
  `HorizonHoppers`): the block's Fabric `ItemStorage.SIDED` on the face, as Fabric Transfer
  API's own patch of `ejectItems`/`suckInItems` does (it wraps plain vanilla `Container`s
  too). One item a call, in the hopper's slot order; vanilla blocks stay Rust's. A block with
  no storage on that face isn't asked again for 40 ticks (`JAVA_NO_STORAGE`).
  `HORIZON_JAVA_DEBUG=hoppers` logs each move. Not done: Java mods'
  hoppers/pipes pulling from Horizon's hoppers (those go through `COPIES` as before), native
  mods' `InventoryMoveItem` event for these moves.
- **Droppers and mods' container entities** (bridge-mixins-9). A dropper with no Horizon
  container in front asks Java (`java_item_storage`, an insert of nothing: is there storage?);
  where there is, it does what Fabric Transfer API's `DropperBlockMixin` does at the default
  dispense: `getRandomSlot` once more, one item of that slot into the storage, nothing
  dispensed (`machines.rs` `java_drop`). Horizon's droppers also fill Horizon's own container
  entities in front now (vanilla's `getContainerAt`; they didn't). A mod's entity that is a
  `Container` (a mod's chest minecart) is found by `HorizonHoppers` when the block has no
  storage (`containerEntity`: its box meets the block's, a random one of several;
  `ContainerStorage` over it), so Horizon's hoppers, hopper minecarts and droppers move items
  into and out of it. Rust asks only where one is: the entity snapshot lists the blocks their
  boxes meet (`containers`, `JAVA_CONTAINER_BLOCKS`; no 40-tick wait there, carts move).
  Fixture `cartmod` (a barrel cart: vanilla's chest minecart under the mod's type); regress
  `droppers`. Its cart isn't drawn yet: vanilla's `MinecartRenderer` in the headless client
  throws (`blockModelResolver` null), a client/gpu task.
- **Mods' foods** (bridge-mixins-9). A mod item used in the air runs vanilla's `useItem` on
  the mirror (mods' own items are theirs); for a food that *started* the use
  (`Consumable.startConsuming` → `startUsingItem`) on the mirrored player, which is never
  ticked, so it never finished, while Horizon was told "taken" and never ate: a Farmer's
  Delight meal's animation ran on and on (beta tester). Now a mod's consumable the mirror
  started using is stopped there and handed to Horizon (`handOverConsumable`), which eats it
  with the components the content build carried (food, consume effects, the bowl as
  `use_remainder`). Not routed: a mod item whose `finishUsingItem` does more than the
  components (FD's hot cocoa clearing an effect: `affectConsumer`), and held uses that aren't
  consumables (a mod's bow drawn: the mirror starts it and never ticks it). Regress `eat`;
  `HORIZON_JAVA_DEBUG=use` logs each hand-over.
- **Mods' fluids flow by their own code** (bridge-mixins-9). A mod's `LiquidBlock` is carried
  with a vanilla template (water's or lava's), so Horizon's Rust fluid code ran it as that:
  BuildCraft's oil (and the tester's Create honey) spread as `minecraft:lava`, set fire, and
  water turned it to cobblestone. Now `LevelAccess::foreign_fluid` (horizon-world: a fluid
  another engine runs) is true for the mods' fluid blocks (`HorizonServer.modFluidBlocks`,
  `JAVA_FLUID_STATES`): Horizon's fluid ticks, lava's random ticks and lava's conversions leave
  them, and where Horizon would schedule their fluid tick (placed, a neighbour changed) it
  tells `foreign_fluid_changed` instead, which goes to Java with the blocks set
  (`HorizonServer.blockEntities` schedules `getTickDelay`). The mirror's fluid ticks
  (`HorizonTicks`, `getFluidTicks`) now hold mods' fluids and run `FluidState.tick` as
  `ServerLevel.tickFluid` does: the mod's own delay, slope, drop-off and physics (BuildCraft's
  gaseous fuels rise), its blocks written to Horizon's world as usual. Regress `fluids`.
  Not done: their drawing (gpu chat), and entities in them: Horizon's physics still read the
  template's fluid (a player in oil is in "lava": burns, swims slowly), and Horizon's water
  meets them as lava when it spreads. Mods with a `-` in their id (milk-lib) aren't carried at
  all (content).
- **A routed break's sound played twice** (bridge-mixins-10; a tester: "dirt goes brrbrr").
  The breaker's game plays its own break (sound and particles) as it predicts it; vanilla's
  `ServerLevel.levelEvent(player, 2001, …)` broadcast leaves that player out. The mirror's
  `ServerLevelMixin` dropped the source, so a routed break's 2001 (vanilla's whole
  `destroyBlock` on the mirror) reached the breaker again. Now the source, when it is a
  mirrored player, goes with the event (`Natives.levelEvent`'s `except`, a UUID →
  `Action::LevelEvent.except` → its connection, `JAVA_CONNS`) and `lan_record_events` leaves
  it out (`java_event_except`); other players still get it. Which tester mod routed it: none
  of FallingTree, WorldEdit, Biomes O' Plenty (+TerraBlender, GlitchCore), Terralith
  (+Lithostitched), Geophilic, Lithostitched alone does (one sound each, tested); Create Fly
  (not tested here: no jar) is the likely one, or a combination. Any mod that patches the
  inside of breaking did it: the `mixinmod` fixture does (2 sounds before, 1 now). Regress
  `breaksound` (scene `dig`: dirt and a log broken by hand, `heard` counts).
- **Loading chunks through vanilla's chunk system** (bridge-mixins-10: Chunky). A
  pregenerator adds a load-only ticket (`TicketType` with the loading flag only) and asks
  `ServerChunkCache.getChunkFutureMainThread(x, z, FULL, false)`, on the server thread, then
  waits for the future. `HorizonChunkLoads` answers it on the mirror: Horizon's chunk at once
  when loaded; else, when `load` or when the level's tickets keep the chunk (where vanilla's
  ticket made a holder: `HorizonTickets.keeps`), a future completed on the server thread once
  Horizon has it loaded (polled each tick; given up after 2 minutes). Waited-for chunks go to
  Horizon with the tickets' chunks, and load-only tickets now keep the chunks their loading
  level brings to FULL (`33 - level` around them): Horizon generates them, and when the ticket
  goes they unload and are saved as any chunk. What is generated already: `ChunkMap.readChunk`
  and `ServerChunkCache.chunkScanner()` read Horizon's own region files, read-only (vanilla's
  format: header, length, compression; a loaded chunk is "full"). Also needed:
  `MinecraftServer.getRunningThread()` is Horizon's server thread (Chunky hands itself to the
  server unless on it: null made it hand itself over every tick, forever), the chunk map's
  `mainThreadExecutor` is the server, `runDistanceManagerUpdates` answers false, and atomics a
  mod's mixin adds to an allocated object (Chunky's `AtomicBoolean` on `MinecraftServer`) are
  made at their defaults (`emptyCollections`). Tested: `/chunky center 2000 2000`, `radius 64`,
  `start`: 81 chunks in 12 s, all saved full; a second start loads none; radius 256: 1,089
  chunks in 1:57 on 2 cores (~9/s), tickets back to 0 after. Gap: Horizon has no load-only
  chunks, so a ticketed chunk ticks while kept (a pregenerator holds a few dozen at a time).
  Regress `chunky`; `HORIZON_JAVA_DEBUG=chunks` logs each chunk asked for and loaded.
- The mirrored player's `tickCount` follows Horizon's ticks: Carry On refuses a second action
  on the tick of the last one, and the mirror's players aren't ticked.
- `HORIZON_JAVA_DEBUG=entities` logs each attack on/use of Horizon's entities, a vanilla mob a
  mod adds (with its stack) and each crafting result's hooks; `=stall` writes every Java
  thread's stack to `horizon-java/stacks.txt` each 30 s (the JVM runs with `-Xrs`: no jstack).
- Fixture `java/fixtures/mixinmod` (`fixture_mod.sh mixinmod <jar>`, which now copies
  `*.mixins.json`): an `@Inject` at INVOKE inside `destroyBlock` (every break drops a
  diamond), an `@Redirect` inside `CropBlock.randomTick` (crops grow in the dark), a HEAD
  cancel of `ItemEntity.playerTouch` (dirt can't be picked up), an INVOKE inside
  `ResultSlot.checkTakeAchievements` (an emerald with every craft), a HEAD cancel of
  `SnowballItem.use` (no throwing), a HEAD cancel of `Player.attack` on pigs, a HEAD cancel
  of `jumpFromGround` holding a slime ball, of `ServerPlayer.teleport` to x 666, of `award`
  for "Acquire Hardware", and an INVOKE inside `award` (before the rewards) that hears the
  rest.

## Mods that draw with the GPU

26.3's blaze3d GPU API is `com.mojang.renderpearl` (`api/`: `GpuDevice`, `CommandEncoder`,
`RenderPass`, textures, buffers, pipelines), with a backend interface under it
(`backend/api/`: `GpuDeviceBackend`, `CommandEncoderBackend`, `RenderPassBackend`) that its
OpenGL and Vulkan backends implement. The headless client has a third: `HorizonGpu`, whose every
call is a native into `crates/horizon-jvm/src/gpu/` (wgpu, the game's version, on its own
device: Vulkan, else GL). Vanilla's `FrontendGpuDevice` sits on it unchanged: its checks, GLSL
compiled to SPIR-V by shaderc (LWJGL's, from the game's libraries) and reflected; the backend
gets SPIR-V, vertex layouts and uniform lists.

- Textures, views, samplers and buffers are wgpu objects by id. A render pass is recorded and
  encoded when it ends; copies and clears are submitted at once (queue writes in between land
  in order). Readbacks wait for the GPU.
- Shaders: naga reads the SPIR-V after `gpu/spirv.rs` splits each combined image sampler
  (`sampler2D`) into a texture (group 0) and a sampler (group 1, same binding), function
  parameters and calls included, and drops `OpLine`s. Pipelines use wgpu's derived layouts;
  bind groups are made per draw from the bound uniforms (the bindings the shaders use).
  Variants per depth format (Vulkan's backend has with/without depth).
- Coordinates are renderpearl-Vulkan's: NDC y -1 is row 0 (naga's reader flips y for wgpu),
  depth 0..1, so front faces are clockwise for wgpu (`FrontFace::Cw`).
- The headless client gets `RenderSystem.initRenderer(new FrontendGpuDevice(HorizonGpu))` at
  `finish`, then on the first client tick (the render thread is the client's: the server
  thread) the game renderer's GPU parts: a GUI renderer with its GUI projection, lighting,
  overlay and light map textures, a fog buffer (`Fog` uniform, mode NONE), a main render
  target; and `GameRenderer.preloadUiShader` over the client resource manager (vanilla's and
  the mods' shaders: the fallback pipeline cache).
- A frame per player and client tick: the mods' injections at the head and tail of
  `GameRenderer.extract` (`HorizonHooks.fireFrame`), the HUD between, then the dynamic uniforms'
  `reset`, fenced tasks, transient memory reused (`HorizonGpu.endFrame`). Mods' HEAD/TAIL
  injections into `Player.tick` run for the client player each tick.
- Pictures in picture (`GuiRenderState.addPicturesInPictureState`: Xaero's minimap): the
  renderer Fabric's registry made for its class (`PictureInPictureRendererRegistryImpl.FACTORIES`)
  prepares it into its texture, and its blit of that texture becomes a HUD image op
  (`horizon:dynamic/gpu<id>`, read back when it changed) and a blit. Drawn at most 10 times a
  second per player (`PIP_INTERVAL`); in between the last blit is repeated.
- Textures the headless client never uploaded (`HorizonTextures.View`: a mod's PNG, a dynamic
  texture) are uploaded the first time a pass samples them; atlases' views bind white.
- Horizon's window draws images over 64 pixels from 16 atlas slots of 512 (`JAVA_BIG_IMAGE_SLOTS`),
  and images over 512 from **textures of their own** (`HudBatch::images`, up to 8: `JavaImages::own`
  in `java_screen.rs`; `Blend::Image(id, linear)` runs in `hud.rs`, the HUD's alpha pipeline with
  the image's bind group). Nearest when each image pixel covers whole window pixels, else linear.
  Only textures a render pass drew into (`HorizonGpu.drawnInto`: not atlases, which
  `TextureAtlas` also draws with passes, nor textures written from memory) become HUD images;
  real atlases' quads go the headless sprites' way. A big one (over 512: a full-screen map's
  target, the window's framebuffer) is read at most 10 times a second per player, and shrunk
  (`HorizonGraphics.shrink`: box filter, any ratio) to the receiver's largest image
  (`HorizonClient.maxImage`): 2048 for this process's own game (`HorizonDeviceMods.own`, in
  memory), 512 for a network player.
- **Each player's window** (`HorizonClient.WINDOWS`): the game sends
  `{"kind":"size",width,height,scale,px_w,px_h}` (GUI size and scale, framebuffer) on the HUD,
  screen and overlay windows; `as()` puts the player's window on the headless `Window`
  (framebuffer and GUI fields, `windowRenderState`, the GUI projection), so pictures in picture
  are drawn at their GUI scale and full-screen targets at their framebuffer. A size input also
  resizes the main render target. Test a big window with `SIZE=2560x1440 GUI_SCALE=6` (launch.sh;
  regress `maps-hires`).
- A GPU texture's pixels are read when the frame's ops are taken (`HorizonGraphics.ops()`:
  `readPending`), not at its blit: the real GUI draws its elements after the whole screen is
  extracted, and mods rely on it. XaeroPlus clears the World Map's target as Xaero adds it to the
  GUI, then Xaero draws the map into it; read at the blit, the map was black.
- Mods' blits of a screen-sized target land in `gameRenderState().guiRenderState` (Xaero's
  World Map): the capture's `GuiRenderState` stands there while a screen or the HUD draws.
- **Flywheel (inside Create Fly) runs with its backend off**: its instancing backends need
  OpenGL, and the headless client's device is Horizon's, so Create draws its kinetic parts
  through its block entity renderers (SuperByteBuffers as custom geometry), which the bridge
  records. Flywheel's model helpers are made at the level renderer's reload
  (`LevelExtractor.allChanged`'s end): `HorizonClient.levelRendererReady` runs the mods'
  injections there once per client level after the models load.
- `HORIZON_JAVA_GPU=0` turns the device off. `HORIZON_JAVA_DEBUG=gpu` logs textures,
  pipelines and GPU blits (and saves one in 100 as `/tmp/horizon-gpu-N.png`; logs each target's
  first picture that isn't all black: "gpu: <name> WxH, N pixels not transparent, M not black");
  `HORIZON_GPU_TRACE=1` logs each pass (targets, draws, pipelines, its first draw's vertices and
  uniforms, the target's lit pixels after it); `HORIZON_JAVA_GPU_DUMP=dir` writes each
  pipeline's SPIR-V, which `HORIZON_SPV_DIR=dir cargo test -p horizon-jvm gpu` reads back.

## Create, Flywheel, OBJ models, mods' fluids, 3D Skin Layers (bridge-gpu-12)

- **Flywheel** (its model helpers made by the mods' own injections at the level renderer's
  reload: see the 26.3 facts, `HorizonHooks.fireMerged`). Create's renderers then draw through
  `SuperByteBuffer`s: custom geometry in the block atlas's UVs.
- **Custom geometry in an atlas's UVs** (`HorizonEntityRender.atlasMesh`): a mesh whose texture
  is `ns:textures/atlas/<name>.png` is split by sprite (the sprite holding each quad's middle),
  UVs made the sprite's own, as block models' quads already were.
- **Blocks their renderer draws whole.** Create wraps its shafts' and cogwheels' block models
  (`BracketedKineticBlockModel`: in a world only the brackets a block entity has; 6 and 130
  quads outside one). Horizon drew the still model under the turning one. A block entity entry
  is marked `whole` when its state's model has a `addPartsWithInfo(BlockAndTintGetter, BlockPos,
  BlockState, RandomSource, List)` that gives nothing at its place (the mirror's client level);
  the player's game then leaves that state's chunk model out
  (`mesher::hide_model_drawn_by_block_entity`, all sections meshed again once).
- Mods' block entity renderers draw only once a client level's renderer reload ran
  (`HorizonClient.levelRendered`): a Create motor placed while the models loaded failed once.
- Create's HUD hook runs `Hud.extractHotbarAndDecorations` to its contextual bar: the
  allocated `Hud` gets empty contextual bars and the client connection a `ClientWaypointManager`.
- **OBJ models** (`horizon-assets/src/obj.rs`): NeoForge's `"loader": "neoforge:obj"` (also
  `forge:obj`), which Create's cogs, wheels and many kinetic parts use (100 models): the `.obj`
  and its `.mtl` are read with the model (`model_library::with_obj`), faces become quads
  (triangles repeat the last corner), `map_Kd #slot` names the model's texture slot, `flip_v`.
  The OBJ is the model's geometry (no elements). Fixes the blank cogwheel, large cogwheel and
  water wheel items. Still blank: items drawn only by a custom renderer (Create's wrench).
- **The kinetic network runs in Java** (regress `create`: motor, shafts and cog at 16 rpm in
  `HORIZON_JAVA_DEBUG=ber`, which also logs once per state what its renderer drew, its client
  block model's quads and whether its renderer draws the block whole; regress `createwhole`).
- **Mods' fluids**: a mod `LiquidBlock` is carried with `minecraft:lava` as its template, so
  Horizon flowed it as lava and drew nothing for its own blocks (the mesher's fluids were water
  and lava by name; the lava seen was Horizon's flow). Now the mesher gives a mod `LiquidBlock`'s
  states (by class in the facts) a lava-family fluid state at its `level`, and the client's baked
  `FluidStateModelSet` (vanilla's bake, with Fabric's fluid registry and Create's mixin) gives
  each mod fluid its still and flowing sprites, tint and layer (`HorizonFluids` →
  `Natives.modFluids` → `java_mod_fluids` → `mesher::set_mod_fluids`). The terrain atlas loads a
  mod block's `<ns>:block|fluid/<name>_still|_flow` when the files exist (no model names them).
  Flow is still lava's (Horizon's fluid ticks; mixins chat). Only where the process runs the
  mods (single player, a LAN host): a dedicated server's players don't get the looks yet.
- **Player model parts mods draw their own way** (3D Skin Layers; generic since 2026-11-04):
  when a mod patches `ModelPart.render`/`compile` (`HorizonMixinTable.patchedAnywhere`, client
  patches counted), `HorizonEntityRender.modParts` keeps the pose the player renderer submitted
  its model with, runs `setupAnim` as that player with the camera on it (mods build their
  parts there), and renders each leaf part alone through vanilla's `ModelPart.render`: a part
  whose quads differ from its own cubes' faces is the mod's. Those are shown (the mirrored
  player's skin-part flags are all off), the others' cubes skipped, and the root rendered into
  custom geometry with the skin texture, with the names of the parts the mod drew (`parts`).
- **Players' real skins in the headless client** (`HorizonSkins`, bridge-skin-layers, 2026-11-06):
  the mod's voxels were built from the profile's default skin (Ari/Steve) and laid over the
  player's real one (white or red boxes at the arms, slim/wide mismatch). Now:
  - a mirrored player's profile carries the `textures` property Horizon's server has for it
    (`java_server.rs` passes each guest's once, `events::profile_textures`); `SkinManagerMixin`
    answers `HorizonSkins.get`, which fetches the skin through a real `SkinTextureDownloader`
    (skins only: `SkinDownloaderMixin` lets only that one through; files in the game folder's
    `horizon-java/skins/`) under vanilla's id `minecraft:skins/<sha1>`, Horizon's own atlas key
    for that skin (`skins::skin_location`), so `java_sprite` finds it;
  - an offline name's launcher skin (`HORIZON_OFFLINE_SKIN`), which no server knows: the game
    sends it once when it first hears a server's Java mods (`horizon:java_own_skin`: slim byte +
    64x64 RGBA, `remote/java.rs` `send_java_own_skin`); the server registers it as a
    `DynamicTexture` `horizon:offline_skin/<uuid>` (`events::own_skin` → `HorizonSkins.ownSkin`)
    and makes the player's avatar again; `java_sprite` maps that id to this game's
    `horizon/offline_skin` slot when the uuid is its own player's (other players' games don't
    have that skin: they keep Horizon's flat layers for that player);
  - Horizon leaves its own outer layers (flat, or its own 3D ones) out where a mod draws them
    and the mesh's skin is in the atlas (`MobExtra::mod_drawn_parts`, ORed into the hidden
    `PlayerModelPart` bits in `anim.rs`; `java_mod_drawn_parts`).
  Regress `skinlayers-skin` (a slim test skin from `tools/java_bridge/fixture_skin.py`, never
  Mojang art). Left: the mod draws all parts whatever the player's Skin Customization says (the
  mirror's flags are off; Horizon's hidden parts aren't passed).

## Create's machines, contraptions and items (bridge-gpu-13)

Generic fixes found with Create Fly (no Create-specific code); regress `createmachines`,
`contraption`, `createitems` (scenes `machines`, `contraption`).
- **Mods' own item model types** (`create:model/wrench`, `create:model/normal`, the extendo
  grip, the linked controller...): Horizon reads only vanilla's types, so the items were blank.
  Once the client's models are in, with a player (`HorizonItemModels.exportOnce`, from the
  client tick), every item whose `ItemModel` class isn't vanilla's is resolved
  (`updateForTopItem`, GUI) and its quads sent (`Natives.modItemModels` →
  `item_render::set_java_item_models`): the layers' quads, the layers' Fabric meshes (Create
  emits there: `FabricLayerRenderState.emitter`, read through `fabric_getMutableMesh`), and a
  special renderer's submit into a collector keeping custom geometry and `submitItem`; local
  transforms applied, UVs made each sprite's own, each context's display transform. Horizon
  uses them where its own layers draw nothing (no quads, or the missing model).
- **OBJ `map_Kd 0`** (no `#`): a texture slot of the model when it has one (Create's backtanks).
- **Items a block entity renderer draws** with the real item models (`submitItem` reaching the
  recording collector: vanilla's 8 arguments and Fabric's with the mesh) are kept as a block
  model's quads; those it resolves with `appendItemLayers` itself (Create's depots and belts)
  are kept items, as `updateFor*`'s are. Iron sheets show on the depot.
- **Mirrored players' item cooldowns tick** (`HorizonServer.tick`): a belt connector's first
  shaft starts a 5-tick cooldown that never ended, so the second click did nothing.
- **A click a mod block consumed ends there** (`CLICK_TAKEN`): Horizon's client can't run a mod
  block's use, so it sends the off hand's `UseItemOn` and `UseItem`s too; an empty off hand on a
  depot took back the ingots just put on it. Within 4 ticks of a consumed `useBlock` (vanilla's
  `rightClickDelay`: no new click comes sooner), the rest of the click is taken (returns 1).
- **Client particles** a mod adds to the client level (`ClientLevel.doAddParticle`: the belt
  connector's line) go to that player as a `ClientboundLevelParticlesPacket` (count 0); the
  headless client has no particle engine (its NPE failed Create's whole client tick).
- **Neighbour updates a mod calls itself** (`neighborShapeChanged`, `updateNeighborsAt`,
  `updateNeighborsAtExceptFromFacing`, `neighborChanged(pos, block, orientation)`) are no-ops on
  the mirror's levels: they have no `neighborUpdater` (made without their constructor), and a
  bearing's assembly failed on it. Horizon updates the neighbours of the blocks it is told of.
- **Contraptions**: the renderers' contexts get the client's `BlockModelResolver` once its models
  load (`HorizonEntityRender.modelsLoaded`; renderers that failed before are tried again):
  `ContraptionEntityRenderer` needs it. A bearing's contraption then draws and turns (its
  blocks' SuperByteBuffer through the atlas-mesh path).
- Works with no change: the press (ingots to sheets on a depot two blocks below it), the
  millstone (fed by Horizon's hopper), the deployer (places what a hopper gives it), belts.
- Not done: Horizon's item entities aren't in the mirror (items thrown on a belt or a
  millstone, a press over items on the ground); players don't collide with contraptions; a
  contraption's own moving actors (drills, harvesters) untested.

## The client from its start, its own block entities, mods' packets, sounds and wheel (bridge-client-14)

Generic fixes for a tester's ~60 client mods and Create's client items (regress `earlyclient`,
`creategoggles`, `createvalue`, `createtoolbox`, `createsounds`, `modscroll`).
- **The GPU device stays when a mod fails at its start.** Async Particles' injection at the end
  of `RenderSystem.initRenderer` casts the device to a `VulkanDevice` (its backend's name said
  "vulkan"): the throw lost the device, so the client had no models, and every mod reading them
  failed (ModelManager null, EMF's models, Xaero's World Map's frame). The device's backend name
  is now `Horizon wgpu` (the wgpu backend is in its driver line), and a throw after the device is
  set is the mod's (told in chat), not the device's.
- **The client is made in vanilla's order**: GPU device, `ClientBootstrap` (atlas source types:
  JEI's mezz_config "Unknown element id: minecraft:directory"), window, a frame rate limiter, then
  options (no more "Failed to load options" on every start), key names in text
  (`KeybindResolver`: "Release Right Button"), and the HUD's field defaults (Immersive Hotbar's
  held-item name read `lastToolHighlight` null).
- **A particle engine** (`HorizonParticles`): `createParticle` goes to the player as a particle
  packet, as `ClientLevel.doAddParticle` does; particle objects mods add are dropped; Async
  Particles finds a real engine.
- **Models from the start** (`standInModels`): a model manager (the one the models' reload fills
  later, so suppliers over it see the models) and an item model resolver with no manager, which
  keeps items for Horizon (`ItemModelResolverMixin`; swap animation defaults).
- **Receivers registered while in game** (ShulkerBoxTooltip at `ClientPlayConnectionEvents.INIT`):
  client connections get Fabric's play addon (`fabricAddon`); HorizonLoop asks it first.
- **Connections made without their constructor never NPE on send** (FancyMenu's handshake:
  `pendingActions` null): `MirrorConnectionMixin` drops the send, a mod's custom payload on the
  client's connection goes to the mods' server code.
- **Mods' reload listeners ordered after vanilla's atlases or models** (Fabric's resource loader
  v1 `addListenerOrdering`: Continuity) run with the models' reload, and one that fails on its
  own (it reads the atlases' shared stitch) runs again there.
- **The client has its own block entities** (`HorizonClientBlockEntities`): `ClientLevel.getBlockEntity`
  of a mod block gives the client's copy, made by the type and loaded from the update tag, then
  from the update packet at each `sendBlockUpdated` (and at most every 10 ticks after a
  `setChanged`), ticked by the block's client ticker as the player's client, within 32 blocks of a
  player (`-Dhorizon.clientBlockEntities`). Create adds its client behaviours there: goggles'
  overlay, value boxes' hover tips, frogport sounds. Writes from client code are dropped.
- **The client's part of using a block runs first** (`MultiPlayerGameMode.useItemOn` injections
  before its prediction, `HorizonClient.useBlockFirst`): one that ends the use takes the click and
  the server's use never runs (Create's value boxes, arm points, glue, chain conveyors). The use
  key is down while the right click lasts (the window's button when mods take every button, else
  6 ticks after each use), screens tick with the player's keys.
- **Mods' own packet types** (`Packet` classes outside `net.minecraft`: Create's `OpenScreenPacket`,
  `ValueSettingsPacket`) go to the other half: the player's client connection handles a server's
  (`HorizonLoop.toClientPacket`), the server connection a client's (`toServerPacket`); the client
  and the mirror's server have packet processors on their thread. Create's toolbox screen opens.
- **Mods' client sounds** (`HorizonSounds`): `SoundManager.play` sends the player a sound packet
  (relative ones at the player), ticking sounds tick and stop (a stop packet); `isActive` answers
  for them. Horizon plays a looping sound once.
- **The wheel while playing** (`MouseHandler.onScroll` injections, `-200` in the bound keys): the
  game moves its hotbar at once and sends the turn with the slot it left; a mod that cancels gets
  the hotbar put back (`ClientboundSetHeldSlotPacket`). Create's own value boxes are a held
  right-click in this port, not the wheel (its wheel is schematics, trains, elevators).
- Not done: Create's value box outlines and other world-drawn overlays (the Outliner draws in the
  level renderer: gpu); looping sounds repeat; the toolbox's 3D drawer in its screen.
- `HORIZON_JAVA_DEBUG=cbe` (client copies), `=sounds` (each sound sent), `=loop` (mods' packets).

## Clicks, items and fluids that mods' blocks take (bridge-mixins-11)

Generic fixes found with Create Fly; regress `createclick`, `createitemsin`, `createfluids`.
- **The client's half of a block click comes first** (`HorizonClient.useBlockFirst`, from
  `HorizonServer.useBlock` before the server's `useItemOn`). A vanilla client runs
  `MultiPlayerGameMode.useItemOn` before it sends anything, and mods end clicks there: Create
  picks a mechanical arm's points, a chain's first conveyor, value boxes, glue corners and
  ejector targets by injecting before `startPrediction`; the server never hears of such a click.
  The mods' injections at `useItemOn`'s head and before `startPrediction` run on the player's
  headless client (Fabric's own left out: its `UseBlockCallback` client half stays in
  `useBlock`); one that sets a result takes the click (a consuming one fills `CLICK_TAKEN`).
  Then those inside `performUseItemOn` (the prediction: `HorizonHooks.fireMergedInside`, every
  HEAD and INVOKE point, locals by type) before the server's half. Vanilla's bodies stay the
  server's (the inventory is shared).
- **Mods' own packet types** (`HorizonLoop.packetToServer`/`packetToClient`). Create registers
  its packets into the play protocol (records with `handle(listener)`), not as custom payloads:
  both directions were dropped (`ClientCommonPacketListenerMixin`, `PacketListenerMixin`). A
  packet whose class isn't Minecraft's, Fabric's or the bridge's now goes to the other half as
  a payload does: carried through its class's static `StreamCodec` field when it has one, the
  server half handled on the mirrored player's connection, the client half at flush as that
  player. Their handlers check their thread with `PacketUtils.ensureRunningOnSameThread`:
  `Minecraft.packetProcessor` and `MinecraftServer.packetProcessor` (made in the skipped
  constructors) are made on the loop's thread.
- **A mod's block gets `onPlace` and `affectNeighborsAfterRemoval`** (`HorizonServer.blockEntities`,
  with what each block replaced: `JAVA_SETS` keeps the old state, sent by name). Neither the
  mirror's `setBlock` nor Horizon's world ran them, so Create's tracks never joined the railway
  graph (`TrackBlock.onPlace` schedules `TrackPropagator.onRailAdded`): a station's item said
  "Cannot target this track here", then "Right-click the targeted train track first". Only
  classes that declare them in a mod's class (`ownsVanilla`): a mod block of a vanilla class
  is Horizon's template's.
- **Horizon's items in the mirror** (`HorizonItems`, Rust `java_items_json`/`java_items_changed`).
  Each tick Horizon lists its items within 32 blocks of a player; each stands in the mirrored
  level as an `ItemEntity` with Horizon's id, kept tick to tick (mods hold on to the item they
  work on), listed by `HorizonServer.entities` (a fan's air current finds it). Before the block
  entities tick: an item on a mod's block gets vanilla's `Entity.move` with no movement (the
  move's patches with the block under it: Create's depot, belt and millstone take what lands on
  them there; the move's own effects on it undone), and the mod blocks it is in their
  `entityInside` (inside shape checked, as `checkInsideBlocks`). After: removed, new stack,
  place, motion go back (`Natives.itemsChanged`). An item a mod moved or changed while it
  touches a mod's block is **held**: Horizon skips its tick (`java_holds_item`) and the mirror
  runs vanilla's `ItemEntity.tick` for it, so blocks whose collision depends on the entity
  (crushing wheels let the item they process fall through) work. Not done: players and mobs
  in fans' currents (only items), items dropped by Java code lose their components
  (`Natives.dropItem` passes id and count).
- **Mod items used over time** (`HorizonServer.tickUse`, `USE_HELD`): a mod item that isn't a
  consumable and that the mirror started using (Create's sandpaper) stays the mirror's:
  `updatingUsingItem` each tick (its `onUseTick`, `finishUsingItem`). Horizon's game doesn't
  know the item is used over time, so it asks again every 4 ticks while the button is held
  (those asks are the held button, not new uses) and never sends the release: 6 ticks without
  one is the release (`releaseUsingItem`). Gaps: the hand pose of a use isn't shown, the release
  is a few ticks late (a mod's bow fires late).
- **Lava's interactions when mods patch them** (routed action `liquid_interactions`,
  `LevelAccess::liquid_interaction`, `HorizonServer.liquidInteractions`). Create injects at the
  head of `LiquidBlock.shouldSpreadLiquid` and runs its own registry (honey or chocolate by
  flowing lava make limestone or scoria; water as vanilla's). Horizon's lava keeps a placed or
  updated lava block for the mods and schedules its tick; at the end of the block tick vanilla's
  `shouldSpreadLiquid` runs there on the mirror, the mods first. Without such a mod, Horizon's
  own conversions no longer take a mod's fluid with water's template for water (honey made
  cobblestone). The tester's "obsidian turns back into lava" didn't reproduce on this branch
  (Create, survival and creative): lava over still water converts once and stays.
- Not a bridge bug: a water bucket on Create's fluid tank places the water beside it in
  survival; Create's `FluidTankBlock.useItemOn` takes buckets only from creative players or on
  creative tanks (`TRY_WITH_EMPTY_HAND` otherwise). In creative the tank fills.

## Create's parts, neighbour updates, own shapes, fluids in screens (bridge-gpu-14)

Generic fixes found with Create Fly; regress `createparts`, `jeifluids` (scenes `createparts`,
`waterwheel`).
- **Renderers draw the client's copy of a block entity** (`HorizonClientBlockEntities`), as a
  vanilla client keeps its own: made with its type, on the client level, loaded from the
  server's update packet through `ClientPacketListener`'s own lambda (so mods' wraps of
  `handleBlockEntityData`'s `loadWithComponents` run: Create's `onDataPacket`), loaded again on
  the server's `sendBlockUpdated`, ticked with its client ticker (once at its making, so mods set
  their client state up before its first frame). Create keeps client-only behaviours there (the
  mixer's head animation: its renderer NPE'd on the server's object). `HORIZON_JAVA_CLIENT_BE=0`
  draws the server's again. A copy that can't be made falls back to the server's (logged once).
- **The block entity renderers' context has the client's sprites** (`getSprites`): Create's saw
  renderer read its blade's from `context.sprites()` (null: no renderer, a black hole).
- **Horizon's neighbour updates reach the mods' blocks** (`LevelAccess::observe_neighbours_updated`
  → `HorizonServer.neighbours`): where Horizon updates the neighbours of a position with a mod's
  block among them, the mirror runs the mods' HEAD injections of `ServerLevel.updateNeighborsAt`
  (Create's `NeighborUpdateListeningBlock`s), then each mod block's shape update
  (`NeighborUpdater.executeShapeUpdate`: it may break, as `updateOrDestroy` does) and
  `executeUpdate`. A hand crank pops off when its cogwheel breaks; any mod block reacting to a
  neighbour (redstone, survival) does.
- **A mod block's own states carry their own boxes** (content API 0.2: a state's `outline`,
  `collision`, `occlusion`, or a `state_table` shape entry `{"like", "outline", ...}`). The carry
  stood each own state in by the vanilla state of the same outline and collision, *else the
  nearest*, and took its occlusion too: Create's basin (a floor short of its edges) took a full
  cube's, which hid the floor's face under it, and the sky showed through round its feet (the
  testers' pale blue-white square under basins, mixers, presses, blaze burners). Java now
  exports each state's occlusion (none when it can't occlude); the carry writes the boxes that
  differ from the stand-in's, and `occlusion.nbt`, `cached-collision.nbt` and the gameplay rows
  take them. Also fixes outlines bigger than the model (fluid pipes). Content FORMAT 16.
- **Fluids in screens**: vanilla's private `blitTiledSprite` (JEI's fluid entries, mods' tanks)
  went to the headless GPU's atlas, which Horizon's window never sees (a black square). Now
  `GuiGraphicsMixin` sends the sprite's own picture, tile by tile, tinted. Blits and GUI quads in
  a real atlas's UVs (the block atlas, once the client's models load) are named by the sprite
  holding them too (`HorizonGraphics.realSprite`).
- **Mod blocks set before the mods' states are known** (the first ticks) are kept for the
  mods: a blaze burner placed then never had a block entity.
- Checked, working: the water wheel in Horizon's flowing and falling water (FlowScore -1,
  speed 8: Create reads the mirror's `FluidState.getFlow`; scene `waterwheel`); the blaze
  burner's head (one, in its cage; `blaze=none` draws no head, as in Create).
- Not done (research below): Ponder, players colliding with contraptions, contraptions' actors.

### Ponder (research)

Create's Ponder (`com.zurrtum.create.client.ponder`) needs four things, in this order:
1. The hold-W trigger: `PonderTooltipHandler` (via Create's `MinecraftMixin` tick and
   `ItemStackMixin` tooltip line) reads the hovered stack of the open `AbstractContainerScreen`
   and the key. Horizon's inventory isn't a Java screen: hand it the hovered stack and the held
   key the way JEI's R/U over Horizon's slots already get them.
2. `PonderUI` opens as a mod screen (Horizon shows those: `HorizonGraphics`).
3. Its scenes are a `PictureInPictureRenderer` (`SceneRenderer`), as Xaero's minimap is, but
   they submit block and entity nodes: `prepare` gets a `FeatureRenderDispatcher`, which
   Horizon passes as null (`HorizonGraphics.prepare`). The headless client needs vanilla's
   (made over the client's model and atlas managers; its shaders already compile on HorizonGpu)
   and a `SubmitNodeStorage`.
4. `SceneRenderer`'s own `GpuTexture`s are drawn into on the headless GPU: they become HUD
   images as Xaero's target does (`HorizonGpu.drawnInto`).
Steps 1-2 are client work; 3-4 gpu. Expect a few days; test with `ponder` scenes once 1 works.

### Contraptions (research)

gpu-13 made a bearing's contraption assemble, draw and turn. Left:
- **Players collide with contraptions**: Create runs `ContraptionCollider` on the client for
  the local player (and the server for others). Horizon's player moves in Rust: each tick, ask
  Java for the world-space collision boxes of contraption entities near the player (their
  blocks' shapes, posed) and add them to the Rust collision as solid boxes; carry a player
  standing on one along with its motion (the contraption's delta per tick). Entities chat +
  a small Java query.
- **Actors** (drills, saws, harvesters on a moving contraption): they run in the contraption
  entity's tick (`MovementBehaviour`) on the server mirror, breaking and placing through the
  mirror's level (routed to Horizon). Untested; a bearing with a drill against stone is the
  case to add.
- **Trains** are contraptions on tracks: blocked first by the use order (track placement).

## Create's pumps, pipes and fans (bridge-create-1)

Generic fixes found with Create Fly; regress `createpump`, `createfan` (scenes `createpump`,
`createfan`); `createitemsin`'s scene got a glass pane north of its lava.
- **Pumps worked; the tank never showed it.** A pump drinks Horizon's water source through the
  mirror (read and removed: `LevelMixin`) into a tank, but the tank's renderer read the client
  copy's fluid level, which never moved: Create syncs with `sendData` →
  `ServerChunkCache.blockChanged` (not `sendBlockUpdated`), which marked the copy only
  "changed" (a plain reload, `read(clientPacket=false)`, where the shown level doesn't chase).
  Now `blockChanged` is an update too (vanilla's chunk holder sends the block entity's update
  packet there), and an update loads through `ClientPacketListener`'s own lambda
  (`lambda$handleBlockEntityData$0`), so mods' wraps of that load run (Create's
  `onDataPacket`). gpu-14 had this; the batch-12 merge kept client-14's plain load.
- **The client level's biome tints** (`ClientLevel.getBlockTint`): made without its constructor,
  it has no tint caches (vanilla's nor Fabric's `customColorCache`); `ClientLevelMixin` computes
  each tint (`calculateBlockTint`). Create's tank drawing water (Fabric's fluid colour) NPE'd.
- **Block models whose parts depend on the world** (a wrapper's `addPartsWithInfo(BlockAndTintGetter,
  BlockPos, BlockState, RandomSource, List)`: Create's fluid pipes add their arms, rims and
  casing from the block entity's connections, pumps their rims, shafts their brackets).
  Horizon's chunk drew only the model's own parts (a pipe was its middle stub, in water or
  not). `HorizonEntityRender.worldParts`: for a block entity whose state's model has one, the
  parts at its place in the client's level, as quads, sent with its renderer's (also for
  blocks with no renderer), made again every 10 ticks or when its state changes; once one
  place of a state gives other quads than the model alone, the state is drawn `whole` (its
  chunk model left out) and every place of it is drawn so. Limit: like every block entity's
  drawing, within 64 blocks of a player (a far pipe isn't drawn).
- **A client copy's first tick as it is made** (`HorizonClientBlockEntities.get`): a renderer
  drawing a copy made this tick (Create's mixer) failed before its client behaviours existed.
- **Client code doesn't move the server's entities**: the client level's entities are the
  mirror's own objects (`HorizonClient.entities`); a client block entity moving them (Create's
  fan's air current runs on both sides) moved them twice. Their motion is put back after the
  client copies tick. **A block a client sets** (`ClientLevel.setBlock` on a mirror: Create's
  crushing wheel fixing its controller client-side) sets nothing (it NPE'd on the missing
  prediction handler and stopped that block entity's client tick).
- **Mods' block entities push players** (`HorizonServer.playerMotionsBefore/After`,
  `Natives.playerMotion` → `Action::PlayerMotion` → `ClientboundSetEntityMotionPacket` to the
  player's client). Before the block entities tick, each mirrored player's motion is its move
  since the last tick (Horizon's velocity doesn't come over); a block entity that changes it
  (Create's fan blowing; belts carrying players should too) sends the result. Vanilla's clients
  run the same block entity and move their own player; the client level here holds no player
  of the player's own, so the server's half stands in. Not done: Horizon's mobs in a fan's wind
  (they stand in the mirror only as `HorizonNeighbours` copies, whose motion isn't written back).
- Checked, working on this branch: the saw's blade, the blaze burner's head (one), no pale
  squares (gpu-14), crushing wheels and fans processing item entities (mixins-11).

## 26.3 facts that cost time

- **A mod's `setBlock` is made inside its call** (bridge-sync). `with_java_world` lends the game
  mutably (`&mut self`: a closure can't hold a borrow of it), and `JavaBlocks` reads Horizon's
  chunks through pointers each time, so a read after a write sees it. Java's per-thread section
  copies (`HorizonServer.Sections`) are patched in place with the write's changes
  (`Written.changes`, every block the cascade changed, a nested write's included, in order), and
  the chunk's cached revision is dropped so a section not read yet is read anew; KEPT copies from
  earlier views are keyed by the old revision and never served again. `HorizonBlockEntities.tick`
  iterates copies and skips a block entity a ticker's write removed (`isRemoved`), as vanilla's
  pending tickers do. The mod's flags are vanilla's (`UPDATE_KNOWN_SHAPE` skips the shape updates,
  2 alone no neighbour updates); before, every write was `UPDATE_ALL`.

- **Real Fabric's server refuses clients without Fabric API** once mods add registry entries
  (registry sync): a protocol bot needs `horizon-bots --fabric` (registers
  `fabric:registry/sync`, answers `fabric:registry/sync/complete`).
- **A server's levels wait for the Java mods' start** (`java_holds_server`: `tick_server` runs
  paused until it ends) and a dedicated server's "Done" comes after it (bridge-benchfix). Before,
  a warm start (the mods' content cached) ticked ~14 s without the mods: a hopper facing a Create
  chute pushed an item into Horizon's own handling of the content block and it was gone. The
  mirror's start hooks still run at its first tick: wait for `[java] Java mods: the server's
  start hooks ... ran` before testing mods.
- **Mods' block entities live in their chunks' `block_entities`**, as vanilla's do (real Fabric
  reads them there; the side folder it doesn't know lost Create's kinetic networks and chute
  items). Horizon's save batch asks the mirror (`with_entities` → `java_save_block_entities`:
  the mirror's data wins over a copy Horizon's world still holds); the mirror's `setChanged`
  marks the chunk modified (`changedChunks`, asked before each save and unload); a loaded
  chunk's mod block entities (Horizon decodes them as `BlockEntity::Mod`) are made in the mirror
  at the level's next tick (`chunkLoaded`), and any the mods ask for first are taken from
  Horizon's world (`Natives.blockEntity`); `Natives.setBlockEntity(…, "")` lets go of them.
  Their first `HorizonServer.world()` doesn't clear what was made before it.
- **The mirror ticks mods' block entities in the order they were made** (a `LinkedHashMap`, as
  `Level.blockEntityTickers`): a `HashMap` by position ticked Create's chutes of a line out of
  order in some chunks only, one item a line behind real Fabric after 200 ticks.
- **`SyncedBlockEntity.sendData` goes through `ServerChunkCache.blockChanged`**, not
  `ServerLevel.sendBlockUpdated`: a client copy has to treat both as the update packet. And a
  client copy loaded with plain `loadWithComponents` reads with `clientPacket=false`; mods do
  their client-side syncing (lerped levels, behaviours) only in the packet's load
  (`lambda$handleBlockEntityData$0`, which their `WrapOperation`s target).
- **A `BlockStateModel`'s parts in a world** (`addPartsWithInfo` with a `BlockAndTintGetter` and
  position) can differ completely from `collectParts`: Create's pipes have no arms without it.
- **The field of view is the camera's, not the game renderer's.** `Camera.tick` → private
  `tickFov` (modifier eased halfway), private `calculateFov(float)` (options' FOV × lerped
  modifier, then death/fluid), `update(DeltaTracker)` stores it in `fov`, public `getFov()`.
  Zoom mods hook `calculateFov`/`getFov` there; `GameRenderer.getFov` is gone.
- **`KeyMapping.ALL` is every mapping** (private static `Map<String, KeyMapping>`; public
  `KeyMapping.get(name)`); Fabric's `MODDED_KEY_BINDINGS` misses mods that make a plain `new
  KeyMapping`. A category's label key is `key.category.<namespace>.<path>`
  (`Identifier.toLanguageKey("key.category")`).
- **A mixin config's package holds only mixins**: a mod (or fixture) class in it fails to load
  with `IllegalClassLoadError: ... is in a defined mixin package`.
- **Xaero's maps size themselves from the window.** The minimap's picture in picture is drawn at
  `Window.getGuiScale()` (447x267 at scale 3, 858x498 at 6) and the World Map's target is the
  framebuffer's size (1280x720, 1281 when the window's framebuffer had padding), so a headless
  window fixed at scale 3 / 1280x720 made them blurry on any bigger screen. `Window` reads
  `framebufferWidth` etc. directly; `windowRenderState` and the GUI projection must follow too.
  With `guiScale` in the scratch options.txt the Java side logs a harmless `Failed to load options`
  NPE (`Options.guiScale()` null at `resizeGui`): it was there before this change.
- **Skins: `PlayerInfo` looks its skin up once** (`SkinManager.createLookup` keeps the future
  `get` gave): a skin that arrives later only shows on a new `PlayerInfo`, so `HorizonSkins`
  drops the player's avatar (`HorizonEntityRender.forgetAvatar`). A `DownloadedTexture`'s
  `texturePath()` is its id itself (`skins/<sha1>`, no `textures/` prefix or `.png`); 3D Skin
  Layers reads the pixels with `TextureManager.getTexture(id)` → `DynamicTexture.getPixels()`
  when the resource manager hasn't the id. `NativeImage.setPixel` takes ARGB.

- **One Vulkan device list a process.** On the user's Fedora 43 PC (RX 9070 XT, RADV, vulkan-loader
  1.4.341, Mesa 25.3.6) a second `vkEnumeratePhysicalDevices` in the process segfaults in the
  loader (`linux_read_sorted_physical_devices`), even on the same instance: every game with Java
  mods died opening its window ("the game ran (12 s)" in regress, a core dump, nothing in the
  log). The window and the Java mods' GPU device now share one instance and adapter, whichever
  opens first (`gfx::SHARED_INSTANCE`, `horizon_jvm::gpu::SHARED_INSTANCE`, wired in
  `java_mods/bridge.rs`). Don't add another `wgpu::Instance`/`request_adapter` in the game
  process. llvmpipe never shows it: only a real GPU does. `coredumpctl info -1` finds it.

- **A client player made without its constructor never ages**: `LocalPlayer.tick` is what moves
  `tickCount` and `attackStrengthTicker`; a mod timing its client by them (Better Combat's
  upswing: `ticksLeft` by `tickCount`) waits forever. The headless client moves both each tick.
- **REI's server display sync comes after the client's reload** when both halves are in one
  JVM (its server waits until no REI reload runs, so single player too): an inventory overlay
  opened after it shows the search box but no list until something is typed (the empty
  filter's search isn't run again: `AsyncSearchManager` not dirty, the new widget empty).
  Open: `reiscreen`, and `rei-lan`'s host list.

- **A mod's injection at the end of `RenderSystem.initRenderer` can throw** (Async Particles
  casts the device to vanilla's Vulkan backend when its backend name says "vulkan"): the device
  is set by then; keep it. Don't name the bridge's device after a native API.
- **Create's client behaviours live on client block entities** (`CLIENT_REGISTRY`, added at the
  block entity's first client tick): with one object for both halves `getBehaviour` is null on
  the client side. A mod block entity's client copy must exist and have ticked before client
  code asks (`HorizonClientBlockEntities.near`).
- **Mods register their own `Packet` classes in the play protocol** (Create): neither a custom
  payload nor vanilla's; the mirror dropped them ("A mod sent a player a OpenScreenPacket").
  Their handlers check `PacketUtils.ensureRunningOnSameThread`: a client/server made without its
  constructor needs a `PacketProcessor`.
- `HorizonHooks.injected` left out every `dev.horizonlab.` mixin, the fixtures' too: only
  `dev.horizonlab.platform.` is Horizon's own.

- **The client renders its own copy of a block entity.** Mods keep client-only state on it
  (Create's behaviours added in `SmartBlockEntity.initialize` when `level.isClientSide()`);
  drawing the server's object with a client renderer NPEs. Load the copy from
  `getUpdatePacket()` through `ClientPacketListener.lambda$handleBlockEntityData$0` (mods wrap
  the `loadWithComponents` call inside it), not a plain load.
- **A carried state's stand-in vanilla state brings its occlusion along.** Shapes matched by
  outline and collision only (or the nearest) gave Create's basin a full cube's occlusion:
  holes in the floor under it. Occlusion is its own field; export it from Java.
- **`blitTiledSprite` is private and goes straight to `innerTiledBlit`** (a
  `TiledBlitRenderState` of the atlas's GPU texture), past every public `blit*` Horizon
  overrides: inject it (GuiGraphicsMixin) or fluids draw black.
- **The headless GPU's textures have null labels**: find an atlas by its texture object
  (`AtlasManager.forEach`, `atlas.getTexture() == texture`), not by name.

- **Anything that swaps the client connection's channel must answer a `LocalAddress`**
  (`HorizonClient.memoryConnection`, `HorizonPacketTap.open`): Simple Voice Chat connects its
  voice to the connection's remote address; a plain EmbeddedChannel's makes it fail
  ("Failed to connect to voice chat server") and no one hears anyone (merge batch 11).

- **Mods hook vanilla's input with `@WrapOperation`, not events.** Architectury's
  `ClientScreenInputEvent` fires from wraps of `Screen.mouseClicked`/`keyPressed`/... inside
  `MouseHandler.onButton`/`KeyboardHandler.keyPress`: `HorizonHooks.fire` (HEAD/TAIL injects)
  never reaches them; `HorizonHooks.wrapCall` runs a call's merged wraps. Their merged
  handlers take the call's receiver, arguments and an `Operation`.
- **REI's keys aren't KeyMappings**: its R/U/O live in its own config (cloth-config's
  `ModifierKeyCode`), so Fabric's key registry doesn't list them; give screens every key the
  screen would get instead of a list of mods' keys.
- `ctl click-at` twice on a slot within a second is a double click (collects the stack):
  a test that hovers a slot by picking its item up and putting it back sleeps between.
- **The mirror's `Connection` is allocated, so its mixins' fields are null** (Fabric's
  `playChannels`, its `packetContext = new PacketContextImpl(this)`): anything that builds a
  listener on it (a configuration listener) needs `HorizonHooks.initFields` + `constructed`
  first. And a send on it would NPE (`pendingActions`): `ConnectionMixin.send` drops it.
- **Fabric's `getSendableChannels()` is a read-only view**; the addon's `sendableChannels`
  field is the set. Configuration payloads must go through their codecs: Fabric's attachment
  sync mutates the set it receives (`retainAll` on the client's immutable one failed).
- **`HorizonMixinTable.patchedInside` ignores client patches** (actions the server routes);
  for how a client class draws use `patchedAnywhere`.
- **Name-matched rules miss mod blocks.** Horizon picks contextual shapes and many behaviours by
  block name (`"minecraft:scaffolding"`); a mod's copy of the block has its own name, so it got
  no rule and its uncached collision shape was a hard error. Look a mod block's template up
  (`CachedCollisionRegistry::template`) rather than adding mod names. `SAVE=<world folder>` in
  `launch.sh` opens a kept save (a tester's crashed world) instead of a new one.

- **A use the mirror starts never finishes.** The mirrored players are never ticked, so
  `LivingEntity.updatingUsingItem` never counts a use down: anything vanilla's `Item.use`
  starts with `startUsingItem` (eating, drinking, drawing a bow) sits there forever. Hand it to
  Horizon (consumables: `handOverConsumable`) or tick it yourself; never answer "taken" for it.
- **A mod's fluid block carries lava's or water's template**, and every Rust path that reads
  its `FluidState` sees that fluid: flow, random ticks, conversions, entity physics. The
  `LevelAccess::foreign_fluid` hook covers flow, random ticks and conversions; entities still
  read the template.
- **Create's press sits two blocks over its depot**, with a gap (the depot asks `above(2)` for
  the processing), and a depot takes items only through its top face: a press set right over
  the depot never ran, and a click on the depot's side passed.
- **A click's packets can come ticks apart** on a busy server (2 cores, llvmpipe): the off
  hand's `UseItemOn` of a click came after the main hand's had been answered, past a one-tick
  window (`CLICK_TAKEN` waits 4).
- **A screen's last argument is a frame's delta in ticks, not a partial tick**
  (`Gui` passes `DeltaTracker.getGameTimeDeltaTicks()`): 0 freezes mods' animations
  (Cloth's smooth scrolling).
- **Fabric mod ids may hold `-`**; a content mod's id may not (its namespaces may).
- `$X.5` in a shell step is wrong for negative X (`-521.5` is block -522): use
  `$(awk "BEGIN{print $X + 0.5}")` (around.sh says so too; it cost a run here).

- **A vanilla method Horizon cancels at its head skips the mods' injections at its end.**
  `LevelExtractorMixin` cancels `allChanged` (Horizon draws the world), so Flywheel's RETURN
  injection there never ran. `HorizonHooks.fireMerged(self, owner, method, at)` runs the
  mods' HEAD or RETURN/TAIL injections of a client method from the merged class (whatever
  config or Mixin plugin applied them: Create's come from its plugin's list, which the
  census doesn't read); call it where vanilla would have run the method.
- **The client player is allocated: its constructor's objects aren't there.**
  `firstPersonHandsAndItems` was null (Create's client tick); its class's synced data was
  never built, and mods that key their data accessors by entity class (Create's
  `AllSynchedDatas`) had none for `LocalPlayer`. Both made in `HorizonClient.localPlayer`.
- **Create's block models are wrappers that draw nothing in a world** (shafts, cogs: their
  renderers draw them); `collectParts` without a world gives the full model. Don't judge a mod
  block's chunk geometry by `collectParts`.
- **Minecraft has no `cameraEntity` field in 26.3**: use `getCameraEntity`/`setCameraEntity`.

- "Hoppers don't work" with a big pack was not Lithium: Horizon's hoppers are Rust and move
  vanilla containers tick for tick as without mods (same counts after `tick step` 1/8/8/23,
  with and without the pack). Lithium's hopper mixins only reach the mirror (they show as
  `java`/`rust` in the table, never routed). What failed was any Java mod's container
  (no Horizon block entity there). Also: in this pack every block use is routed
  (`ServerPlayerGameMode.useItemOn` patched inside by Energized Power, SuperMartijn642's lib,
  Mythic Upgrades), so chests and hoppers open as mirror menus over `COPIES`: that works.
  Test trap: `look-at X.5` with a negative X aims at the block's west edge; press Escape only
  with a screen open (else the pause menu stops the server and nothing moves).

- **Fabric's screen events come from Fabric's mixins into MouseHandler, KeyboardHandler and
  Gui** (`ScreenMouseEvents`, `ScreenKeyboardEvents`, `ScreenEvents.beforeExtract`/`afterTick`/
  `remove`), not from `Screen`. The headless client calls the screen's handlers itself, so it
  fires them in `HorizonScreenEvents` (only `BEFORE_INIT`/`AFTER_INIT` come from `Screen.init`).
  A screen Fabric's init never saw throws "has not been correctly initialised" from its events:
  the handler then runs without them.
- **Client levels need a `tickRateManager`** (made without their constructor): every potion's
  tooltip reads it (`TickRateManager.tickrate()` NPE). JEI reads every item's tooltip to build its
  search index; with 256 of them failing, its searches came back empty. It is the server
  level's now.
- 26.3's mouse buttons count from one (`InputConstants.MOUSE_BUTTON_LEFT` = 1, RIGHT = 3).

- A big pack's join sends packets past the network's 2 MiB frame (2.3 MB in the 81-jar test
  pack) to the window's own client. Its connection is an in-memory pipe without compression, so
  it now takes wider frames (`frame::unbounded_contents`/`wide_header`); before, the server's
  writer dropped the connection silently ("End of stream" in the window, then the server
  stopped). Sockets keep vanilla's limit.
- `ChunkGeneratorStructureState.ensureStructuresGenerated` isn't thread-safe: vanilla's
  ChunkMap runs it once before any chunk generates. Left to the worldgen workers, they fill its
  ring-position map together (AIOOBE "Index -1 out of bounds for length 33" in fastutil's
  rehash, every first chunk failing). `HorizonServer.makeGeneratorState` runs it.
- The mirror's `PlayerAdvancements` is made without its constructor (`HorizonServer
  .advancements`): its `tree` is null and `award` NPEs in `markForVisibilityUpdate` unless it
  is set (`HorizonVanillaActions.award` sets the server data's tree). `AdvancementHolder`s are
  equal by id, so a copy with other rewards finds the same progress entry. `DisplayInfo` is a
  record (`announceToChat()`, `showToast()`, `hidden()`), its constructor (icon, title,
  description, background, type, toast, chat, hidden).
- Mixin's `@Inject` on `award` at `INVOKE AdvancementRewards.grant` only runs where the
  advancement becomes done (`!wasDone && isDone`): the progress has to be Horizon's first.
- A jump's or teleport's HEAD cancel must be asked before Horizon acts, not after: a
  `TeleportTransition`'s `newLevel()` is the target level, so mods check the dimension there.
- **Screens are opened with `Minecraft.gui.setScreen`, not `Minecraft.setScreen`** (gone in
  26.3; `gui.screen()` reads it). A fixture written from older examples doesn't compile.
- **Mods' own threads send client messages too** (REI's plugin reload threads: Advanced Loot
  Info). Nothing that answers them may assume the loop's thread or a running player's client:
  `HorizonLoop` queues them (see "Mods' overlay on Horizon's inventory").

- Fabric's extended menus (`ExtendedMenuType`) open with `Networking$OpenScreenPayload`
  (identifier, containerId, title, innerCodec, data); their client menu is
  `type.create(id, inventory, data)`. Mods with state on the client's menu only (CC: Tweaked's
  terminal) throw on the server's.

- Mixin renames a merged handler `handler$<id>$<mod>$<name>`, and drops a leading `<mod>_`
  from `<name>` (MaLiLib's handlers: `malilib_foo` becomes `handler$<id>$malilib$foo`). Match
  handlers with `HorizonHooks.handlerNamed`, not `endsWith("$" + name)`.

- Keys are SDL3: `KeyEvent(scancode, keycode, mods)`; letters' scancodes from 4, keycodes are
  ASCII (`java_screen.rs`: `sdl_key`, `sdl_keycode`). Shift mod is 3, control 192.
- Mouse buttons count from 1 (left 1, right 3).
- The open screen is `Minecraft.gui.screen()` (not `Minecraft.screen`).
- Screens draw with `extractRenderStateWithTooltipAndSubtitles` into a `GuiGraphicsExtractor`.
- `ServerPlayer.openMenu` runs `initMenu` (menus send their first data) *before*
  `containerMenu` is set: messages wait for `flush`.
- Objects made with `Natives.allocate` have no constructor-set fields; mods' mixins add fields
  set in constructors too (`emptyCollections` fills collections). A new NPE in the mirror is
  usually one more such field: set it where the object is made.
- Scene coordinates: write `$(p $X+0.5)` (around.sh's `p`), never `$X.5`: for X = -20 that is
  -20.5, a block off. Scratch worlds have seed `SEED` (default 1234) so scenes land the same.
- Javap the real jar instead of guessing: `javap -p -cp .local/minecraft/26.3/jars/client.jar <class>`.
- Fire Fabric events with `fireEach` (one listener at a time), not `invoker()`: through the
  invoker one mod throwing each tick silently stops every listener after it.
- Vanilla's "who sees this" and sync go through the level's chunk source
  (`getChunkSource().blockChanged`, `PlayerLookup.tracking`, `getDataStorage`, `getGenerator`,
  `randomState`): mirrored levels have a bare `ServerChunkCache` that `ServerChunkCacheMixin`
  answers for. Saved data lives in `horizon-java/data/` (`HorizonSavedData`).
- The headless client's `LocalPlayer`s live in the mirror's *server* levels: code that checks
  `isClientSide()` takes the server branch there, and Fabric lookups must not treat a client
  entity as tracked (`PlayerLookupMixin` checks `isMirror(entity)`).
- `MinecraftServer.getDataStorage()` is a 26.x server-wide storage (field `savedDataStorage`),
  besides each level's.
- Chat from Java goes to Horizon as JSON components (`chatJson`): `getString()` on the server
  side gives a mod's translation key (its Language is vanilla's English only).
- Mods' constructor injections (`@Inject` at `<init>` TAIL/RETURN) don't run on mirror objects
  (made without constructors): `HorizonHooks.constructed(object)` runs them, superclass first,
  for mirrored levels once the world's saved data is there (Chunk Loaders' per-level loaders,
  Fabric's attachments). Levels are first made by the content export, before any world.
- `ServerLevelEvents.LOAD` fires for each mirrored level (vanilla: `createLevels`, before
  `SERVER_STARTED`).
- Saved tickets come back deactivated (`TicketStorage`'s codec): vanilla activates them as the
  server prepares its levels; `HorizonTickets.storage` does on first use.
- `Player.isShiftKeyDown` follows the player's sneak key (bit 8 of `tick`'s per-player
  `permissions`): mods' sneak-clicks (Chunk Loaders' owner, wrenches).
- `tools/java_bridge/java.sh` fails when javac does (it used to copy the last jars and go on).
- A mod's block write made between ticks (a player's use: `useBlock`) is applied by Horizon
  after the next tick starts; pending writes (`WRITTEN`) stay until Horizon's world shows them
  (a block entity read as air in between was thrown away: a placed Chunk Loader lost its owner).
- The headless client's `LocalPlayer` is set up on each use (`localPlayer`): position, box,
  rotation, `input` (sneak) from the server player. A field it lacks shows as an NPE in a mod's
  client code; Java's own errors (log4j) go to the game folder's `logs/latest.log`, not
  Horizon's.
- `Connection.isMemoryConnection()` is true for mirrored players while the headless client runs
  (as singleplayer's): mods skip remote-only work (config sync expecting a configuration phase).
- Entities need more of a level than blocks: `getChunkSource()` (broadcasts, path regions:
  a mirror `ServerChunkCache`), `getWaypointManager()` (removal), `server.debugSubscribers()`
  (`checkInsideBlocks`), `environmentAttributes()` (moon brightness, difficulty: the
  dimension layer only), `getWorldBorder()` (saved data: a default border),
  `isPositionEntityTicking` (entity manager). `getOverworldClockTime()` needs the server's
  `ClockManager`, which the mirror lacks: use `getGameTime()`.
- Vanilla's entity ids count from 1 (`Entity.ENTITY_COUNTER`, not the level): mirrored
  entities get the bridge's ids (`setId`) so they never clash with Horizon's.
- A mirrored player isn't ticked: `detectEquipmentUpdates` (the held sword's damage) and its
  attack strength must be set before `Player.attack`.
- An entity renderer's name tag reads `Minecraft.player` (`isInvisibleTo`, its team: the
  `LocalPlayer` needs the server player's `gameProfile`): extract inside `HorizonClient.as`.
- Renderers submit through `SubmitNodeCollector`; every model submit ends in the abstract
  `submitModel(model, state, pose, renderType, light, overlay, tint, UvMapping, outline)`,
  and `ModelFeatureRenderer` calls `model.setupAnim(state)` only then. A `Proxy` collector
  runs the defaults with `InvocationHandler.invokeDefault`.
- The texture of a `RenderType`: `state` (RenderSetup) → `textures` → `Sampler0` → `location()`.
- Widgets' text (buttons, lists) goes through `GuiGraphicsExtractor.textRenderer*()`, an
  `ActiveTextCollector` that adds to the render state directly, not through `text()`:
  `HorizonGraphics` returns its own collector. Before that, vanilla buttons had no labels.
- A screen with `minecraft.level == null` draws the title screen's panorama and opaque menu
  background: `ScreenMixin` skips the panorama and uses the in-world background. That
  background is a 32x32 tile blitted with UVs past 1: `java_screen.rs` tiles such blits.
- Vanilla's server Language has only vanilla's English: `HorizonClient.language` injects a
  `ClientLanguage` with every mod's `assets/*/lang/` (English, then the options' language).
- Screens ask `minecraft.getWindow()` (gui scale, scaled size): an allocated `Window` with
  the GUI size at scale 3, updated on the screen's `size` input.
- Xaero's lib finishes loading (`tryLoadLaterClient`, its registries frozen) in a
  `@ModifyArg` on `Minecraft.runTick`'s `renderFrame` call; without a client frame before
  SERVER_STARTING it failed with "Registry is not frozen yet!".
- `horizon-ctl key <name>` needs an action: `key escape tap`.
- Looking at blocks: the headless client reads Horizon's server world (`Natives.blockState`,
  the same for client and server mirrored levels). Jade naming a block other than the one
  Horizon's window draws means the server and the window disagree: the worldgen lost-block
  case (a Java-generated chunk landing over a block already set), not the HUD.
- Jade catches its own exceptions (its tick, its overlay) and logs them to its own logger,
  not ours: when it draws nothing, call `WailaTickHandler.tickClient` and
  `OverlayRenderer.renderOverlay*` by reflection to see the throw.
- `ClientLevel.getLevelData()` is its `clientLevelData` (a `ClientLevel.ClientLevelData`), not
  `Level.levelData`; `getEntities()` reads its `entityStorage`.
- `Minecraft.getCameraEntity()` reads `gameRenderer.mainCamera().entity()`.
- `VanillaPackResourcesBuilder.build` gives a `VanillaPackResources`, which is not a
  `PackResources`: use `.fullResources()`.
- Worldgen mods set up in `MinecraftServer`'s constructor (TerraBlender's `MixinMinecraftServer`
  puts its regions into the level stems' parameter lists and its surface rules into the noise
  settings; Fabric's biome modifications): the mirror skips it, so `HorizonWorldgen.start`
  fires the mods' `<init>` RETURN/TAIL injections (`HorizonHooks.fire`) after giving the
  server `worldGenSettings`, `structureTemplateManager` and `fixerUpper`.
- 26.3's generation pyramid: STRUCTURE_STARTS, STRUCTURE_REFERENCES, BIOMES, TERRAIN (noise,
  surface and carvers in one step: `NoiseBasedChunkGenerator.buildTerrain`), FEATURES. Every
  step but STRUCTURE_STARTS reads structure starts 8 chunks out; TERRAIN reads biomes 1 out.
- `WorldGenRegion`'s constructor takes `StaticCache2D<GenerationChunkHolder>` and reads
  `holder.getChunkIfPresentUnchecked(status)` (the private `futures` array): a subclass that
  fills it with completed futures is all a step needs. `Blender.of` asks
  `isOldChunkAround` of ChunkMap (none on the mirror: `WorldGenRegionMixin`).
- `ServerLevel` needs `structureCheck`, `structureManager`, `uncachedBiomeResolver` and
  `Level.palettedContainerFactory` for generation (constructor-set: `HorizonWorldgen.readyLevel`).
- Horizon's biome table reads `data/*/worldgen/biome` of the content root (mods' after
  vanilla's, so vanilla's keep their ids); the network biome registry lists them in that order.
- `horizon-ctl block X Y Z` now says the biome there (`"biome"`): F3 has no biome line here.
- 26.3 has no `Biome.getMobSettings`: spawn lists are the environment attribute
  `NATURAL_MOB_SPAWNS` (a biome layer). The mirror gets a `Level.biomeManager` from the
  dimension's biome source with Horizon's seed, and `addStaticLayers` for its attributes.
  Fabric applies `BiomeModifications` from a mixin into `MinecraftServer`'s constructor,
  which the mirror skips: `BiomeModificationImpl.INSTANCE.finalizeWorldGen` is called once.
- Block scans (`containsAnyLiquid`, `checkSpawnObstruction`) read chunk sections
  (`BlockScanUtils.sectionBasedScan`): on the mirror they read the level block by block.
- `/summon` with NBT doesn't run `finalizeSpawn` (no equipment); without NBT it does.
- An item an entity renderer holds goes through `ItemModelResolver.updateForLiving` into an
  `ItemStackRenderState`, then `state.submit(pose, …)` from its layer: keep the stack on the
  state, make `isEmpty` false, and record the pose at `submit`.
- 26.3 scans blocks through chunks, not the level (`BlockScanUtils.sectionBasedScan`: a
  sapling's "flowers nearby", a vine's spread): `level.getChunk` must give a chunk.
  `HorizonChunks` copies Horizon's chunk (blocks and biomes in one native call each,
  `Natives.chunkBlocks`/`chunkBiomes`) the first time a call asks, for that call only
  (`Natives.viewEpoch`), follows the call's own writes, and sends writes made on it to the
  level. Mirrored levels now all have `palettedContainerFactory`.
- 26.3's block tints are `BlockColors` lists of `BlockTintSource`s; mods register them through
  Fabric's `BlockColorRegistry` (held in `BlockColorRegistryImpl.map` until `BlockColors`
  exists). `HorizonTints` classifies each by calling `colorInWorld` in a fake world whose biome
  colours are markers; the mesher's `set_mod_tints` takes them (cache file `block-tints.json`,
  content `FORMAT` 9).
- The command parser's biome ids come from the vanilla export's `by_registry` first, not
  `Registries.biomes`: mods' biomes are added to both (`chat.rs` `build_registries`).
- Fabric starts the client entrypoints from `Minecraft.<init>` at its first
  `Thread.currentThread()`; mods inject there (`shift = BEFORE`: before the entrypoints,
  `AFTER`: after). SuperMartijn642's Core Lib gathers its `@RegistryEntryAcceptor` fields in
  the BEFORE one, so without it every mod on it (Trash Cans...) fails Fabric's whole start.
  `HorizonHooks` records these as `<start before/after entrypoints>` and `FabricBoot` fires them
  around `main` (`HorizonClient.gameStart`); the headless client now boots before `main`.
- Mods read `Minecraft.languageManager` (Server Translations API in `Language.inject`): the
  headless client has one now.
- Data packs: a new world's packs are vanilla's choice (`MinecraftServer.configurePackRepository`
  with `DataPackConfig.DEFAULT`), not every available pack: mods ship optional built-in packs
  for other mods (BetterEnd's `flamboyant_extensions`) whose recipes fail the whole registry
  load when that mod is missing.
- Constructor-end hooks on mirrored objects (`HorizonHooks.constructed`) now get the
  constructor's arguments the object kept (the field of that type, up the classes: Level's
  dimension type holder, its dimension key) and a level's `LevelStem` from the registries.
  Lithium caches a level's height there: without it every level was 0 tall and every chunk's
  structure step failed (`Size must be positive, was: 1x0x1`). `<init>*` targets count.
- The headless client's block and item models reload (`HorizonClient.models`) never finished
  with a 91-jar pack, holding the first server tick (the world stayed at "Loading terrain"):
  it now gives up after 90 s and logs it (mods reading models get none). The cause, one
  reload stage waiting for good, is still open.
- `launch.sh` stopped (`set -e`) when `/tmp/hz-bridge-scratch` didn't exist.
- In a pack where a mod patches `ServerPlayerGameMode.useItemOn` past its head (Energized
  Power, Core Lib at INVOKE, Mythic Upgrades at RETURN), every use of a vanilla block runs
  vanilla's code on the mirror (`Using minecraft:air on minecraft:stone goes to the mods`):
  chests (Horizon's menu by type) and doors still work there.
- Mirrored players aren't ticked, so their pose doesn't follow the sneak key: `isCrouching()`
  (Veinminer's "sneak to mine a vein") reads the pose, now set to CROUCHING with the key.
- Veinminer (Miraculixx) has no mixin into breaking: it uses Fabric's `AttackBlockCallback` and
  `PlayerBlockBreakEvents.BEFORE`, then calls `player.gameMode.destroyBlock` for each block of
  the vein over the next ticks (vanilla's whole break on the mirror).
- Serene Seasons' `RandomUpdateHandler` (snow and ice by season) walks
  `ServerChunkCache.chunkMap.forEachBlockTickingChunk`: null on the mirror, fails each tick.
- `MinecraftServer.weatherData` (Serene Seasons reads `ServerLevel.getWeatherData`): clear.
- A recipe display with a null category (a mod's recipe type) can't cross vanilla's codec, so
  `HorizonClient.recipeBook` skips it; otherwise the whole client recipe book fails.
- A recipe viewer's client fills displays from the server's sync payload (a raw
  `player.connection.send` of a custom payload, which reaches the mod's client code now) after
  the client's join reload (`recipesArrive`). Its registry access comes through the client's
  connection: the search connection has the server's registries and is the game mode's.
- A mod drawing through its own extractor on the one it was given (REI's
  `GuiGraphics.of(graphics)`, a second extractor on the same render state) skipped
  HorizonGraphics' overrides: `PlatformPlugin` hands every recorded method and `pose()` of
  `GuiGraphicsExtractor` to the HorizonGraphics of that render state (see "Mods' overlay").
  Access wideners make vanilla's private `innerBlit` public *final*: no override, so
  `GuiGraphicsMixin` forwards it. GUI atlas sprites
  mods look up themselves (`getAtlasOrThrow(GUI).getSprite`) are made on demand (name only)
  and drawn by name. `setScreenAndShow` draws a frame at once: `renderFrame` is a no-op
  headless. Screens that read `mouseHandler.xpos()` get Horizon's mouse.
- Field initializers merged from mixins, on the headless client's player, run in this order:
  empty collections, mods' factory methods returning a field's type (`create...`: Player
  Animation Lib's animation manager fills its layer map), the server player's fields, a mod
  type's constructor taking the player, then the constructor-end hooks, which now include
  client mixins' (`HorizonHooks.read(..., constructorsOnly)`), with the player in a client level.
- New worlds' dimensions: the preset's under the data packs' own (`WorldDimensions.bake` with
  `context.datapackDimensions()`); before, a pack's `dimension/overworld.json` was ignored.
- `Connection.isConnecting()` is `channel == null`: a mirrored connection says it isn't.
- Mirrored levels now have a chunk map (`HorizonServer.chunkMap`, made without its
  constructor, then its constructor-end hooks with a world storage rooted in `horizon-java/`):
  GraphLib (Extended Drawers) keeps its graph storage there. `ChunkMapMixin` makes
  `forEachBlockTickingChunk` walk nothing (Horizon random-ticks its own chunks).
- Field initializers in mixins are merged into constructors: an object made without its
  constructor has those fields null (Spell Engine's `castController` on LocalPlayer, Trinkets'
  attachment on LivingEntity). The headless client's player runs its constructor-end hooks,
  takes the server player's mod-typed fields and makes mod objects that have a constructor
  taking the player. `emptyCollections` now also fills concrete `java.util` collections.
- Mods' data-pack functions (`HorizonFunctions`): the load/tick tags and `/schedule` run on the
  mirror. Vanilla's `/forceload add` loads the chunk at once (`setChunkForced` calls `getChunk`)
  and packs rely on it; Horizon can't, so the run stops (`FunctionsMixin` drops the queue) and is
  retried each tick until Horizon has the chunk. Forced chunks now load as near as the player's
  own (streaming `Ranges::priority`: vanilla's FORCED ticket is level 31). Trace a pack with
  `HORIZON_JAVA_FUNCTIONS_TRACE=1`; command errors go to Java's own log (`<scratch>/logs/latest.log`,
  "Failed to execute function").
- What functions do that Horizon keeps: `/setworldspawn` (`Natives.worldSpawn`), `/gamerule`
  (the mirror's `NotificationManager` → `Natives.gameRule`), teleports (already). Neighbour
  updates on mirrored levels are Horizon's (`MirrorUpdatesMixin`), so `/place template` works.
- A pack that replaces vanilla's noise settings, density functions, dimensions or biomes, or
  ships its own generator, now sends the dimension through Java (`HorizonWorldgen.modded`):
  void and skyblock worlds.
- Commands through `horizon-ctl command` run as the owner's console; server mods' commands
  (and `/summon` of a mod entity) now go to the mods first (`owner_console_command`).

- blaze3d's GPU classes moved to `com.mojang.renderpearl` (`GpuDevice` is
  `renderpearl.api.device.GpuDevice`); shaders are GLSL 330 compiled to Vulkan SPIR-V with
  `auto_bind_uniforms` (renderpearl rewrites each descriptor to set 0, binding = the uniform's
  index). Vanilla's `core/clouds` uses a texel buffer: no wgpu binding, that pipeline fails.
- `GpuBufferSlice.MappedView.data()` is written by moving its position: write back from the
  memory's own address (`MemoryUtil.memAddress0`), not `memAddress` (all zeros reached the GPU:
  every model-view matrix zero, nothing drawn).
- naga sizes a std140 uniform block rounded to 16 (the 40-byte `Fog` block needs 48 bound):
  buffers are made in 16-byte steps and uniform bindings rounded up.
- Biome tints need the colour maps (`GrassColor.pixels`...: their reload listeners) or every
  grass and leaf is black; a client level without a `BiomeManager` throws in `getBiome`.
- Atlas definitions fail ("Unknown element id: minecraft:directory") and item models too
  ("minecraft:model") until `ClientBootstrap.bootstrap()` has run.
- An `--offscreen` window is paced only by the frame limiter (no vsync, nothing presented): the
  loading screen drew as fast as it could (99% of a real GPU) until it was held to vanilla's 60
  for a menu with no level (`Loading::about_to_wait`); in a world, `maxFps` (default 120) holds.
- `DynamicGpuDataStorageMapped` grows its ring every frame unless `getDynamicUniforms().reset()`
  runs at the frame's end.
- `RenderSystem.bindDefaultUniforms` binds `Lighting`, `Globals` and `Fog` only when set:
  vanilla's frame sets them (`Lighting.setupFor`, `GlobalSettingsUniform.update`); a pass of a
  pipeline that reads an unset one draws nothing here ("uniform N unbound", N the pipeline's
  uniform index: `HORIZON_JAVA_DEBUG=gpu` logs each pipeline's uniform names).
- Mods hook `Minecraft.renderFrame` for per-frame work (Iceberg's render tick), not only
  `runTick`; `getDeltaTracker()` is `DeltaTracker.ONE` here (whole ticks).
- A mod's payload class can be one record for both ways with fields only one side fills
  (Pufferfish's Skills' `InOutPayload`): handing the object over drops the other side's data.
- Each player's client has its own `ClientPacketListener` (`LocalPlayer.connection`,
  `Minecraft.getConnection()`): mods keep per-client state on it. Its join fires the mods'
  injections into `handleLogin` (no packet) and `handleSetSpawn` (the level's spawn) on it.
  On a dedicated server it has a `ServerData` ("horizon"), as a multiplayer client's.
- Xaero's minimap: its session starts in `ClientPacketListener.handleLogin` and lives on the
  connection (`HudSession.getForPlayer`); the static `XaeroMinimapCore.currentSession` holds the
  last joined, and the next join ends it (one client, one session in the real game). With
  several players on the one headless client, a join ends the previous player's session (not
  patched since bridge-generic-maps: no code into mods' internals). Its
session. It names its world by `Minecraft.getSingleplayerServer()` (the mirror, set at the
  join when it is an `IntegratedServer`), or on a dedicated server by the `ServerData` and the
  spawn from `handleSetSpawn`, follows the world from `Player.tick` HEAD, writes its map chunks from
  `GameRenderer.extract` HEAD and draws in a picture in picture (`MinimapPipRenderer`), reading
  `gameRenderer.guiRenderer`'s projection, `lighting()`, `mainRenderTarget()`, a client level's
  `clockManager()` (`ClientPacketListener.clockManager`) and `Minecraft.getResourceManager()`.

- `Minecraft.running` must be true (`isRunning`): mods' own threads loop on it (Xaero's
  World Map's `MapRunner`, started once and gone at once otherwise: "Preparing World Map..."
  for ever).
- A client level's chunks (`Level.getChunk` on a headless client level) are Horizon's copies
  whose `getLevel()` is that client level (JourneyMap maps only chunks of the player's level),
  kept (`HorizonChunks.held`) until Horizon's chunk changes (`Natives.chunkRevision`). Mods
  read them on their own threads, where Horizon's world can't be read (no view: revision -1):
  those get what was made; one never made is wanted and made on the next client tick
  (`serveWanted`). Copies are filled at once for that.
- The login's chunk radius is the client's `serverRenderDistance` (`getEffectiveRenderDistance`):
  0 makes JourneyMap map one chunk.
- `TextureManager.byPath` mirrors what mods register (JourneyMap reads it with an accessor and
  makes a new texture each frame otherwise, which pushes the GPU images out of the window's
  slots). Each new dynamic texture is a new `horizon:dynamic/N`.
- What else the headless client carries for map mods: a monitor (`HorizonMonitors`: SDL's
  `findBestMonitor` otherwise), a debug overlay and a chat component on the HUD, chat
  abilities on the local player, a mouse handler from the start (its window events are
  cancelled: `MouseHandlerMixin`), the client level's spawn (`ClientLevelData.setSpawn`), an
  empty player list on each connection, a camera at the origin with no player.
- JourneyMap remakes its full-screen map's buttons, and clears its map, every frame while a
  button's texture `hasImage()` is false: that needs `AbstractTexture.texture` set, which the
  headless dynamic textures now have (`HorizonTextures.Texture`, a GPU texture of nothing).
- `TextureManager.getTexture(id)` of a resource is a `SimpleTexture` (`HorizonTextures.Named`):
  VoxelMap reads the resource itself for a `ReloadableTexture` and gets nothing otherwise.
- `GameRenderer.featureRenderDispatcher` (real render buffers, models, atlases, font) is made
  with the models: VoxelMap's `SubmitPass` draws its HUD through `prepareFrame` and
  `renderAllFeatures`.
- `ClientLevel.hasChunk` is Horizon's (a held copy exists): VoxelMap maps only chunks it has.
- Threads without a view (mods' own: VoxelMap's map thread) read blocks from the held client
  chunks (`HorizonServer.blockState` falls back to `HorizonChunks.heldBlock`); before, every
  block there was air (the map stayed black).
- Text components are resolved on the Java side (`getVisualOrderText`, styles kept): a LAN
  guest's or a dedicated server player's game has no mod's lang files ("jm.minimap.compass.n").
  Vanilla keys in mods' HUD text now come in the server's language.
- Mods' injections into Minecraft's constructor at its options (`INVOKE Options.<init>`:
  Yumi runs its mods' `yumi:client_init` there, Trinkets Updated's) run after the registries'
  freeze (`HorizonHooks.START_OPTIONS`, from FabricBoot): Yumi's common init runs at the
  freeze, and the client init registers receivers of payloads it made.
- The mods' client reload listeners ordered after the models (`getFabricDependencies`) run in
  the models' reload, after them, each failing alone (`failingAlone`); they used to be left out.
- The client player's `levelCallback` is a no-op callback, not `NULL` (a copy of the server
  player's): JourneyMap gives no mapping task to a player not in a level (its map stayed empty).
- Each player's client is told of its chunks (`HorizonClient.clientChunks`): Fabric's
  `ClientChunkEvents.CHUNK_LOAD` for Horizon's chunks within the login radius, nearest first,
  24 a tick, and `CHUNK_UNLOAD` beyond it (or at a dimension change). Diorama maps only what
  it is told of. A client level's chunk cache answers with Horizon's chunks
  (`ClientChunkCacheMixin`), and `ClientLevel.hasChunk` from the chunk's revision without a
  copy (`HorizonChunks.has`: VoxelMap asks hundreds a tick; with copies the server thread
  stalled and LAN guests were dropped).
- While the client's models load, players' clients don't tick (the client stays at its title
  screen), as a vanilla client loads its resources before joining: Diorama reads the models
  once, when its player joins.
- Mods' injections at the head of `GameRenderer.render()` run each frame after `extract`'s
  tail (`HorizonHooks.fireRender`).
- The HUD sends a GPU texture as an image when a render pass drew into it or it was written
  from memory (`writeToTexture`: Diorama's tiles), unless it is an atlas (`Texture.atlas`).
- VoxelMap and JourneyMap together: VoxelMap's client init fails registering a payload type
  JourneyMap registered (probably the same on real Fabric); VoxelMap then has no world-id
  packets.
- The keys mods bind are read again every 100 server ticks and resent when they change
  (JourneyMap registers its keys at its first client tick, after the first join).
- **Mixin strips the injector annotations off merged handlers**: only `@MixinMerged` (mixin,
  priority) is left on the target's method. What a handler is (`@WrapOperation`, its `at`) is
  read from the mixin class in the mod's jar (the census, `HorizonHooks.scan`).
- Mods' `@WrapOperation`s on calls inside a constructor don't run on mirror objects either:
  `HorizonHooks.constructed` runs them (levels, chunk maps; not entities) before the TAIL
  injections, the receiver and arguments from the object's fields, an `Operation` calling the
  wrapped method. Biolith seeds its biome noise in one in ServerLevel's (without it every Java
  worldgen chunk failed with an NPE in `OverworldBiomePlacement.getLocalNoise`).
  A wrapped call whose result the mirror makes itself elsewhere runs its wraps there:
  `RandomState.create` in ChunkMap's (Lithostitched's noise router modifiers) runs in
  `HorizonServer.makeRandomState` through `HorizonHooks.wrapConstructorCall`, the handler's
  parameters matched by type (the call's arguments, then the locals it captures: the level,
  the registries), its return value kept.
- Mixins' field initializers (`private RollManager rollManager = new RollManager();`, or a
  static getter: Enderscape's `variables = EnderscapeClient.clientsideVariables()` on the HUD)
  are merged into the target's constructors: `HorizonHooks.initFields` makes them on mirror
  objects (recorded from the mixins' own `<init>` by the census). A getter may have nothing
  yet when the object is made (the mod's client entrypoint runs later): the HUD's run each
  frame while null.
- A client connection's `scoreboard()` is what `ClientLevel.getScoreboard()` answers, and
  `Minecraft.getConnection()` is the player's own connection
  (`HorizonClient.connection(player)`): each needs the shared one's client, network
  connection and a scoreboard (Cardinal Components' client tick, Puzzles Lib's and
  Architectury's handleLogin).
- A Java mod can register its blocks under namespaces other than its id (BuildCraft's
  modules: `buildcraftenergy`, `buildcraftbuilders`...): the content mod declares them under
  each namespace it owns (`data/<ns>/horizon/block/`, the namespace in the manifest's
  `namespaces`), and the port pipeline gives a jar the oracle's facts when its ids are under
  any of them (it looked at the mod id only: every BuildCraft block was unknown).
- The headless client's level reads the server's block entities, so a client handler can
  change the server's state and make it send the same message again (Large Fluid Tank's
  content message): `HorizonLoop.flush` delivers only what was queued when it started (it
  drained until empty: the server thread never left it).
- Mixin configs with a trailing comma in a list read as a null entry (lenient Gson):
  skip it, or the whole config is lost to the census (Simple Pocket Machines').
- The load functions can schedule what a first player needs (Skyblock Infinite places its
  island 2 s after load, `schedule function … 2s`): the first player also waits until the
  events queued when they finished, due within 10 s, have run (`HorizonFunctions.settle`).
- `Minecraft.getConnection()` is `player.connection` (null for a player without one): the
  headless client's players have `HorizonClient.connection(level)`, whose network
  `Connection` is a mirror one (`HorizonServer.clientConnection`).
- The content cache's stamp is the jars' names, sizes and mtimes: copying the jars without
  keeping mtimes (`cp` without `-p`) makes the content again (minutes for a big pack).
  `launch.sh`: `CACHE=dir` keeps it across scratch folders, a `config/` beside `MODS` is copied.
- The world spawn's search (`find_world_spawn`) starts from the generator's origin
  (`ChunkGenerator.getOrigin`, the climate's spawn target): with Java worldgen it is the mods'
  (`HorizonWorldgen.origin`), asked once the first chunk has started their generation.
- `horizon-ctl launch` waits 300 s for the game's socket; `HORIZON_CTL_LAUNCH_TIMEOUT` (s)
  for a pack that makes its content first.

- **The client level needs a connection.** REI (and others) read the client's registries as
  `minecraft.level.connection.registryAccess()` once there is a level; with a null one REI
  silently drops every recipe whose display touches a tag (half of them). Client levels share
  `HorizonClient.connection(level)`, which carries the server's registries.
- `Level.getEntities(Entity, AABB, Predicate)` goes through `getEntities().get(box, consumer)`,
  not `all()`: anything the mirror lends a level for a call must be in the box queries too
  (Better Combat's `TargetFinder` saw only its cursor). `regress.py` now rebuilds a fixture
  jar when its sources are newer than the cached one (a stale `mixinmod.jar` hid a mixin).

Found in the upstream sync (2026-11-01):
- Upstream's content blocks fan their states out over declared `state_properties`
  (`NewBlock::state_combinations`) and rewrite `model-states.json` after `names()`: a block
  with its own states (`own`) must be left out of that pass, or its states shrink to the
  template's one and the game refuses the whole content root ("model state definitions disagree
  with the imported block registry": every mod block unknown).
- Upstream routes a guest's hit on a mob through its own early-returning block in
  `guest_attack`: a hook placed after it never runs. Routed attacks are `java_mob_attack`,
  inside that block.
- A fresh game folder opens on vanilla's first-launch accessibility screen; scripts that drive
  the title screen click Continue first (`new_world.sh`).
- Upstream removed `Levels::parked_chunks` (lent world): `with_java_world` reads parked levels
  through `parked_dims` + `parked_world`.
- `save::decode_with` takes the dimension's `ColumnLayout` (`VanillaTerrain::layout`, now pub).
- **A `LocalPlayer` made without its constructor lacks what 26.3's constructor makes**:
  `firstPersonHandsAndItems` (Create's client tick), and its class never built synced data:
  mods that hand out accessors per exact entity class from `SynchedEntityData.Builder`'s
  constructor (Create's `AllSynchedDatas`) had none for `LocalPlayer`. `localPlayer` makes a
  `new SynchedEntityData.Builder(local)` once (the data itself stays the server player's).
- **The allocated `Hud` needs `contextualInfoBar` and `contextualInfoBars`** (vanilla's hotbar,
  run inside a mod's wrap of it: Create's), and the client connections their
  `waypointManager` (the locator bar asks it). `Hud.ContextualInfo` is private: by name.
- **`export X=$(cmd) || exit 1` never exits**: the export's status hides `cmd`'s. The test
  scripts ran stale jars after a failed `java.sh`; they now assign first.

- **The server can run ticks faster than Horizon loads chunks** (after the upstream sync, a
  catching-up server runs the 40 ticks Skyblock Infinite schedules within a second). Anything
  that holds a player "until N ticks passed" must also wait for the chunks it needs: a new
  player waits for every chunk a function forced (`HorizonFunctions.WAITING_CHUNKS`).
  Then it also waits until the run that a forced chunk stopped has gone through: no
  `horizon-retry-` event scheduled and two ticks with no run stopped (pack-6).
- **Some datapacks wait for a player on purpose.** Skyblock Infinite's island function checks
  `data get entity @p` and reschedules itself each tick until someone is online, so its island
  lands ~1 s after the first join, in vanilla too. Holding the join longer can't fix that.
Found in upstream sync 5 (2026-11-04, upstream `73d3ee3d2`):
- **A fixed pool of network threads** (`lan/io_pool.rs`) replaced the writer thread per player:
  a connection is `detach`ed and framed with `conn::frame_body`, so `Stream::Memory` can no
  longer be matched there. `ConnReader`/`ConnWriter` keep a `wide` flag from creation (an
  in-memory pipe's frames may pass 21 bits: a big pack's join to the window's own player), and
  `frame_body` uses `unbounded_contents` when it is set.
- Upstream's `BakedFace` holds a whole `ModelQuad`; ours keeps the compact `BakedQuad` (no
  sprite name: a big pack's 2.3 M faces), which now copies upstream's new quad fields (`light`,
  `color`, `ambient_occlusion`, `render_type`, `shade`). `force_translucent` is
  `render_type.is_none() && material.force_translucent`, as upstream's mesher reads it.
- Item models: upstream does mods' own node types, tints and properties (`item_model/foreign.rs`,
  custom `condition` properties a mod answers); our `ItemModelNode::modded` is gone.
- Mod particles: upstream's `mod_sets` values are `(set, Option<ModBehaviour>)`; `add_mod`
  takes the set.
- `horizon-world`'s `level/data.rs` no longer imports `File` (`std::fs::File` in ours).

Found in upstream sync 6 (2026-11-05, upstream `41d5649ed`, 1539 commits):
- **Mods' chunk tickets are upstream's `Streaming::mod_forced`** (native mods' NeoForge-style
  tickets). Our separate `ticketed`/`kept` union is gone: `set_ticketed` (Java's tickets) now
  adds and removes its own share in `mod_forced` and remembers it in `ticketed` to see what was
  dropped. A chunk both a native and a Java mod hold is dropped when either lets go.
- `Hub::open_container` takes `extra` (a `horizon:menu_extra` payload, sent only for a placed
  container); our `open_unplaced_container` passes `None`.
- Synced registries are NBT now (`Entries.data: Vec<Tag>`): mods' biome JSON goes through
  `json_to_nbt`.
- `Damage` has `attacker_species`; `MotionWorld` has `health` (`&game.health_resources`);
  `Screen::JavaMod` must be in `debug_options_interrupts` (F3+F6 closes it, as `Mod`).
- `game.rs`: moving pistons tick in their own `tick_moving_pistons`; `java_random_ticks` stays
  right after the random ticks. Upstream's game-master-block check runs after `java_break`.

- **Loot tables are parsed on first use** (`horizon_world::loot::LootTables`): the
  `native_loot_table_load` hook runs then, not at world load. Code that changes a table's file
  after reading it keeps the parsed copy.

## Build and test

```sh
# Java 25 (JAVA_HOME may point at 21 on these machines; the scripts pick 25)
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 PATH=/usr/lib/jvm/java-25-openjdk-amd64/bin:$PATH
cargo build --profile fast -p horizon-client -p horizon-client-lib --features horizon-client/java-mods-dev   # ~5 min after a Rust change; horizon-ctl is horizon-client-lib's
cargo test -p horizon-jvm --features dev                                            # bridge tests (+ prototypes for boot112)
cargo clippy --profile fast -p horizon-client --features java-mods --bins --lib -- -D warnings -A clippy::manual_range_contains
```

**Features (2026-11-06, bridge-gate-2):** `java-mods` is the release build: Fabric only, mods run
only for the launcher's owner record ("Ownership"). `java-mods-dev` adds the developers' opt-in
`HORIZON_JAVA_MODS=1` and the stand-in services variable: every script in `tools/java_bridge/`,
regress and `lan_e2e --java-mods` build or need it. `java-mods-prototypes` (implies `-dev`) adds
the NeoForge and Forge 1.12 prototypes (`horizon_jvm::{neoforge,boot112}`, `HORIZON_NEOFORGE`,
`tests/boot112.rs`); without it the game ignores `HORIZON_NEOFORGE`. **Trap:** a `java-mods` build
started by a script with `HORIZON_JAVA_MODS=1` runs no mods ("no owner record" in the log):
build with `java-mods-dev`. `tests/test_java_mods_release_features.py` checks the release builds
name `java-mods` alone.

A build made before `.local/` was prepared has no Fabric API modules (the fixture mods then
fail to build): prepare, then `touch crates/horizon-jvm/build.rs` and build again.

The Java sources compile in `horizon-jvm`'s build script: a Java error shows as a failed build
script. Always look at the build's output: a failed build leaves the old binary running.

**Java-only changes: don't rebuild the game.** The jars are baked into the binary, so a full
build relinks the game (~4 min). `tools/java_bridge/java.sh` rebuilds only the jars (~6 s, ~1
min the first time) into `.local/bridge/dev`; a game started with `HORIZON_JAVA_JARS` set to
that folder runs them. `JAVA_ONLY=1 tools/java_bridge/screen_test.sh …` does both. Most bridge
work is Java: iterate there, and batch Rust changes into as few full builds as you can.

Headless, with screenshots (scratch folders only, see CLAUDE.md):

```sh
MODS=~/mods/fd    SCENE=pot      tools/java_bridge/screen_test.sh '$C click-at 141 187' '$C click-at 163 62'
MODS=~/mods/toms  SCENE=terminal tools/java_bridge/screen_test.sh '$C click-at 149 45'
MODS=~/mods/x     SCENE=none     tools/java_bridge/screen_test.sh   # just start, print failures
```

`launch.sh` starts a game, `around.sh` runs steps around the player. Test games are capped at
30 FPS (`fps_cap.sh`: `maxFps` in the game folder's options.txt; `MAX_FPS=n`, or an `OPTS` file
with its own `maxFps`); an offscreen window has no vsync, so uncapped it takes a real GPU whole.
A `resourcepacks/` and an `options.txt` beside `MODS` (a pack's folder) come along as a launcher
instance's would: the packs linked, their selection on. Clicks are GUI pixels
(427x240 at 1280x720; a screenshot pixel / 3). The log of a run: `<scratch>/home/logs/latest.log`;
Java's lines start `[java]`. The first start of a mod set builds its content (~20 s, cached in
`.horizon-cache/java-content`; bump `FORMAT` in `content.rs` when the content format changes).

Test mods (Modrinth, 26.3 Fabric): Farmer's Delight Refabricated (blocks, cooking pot screen,
recipe book), Tom's Simple Storage (block entities, terminal screen, client↔server messages),
Naturalist (mobs with ModelPart models, Fabric API only). The repo's own critter mod
(`java/fixtures/crittermod`: goals, a walking model, a spawn egg) builds with
`tools/java_bridge/fixture_mod.sh crittermod ~/critters/critters.jar`; then
`MODS=~/critters SCENE=entity tools/java_bridge/screen_test.sh '$C mouse left click'`
(`ENTITY=naturalist:bear` for another mod's).

## Status

Mods' writes made inside their call (2026-11-10, `bridge-sync` step 1): a mod's `Level.setBlock`
goes straight into Horizon's world during the Java call (`Natives.setBlockNow`), with the mod's
flags and vanilla's update cascade (Horizon's neighbour and shape updates, Horizon's block entity
replaced; then on the mirror the mods' block entities made or dropped, `onPlace`,
`affectNeighborsAfterRemoval`, the mods' blocks' neighbour updates), and the mod's next read sees
it (its section copies follow every block the cascade changed). The deferred queue and Java's
overlay (`WRITTEN`) are left only for writes Horizon can't make now (another dimension, a chunk not
loaded). `setBlock` returns vanilla's answer (false when nothing changed). Regress and the bench:
see java-bridge-perf.md "Synchronous writes and the tick in Horizon's".

Bench fixes (2026-11-10, `bridge-benchfix`): mods' block entities are saved in their chunks
(real Fabric opening Horizon's world keeps Create's networks and chute items: 312 problems → 0;
an older world's `horizon-java/block_entities/` moves into its chunks as they save, checked with a
bench world turned back into the old layout); servers don't tick and the
dedicated server doesn't say "Done" before the Java mods are up (no item lost into a chute on a
warm restart); mods' block entities tick in the order they were made (chute parity 32 diffs →
0); `/data get block` on a hopper has `TransferCooldown`. See java-bridge-perf.md "Bridge vs
real Fabric".

Bridge vs real Fabric (2026-11-10, `bridge-bench`): `tools/java_bridge/bench_vs_fabric.py` runs the
same mods, seed and scripted scenario (hopper, Create chute and kinetic lines; a bot exploring)
on a real Fabric 26.3 server and Horizon's `--dedicated`, and reports MSPT, RSS, heap, start
time, item parity after `/tick step`, a restart's save check and real Fabric opening Horizon's
save. See [java-bridge-perf.md](java-bridge-perf.md) "Bridge vs real Fabric". Fixed: `/tick
freeze` now stops the mods' block entities, ticks, entities and spawner; `/data get block` reads
mods' block entities. Open (found by it): a warm start ticks ~14 s before the Java side is up
(hoppers lose an item into mods' blocks), mods' block entities aren't in the region files (real
Fabric loses their data), Create chutes one tick off in some chunks: all fixed in `bridge-benchfix`.

Create's pumps, pipes and fans (2026-11-06, `bridge-create-1`): a pump fills a tank from
Horizon's water and the tank shows it (client copies load the update packets as a client does),
pipes draw whole, in water too (block models' parts in a world), fans blow players (mods' block
entities setting a player's motion). See "Create's pumps, pipes and fans". Regress `createpump`,
`createfan`. Left: Horizon's mobs in a fan's wind; far pipes (over 64 blocks) not drawn.

Client-side mods on any server (2026-11-06, `bridge-own-client`, brief section 5): the player's own
game runs the client half of the mods the server doesn't run, by default (no switch): every mod's
against a vanilla server or a Horizon server without Java mods, only the missing ones beside a
Horizon server's mods (its HUD over the server's). Carried mods with no world content are
client-side, so servers without them accept the player. Headless: Xaero's minimap drawn for a
dedicated server's player with no Java mods on the server (`maps-client-only`), against vanilla's
server jar (`maps-client-vanilla`), and beside the server's Jade (`maps-client-some`). See "Each
game runs its own mods' client" above.

Xaero's maps at full resolution (2026-11-06, `bridge-hud-res`, brief section 3): the game sends its
GUI scale and framebuffer with each size input, Java keeps a window per player (applied in `as()`),
big images shrink by a fractional box filter to 2048 for the window's own game (512 for network
players), and Horizon draws images over 512 from their own wgpu textures. At 2560x1440, GUI scale 6:
the minimap's picture 447x267 → 858x498, the World Map 427x240 → 2048x1152. Regress `maps-hires`.
Left: network players stay at 512 (deflate or a higher remote cap would need payload limits checked);
the device client (`HorizonDeviceClient`) still lays out at scale 3.

No code tied to recipe viewers' and HUD mods' internals (2026-11-04, `bridge-generic-ui`,
Ludovic's second condition for REI, JEI, Jade, AppleSkin, Architectury, Cloth Config): every
class, mixin and reflection naming them is gone (`HorizonRecipeViewer`, `HorizonJei`,
`ReiGuiGraphicsMixin`, `ReiAsyncSearchMixin`, `JeiConnectionMixin`, their entries in
`HorizonClientState`, `HorizonClientConfig` and `ModsPlugin`, Architectury's events fired by
name). In their place, what a vanilla client does, for every mod: the client's join gets the
server's tags and recipes (`recipesArrive`), the client's connection is connected, the mods'
`@WrapOperation`s of the screen calls in MouseHandler/KeyboardHandler run (`wrapCall`), a mod's
own extractor records (`PlatformPlugin`), keys the inventory doesn't use go to the screen under
the overlay with the hovered slot, and typing is seen from text boxes' focus and from a mod
taking the typed characters. (`git grep -il "shedaniel\|mezz\|snownee\|squeek\|architectury\|autoconfig"`
in `crates/horizon-jvm crates/horizon-client` finds comments only.)
- Works (headless, screenshots): REI's list, search box (E typed in it), R/U over a slot
  (crafting table, Farmer's Delight's beef stew), Escape back; REI draws its recipe screens
  through its own GuiGraphics; JEI's start, list, search, uses from its list, R/U over slots,
  over the cooking pot's screen (`pot-jei`); Jade's tooltip and AppleSkin on the HUD; Options →
  Mods' Java settings screens (Jade's, Cloth Config's). Regress `rei jei pot-jei hud
  modsettings join` pass; `reiscreen` intermittent (below).
- Broken (listed, not patched; their cases stay): **per-player state of these mods on a LAN or
  dedicated server**: REI's and JEI's search text and REI's craftable filter, REI's, Jade's,
  AppleSkin's and AutoConfig mods' settings are one for every player again (`rei-lan`,
  `jei-lan`, `config-rejoin` fail); JEI's search box can't be typed in while another player's
  inventory is open (each frame JEI lets go of a box whose screen isn't the open one); REI's
  craftable filter searches with no player's inventory (an empty list). The first character typed into
  REI's search box, if it is the inventory, drop, swap or a hotbar key, acts on the inventory.
  `/recipes` and `/uses` are gone.
- Licences: REI MIT; JEI MIT; Jade CC-BY-NC-SA-4.0; AppleSkin Unlicense; Architectury
  LGPL-3.0; Cloth Config LGPL-3.0 (from each jar's `fabric.mod.json`). The bridge ships none of
  their code and now names none of their classes.
No code tied to one mod's internals (2026-11-04, bridge-generic-entities; the maps, recipe
viewers, Jade and Architectury are another chat's): Better Combat's reflection, 3D Skin Layers'
`getInjectedMesh`, WorldWeaver's preset manager, TerraBlender's rule shape and Grid World's
generator config are gone; what replaced them is generic (sections above):
- **The configuration phase** (`HorizonConfiguration`): a joining player's mods' configuration
  tasks run before play on a real `ServerConfigurationPacketListenerImpl` (Fabric's configure
  events, then each task started in turn), their payloads carried by codec to the headless
  client's configuration receivers and the answers back. Better Combat's weapon and config
  sync, Fabric's attachment sync now reach the client. The mirror's connection gets its mixins'
  parts (`HorizonHooks.initFields`/`constructed`; `new T(this)` field inits now too) and drops
  what is sent on it (`ConnectionMixin.send`). Fabric's own channel handshake tasks
  (`c:version`, `c:register`) are skipped: both halves are in one JVM.
- **The client's attack** (`HorizonClient.startAttack`): see the third round above.
- **Better Combat** works again (bridge-generic-2): its swing waited on the client player's
  own counters, which the headless client never moved. `LocalPlayer.tick` moves `tickCount`
  (Entity.baseTick) and `attackStrengthTicker` (Player.tick) each client tick; now
  `HorizonClient.clientPlayerTick` does, so a mod timing its client's upswing by them sends its
  attack (regress `bettercombat`, `sweep`, `attack` pass). Not working: **Grid World**'s far
  terrain (whole land far away).
- Licences (fabric.mod.json): Better Combat All Rights Reserved; 3D Skin Layers tr7zw Protective
  License; WorldWeaver MIT (parts ARR); TerraBlender LGPLv3; Grid World Zlib; GeckoLib MIT
  (only our fixture uses its public API); Farmer's Delight MIT (test data, comments); Create
  (comments, our kinetic fixture copies its patterns, no code of it).
Survey of the 60 most-downloaded Fabric 26.3 mods (2026-11-04, bridge-survey-1): 39 work, 4 left
out on purpose (Sodium, Iris, Sodium Extra, Reese's), 16 play with a failure, Essential stops the
game. See [java-bridge-survey.md](java-bridge-survey.md); rerun with `tools/java_bridge/survey/`.
**26.3 traps it found:** the headless `Minecraft` still has nulls mods read at init
(`resourcePackRepository`, `profileKeyPairManager`, `launchedVersion`, `userPropertiesFuture`,
`modelManager`, the SDL window); one Java mod failing to start stops the whole game; mods writing
`config/x` relative to the game folder fail on a fresh folder (no `config/` yet).
NeoForge (2026-11-04, bridge-neoforge-research): NeoForge 26.3.0.101 starts inside the bridge's
JVM, server half only. `HORIZON_NEOFORGE=<install>` loads `mods-java/` with NeoForge instead of
Fabric: the mods are constructed and registered, and common setup runs. Nothing else runs on them
yet: no mirror, content or screens. Plan, the Fabric/NeoForge differences, NeoForge's start
sequence and its traps: [java-bridge-neoforge.md](java-bridge-neoforge.md). **NeoForge and FML are
LGPL: run them, never read their source** (that page says what's allowed).
Forge 1.12.2 (SkyFactory 4): plan in [java-bridge-112.md](java-bridge-112.md) (2026-11-04,
bridge-112-research). Phase 1 runs (2026-11-05, bridge-112-boot): Cleanroom 0.7.14-alpha boots
hidden in the bridge's JVM from Rust (`horizon_jvm::boot112`, its own start, the Fabric path
untouched) and reports blocks with stored states, items with subtypes, fluids, entities, biomes,
dimensions: the fixture in ~8 s, SkyFactory 4 (217 mods) in ~90 s with 4 GB of heap.
`tools/java_bridge/{fetch112.py,build112.sh,run112.sh}`; status and 1.12 traps on that page.
**Cleanroom is source-available, not LGPL: run it, never read or redistribute it.**
`tools/java_bridge/scan112.py <mods>` measures what a 1.12 mods folder needs (classes only).
Recording and replays (2026-11-04, bridge-replay-1): ReplayMod records in single player and as a
LAN host (the window's player), and Horizon plays its `.mcpr` recordings (`--replay FILE`).
Flashback loads, its client command runs, but it can't record or replay yet (told at join). See
"Recording and replays". Regress `replaymod`, `flashback`.
Chunky (2026-11-04, bridge-mixins-10): `/chunky start` pregenerates through Horizon's streaming
(81 chunks in 12 s, ~9 chunks/s on 2 cores), saved full; a routed break's sound plays once
(see "Mods that change vanilla's behaviour"). Regress `chunky`, `breaksound`.
Testers' Trello retest (2026-11-04, bridge-trello-1): JEI, Farmer's Delight, Litematica's menu
and Cloth Config's screens (`/modconfig`) work; Chunky, Simple Voice Chat, TACZ fail; Chat Heads,
Flashback, ReplayMod load and do nothing. Log lines and areas: java-bridge-tasks.md, "Retest".
Retest 2 (2026-11-05, bridge-trello-2, java-bridge 6a252e94f): Chunky, Cloth's lag, ReplayMod,
SVC's screens, Create's water wheel, pump, depot clicks, track's first click and the scaffolding
fixed; TACZ half (drawn, fires once); Better Combat, Chat Heads' heads still not. Create's
leftovers fall in three buckets: its client click handlers (chain conveyor, arm, glue, value
boxes, goggles), item entities in the mirror (depot throws, crushers, fans), mod blocks' shape
updates and the routed lava/water interaction (with Create no obsidian forms). Table:
java-bridge-tasks.md, "Retest 2".
Mod blocks with no collision rule never stop the game (2026-11-04, bridge-crash-1): placing
Create's scaffolding and walking near it ended the game ("unsupported block collision state
36126", on the window's thread, so the world saved beside it crashed again on the first step).
A mod block copying a vanilla template whose states have no cached collision shape
(scaffolding, bamboo, powder snow, liquids...) now follows the template's rule (the content
root's `cached-collision.nbt` has `templates`, read by `CachedCollisionRegistry::template`);
a state with no cached shape and no rule collides as a full cube, logged once ("N block
states have no collision rule"). Regress `scaffold`; a world saved at that crash opens and plays.
Left: Create's scaffolding with `bottom=true` collides as vanilla's (Create's own is
`SCAFFOLD_HALF`); 2 `create:fluid_pipe` states are full cubes (their nearest vanilla shape is
a contextual one).

TACZ Refabricated (the 26.3 unofficial port 0.8.6-beta1, with Player Animation Lib and Forge
Config API Port; 2026-11-04, bridge-entities-10): guns fire, bullets hit, and the guns draw in
first and third person with their animations, muzzle flash and ejected casings. All generic
fixes, no TACZ code (its licence: GPL3 / CC BY-NC-ND 4.0; nothing of it is copied):
- **Mouse-bound key bindings.** Mods' KeyMappings set to a mouse button (TACZ's shoot on the
  left, aim on the right) are pressed while playing, and their handlers in
  `MouseHandler.onButton` run (`HorizonClient.bindMouse`, `mousePress`; the window sends
  `{"kind":"bind","mouse":n}` for `-100 - n` in the bound keys). While a player plays, the
  client's mouse is grabbed and its window focused (mods check both: TACZ's `isInGame`).
- **Mixins' field initializers, whole** (`HorizonMixinInit`): a mixin's constructor code after
  its super call is copied into a hidden class nested with the target and run on objects made
  without their constructor (the client player first: TACZ's twelve gun-state objects, which
  take each other and the player). Final fields are written through reflection.
- **Ticks**: the client player's `LocalPlayer.tick` injections fire (client mixins, via
  `fireMerged`); a mirrored player's `LivingEntity.tick` and `Entity.tick` injections fire
  inside `Player.tick`, as vanilla nests them (TACZ ticks draw, cool-downs and reloads there).
- **The mirror's server and chunk maps**: `tickTimesNanos` and the average/smoothed tick times
  are recorded (TACZ's shots threw on null); `ChunkMap.getPlayers`/`isChunkTracked` answer from
  the mirrored players (its gun sounds threw on the null player map).
- **Client start**: the model manager exists from the start (TACZ's renderers keep
  `getEntityModels()`), and packs mods add to the client's pack repository at its first reload
  (`HorizonHooks.START_PACKS`: TACZ's gun packs, with every gun's display, model and
  animation) are loaded; the game mode has its `minecraft`.
- **Models**: the recording collector runs custom geometry renderers when the submission ends,
  as the feature dispatcher does (TACZ writes its vertices after submitting). First person is
  recorded when mods hook the arm's drawing; its meshes are drawn. Items whose baked model is a
  mod's own (`HorizonEntityRender.modDrawn`) are resolved by the client's real resolver, so
  their renderer submits geometry (third person, held by mod mobs; Create's wrench should
  benefit too). Textures Horizon's atlas doesn't pack go to each game once as `img:` trees
  (Java image slots; 16 big ones now).
- **Horizon's mobs take rapid fire**: a shadow carries Horizon's invulnerable ticks; a mod that
  clears them before hurting (TACZ, each bullet) clears Horizon's (`hurtMob`'s
  `resetInvulnerable`).
Regress `tacz` (an AK-47 kills a zombie at night, the magazine empties), `tacz-model` (the
AK's texture sent, the gun's item model the mod's; look at first.png and third.png).
Not yet: gun icons in Horizon's inventory and hotbar (items drawn only by a custom renderer, in
Horizon's GUI); bullet tracers (the renderer draws the server's bullet, which lacks what the
client's copy gets from its spawn data); Horizon's own attack still runs on the left click
(vanilla TACZ cancels it: a punch with the gun, block breaking in survival); the arms' aiming
pose in third person (Horizon draws the player's body); aiming's zoom and camera recoil (the
client camera is Horizon's).

Create (Create Fly's 26.3 port, 2026-11-04, bridge-gpu-12): kinetic networks run and their
shafts, cogs and motors draw turning; OBJ items draw; mods' fluids use their own sprites; 3D Skin
Layers draws its layers (see "Create, Flywheel, OBJ models, mods' fluids, 3D Skin Layers").
`SCENE=create`, `SCENE=fluids`; regress `create`, `fluids`, `skinlayers`.
bridge-gpu-13: press, millstone, deployer and belts work; a bearing's contraption assembles,
draws and turns; the wrench and the creative tab's blank items draw (Java's item models). See
"Create's machines, contraptions and items"; regress `createmachines`, `contraption`, `createitems`.
bridge-mixins-11: mechanical arms' points, chain conveyors, stations and signals on tracks placed
with their item, thrown items into depots, millstones, crushing wheels and fans, sandpaper, and
Create's lava interactions work. See "Clicks, items and fluids that mods' blocks take"; regress
`createclick`, `createitemsin`, `createfluids`.

Create's client side (2026-11-05, bridge-client-14): goggles' overlay, value boxes (hold
right-click: the settings board; the speed changes), the toolbox's screen, frogport and machine
sounds; a tester's client mods (Async Particles, Immersive Hotbar, EMF/ETF, ShulkerBoxTooltip,
FancyMenu, Continuity, JEI's mezz_config, Xaero's World Map) start with no failures. See "The
client from its start..."; regress `earlyclient`, `creategoggles`, `createvalue`,
`createtoolbox`, `createsounds`, `modscroll`.
bridge-gpu-14: the pale squares under basins and burners are gone (mods' own occlusion), the
mixer's head, the saw's blade and the burner's head draw (renderers draw client copies, with the
client's sprites), a hand crank pops off with its cogwheel (neighbour updates reach mod blocks),
fluids draw in JEI and mods' screens; water wheels turn in flowing water. See "Create's parts,
neighbour updates, own shapes, fluids in screens"; regress `createparts`, `jeifluids`.

Nether and End (tested 2026-10-31 with BOP, BetterNether, BetterEnd, BCLib, WorldWeaver):
both generate through the mods, the Nether with BetterNether's and BOP's biomes and blocks
(lumabus and golden vines, cincinnasite and nether ores), the End with WorldWeaver's End
source (`WoverEndBiomeSource`: Better End's chorus forest with its pythadendrons; `/locate
biome betterend:chorus_forest` finds one). Three things it took: the new world's dimensions
are the server default's (`HorizonData.defaultDimensions`: vanilla's DedicatedServerProperties
from no properties, whose default level type WorldWeaver's own patch makes `wover:normal`, then
its WorldDimensionData.create; no code of WorldWeaver's),
the mods' `ServerLevel` constructor injections fire (`HorizonWorldgen.levelHooks`: WorldWeaver
seeds its biome sources there) and `MinecraftServer`'s get the constructor's arguments
(`serverArguments`: WorldWeaver's registry-ready events), and Better End's content loads
(its `fabric.mod.json` has raw newlines in strings: `jar::fabric` reads it leniently; its
eternal pedestal's hardness -11 is carried as -1). `world()` runs once a world: the
Nether's start came mid-tick and remade the stores the tick reads.
Fluids: generated chunks bring their scheduled block and fluid ticks and post-processing
marks (`block_ticks`, `fluid_ticks`, `PostProcessing`, read with the save tables): in the
same area 12 flowing water and 50 flowing lava blocks, none without.
Protos dropped unfinished are saved (`worldgen_snapshot`, `worldgen_restore`: blocks, block
entities, entities, decorated) as Horizon saves its own: leaves no log holds
(`distance=7`) after walking away mid-generation and back, three places: 64 before, 4 after (nine 32×32 areas around each).
`/locate structure` asks the mods' generator (`findNearestMapStructure`; its chunks come
from the generation while it runs: `locatingChunk`): `betternether:pyramid`,
`betternether:ghast_hive`, `minecraft:fortress`, and the overworld's villages where the
mods' biomes put them (a taiga village with its bell, not Horizon's plains one).
`tools/java_bridge/launch.sh` now removes the previous run's scratch folder (~1 GB each).

World types from mods (tested 2026-11-01 with One Block 5.1.6 and Grid World Generator
1.1.0; regress case `preset`; `WORLD=none tools/java_bridge/launch.sh` starts at the title
screen, then `tools/java_bridge/new_world.sh "One Block"`): Create New World's World Type cycles Default, Superflat, then the mods' presets in
`#minecraft:normal` (`create_world::mod_presets`: `data/*/worldgen/world_preset/` of the
content root, named by the mods' `generator.<ns>.<path>`). The world keeps the preset's
dimensions and its id (`horizon:preset` in `world_gen_settings`; dedicated:
`level-type=oneblock:oneblock`); the Java side takes that preset's level stems for the world
(`HorizonData.usePreset`/`stem`, read by `world()` from the world folder or one above it:
the tick passes the dimension's folder). A mod's own generator (One Block's void, Grid's
`GridChunkGenerator`) gives the spawn (`worldgen_origin`: getOrigin and getSpawnHeight; the
spawn search waits until Java says whether it generates the overworld) and no far terrain
(Horizon's noise would show land that isn't there). One Block: spawn on its grass block at
0 64 0, `[OneBlock] Your island is at (0, 64, 0)`, breaking it gives the next block (stone,
mangrove planks, wool), its break counter shows. Grid World: the 5-block grid of terrain.
Fixed on the way: the mirror server had no `packRepository` (One Block's generator reads
the selected packs).
`/locate structure #minecraft:bastion_remnant` found nothing because there is no such tag
(vanilla's bastion is `minecraft:bastion_remnant`, found at 599 blocks with the mods'
Nether); an unknown tag now says so (`commands.locate.structure.invalid`).

Surface rules, faster (2026-11-01, bridge-worldgen-speed2): `HorizonSurface` runs
MaterialSystem.buildSurface's walk (`MaterialSystemMixin`, its head) with the same rules on
the same blocks, for less: blocks read and written in the sections directly (ProtoChunk's
getBlockState/setBlockState checked the height, took the section's lock and updated both
generation heightmaps on every write), a write of the state already there skipped, the fluid
check once per state run, and a block's biome (BiomeManager.getBiome: eight seeded corner
hashes) taken straight from the noise biomes when its eight corners agree.
`HORIZON_JAVA_SURFACE_CHECK=1` runs both walks on every chunk (alternating which goes first:
the first fills the noise chunk's caches) and compares blocks, generation heightmaps and
post-processing marks, vanilla's result kept: 0 of 2700 chunks differ with BOP (8.1 ms a
chunk against vanilla's 10.2), 0 of 2600 with BOP + BetterNether + BetterEnd over the
overworld, Nether and End (4.5 against 8.6). `HORIZON_JAVA_SURFACE=vanilla` turns it off;
another mod's mixin into MaterialSystem turns it off too (logged). What's left of the walk
(JFR): the rules themselves (TerraBlender's dispatch and vanilla's condition tree, ~60%) and
the ore veins' density volumes they sample (~20%, vanilla's density functions): changing
those would mean evaluating the mods' rules differently.
Far terrain (LOD) of mods' worlds: the land is Horizon's noise (the mods' when their
generator is vanilla's or on it), the biomes the mods' (as before), and now in the mods'
biomes the land's top block is their surface rules' (`HorizonWorldgen.lodTops`: the rule at
the column's land top, flat around it, water over it to its height; the land top is the
highest run of a block the rules can give, `lodMaterials`, so trees and plants stay). Vanilla
biomes keep Horizon's own (they are vanilla's rules). BOP areas: grass to podzol in coniferous
forests, sand to dirt or gravel by its marshes, dried salt; ~3 ms a batch of columns on a far
terrain thread. A mod's own generator off the noise (One Block) draws its base columns
(`lodColumns`: ChunkGenerator.getBaseColumn): the void stays empty. Grid World is on the
noise and cuts its land after it (its own `doFill`, no `getBaseColumn` of its own): far away
its land shows whole. Its per-mod mask (`lodMask` reading its config) was removed
2026-11-04 (no code tied to one mod); regress `gridlod` shows it.
Lost blocks: a column generated again while it is loaded now leaves the loaded one (and
warns); a decorated region's protos of columns already in the world are dropped, and so is a
far proto of a loaded column instead of saved over its save. Repro (`setblock` of 36 marker
blocks right after spawn with FD, 25 s later each read back): 4 lost in one of 17 runs
before (none in the 15 of those that logged a column landing on a loaded one), none in 4
runs since; the race wasn't caught in the act, so these are guards on the paths a proto
could take over a loaded column, not a proven cause. It is now the regress case `lost`
(`tools/java_bridge/lost_blocks.sh`: the repro, one summary line; FD): 0 lost in 4 more runs
(2026-11-01, bridge-worldgen-4). A failure there names each lost block and its chunk.

Worldgen speed (2026-11-01, bridge-worldgen-speed, 2 cores, BOP): where Java worldgen's
CPU goes (JFR, `-XX:StartFlightRecording` in `JAVA_TOOL_OPTIONS`, `duration=` since the game
doesn't exit the JVM): terrain 60% (noise fill 31, surface rules 25, carvers ~4), features
13%, structure starts/references and biomes ~5%. Per chunk, all in (generation, light, mesh,
by thread CPU over 900 chunks): Java ~86 ms, Horizon's own ~61 ms. With the software renderer
the two cores are also the GPU's (llvmpipe takes ~1 core even at `maxFps:10`), so wall-clock
throughput here says little.
- Parallelism: the mirror's `randomState`/`generatorState` (every step asks, from every
  worker) were `synchronized` statics, and the cache of lower-status chunks one synchronized
  LRU map (289 reads a chunk's structure references): both lock-free now. TERRAIN's and
  BIOMES' work runs on the Horizon worker that asked (`HorizonWorldgen.executor`, the
  `buildTerrain`/`createBiomes` hops redirected) instead of vanilla's background executor:
  Horizon's workers are behind the server and window threads and as many as the cores but
  one; the executor's threads are as many again at normal priority. Default from 4 cores;
  `HORIZON_JAVA_TERRAIN=inline|executor` picks. 2 cores: 300 chunks after a teleport, three
  places, executor 33.6/24.9/30.5 s, inline 36.3/24.4/30.4 s (the earlier "inline slower"
  was before the locks went). Not measurable here: a desktop's many cores.
- Surface rules per biome: TerraBlender already compiles its rules per namespace and
  dispatches by biome only where a chunk's 3x3 has biomes of two mods (`possibleBiomes`);
  compiling is ~1% of the surface time. The cost is vanilla's own walk of every stone block
  of the column through the rule tree (setBlockState 21% of it, ore veins' densities 14,
  biome lookups 9): nothing to cache without changing results.
- Skipping Java where a chunk has only vanilla biomes: most of a BOP world is vanilla's
  (4 of 5 places sampled had no BOP biome within 6 chunks), but the result would differ:
  feature seeds are indexes in the order of every feature of every possible biome
  (`FeatureSorter.buildFeaturesPerStep` over the mods' biomes too), so Horizon's decoration
  of a vanilla chunk isn't what the mods' generator makes, and features crossing into a
  Java neighbour would be cut. The gain is ~25 ms of ~86 a chunk. Not done.

Mods' worldgen (tested with BOP + TerraBlender + GlitchCore, a new world): the overworld
generates through the mods' chunk steps; BOP biomes (orchard, lavender field...) with their
trees, flowers and blocks, read back by Horizon with their biome ids (`horizon-ctl block`).
`MODS=~/bop tools/java_bridge/launch.sh`, then `tools/java_bridge/biomes.py` lists the biomes
around the player with a place of each (`horizon-ctl block X Y Z` gives one).
Also tested: BOP saplings grow (bone meal: a jacaranda), BOP's tinted blocks
(flowering oak leaves, clover, BOP grass) take their biome colours, `/locate biome
biomesoplenty:redwood_forest` finds one (the mods' biome source), far terrain (LOD) has the
mods' biome colours. The spawn search needs nothing: 26.3's spawn origin is the noise
router's climate (TerraBlender doesn't change it), then the spiral reads generated chunks.
`GRAPHICS=file` (a `horizon-graphics.txt`, e.g. `lodDistance:256`) for `launch.sh` turns far
terrain on.

Vanilla-patching mods (tested headless 2026-11-01, server thread, survival; each is a
`regress.py` case: `veinminer`, `harvest`, `carry`, `seasons`, `orbs`, `bettercombat`,
`snow`, `sweep`; the `mixinmod` fixture's `mixins1`, `attack`, `ride`, `dismount`, `fall`,
`effects`, `slot`, `mixins2`; every routed action has one, and the fixture ones need no
downloaded jars):
- Better Combat (through the generic client attack, see Status): its client takes the click
  and its swing lands (`bettercombat`, `sweep` pass since bridge-generic-2).
- Serene Seasons' snow: mid winter, rain: the plains' floor gets snow layers.
- The fixture: ten times the damage inside `Player.attack` (a cow dies to one sword hit, a pig
  spared by its head), no minecarts (the boat still takes the player), no ninth hotbar slot,
  no snowball throws, an emerald with each craft, no getting off while holding a stick, no
  fall damage holding a feather (half the distance with a slime ball: 2 for 10 blocks, 7
  with empty hands), players immune to poison, cows that can't be milked (`Player.interactOn`'s
  head); `mixins1`: a broken block also drops a diamond, dirt isn't picked up, wheat lit 8
  (one short of vanilla's 9) grows.
- Veinminer: sneak + one dig breaks a 7-block iron vein, raw iron and experience drop, the
  pickaxe wears 7.
- Right Click Harvest: a ripe wheat right-clicked is replanted (age 0) and drops wheat.
- Carry On: sneak + right-click with empty hands picks a cow up (gone from Horizon's world,
  its name kept), a right-click puts it down. The carried mob isn't drawn in Horizon's hands
  (client rendering); its `Inventory` slot lock and `Entity` riding patches are Rust's.
- Serene Seasons: wheat grows in mid summer, not in early winter (`seasons` writes
  `out_of_season_crop_behavior = 1`; the default lets one tick in six through). Glass over a
  crop makes it a greenhouse (fertile every season). Its biome/weather patches stay Rust's.
- Clumps: orbs' pickup goes through its `playerTouch` (6 orbs of 7: 42 points).
- The `mixinmod` fixture: routed break, crop ticks, pickup (first round); pigs spared, cows
  hit; snowballs not thrown; an emerald with a crafted stack of planks.

Works (tested): content (blocks with their own states, items, food, crops, light), mod block
logic and block entities, Fabric and vanilla menus in Horizon's window, mods' own screens with
clicks, keys, typing, scroll, tooltips, recipe book; creative tabs with Fabric pages; mod
commands; mods' messages between their two sides; vanilla block entities shared with mods.

Mods' block entity renderers (tested with Farmer's Delight: a stove's food, a cutting board's
item; `SCENE=ber`): each tick, mod block entities within 64 blocks of a player go through the
renderer the mod registered (`BlockEntityRenderers.PROVIDERS`, a context with no block models
or sprites) into the entities' recording collector; they join the entity snapshot as entries
marked `block` (no hit box, no shadow, lit from the block above). Renderers that draw block
models or atlas sprites (signs) draw nothing yet.

Mod entities (tested with the critter fixture, server thread): `/summon` and spawn eggs make
them, they tick in Java (goals, paths, physics against Horizon's blocks), their own renderers'
models are drawn by Horizon (parts posed each tick, textures from the mods' `textures/entity/`),
players hit them (red flash, knockback, death, removal) and use them, and they are saved and
come back with their chunks.

A 40-jar pack (124 Fabric ids: BOP, Waystones, Farmer's Delight, Tom's Storage, JEI, Jade,
Carry On, Nature's Compass, Xaero's Minimap, Balm, Architectury, Cloth, YACL, Kotlin...; list in
`bridge-pack`'s commits) starts with one logged failure, renders its blocks, runs block use,
server-side item use (`events::use_item`), saved data, translated mod chat, and the pot and
terminal screens still work. `SCENE=pack` in `screen_test.sh` places a row of its blocks.

Client-only screens (tested headless: Nature's Compass's biome list opened by right-clicking
in the air, its clicks and Escape; Xaero's Minimap and Mod Menu settings by `/modconfig`) and
the client's tick (`ClientTickEvents`, mods' `runTick`/`tick` injections; Xaero's "Registry is
not frozen yet!" is gone) work.

Next (top failure of the pack, in order):
1. Mods' worldgen gaps: far terrain's trees are still Horizon's (vanilla's features under the
   mods' biome colours; its top blocks are the mods' now); `/locate structure` from one
   dimension into another (`execute in … run locate`) asks the current dimension's
   generator; a world made before the mods keeps the mods' default preset (WorldWeaver's)
   on the Java side, not its own (a world made with a mod's world type keeps that one).
   The tick passes `world()` the dimension's folder and worldgen the world's, so `worldDir`
   flips between the two (the stores are made again each time).
   Seen once in five `SCENE=ber` runs (with FD): the stove placed by the scene was gone a few
   seconds later. Guarded since (Status: Lost blocks); watch for "generated again while
   loaded" in the log.
2. Chunk Loaders: cells toggle (`SCENE=loader`), owner name and panel show, its floating
   core turns and bobs (its block entity renderer's block model).
   Mods' settings: Options → Mods lists the Java mods with a settings screen (see "Players,
   joins and settings").
3. Vanilla patches Horizon still does in Rust (the `Mixin table: Rust does …` lines): biome
   see `mixin-table.tsv`; routed so far: weather and snow, riding on and off, slot picking,
   Better Combat's attack and sweep, players' fall damage and new effects; in-frame single player's pickups aren't routed. Next actions to route by the
   same pattern: mob drops (`LivingEntity.dropAllDeathLoot` past its head), fishing, crafting
   results.
Then: NeoForge later.

Nature's Compass searches (tested: right-click, pick a biome, Search; it samples and finds,
and Horizon's world has that biome there). The headless client's `LocalPlayer` has the server
player's position, block and chunk position, box and rotation each time it is used; an
unchanged inventory slot keeps its `ItemStack` (mods write to the stack they were used with).

Mods' key bindings (tested with the `keysmod` fixture: `tools/java_bridge/fixture_mod.sh
keysmod <jar>`, G says so in chat): the server tells each player's game once which keys mods'
`KeyMapping`s are set to (`horizon:java_keys`, 26.3 scancodes); the game sends those while
playing with no screen open, and their releases (`bind` on the screen input channel;
`horizon-ctl key` too). Each player's keys are kept (down, clicks not consumed), and the client
tick runs as each player (`Minecraft.player`, their screen) with their keys on the mods'
mappings. With no player, it runs once as before. Gap: a key rebound in Java's
`options.txt` after a player joined isn't sent again.

Mod chunk loaders (tested with the `loadermod` fixture: `tools/java_bridge/fixture_mod.sh
loadermod <jar>`, `/loader ticket|unticket <cx> <cz> <radius>`, `/loader force <cx> <cz>
<bool>`): tickets and `setChunkForced` go to each mirrored level's own `TicketStorage`
(`HorizonTickets`, saved in `horizon-java/data/<dimension>/`, back on reopening). Horizon keeps
the chunks they reach loaded and ticking, as forced chunks (`Streaming::set_ticketed`, not
`/forceload`'s list), a level with only those ticking too. The ticket's levels are vanilla's:
a radius-3 ticket entity-ticks 3x3 chunks, a forced chunk only itself. Load-only tickets (no
simulation: a pregenerator's) keep the chunks their loading level brings to FULL, as kept
chunks (they tick while kept; bridge-mixins-10, "Loading chunks through vanilla's chunk
system").

Block use has a client half too (`HorizonClient.useBlock`, mod blocks only, after the
server's): Fabric's `UseBlockCallback`, the block's `useItemOn`/`useWithoutItem` with the level
answering `isClientSide()`; what it would write to the world (blocks, entities, level events)
is dropped as a client's prediction, the server's change being the one made. Messages a mod's
client code gives its player (`LocalPlayer.sendSystemMessage`, `sendOverlayMessage`) go to the
player's game, and overlay system messages (the server's too, dropped before) reach the action
bar (`Natives.overlay`, `Action::Overlay`).

Mod screens for players of a server: a dedicated server now runs Fabric with the headless client
too (as a LAN host does), so its players' mod screens are drawn there and sent to their games
(tested: Farmer's Delight's cooking pot, a dedicated server and a Horizon client).
`HORIZON_JAVA_SIDE=server` keeps the old dedicated-server Fabric (no client code). A player's
game draws the frames with its own textures: one without the mods' content shows missing
sprites.

Mod entities also spawn naturally (their biomes' spawn lists, per player, under the caps,
creatures every 400 ticks), Horizon's commands reach them (`@e[type=mod:x]`, `/kill @e`),
and their held items, custom name tags, shadows, sounds and death poof show (tested with the
critter fixture, which spawns in every overworld biome and holds a stick).

Custom geometry (GeckoLib's way: `submitCustomGeometry`, the renderer writing vertices
into a `VertexConsumer`) is recorded too (`HorizonEntityRender.mesh`, a `Recorder`
consumer): quads relative to the entity, sent as `"v"` entries of `models` and drawn as they
are (they move with the entity between ticks, the bones themselves step per tick). Entities
and block entities alike (`GeoBlockRenderer` goes the same way). Tested with
`java/fixtures/geomod` (a spinner whose renderer writes posed boxes, a block entity turning
a cube over its block; `tools/java_bridge/fixture_mod.sh geomod …`); a real GeckoLib mod
isn't tested here (this container may not run downloaded mods): GeckoLib's models load
through its client reload listener, which `HorizonClient.reloadClientResources` runs.
Emissive render types (`eyes`, `entity_translucent_emissive`: GeckoLib's glow layers,
spiders' eyes) are sent `"glow"` and drawn at full light.

GeckoLib in the big pack (2026-10-31, the mods' Modrinth dependencies): none of the 72 jars
needs it. The Graveyard (`the-graveyard-fabric`, 4.0.0+mc26.3) does: plain
`GeoEntityRenderer`s (ghoul, revenant, lich, nightmare...) with `TextureLayerGeoLayer` eye
layers, its textures in `textures/entity/`; it also needs `villagertradingplus`. To try it:
GeckoLib 5.5.7 + The Graveyard + Villager Trading Plus + Fabric API in the mods folder,
`/summon graveyard:ghoul ~3 ~ ~ {NoAI:1b}`.

Name tags are the renderer's own (`EntityRenderState.nameTag`, made with the camera on the
entity and it under the crosshair, the nearest player as `Minecraft.player`), shown always
when `shouldShowName` says so with nothing under the crosshair, else when looked at; at the
renderer's attachment height. The custom name is the fallback when the renderer fails.

The mirror's files (mods' entities, block entities, saved data) are written whenever Horizon
saves the world (`save_game_metadata` → `events::save_all` → `HorizonServer.saveAll`): its
autosave, `/save-all` and the world closing, besides every 10 s.

Known gaps of mod entities: vanilla mobs don't see them nor they vanilla mobs (a mod's wolf
won't hunt Horizon's sheep); worn armour, fire, leashes and sprite-atlas models
(`UvMapping`) aren't drawn; custom geometry is sent whole each tick (fine for a few
GeckoLib mobs, heavy for many); a mod's particle types are drawn as a generic particle
(its sprites, not its provider's motion); in-frame single player is untested.

Game rules and difficulty (2026-11-01): Horizon's rules and difficulty go to the mirror's
server when they change (`events::game_rules` from `tick_java_mods`, `HorizonServer.gameRules`:
`GameRules.set` without the server, so nothing echoes back; `Java mods: game rule … is …` in
the log). The mods' spawner keeps `spawn_mobs` (creatures of generated chunks still come, as
vanilla's), and monsters only off peaceful with `spawn_monsters`; despawning in peaceful,
mobs' griefing and the rest read the same rules. Tested: no critter spawns near the player
with `spawn_mobs` off, ten in 45 s once on.

Mods' player layers (2026-11-01; Carry On's carried mob): each mirrored player has a client
avatar (`HorizonEntityRender.avatar`: a real `RemotePlayer` in its client level, synced each
snapshot: position, rotations, walk and swing, synced data, equipment, inventory, mixins'
fields and Fabric's attachments). The players' renderers (`EntityRenderers.createAvatarRenderers`,
on the context's dispatcher, which is now also `Minecraft`'s with its renderers by type) have
their mods' layers wrapped (`ModLayer`); the snapshot records only what those submit, as an
entry at the player with no box (`"block"`) and an `"owner"`: Rust sends a player its own in
`"own"` (drawn in third person at the player as drawn), the others with the entities. First
person: vanilla's `FirstPersonHandsAndItemsRenderer.submitArmWithItem` per hand, which mods
hook (Carry On draws what is carried and cancels), recorded without vanilla's own arm and
held item; `"hands"` goes to that player (view space, drawn in `hand_geometry` through its
`to_world`), with the hands to hide. Left: the arms' pose mods set (`HumanoidModel.setupAnim`
mixins: Carry On holds its arms out) isn't Horizon's player model's; a carried block isn't
tested (Carry On draws it as an item).
26.3 traps found: Fabric's `UseEntityCallback` and `AttackEntityCallback` fire in the packet
listener, not in `Player.interactOn`/`attack` (the bridge fires them now); mirrored players
weren't ticked, so their `tickCount` stood still (Carry On refuses on "the same tick": it is
advanced each server tick now); Fabric's attachments are a field mixed into `Entity`
(`dataAttachments`), which client copies of a player share now (`HorizonClient.shareAttachments`).

LAN (2026-11-01): `python3 tools/lan_e2e.py --bin-dir target/fast --java-mods <jars> --check
javamobs` (a build with java-mods; the crittermod fixture jar in `<jars>`; on Linux without
vscreen `DISPLAY` and `HORIZON_E2E_HEADLESS=1`): the host summons two critters, the guest's
game shows both, the guest kills one with a sword (gone on both sides), the host kills the
other (gone on the guest's side). Passes.

Natural spawning of mods' mobs (`HorizonSpawner`, 2026-11-01): heights as
`NaturalSpawner.getRandomPosWithin` picks them, up to Horizon's WORLD_SURFACE heightmap
(`Natives.surface`), 6 tries a player a category a tick (48 for creatures every 400 ticks:
vanilla tries every chunk in range). Chunks Horizon generates (`Chunk::fresh`, also the
mods' Java-generated ones) are queued (`horizon_jvm::world::fresh_chunk`, from
`tick_creatures`) and get `spawnMobsForChunkGeneration`'s creature groups of the mods' types
(`Natives.freshChunks`, the biome's `CREATURE_WORLD_GEN_SPAWN_PROBABILITY`). A minute's count
is logged: `Java mods: the last minute's spawns of mods' mobs: N with generated chunks, M near
players` (big pack, walking: ~20 of each).

Client HUD mods (tested headless, survival): Jade's tooltip for the block looked at (box,
name, mod, item icon), AppleSkin's food overlays over Horizon's food bar.

Known gaps: extended (Fabric) menus share the server's menu with the screen (no client copy);
the recipe book lists every recipe (not the player's unlocked ones); sounds from Java's client
side are silent; textured GUI elements mods add directly with a GPU texture of their own
are drawn only when the texture is the headless GPU's (pictures in picture);
HUD frames go to each player (LAN guests too: `tools/java_bridge/lan_test.sh`, tested with
REI, Jade, AppleSkin, Tech Reborn); each player's client tick runs in turn, and the mods' state
listed in `HorizonClientState` is kept per player;
saved data is written every 10 s and when the world saves.

Mods that draw with the GPU (tested 2026-11-01 headless, Xaero's Minimap 26.6.2 alone,
llvmpipe): Xaero's minimap draws on Horizon's HUD at its place: the map of the land around in
its real colours (block textures, grass and foliage tints from vanilla's colour maps and the
biomes of Horizon's chunks, water), its frame, the compass letters, the player's arrow, other
players' dots and the coordinates under it, all through Xaero's own pipelines and shaders on
wgpu. Each player has their own minimap (single player, LAN guests, a dedicated server's
players: tested with a host and a LAN guest, and a guest on `--dedicated`).

What the headless client loads for it at its start (`HorizonClient.models`, ~4 s): the client
bootstrap (`ClientBootstrap`: atlas sources, item model types), `BlockColors.createDefault`, a
real `AtlasManager` and a `ModelManager` reloaded over the client resources with the colour
map listeners (`GrassColorReloadListener`...), and a real `FontManager` (`fonts`): the
client's Font answers with its glyphs from then on (text drawn on the GPU shows). The real
atlases join the headless GUI one in `Minecraft.getAtlasManager()`. Client levels get a
`BiomeManager` over their own chunks with the world's seed.

Map mods (2026-11-01, `bridge-gpu2`, headless, llvmpipe):
- Xaero's World Map 1.47.2 (with the minimap): M opens the full-screen map, drawn by its own
  pipelines into a window-sized target, the explored land around in its colours.
- JourneyMap 6.0.10: its minimap (circle, compass, coordinates, biome, the map in its colours,
  filling in over ~2 minutes as it maps the chunks around) draws on the HUD. J opens its
  full-screen map (the first time its About screen, Escape for the map): toolbars, the map,
  the key list (2026-11-01, `bridge-gpu3`; "Map Zoom: 512" is its normal 1:1). Its server
  side's `LoadedChunkOverlayPlugin` fails each tick (`ChunkMap` of the mirror).
- VoxelMap 1.16.13 (`bridge-gpu3`): its minimap draws (map, frame, arrow, compass,
  coordinates), through the game renderer's feature dispatcher.
- Diorama 0.6.0 (`bridge-gpu-3`): its square minimap draws the map tiles it bakes on the CPU
  from the client's chunks (512 px textures written from memory, blitted to the GUI). Its
  water (`bridge-gpu-6`): what it reads matches vanilla (plains water `3f76e4`, grass `91bd59`
  from the client chunks' biomes, blend radius 2; water's look `b2b2b2` tinted by water, grass
  `959595` by grass), and the client level's light engine now gives whole sections
  (`HorizonLight.getDataLayerData`, native `lightSection`; it was null). Its shallow rivers
  still bake grey-lilac: Diorama's "clear water" shows the bed (sand, clay) through shallow
  water; compare with real Fabric (seed 1234, near -525 64 70) before changing anything.
- XaeroPlus 2.37.2 (over Xaero's Minimap 26.6.2 and World Map 1.47.2, `bridge-gpu-4`): the
  minimap, and M's World Map (its target's pixels read at the frame's end, above). Regress
  case `xaeroplus`.
- MiniHUD 0.41.2 with MaLiLib 0.30.2 (`bridge-gpu-4`): its info lines (date, coordinates,
  facing) at the top left. MaLiLib draws from `Gui.extractRenderState` TAIL
  (`HorizonHooks.fireGui`, run at the end of the HUD), starts from `Minecraft.<init>` RETURN
  (`START_END`) and `Options.<init>` (`START_OPTIONS`); MiniHUD waits for
  `Minecraft.isGameLoadFinished()` (set once the models load). Regress case `minihud`.
- CC: Tweaked 1.120.3 (`bridge-gpu-7`): a computer's screen draws its terminal (a picture in
  picture), and typing reaches it (`horizon-ctl type` now types into mod screens too). It needed
  the client's own menu for Fabric's extended menus (`ExtendedMenuType`): made from the opening
  data the server sent (`HorizonServer.payload` keeps it, `HorizonClient.openingData`), written
  and read through its stream codec as the network would; before, their screens got the
  server's menu (CC's: "Cannot update terminal on the server"). Scene `computer`, regress
  case `computer`.
- **Map mods with more than one player: broken** (bridge-generic-maps, 2026-11-04). The
  per-mod patches that kept JourneyMap's, VoxelMap's and Xaero's client state per player were
  removed (no code into other mods' internals; licences: JourneyMap, Xaero's Minimap and World
  Map, VoxelMap Updated are All Rights Reserved; XaeroPlus MIT; MiniHUD, MaLiLib, Litematica
  LGPL-3.0). Single player and a dedicated server's single player are unchanged (regress
  `maps journeymap voxelmap xaeroplus worldmap-lazy minihud litematica maps-server
  voxelmap-server server` pass). On a LAN with a guest (`maps-lan`, JourneyMap + VoxelMap),
  the host's and the guest's minimap are both black/empty: one headless client holds every
  player's map in the mods' statics. Xaero's with a second player: its `currentSession` is
  ended at the next join (not tested on LAN). The generic fix is a client per player:
  `HORIZON_JAVA_OWN_CLIENT` (bridge-generic-2, "Each game runs its own mods' client"): `maps-lan`
  (JourneyMap) passes with it.
- Dedicated servers (`bridge-gpu-8`, `server_test.sh`): their players get the map mods as a
  LAN host's do: JourneyMap (minimap; J's About screen, then its full-screen map), VoxelMap,
  Xaero's minimap and World Map, XaeroPlus, Diorama, MiniHUD. The mirror's `ChunkMap` now has
  its (empty) chunk holder maps: JourneyMap's `LoadedChunkOverlayPlugin` failed each server tick
  on them. JourneyMap and VoxelMap together fail the same way as in the real game (both
  register `worldinfo:world_id`: VoxelMap's client code stops). Regress case `maps-server`.
- Tooltips of Java screens draw through vanilla's path (deferred, `GuiGraphicsExtractor.tooltip`,
  captured like the rest) when mods merged handlers into the tooltip's drawing
  (`HorizonGraphics.vanillaTooltips`: Legendary Tooltips, Iceberg); else as one Horizon op as
  before. Legendary Tooltips 1.6.3: centred titles and rarity borders show, and (`bridge-gpu-9`)
  its turning 3D model of tools and weapons (Iceberg's `CustomItemRenderer` draws the item
  into its own target with the feature dispatcher; the blit is a GPU image). It took the
  client's item model resolver over the mirror's models (`Minecraft.itemModelResolver`, set
  when they load), the GUI's lights from the start (`Lighting.setupFor(ITEMS_3D)`: pipelines
  reading `Lighting` skipped every draw, "uniform N unbound"), and mods' injections into
  `Minecraft.renderFrame` run once a client tick (Iceberg's render tick turns the model and
  gives back its target pool). Armour's model doesn't draw: Iceberg puts it on an armour stand
  drawn by the entity renderers, whose context has no equipment renderer (entities area).
  Regress case `tooltips` (an iron sword's tooltip).
- Models when a pack's don't fit (`bridge-gpu-9`): the mirror's baked models are baked on
  first use instead of skipped (`HorizonLazyModels`; `ModelBakeryMixin` turns `bakeModels`'
  two parallel bakes into lazy maps, `ModelManagerMixin` keeps the lazy map as the model
  set). Skipped, there was no `ModelManager` and Xaero's World Map's frame hook failed each
  frame (its minimap drew no colours). Xaero's maps bake ~1k of 36k states; the log says how
  many at each power of two. `HORIZON_JAVA_MODELS=off` forces it. Regress `worldmap-lazy`.
- Pufferfish's Skills 0.19.2 (`bridge-gpu-9`): K opens its skill tree, item icons drawn as
  oversized items on the headless GPU (`GuiRenderer`'s `oversizedItemRenderers`, GUI scale
  and feature dispatcher are now set). Four fixes useful beyond it: mods' messages between
  the halves are written and read back with their codecs (`HorizonLoop.carried`: Skills'
  payload is one class both ways, filled on one side); a player's client joins before the
  first message reaches it (Skills clears its data at the client join, after the server had
  sent it); quads at an angle (its lines between skills) are cut into rows of fills
  (`HorizonGraphics.slanted`: their bounding boxes filled black and white squares); repeated
  textures (UVs past 0..1) are drawn one blit per repeat. Mods' toasts don't fail (a
  `ToastManager`) but aren't drawn. Its category backgrounds don't show. The Default Skill
  Trees data pack needs Pufferfish's Attributes, which has no 26.3 build. Regress `skills`
  (a small tree written as config).
- Per player (host and LAN guest): **not any more** (bridge-generic-maps, 2026-11-04). The
  patches that gave each player their own JourneyMap (`MapState`/`MapRenderer` made non-final
  by `ModsPlugin`, its waypoint stores, its eight properties objects, a guest's waypoint folder
  and unsaved settings: four mixins into JourneyMap), VoxelMap (a `Map` per player, its images
  re-registered, its thread stopped) and Xaero's minimap (`currentSession` cleared before each
  join) are gone: Ludovic's condition, no code tied to another mod's internals. These mods keep
  one client's state in statics; one headless client serves every player, so on a LAN or a
  dedicated server with more than one player they share it (see Status). A generic fix would
  be a client per player (a class loader per player's client), not per-mod lists. Regress case
  `maps-lan` stays and shows it.

Left: the client tick is ~35 ms with the minimap on llvmpipe (the frame's start ~25: Xaero's
writer, the HUD ~10: the picture drawn and read back at 10 Hz).

Big pack (tested 2026-10-31, `SCENE=none`, 72 jars, 213 mods with nested ones: the 40-mod
pack plus REI, Modonomicon, Tech Reborn, Energized Power, Storage Drawers, Extended Drawers,
Traveler's Backpack, Trash Cans, Lootr, CC: Tweaked, BetterEnd/BetterNether, Universal Graves,
Serene Seasons, Mutant Monsters...): Fabric starts, 9305 recipes load, the overworld generates
through the mods with no failed chunk. Not available for 26.3 Fabric yet: EMI, Patchouli
(Modonomicon instead), AE2, Mekanism, Create, Sophisticated Backpacks (Traveler's instead).
R and U over a slot open REI's recipe view (since `bridge-generic-ui`; `/recipes` and `/uses`
are gone): vanilla crafting, Farmer's Delight cooking, Tech Reborn's compressor, Energized
Power's recipes draw.
JEI's item list, search, recipe screens and R/U work over Horizon's inventory too
(2026-11-04, `bridge-client-11`; generic since `bridge-generic-ui`).
REI's item list, search box and R/U keys work over Horizon's inventory (2026-10-31,
`bridge-rei-overlay`), and over mod container screens (2026-11-01, `bridge-client2`). REI
26.3's craftable-filter button has no icon of its own (a tinted button: none is drawn in its
code). Left: GraphLib logs
an error each tick per level (`ServerChunkCache.chunkMap` is null on the mirror); Glitchcore's
client tick wants `LevelExtractor`; Traveler's Backpack's model
reload listener fails.

SkyFactory direction (tested 2026-10-31): no sieve mod exists for 26.3 Fabric (Ex Nihilo,
Ex Deorum, Ex Compressum stop at 26.2 or NeoForge). Skyblock Infinite (void world + island
function) works: the void generates through Java, its function places the island at 6 61 6,
moves the world spawn and the player there. Simply OneBlock works from the first spawn: a
player who joins while the Java mods' load functions haven't run (or the spawn's chunks
aren't there) waits, and joins a second after them, at the spawn they set (`hold_join` in
`game/lan.rs`); its void overworld is the pack's own `dimension/overworld.json`. One Block (theoneblock) and Grid World Generator need their
world preset, which Horizon's world creation can't pick yet. Just Hammers (needs Nanite
Library, undeclared on Modrinth), CB Compressed Blocks, Forgiving Void load.
A 107-jar pack (`big3`: the big pack plus Veinminer, Just Hammers, Compressed Blocks, XP Tome,
Moving Elevators, Simple Copper Pipes, Effortless Building, Nether Chested, Easy Shulker
Boxes, Inventory Sorter, Wizards/Spell Engine/Runes, Jewelry, Archers, Mythic Upgrades,
Forgiving Void, Elevator Mod, Mob Lassos, Hang Glider, FallingTree, Towns and Towers,
Repurposed Structures, Open Parties and Claims, Better Combat): 265 mods, 20179 recipes, REI
shows them (R over a hammer). Fixed since (bridge-pack3): Open Parties and
Claims (a mirrored connection isn't "connecting"), FrozenLib capes (no downloads), Better
Combat / Spell Engine / Player Animation Lib on the client player, and the server tick that
froze while the client's models reloaded (now prepared on background workers, applied by
`tasks()`). With Trinkets installed the models' reload never ended (bridge-gpu-1 fixed it:
Trinkets Updated's client init is a Yumi entrypoint run from Minecraft's constructor at its
options, which never ran, so its client manager, whose preparation the models wait for, was
never registered).

A 137-jar pack (`big4t`, 2026-11-01, bridge-pack4: `big3` plus RFTools Base/Builder,
Entangled, Hopper Gadgetry, Copper Hopper, Item Collectors, Simple Magnets, Paladins, Rogues,
Construction Wand, Enderscape, Heart Crystals, Disenchanting Table, Cooking for Blockheads,
Echo Chest, Inventory Management, Storage Delight, Items Displayed, Beautify, Promenade,
Villages and Pillages, Xaero's World Map, Tectonic, Advanced Netherite, Combat Roll): 231,940
block states, 12,929 items; Java starts, the overworld generates through the mods (Tectonic,
Biolith, TerraBlender) with no failed chunk, and the player joins on land 255 s after the
start (`regress.py --pack … bigpack`). Fixed for it: Biolith's biome noise (every Java chunk
failed: its seeding is a `@WrapOperation` in ServerLevel's constructor, now run), the world
spawn's search starts from the mods' generator's origin (Tectonic's land: the vanilla origin
was sea), Puzzles Lib's join (the client player has its connection), Combat Roll's
`RollManager` (mixin field initializers run on mirror objects). Left: Lithostitched's
`wrapNoiseRouter` constructor wrap fails (it wants a registry the mirror's chunk map lacks;
no worse than before, when it never ran); CCA's scoreboard client tick wants a scoreboard;
the vanilla HUD's vehicle hearts (`this.variables` null).
Item Alchemy is out: its library bundles CompatDatapacks, which rewrites every recipe's
`result.item` to `id` and breaks BCLib's recipes (BetterEnd's alloying and infusion: the
registries don't load), a conflict on real Fabric too; Item Alchemy's own recipes need that
rewrite.

Startup (2026-11-01, bridge-worldgen-1; a 110-jar pack like `big4t`'s, 2 cores, content
already made): 193 s to the join before, ~120 s now. Two things: the content root (the
mods' assets and tables merged over the game's, ~40 s to build) was built again on every
run whose working folder differed (its stamp named files through the folder's
`.horizon-cache`, a link to the kept cache in `launch.sh`/`regress.py`): the cache's real
path is used now (`mods::horizon_cache`), so a kept content root is reused; and Java and
Fabric (~75 s of bootstrap, entrypoints and data for this pack) start as soon as the content
root is there (`java_mods::start_early`), beside the rest of the resources' load, instead
of after the window's first frame (`HORIZON_JAVA_EARLY_START=0`: as before). Not beside the
content root's build: the two at once outgrew this sandbox's 6 GB. Left after Java is up
(~35 s here): the overworld's start (~10 s, mostly Fabric's biome modifications asking
TerraBlender's `possibleBiomes` per biome, mod code), then the spawn's chunks. In this pack
Biolith and Lithostitched patch MaterialSystem, so the faster surface walk stays off.

Memory is what limits a pack's size: Horizon keeps several per-state tables (client and its
server thread) and the content build exports every state. The full 157-jar set (`big4`) has
577,094 states (Rechiseled 111,669, Diagonal Fences 86,528, Stoneworks 53,510, XNet 29,207
from 12 blocks, the Macaw's sets ~70,000): it needs more than this sandbox's 8 GB (the 137-jar
pack peaks at 5.8 GB with its content made in the same run). A 32 GB PC runs it. The content
root's per-state tables now keep new rows as text and stream to disk (they took over 6 GB as
trees: `J::Raw` in `mod_content.rs`).

Where the memory goes (2026-11-01, bridge-pack-5; `HORIZON_MEM_LOG=1` logs it after each load
stage, `examples/state_memory_probe` measures a content root's tables; RSS kept, the heap
trimmed first). Vanilla / the 577k-state pack: the block registry and before it 181 / 888 MB,
the state models +28 / +665 MB, block textures +30 / +175, block facts +39 / +512, pack tables
+28 / +176, items +22 / +243, the renderer +458 / +1044 (llvmpipe: GPU memory is system memory
here; on a GPU most of it is VRAM), loot +9 / +216, worldgen data +33 / +418, the generator
and streaming +25 / +326. Fixed since: `level::Behaviours` streams `gameplay-facts.json` (was a
3.4 GB peak); upstream's `BlockProperties` (interned, ~350 B a state now); loot tables parsed
on first use (pack-6: the loot stage +1 MB at 208k states); `block-behaviours.json`'s tree
(~380 MB at 577k) dropped after the facts load and read again only when the behaviours'
snapshot misses; baked faces keep a `BakedQuad`, not the `ModelQuad` with its sprite name
(model catalog at 577k: 579 → 385 MB; `examples/texture_memory_probe` prints what each part of
the render assets keeps). State of 2026-11-02 (pack-6): the 155-jar pack with no JVM
(`HORIZON_JAVA_HOME=/nonexistent`) opens its world at 3.76 GB and the player joins; with the
JVM (~2.5 GB for 155 jars) it still passes this sandbox's ~6.2 GB cgroup limit, of which
llvmpipe's textures are ~1.2 GB: a real GPU should fit in 8 GB, untested. Left: render assets
683 MB (texture array 8043 layers at 64 px: 256 MiB budget; sprite pixels 125 MB), the
remaining catalog (~165 MB beyond faces: choices and allocation slack). Dead ends: heaptrack's
interpreter runs out of memory on this game (tens of millions of live allocations).

The JVM's share (2026-11-02, bridge-pack-7; measured with `HORIZON_JVM_OPTIONS=
"-XX:NativeMemoryTracking=summary -XX:+StartAttachListener"` and `jcmd <pid> VM.native_memory
summary` / `GC.class_histogram`: `-Xrs` turns off the attach signal, `StartAttachListener`
opens it at start). 107 jars: heap 1.5 GB committed with 555 MB live, metaspace 233 MB, GC
structures 75 MB, code 68 MB, symbols 49 MB. 148 jars (577k states): ~1.05 GB live by the
join, of which the mirror's block states with their shapes ~180 MB; two things grew it past
the default max heap (a quarter of RAM: 1.5 GB here), `OutOfMemoryError` in mods' worldgen:
- **DataFixerUpper's rule chains.** Mods' structure templates carry ~100 DataVersions (10k
  templates in this pack, mostly Dungeons and Taverns and Repurposed Structures); DFU builds a
  chain per old version and keeps it with its rewritten types (`DataFixerUpper.rules`,
  `Type.REWRITE_CACHE`, `Fold`'s two maps): 250 MB a few minutes into worldgen.
  `HorizonDataFixer.check` (server tick and Java worldgen's proto and decorate, every 5 s at
  most) clears them all once more than 6 are kept; the structure manager keeps fixed
  templates, so a chain is rarely needed again. Regress `fixer`.
- **The mirror's baked models** (`HorizonClient.models`, with a GPU): ~700 MB at 577k states
  (vanilla `BakedQuad`s, their `float[]`s, Fusion's connected-texture quads for Rechiseled).
  Horizon draws with its own; this copy is for the mods that read models (map mods' colours).
  It's skipped when it wouldn't fit: block states × 1.2 KB beyond 85% of the max heap, counting
  the old generation and survivors as live; the log says so. `HORIZON_JAVA_MODELS=on|off`
  forces it.
The JVM now starts with G1 giving back what it doesn't use (`MinHeapFreeRatio=10`,
`MaxHeapFreeRatio=30`, a periodic cycle every 30 s) and string deduplication;
`HORIZON_JVM_OPTIONS` (space-separated) comes last and wins (`-Xmx3g` for more heap). Result
here: the 148-jar pack with Java loads, joins (peak ~5.9 GB at the join, was killed before it)
and plays ~3 minutes, then passes this sandbox's ~6.2 GB as chunks load (RSS 5.7 → 6.0 GB; the
heap stays at 1.5 GB). llvmpipe's ~1.2 GB of textures are in that, so on a GPU it should hold
in 8 GB (untested). Next: what grows in-world (chunks, meshes, LOD on the Rust side; Java's
metaspace as mods' features first run), and Fabric's open mod jars (`ZipFileSystem` index
nodes: 290k, ~25 MB with names).

- **Mirror objects made without their constructors get empty fastutil maps too**
  (`HorizonServer.emptyCollections`): `ChunkMap.updatingChunkMap` was null, and Fabric API's
  block entity attachments (`BlockEntity.fabric_markChanged` → `getUpdatingChunkIfPresent`)
  threw on every McJtyLib machine (XNet, RFTools) when its screen opened.
- **A block declaration over 16 MiB drops the whole mod's content.** `horizon-mods`' fence
  (`MAX_FILE`) is per file; a block with its own states lists each state's properties and
  shape (`horizon-port-pack` carry). ALA Industrial's pipes have 76,832 states each (26.7 MB
  pretty, 18.8 MB compact, 49 distinct shapes), so its 234 blocks were unknown. Fixed
  (bridge-pack-8): from 1,024 own states the declaration has a `state_table` instead of
  `states` (`oracle::compact_states`: each property's values and the distinct shapes once, a
  row of indices `[shape, value, ...]` per state, `light`/`random_ticks`/`default` by row;
  `horizon_mods::content::StateTable` reads it back as the same `State`s). A 16k-state pipe
  takes under a quarter of the array's bytes; ALA's should be ~1.7 MB a block. Content
  `FORMAT` 14. Not a bigger fence: those pipes still add ~400k states (memory, below).

Mods' sounds and particles (2026-10-31): a mod's sound event plays by name (its
`sounds.json` and `.ogg` files are carried into the content mod, which Horizon's sound
registry reads). A mod's own particle type goes as `horizon:java_particles` (JSON: the type's
name and the packet's fields; `HorizonPackets.modParticles`), and the game draws it with the
type's sprites (`assets/<ns>/particles/<name>.json`, textures in the HUD atlas as
`<ns>:particle/...`): `ParticleEngine::add_mod`, a `SingleQuadParticle` animated by age,
moving as sent. Entity events whose particles vanilla's client makes (taming hearts and
smoke, breeding hearts, villager hearts, anger, happiness and sweat, spawn poof, teleport
portal) are sent as vanilla level particles, for the classes that define them. Tested with
the critter fixture: its squeak (`heard` in `horizon-ctl status`) and sparkles when hit.

### The regression run

Before merging into `java-bridge`, play every scene that once worked:

```sh
python3 tools/java_bridge/regress.py --jars ~/hz-test/jars             # all (~10 min)
python3 tools/java_bridge/regress.py --jars ~/hz-test/jars pot rei     # some
python3 tools/java_bridge/regress.py --java-only --jars …             # after a Java-only change
python3 tools/java_bridge/regress.py --list
python3 tools/java_bridge/regress.py --code-check --jars …            # only the check below
```

**No mod's packages in the bridge's code** (`nomodcode`, Ludovic's second condition): every
run starts by looking through the bridge's code (`crates/horizon-jvm` without its test fixtures,
the game's `java_*.rs`) for a mod's package root (`me.shedaniel`, `journeymap`, `com.simibubi`)
outside comments, in strings too (`Class.forName`, mixin targets, `"xaero/common/…"`). One found
fails the run. The roots: `KNOWN_PACKAGES` in regress.py, plus every jar in `--jars`/`--pack`
(its `fabric.mod.json` entrypoints and mixin packages, nested jars too). A mod's public API the
bridge stands in for when the mod isn't there goes in `ALLOWED_APIS` with a reason (today only
Mod Menu's `com.terraformersmc.modmenu.api`, as the bridge stands in for Fabric API). A comment
may name a mod; code may not: do it the vanilla way, for every mod.

`--jars` is one folder of Modrinth jars (Farmer's Delight, Tom's Storage, Jade, AppleSkin, REI
with Architectury and Cloth Config, Biomes O' Plenty with TerraBlender and GlitchCore, One
Block with CodxLib, Fabric API); a case missing one is skipped, the fixture mods are built. Each case checks what
horizon-ctl reports and the log, and prints `pass`/`FAIL`; screenshots and logs are in
`/tmp/hz-regress/<case>/` (a `look` line names what to see on one). A new scene that works gets
a case in `CASES`. A case's `config` writes mods' config files before the game starts
(`launch.sh`'s `CONFIG`: a folder copied over the game's `config/`). `skyblock` and `oneblock` take Skyblock Infinite's and Simply OneBlock's jars.
`lost` is the lost-block repro (gold blocks set right after spawn, read back 25 s later).
`bigpack` plays a whole pack: `--pack <mods folder>` (a `config/` beside it is the mods'
configs), and checks that Java starts, the registries load, no Java worldgen chunk fails and a
player joins within `--join-within` seconds (default 900; the first run of a set of jars also
makes its content, minutes; it is kept in `/tmp/hz-regress/pack-cache`).

### From a normal install (no checkout)

A launcher-installed version runs the Java mods as it is, when built with `java-mods`: the
bridge's jars are in the game binary; the install is found above `--resources`
(`<version>/.local/horizon-client/<v>` → `<version>`, with research-lock.json; `HORIZON_ROOT`,
then a working folder holding research-lock.json, come first), and its preparation left the
game jar and libraries in `<version>/.local/minecraft/<v>/` (jars/, libraries/, manifest.json);
the bridge's work folder is `<version>/.local/bridge` (Fabric Loader, its libraries and Fabric
API, downloaded once, SHA-256 checked). Java: `HORIZON_JAVA_HOME` (the launcher sets it), `JAVA_HOME`,
the PATH, installed JDKs, Minecraft's and the Horizon launcher's runtimes (`<launcher data>/minecraft/
runtime`, `runtime.rs`). **The game downloads no Java** (bridge-launcher-only): the launcher
fetches Mojang's runtime for 26.3 (`java-runtime-epsilon`, `java::install`) when an instance with
the Fabric switch plays and no Java 25 JDK is here, only past the strict owner gate
(`java_variant::ensure`). Without one, the game downloads nothing: it says why and stops (exit 78,
as any start of the mods that fails). Fabric's downloads trust the public roots and the system's CA bundle
(`SSL_CERT_FILE` or the distribution's file: a TLS-inspecting proxy or antivirus the system
trusts) and use `HTTPS_PROXY`.

**A build without the game** (a release build: no Minecraft jar on the build machine) carries
the platform mod's sources instead of its jar (`build.rs`: no prepared game, or
`HORIZON_BRIDGE_PLATFORM=source`), and the player's first start builds it with the Java the mods
run on (`platform.rs`: `javac` of that Java home, which Mojang's runtime has; ~11 s, 116 sources),
against their own game jar, its libraries, Fabric Loader and Fabric API, into
`<work>/platform/<key>/` (the key: the sources, the game jar and the bridge jar). A Java with
no `javac` (a JRE) is told to use a JDK (the launcher picks only Java homes with `javac`). `game-build.yml` builds Linux and Windows variants with
Java mods when its opt-in `java_mods` input is set (assets `<asset>-java`, the game built with
`HORIZON_BRIDGE_PLATFORM=source`); nothing calls it with that yet (release and nightly signing
are the maintainer's). Tested headless: `HORIZON_BRIDGE_PLATFORM=source` build, install_test (the Java the launcher
would pass): the platform built, Farmer's Delight's pot screen opens.

**The launcher's Java mods tab** (Home, beside Mods: `launcher/src/java_mods_page.rs`, core in
`java_mods.rs`): shows for an instance whose game folder has `mods-java/`; lists the jars with
their Fabric names and versions, Add (file picker or dropped jars; a jar without
`fabric.mod.json` is refused), Open folder, Remove (to the trash, confirmed), and which mods
failed in the last game (its `logs/latest.log`: the "[Java mods] <mod> failed in …" lines, a start
that failed) on their rows. CLI: `horizon-launcher java-mods list|add <jar>|remove <name>|failed
[--instance <name>]`.
**Copy bug report** (the tab's buttons, `launcher/src/java_bug_report.rs`; CLI `horizon-launcher
java-mods report [--instance <name>]`) copies one Markdown block for an issue at
iaxivers/horizon-java-bridge: launcher version, the version's Java mods build (its `.installed`
marker's commit), OS, GPU (the log's `Horizon renderer:` line), the switch, Java home + version,
the jars and sizes, the log's errors (`Java mods`, `[java]` ERROR/WARN, `Exception`, `Caused by`;
deduplicated) and last 300 lines (1500 at most), and the last run's crash report and
`hs_err_pid*.log` (150 lines each). Clipboard only. Redacted: every string in `accounts.json`,
emails, UUIDs, token-shaped words (`eyJ…`, 100+ chars), the home folder and user name as `~`.
"Open logs folder" beside it opens the instance's `logs/`.

`tools/java_bridge/install_test.sh` checks it: a scratch install laid out like a launcher
version (binary, research-lock.json, the preparation's folders linked from the checkout), the
game started as the launcher starts it (in its game folder, `--resources` only; no
`HORIZON_ROOT`, Java variables or Java on the PATH, `HORIZON_JAVA_NO_SYSTEM=1` so installed
JDKs aren't looked at), so Fabric's jars are downloaded into the scratch folders; the Java is
the one the launcher passes (`HORIZON_JAVA_HOME`), and `NO_JAVA=1` checks that the game, with
none, downloads nothing and says so. The launcher starts the game that way (`client_command`: the game folder,
`--resources`), plus `--java-mods` when the instance's switch is on (below); `mods-java/` goes in
the game folder (HORIZON_HOME). `OFF=1` runs it without `--java-mods`: no Java may start.

### The Fabric switch: off by default (2026-11-04, bridge-upstream-prep)

For upstream (Ludovic asked for the bridge in the main repo), Java mods are **off unless the
player turns them on**, per instance:
- **The game** (built with `java-mods`) starts Java only when opted in: `--java-mods` from the
  launcher with an owner record the game confirms (`HORIZON_JAVA_MODS_OWNER`, "Ownership"
  below), or, in a `java-mods-dev` build only, `HORIZON_JAVA_MODS=1` (developers' scripts, servers). Every read of `mods-java/` goes through
  `java_mods::mod_jars()`, which has no jars otherwise (one log line says how many it left off).
  So no Java start, no Java 25 download, no Fabric download without the switch. The tools set it:
  `launch.sh`, `lan_test.sh`, `server_test.sh` (`HORIZON_JAVA_MODS=1`), `install_test.sh`
  (`--java-mods`, as the launcher). **Trap:** a game started by
  hand (or by a script of your own) with jars in `mods-java/` and no opt-in runs plain: the log's
  "Java mods: off (N jars in mods-java)" says so.
- **The launcher** (`launcher/src/java_variant.rs`): Launch… on Home's Instance row has a
  "Fabric 26.3 mods (beta)" switch (Horizon instances, Linux and Windows: game-build.yml makes no
  macOS `-java` build), saved in `instances.json` (`java_mods`). On: the version's Java mods
  build (`<asset>-java`, signed like the plain one) is installed in the version's `java/` folder,
  beside the plain build, so it plays with the version's prepared files; Play starts
  `java/horizon-client … --java-mods` (`instances::apply` → `java_variant::switch_command`), and
  Home shows the Java mods tab. A missing build is fetched when the switch turns on, after each
  install or update of a version an instance with the switch plays (`java_variant::follow`), and
  before Play; when it can't be had, Play refuses and says why instead of starting the plain game.
  Off: the plain build, no tab, `--java-mods` never passed, whatever `mods-java/` holds.
- **Home (glass look, 2026-11-05):** upstream's redesign dropped Launch…, so the switch is a
  chip on Home's play row (between the version picker and the puzzle, `glass_home.rs`
  `glass_java_mods_chip`) and an item in Play's ⋯ menu, both on `flip_java_mods`. Lit in the
  accent while on; dimmed with the reason (`java_mods_lock`: no `-java` asset for this platform,
  no signed-in owner) while it can't be turned on; turning off always works. Its second line
  (`java_mods_state`) shows On/Off, the build's download or its error.
- **An imported build** (`import-build`, `imported-…` versions) is its own Fabric mods build:
  with the switch on, the launcher plays its program with `--java-mods` (nothing to download;
  `use-build` still overrides it). **Trap:** a build imported before 2026-11-04 has no opt-in and
  runs `mods-java/` whatever the switch says: import a fresh one.
  It also needs the version's `.local/minecraft/26.3/jars/client.jar` (Fabric's game jar): an
  import copies the selected version, which may not have it ("Fabric can't start: no …
  client.jar"): Prepare again, or copy `.local/minecraft` and `.local/bridge` from a version
  that played the mods.
- **Trap: a launcher you build yourself replaces itself with upstream's** (release profile:
  `self_update_allowed`), which has no switch and no `java-mods` command, while cargo still
  says it's fresh. Build it with `HORIZON_LAUNCHER_NO_SELF_UPDATE=1 cargo run --release`
  (compile time; the variable change makes cargo rebuild).
- **CLI:** `horizon-launcher java-mods on|off|status [--instance X]`, and
  `java-mods use-build <horizon-client> [--version TAG]` for a Java mods build of your own
  (upstream's releases have no `-java` assets; the fork's `java-mods` release does: below). `instance list --json` has `java_mods`.
- **Tested headless:** `tools/java_bridge/launcher_switch_test.py --launcher <horizon-launcher>`
  (stand-in programs: off by default, plain program with jars in `mods-java/`; on with no build:
  Play refuses; `use-build`; off again). Its Play checks stop at the owner gate in a scratch home
  (no signed-in account) and say so. The window's Play was checked under Xvfb with the developer
  option (`settings.json` `"dev_offline": true`) and `HORIZON_LAUNCHER_PAGE=launch` (Launch…
  open): off → `horizon-client --resources …`, no Java mods tab; on → `java/horizon-client
  --resources … --java-mods` and the tab; off → plain again.
- **The game, as the launcher starts it** (`install_test.sh`, Farmer's Delight in `mods-java/`):
  `OFF=1`: the world plays, the log says "Java mods: off (1 jars in mods-java)", no `libjvm` in
  the process, no `.local/bridge` (nothing of Fabric's downloaded) and no Java runtime fetched;
  with `--java-mods`: Fabric and Farmer's Delight load, `/give @s farmersdelight:cooking_pot` works.

Upstream's releases still carry no `-java` assets (the maintainer's call); until they do, the fork
publishes its own (below).

### Fabric mods releases (2026-11-05, bridge-release-1)

Testers no longer build: the fork publishes **the `java-mods` pre-release** on iaxivers/horizon-java-bridge
(`.github/workflows/java-mods-release.yml`), and the fork's launcher installs from it.
- **Opt-in:** runs only in iaxivers/horizon-java-bridge, only when asked: push the commit to the branch
  `java-mods-build` (`git push -f origin java-bridge:java-mods-build`), or a tag
  `java-mods-build-<x>` on it, or `gh workflow run "java mods release" --ref java-bridge -f dry_run=false` (dispatch needs the
  file on the default branch; the tag doesn't). Without the signing key it is a dry run.
- **What it builds,** on GitHub's runners (no Minecraft jar there): the game with `java-mods` and
  `HORIZON_BRIDGE_PLATFORM=source` (`horizon-{linux-x86_64,windows-x64}-java`, only
  `horizon-client` + licences: the launcher plays it with the version's own tools and prepared
  files; the package step fails on any other file), and the fork's launcher
  (`horizon-launcher-{linux-x86_64.tar.gz,windows-x64.zip}`) built with
  `HORIZON_LAUNCHER_NO_SELF_UPDATE` (compile time: `self_update_allowed`), so it never replaces
  itself with upstream's, which has no switch. `java-mods.json`: the commit and the preparation
  format (`horizon_prepare::OUTPUT_VERSION`) the build reads.
- **Signed like the Nightly,** with the fork's own key for the `java-mods` channel (message
  `horizon release java-mods`): secret `JAVA_MODS_SIGNING_KEY`, public half in
  `launcher/java-mods-keys.txt` (never upstream's release-keys.txt). The sign job checks out only
  `tools/ci/sign_release.sh`; publish verifies as launchers do, then replaces the release.
  Set up once: `cargo run -q -p horizon-release-sign -- keygen --public-out java-mods.pub | gh secret
  set JAVA_MODS_SIGNING_KEY -R iaxivers/horizon-java-bridge`, commit java-mods.pub's line in the keys file.
  Sign-in needs the repository variable `HORIZON_MSA_CLIENT_ID` (as launcher.yml).
- **The launcher** (`launcher/src/java_release.rs`, `java_variant::install_from_fork`): when the
  version's own release has no `-java` build, past the owner gate, it takes the fork's: the
  manifest's checksum, the archive's, both from SHA256SUMS signed by a key in java-mods-keys.txt,
  and only if the installed version prepares the manifest's format (`fits`; else "install a
  newer/older version"). The install marker (`java/.installed`) names `iaxivers/horizon-java-bridge@<commit>`.
  An installed build stays as it is (2026-11-06: no more check for a newer one at each Play): a new
  version gets its own build, and `horizon-launcher java-mods update [--version T]` downloads the
  newest that fits on request (`java_variant::update`, past the owner gate).
- **Tested:** launcher unit tests `java_release::` (signed by a listed key for the channel; other
  keys, the nightly channel, no key, a swapped manifest refused; format check). The workflow on
  GitHub, dry run (branch `java-mods-build`, run 41995308602, ~45 min): both builds, both
  launchers, signed with a throwaway key and verified; the Linux `-java` artifact played headless
  here as the launcher installs it (`GAME=<it> tools/java_bridge/install_test.sh`): its platform
  mod built from sources against the local 26.3, Fabric and Farmer's Delight loaded,
  `/give @s farmersdelight:cooking_pot` held. Not tested: the launcher's download from a published
  release (none until the key is set up), Windows' `-java` playing.
- **Trap (release profile, llvmpipe):** `horizon-ctl … status` polled while a *fresh* game folder's
  first start loads (install_test's `--wait`) segfaults the release build right after "control
  socket" (no hs_err; the fast build doesn't; a direct start without polling, or the second start,
  plays). Under gdb the SEGV shows in `vkEnumeratePhysicalDevices` (Mesa's device-select layer)
  with the JVM up. Harness-only as far as seen (players don't poll the socket); not fixed. Work
  around: start once without `--wait`, then test.

### First-run traps (2026-11-06, bridge-firstrun)

A review of a first Play with Fabric mods on; what changed:
- **Play stops on the real reason** (`java_mods_page::ensure_at_play` → `Msg::JavaPlayStopped`
  → `client_error`) when `java_variant::ensure_with` fails, instead of logging it and failing
  later in `switch_command` with a message that never helped. Its download shows on Play's bar
  (`Msg::JavaPlayProgress`, "Downloading") and its lines (Java 25, game files, the build) in
  the game log.
- **One download at a time:** `java_variant::BUILD_LOCK` around `ensure_with`, `follow` and
  `use_build` (the switch and Play at once wrote the same `.java.partial`).
- **Launcher contract:** `java_release::CONTRACT` (1), written as `contract` into
  `java-mods.json` by java-mods-release.yml (read from that line); a manifest without it is 1.
  `fits` refuses another contract ("needs a newer launcher: download it from <release page>").
  **Bump it** with any change to the game's opt-in/owner check that an older launcher can't
  meet (the gate owner's call): Play's refresh of a fork build then leaves the old build
  playing and says why, instead of installing one that refuses the mods.
- **Wrong-version jars:** `java_mods::runs_on` reads `depends.minecraft` (Fabric's predicates:
  `>=`, `<`, `~`, `^`, `x` wildcards, AND by spaces, OR by arrays; unknown forms never flagged);
  the tab and Add name a jar that excludes 26.3, and two jars with one mod id. `failures_in`
  keeps Fabric's "- Mod '…'" report lines (no log prefix), so the tab marks the mod.
- **A player's Fabric API is skipped** (`bridge.rs` `run_jars`, by id or `fabric-api*` name),
  with a log line; Fabric then gets an explicit jar list (no folder scan) whenever jars were left
  out. The pinned one always loads.
- **Player-facing messages** name player actions (Prepare again on Home, download the launcher)
  instead of `horizon-launcher …`/`horizon-prepare` commands; `has_game_files` also wants a jar
  in `libraries/client/`, so the launcher re-prepares a version missing them.
- **A Fabric jar dropped on the window** (`import::Dropped::JavaMod`) goes into the selected
  instance's `mods-java/` with the switch on, else says to turn it on.
- Tested: launcher unit tests (`java_variant::`, `java_release::`, `java_mods::`, `import::`),
  clippy. Not tested on a real machine: the window's Play stop/progress and the drop.

### Launcher only (2026-11-04, bridge-launcher-only)

Players play the Java mods only through Horizon's launcher, signed in (Ludovic's first condition
for linking the fork). Gone: `crates/horizon-play` (its CI step, `xtask package`'s copy, PLAY.txt's
lines, regress `prism`), and with it Prism's wrapper command, `--instance` offline play and its
download of the server jar. What holds now:
- **Play with the switch on needs the strict owner gate:** the window's developer option
  (`dev_offline`) lets no Fabric instance through (`start_client`: `offline_without_owner` is off
  when `java_mods`); the CLI's and MCP's Play always ask the gate (`backend::client_as`).
- **No download before that gate** (`java_variant::owner_signed_in`, `account::launch`, as
  `backend::download_for_owner`): the Fabric mods build (`ensure`, `follow` after an install, the
  switch turning on, `java-mods on`) and Mojang's Java 25 (`ensure_java`) come only for an account
  that owns the game; otherwise "Sign in … first: Fabric 26.3 mods download nothing before that."
  `use-build` (a build of your own) downloads nothing and needs no sign-in.
- **The game downloads no Java** (`find_or_fetch_java_home` is gone): `switch_command` passes the
  launcher's Java 25 as `HORIZON_JAVA_HOME`. The server jar always comes from the player's own
  install (`horizon-prepare`, as upstream).
- **Developers' runs stay:** `HORIZON_JAVA_MODS=1` (a `java-mods-dev` build) on the game binary (horizon-ctl,
  regress, launch.sh), as Ludovic's own harnesses run the plain game.
- **Tested headless:** `tools/java_bridge/launcher_switch_test.py` (no owner: `java-mods on` and
  Play download nothing and say "Sign in"; `use-build` works signed out). The window under Xvfb
  (clicks through python-xlib's XTEST), `settings.json` `"dev_offline": true`, stand-in programs:
  a Fabric instance's Play (Update's ⋯ > Play without updating) starts nothing and the "Sign in to
  play" screen comes up (the option hides it only for plain instances, `owner_shown`); with no
  Java 25 here, the Console tab says "Sign in … first: Fabric 26.3 mods download nothing before
  that" and `minecraft/runtime` stays empty; switch off, the same Play starts the plain program.

**A modpack's resource packs** (game side): `resourcepacks/` and `options.txt` beside
`mods-java/` load with it; regress `packs` checks it.

**When the mods can't start,** the game exits with code 78 (`NOT_STARTED_EXIT`) instead of going on
without them, and its log says why: a player would take the world for modded, and a modded world
opened without its mods loses their blocks and items. `HORIZON_JAVA_OPTIONAL=1` goes on without
them, as it used to.

### Ownership and licences (2026-11-05, bridge-ownership-1; owner record 2026-11-06, bridge-gate-2)

The maintainers' conditions, and what holds them:
- **(a) Fabric Loader, Mixin, Sat4j, ASM and Fabric API are never shipped, vendored, copied or read
  by Horizon's builds.** The repository has none of their files (`check_no_fabric.py --tree`; Python
  test `tests/test_check_no_fabric.py`). A release build (`HORIZON_BRIDGE_PLATFORM=source`, as
  `java-mods-release.yml` and `package_java_variant.sh` build) carries **the bridge's own
  sources too** (`horizon-bridge-src.zip`), not only the platform mod's: build.rs fetches and reads
  nothing of Fabric's, and both steps fail if `.local/bridge` appears on the build machine or an
  archive holds a class or jar of theirs (`check_no_fabric.py <archives>`). On the player's machine,
  the first start fetches Fabric's jars from maven.fabricmc.net (SHA-256 pinned, unchanged) and
  compiles the bridge (`platform::ensure_bridge`, 7 sources, a few seconds) then the platform mod against them
  (`work/bridge/<key>/`, `work/platform/<key>/`). A developer's build (no `source`) still compiles
  against the jars it fetches into the checkout's `.local/bridge/lib`, on the developer's machine.
  The release build is **Fabric only**: the NeoForge and 1.12 prototypes are behind
  `java-mods-prototypes`, which no release enables.
- **(b) Nothing plays without owning the game, nothing of Mojang's is downloaded for a non-owner.**
  The game downloads nothing of Mojang's, ever (no Java, jars or assets: the game jar is the player's
  own preparation). Fabric mods run only when `horizon_jvm::gate` says so, and environment variables
  alone never make it say so:
  1. **The launcher's owner gate** (`account::launch`, the same check plain Play and
     `download_for_owner` use) asks the Minecraft services with each account's token whether it
     owns Minecraft: Java Edition; the last "yes" is kept in memory with its time
     (`account::LAST_OWNER`).
  2. **The owner record:** with the switch on, `java_variant::switch_command` writes, from a "yes"
     at most 5 minutes old, the owner's session file (`account::write_owner_record`: profile UUID
     and Minecraft access token, 0600, random name, the same writer and reaper as
     `--session-file`) in `<launcher home>/java-mods-owner/`, and passes its path in
     `HORIZON_JAVA_MODS_OWNER` beside `--java-mods`. No fresh "yes" (offline): Play refuses ("need
     the Minecraft services to confirm …; turn Fabric 26.3 mods off to play offline").
  3. **The game checks it itself** (`gate::decision`, on a thread from the process's first line,
     `entry.rs`): the record is a regular file, its owner's only, written in the last 10 minutes
     (`RECORD_LIFE`: a pre-launch command or wrapper may run in between; the launcher's reaper
     keeps it as long); read once and deleted; its UUID is an
     account of the launcher's `accounts.json` beside the record's folder; then the game calls
     `GET /minecraft/profile` (must be that UUID) and `GET /entitlements/mcstore` (must hold
     `game_minecraft`/`product_minecraft`) on api.minecraftservices.com with the token, as the
     launcher's `owns_game` does. Only a token Mojang issued to an owner passes. 401/403/404 is a
     "no"; 429, 5xx and network errors are "no answer". The launcher passes its proxy setting
     (`proxy::spec_for`) as `HTTPS_PROXY` to the game, which the check and Fabric's downloads use.
  Why not read the launcher's stored proof (`Secrets.owns`) directly: it lives in the OS keychain
  (Secret Service, Keychain, Credential Manager), which the game can't read without a keychain
  stack of its own, and any local file or variable a player can write as well. The token is the
  one proof the game can check without new crypto. **Trade-off:** Fabric mods need the services
  reachable at each start (plain Horizon still plays offline). Anything else runs no mods and
  fetches nothing, and says why in the log/stderr: "Fabric 26.3 mods run only when Horizon's
  launcher starts the game … (no owner record from the launcher | the owner record is missing,
  too old or readable by others | … not signed in to the launcher | the Minecraft services did not
  confirm … | … could not be reached …)". With jars in `mods-java/` the game then **stops**
  (`not_started`, exit 78, "Java mods: not started: …", which the launcher's Java mods tab shows):
  a modded world opened without its mods would lose their blocks and items. With no jars it plays. The launcher
  fetches the Java mods build and Mojang's Java 25 only past `owner_signed_in` (`ensure`, `follow`,
  `update`, `java-mods on`). A determined user can still patch a binary; these checks keep Horizon
  from offering a path.
  **Developers:** `HORIZON_JAVA_MODS=1` (regress, `launch.sh`, `lan_test.sh`, `server_test.sh`,
  `lan_e2e --java-mods`) and `HORIZON_JAVA_MODS_SERVICES=<url>` (a stand-in services, `install_test.sh`'s
  `tools/java_bridge/fake_services.py`) exist only in `java-mods-dev` builds.
- **(c)** Startup and frame time with the bridge off: [java-bridge-perf.md](java-bridge-perf.md),
  "The bridge switched off" (`tools/java_bridge/perf_off.sh`).
- **(d) A warning first:** turning "Fabric 26.3 mods (beta)" on in the window (Home's chip, Play's
  ⋯ item, Launch…'s switch: all `flip_java_mods`) first shows a confirmation
  (`java_mods_warning`, texts `java_variant::WARNING_TITLE`/`WARNING`, through `tr`): mods are
  Java programs with full access to the computer, files and the Minecraft account; only add mods
  you trust; beta. "Turn on Fabric mods" saves `java_mods_warned` in the instance (asked once per
  instance); Cancel or a click outside leaves it off. `java-mods on` prints the same text once.
- **Checks:** unit `horizon-jvm gate::` (hand-set variables and the old word `1` → no mods, before
  any services call; stale, future, world-readable, unknown-account records refused and deleted;
  the services' answers; `services_check` against a localhost stand-in: owner, Bedrock only,
  another profile, refused token, 503, nothing listening; `--features dev`: the opt-in);
  launcher units `account::…the_owner_record_needs_a_fresh_answer…`,
  `java_variant::…the_command_becomes_the_java_builds…` (the record's path, never
  `HORIZON_JAVA_MODS`); `tests/test_java_mods_release_features.py`; regress (install_test.sh, a
  scratch install): **`direct`** (no record), **`forged_word`** (`HORIZON_JAVA_MODS_OWNER=1` by
  hand), **`forged_record`** (a hand-written record + accounts.json, made-up token, Mojang's real
  services): the message, no `.local/bridge`, no Java runtime, the game stopped, no world opened; and
  **`owner`** (a record whose token the stand-in accepts: "confirmed by the Minecraft services",
  Fabric fetched into the install, the fixture loads, the record deleted).
- **Trap:** a script of your own that starts the game with `--java-mods` runs no mods: use a
  `java-mods-dev` build with `HORIZON_JAVA_MODS=1`, or start it from the launcher.

### Windows

Builds with MSVC Rust and plays on Windows 11: Farmer's Delight in a window, and a 40-mod pack
headless. What's different there:
- `jvm.dll` needs `vcruntime140.dll`, which sits in the Java home's `bin`, not beside the DLL:
  `create_vm` puts that `bin` first on the PATH before loading (`dll_search_path`).
- build.rs runs javac with an argument file (`@javac-*.args`): the platform mod's class path and
  sources are ~28,000 characters on Linux with short paths, past Windows' 32,767-character command
  line with `C:\Users\…` ones.
- `fs::canonicalize` gives `\\?\C:\…`, which Java's paths reject: paths going to Fabric use
  `horizon_prepare::java::real_path`.
- Linking a folder takes `mklink /J` (no privilege), which reads a forward slash as a switch.

- JNI reads JVM options in the system's code page, not UTF-8: `create_vm` converts them, and a class
  path entry with a character the code page lacks goes by its short name (a user name like `Ivér`).
  HotSpot and javac's launcher find their own Java home the same way (from their file's path,
  through the code page): the home's `jvm.dll` and `javac.exe` go by `option_path` too. Where a
  drive has 8.3 names off, Windows gives the long name back: `option_path` then fails with a
  "move the launcher's data folder" error instead of a garbled class path.
- Every stripper of `\\?\` (`horizon_prepare::java::without_verbatim`, which `plain_path`, the
  launcher's `backend::plain` and `data_location` use) also turns `\\?\UNC\server\share` into
  `\\server\share`. The launcher's Java homes (`java::installed`) are stripped before they reach
  `HORIZON_JAVA_HOME`.
- Windows locks a running program's folder and a jar Java has open: the launcher replaces the Java
  mods build by moving the old one aside first (`java_variant::swap`), and removing a held jar says
  "close the game first".
- Fabric's folder scan takes `.jar` only, case-sensitively: `horizon_jvm::jars` and the launcher's
  list do too, and the launcher adds `Foo.JAR` as `Foo.jar`.

Not tested on Windows: the launcher's Java mods tab. The `tools/java_bridge/*.sh` scripts are developers' and Linux-only (Xvfb,
mktemp).

### Profiling

`HORIZON_JAVA_PROFILE=1` logs every 200 server ticks where the time goes: the game's calls into
Java by call site (`game/java_profile.rs`: `Java profile: …`) and the mirror tick's phases and
each worldgen status (`HorizonProfile`: `Java profile (mirror tick): …`). Worldgen runs on
Horizon's workers, so its numbers are time summed over threads, not tick time.

```sh
HORIZON_JAVA_PROFILE=1 MODS=/tmp/pack SCENE=none tools/java_bridge/screen_test.sh \
  'sleep 40' '$C key w down; $C key ctrl down; sleep 30; $C key w up' 'sleep 15'
grep "Java profile" <scratch>/home/logs/latest.log
```

Measured 2026-10-31, 17 mods (FD, BOP, Tom's, Naturalist, Friends&Foes…), 2-core container:
the mirror tick is ~1 ms (spawner ~0.5, client tick ~0.2). Java worldgen is the cost: 25–50 ms
of `minecraft:terrain` per chunk, features ~10–20, structure starts and references ~20–40 in
the first seconds. A mod that adds any overworld biome or feature sends the whole overworld
through Java.

Worldgen lines now say a step's time per chunk (`(… ms each)`), and TERRAIN's parts
(`worldgen terrain: fill`, `surface`, `carvers`, `noise chunk`: `NoiseFillMixin`). With BOP
alone (2 cores, 2026-10-31) a chunk's TERRAIN is ~31 ms: the noise fill (`doFill`) 11,
surface rules 9.5, carvers 1.4, `createNoiseChunk` 0.4, and ~9 waiting in
`Util.backgroundExecutor`'s queue (buildTerrain and createBiomes hop there). "Terrain = noise"
was wrong: the noise is a third of it.

**Horizon's noise in Java protos** (`HORIZON_JAVA_NOISE=horizon`, off by default): Horizon's
Rust noise fills the proto (`VanillaTerrain::noise_chunk`: biomes, structures and surface
left out) and `NoiseFillMixin` skips `doFill` for it; Java keeps biomes, surface, carvers,
features. It is exact: `HORIZON_JAVA_NOISE_CHECK=1` (with it on) runs vanilla's fill too and
compares, 0 of 2100 chunks differed, villages included. Near a structure that adapts the
terrain (a beard) Java fills (its structures follow the mods' biomes, Horizon's don't);
mods' noise settings, density functions or noises, or a mod structure with terrain
adaptation, turn it off for the dimension. It isn't faster here: Horizon's noise is 12–13 ms
a chunk plus 3 to copy and 1 to apply, against HotSpot's batched `sampleVolume` at 11; time
to generate 300 chunks after a teleport (render distance 8, three places, twice):
Java 85 / 83 s, Horizon 90 / 87 s. Running buildTerrain and createBiomes on the calling
worker instead of the executor (tried) was slower still (96–98 s): more threads at once on
2 cores. Worth measuring on a many-core desktop before turning it on; the copy (98k
`block()` calls) can go faster by section palettes. The other costs left: surface rules
(~10 ms, the mods' biomes: Java's) and features (~15 ms).

Measured 2026-10-31, the 72-jar big pack (same container), standing still after spawn: the
game's calls into Java are 85–170 ms a tick, of which the mirror tick (`server_mods.rs:943`) is
37–84 ms: mod entities 22–75 ms (many mobs from Naturalist, Friends&Foes, Mutant Monsters,
Ecologics spawned around), the client tick 5–10 ms, level hooks ~2 ms; the rest is worldgen
work done on the server thread's calls (proto 35–65 ms). Entities are the first thing to
look at for a big pack.

**Measuring a whole pack** (2026-11-05, bridge-perf-1): `tools/java_bridge/perf_measure.sh`
(start time, RSS, the JVM's heap and native memory, live heap classes, `/tick query`, a JFR
recording); the 81-jar test pack's numbers, where its time and memory go and the next cheap
wins: [java-bridge-perf.md](java-bridge-perf.md). Fixed there: the packet tap kept a packet's
unread rest in Netty's decoder for good, so each later packet was copied onto megabytes (up to
795 MB of direct buffers, a third of the mirror tick, mods' packets misread); HUD sprites
looked up through every jar each frame. `HORIZON_JAVA_TAP=0` turns the tap off (default on).
Trap: jcmd attaches to the bridge's JVM only with `HORIZON_JVM_OPTIONS=-XX:+StartAttachListener`
(it runs with `-Xrs`).

### Mod mobs' cost (2026-10-31, bridge-mob-perf)

`HORIZON_JAVA_PROFILE=1` now also logs per entity type the tick and snapshot time
(`Java profile (entity ticks)`, `(entity snapshot)`), the block reads (`(block reads)`: sections
fetched from Horizon, kept from earlier ticks, reads served), and the snapshot's bytes
(`Java profile (bytes)`). The load test: 100 critters (`horizon_critters:critter`) summoned on
a grid around the player (dense: 3 blocks apart, all within 16; spread: 9 apart, within 48),
`maxFps:10` so the software renderer leaves the 2 cores alone.

Where the time went at first (dense, 100 mobs: 188 ms a tick):
- AI and physics 130 ms, of which ~70% copying whole chunks: `EntityFluidInteraction.hasFluidAndLoaded`,
  `PathNavigationRegion` and shadows asked for their chunk, and `HorizonChunks` copied all 98k
  blocks of it, again each call (the copies were good for one view only).
- Block reads ~23 ms: one JNI call and a state name string per block (1.9 us each, 13k a tick).
- The snapshot 46 ms (shadows copying chunks again, reflection lookups per entity, Gson's float
  formatting, a held stick's SNBT each tick), Rust 4.5 ms parsing it into a `Value` tree and
  writing it out again per player, the player's game 3 ms parsing it.

What changed:
- Block reads go through whole sections (`HorizonServer.section`, `Natives.sectionIds`: Horizon's
  state ids, unpacked from the section's palette, named once by `stateName`). They are kept
  across ticks while the chunk's `Chunk::block_revision` stays (new on every block set, every
  copy of a chunk; `Natives.chunkRevision`): a mob standing in the same place reads its sections
  once. The view's own epoch is per thread (`Natives.threadEpoch`).
- `HorizonChunks` copies are lazy: block and fluid reads go to the level; only code that reads a
  chunk's sections or heightmaps fills it. `EntityFluidInteractionMixin` answers the fluid check
  from loaded-ness (the exact per-block check follows).
- Lock-free `isMirror`, a flag in front of `WRITTEN`'s lock, cached reflective fields
  (`HorizonClient.set`), cached item SNBT, `HorizonJson` (floats rounded to 1e-4 written by
  integer arithmetic).
- The snapshot is made in the tick's own view (one lend), each entry routed by Java
  (`x y z`, model `keys`, the entry `e`): Rust filters and concatenates the raw JSON per player
  (`serde_json::RawValue`), and the player's game reads it into typed structs.
- Entity activation range: a mod entity with no player within 32 blocks that is idle (no
  target, not hurt, not moving fast, not in a fluid, not riding or ridden) ticks one tick in
  4, and its last look is kept (`"keep"` entries: the game keeps the model, moves the box).
  `HORIZON_JAVA_ENTITY_RANGE` sets the range; 0 turns it off.

Now (same container): dense 100 critters 15 ms a tick on the server thread (AI 7.1, snapshot
5.3, the rest spawner and client tick; block reads 0.1), spread 8–9 ms (AI 2.9, snapshot 2.9).
Per mob: AI ~0.06–0.08 ms, snapshot ~0.03 ms. The container is two shared cores; a desktop
core should be 2–3x faster. Left: the snapshot's fixed part (~2 ms: JSON for all, block
entity scan), the player's game parsing ~1–2 ms on its own thread, and the mobs' own AI.

### The 106-jar pack's mobs (2026-11-01, bridge-mob-perf2)

`HORIZON_JAVA_PROFILE=1` on the big pack (standing, walking, standing; Naturalist, Friends&Foes,
Mutant Monsters, Ecologics spawning): Naturalist's bass ~3.5 ms each a tick, butterflies ~5 ms,
the rest of its mobs 0.3–1.3 ms; 40 mobs 42–75 ms. Not their AI: a Polymer mixin in
`LevelChunk`'s constructor reads every section (`polymer$generatePolymerBlockSet`), which filled
each lazy `HorizonChunks` copy (98k blocks, lithium's state tracking on each), on every
`getChunk` of every mob that moves. Fixed:
- `HorizonChunks.Copy` answers its constructor's mixins with the empty sections the constructor
  made (a `made` flag); copies are kept across ticks while Horizon's chunk revision stays (512 a
  level, LRU; the calls' unapplied writes followed again on reuse).
- The mirror level's entity searches use a chunk-column grid made once a tick
  (`HorizonEntities.near`; each entity in the column it stands in, searches grown by its widest
  box + 8 blocks), the entity list is kept between changes (`HorizonEntities.of` made a new
  list on every `getEntities`).
- The last section read is kept per thread (most block reads follow one another).
- Snapshot: an entity whose look is the same as last sent (standing, resting) goes as `"keep"`
  with its position only; a standing one is drawn every other tick (idle animations at 10 Hz,
  lerped); Rust sends the last whole entry to a player that hasn't had it
  (`send_java_entities`: `looks`, `has`). The snapshot is written straight to one string.
- The profile's block read counter is a `LongAdder` (it took a lock per read).

Now: bass 0.1 ms, butterfly 0.15, whale ~1 (its body parts' collisions), the rest ~0.1–0.2;
the natural 40 mobs ~7–10 ms of ticks + ~2.3 ms of snapshot. 100 mixed Naturalist mobs
summoned within 24 blocks (`mix_load` test: all active): ticks 10.5–11.6 ms (~0.11 each),
snapshot 4.6–4.8 ms (~80 drawn a tick), Rust ~1 ms, payload 22 KB a tick; on two cores shared
with the mods' worldgen (30–45 ms a tick of Java terrain there). Left above 5 ms: the mobs' own
AI (goals, pathfinding, lithium's collision sweeps: ~0.1 ms each here), and a fill each time a
chunk's revision changes (grass, crops).

Big pack traps: the first client tick's model reload (`HorizonClient.models`, with a GPU
device) never ended with 106 jars (a future waiting on nothing; the server tick stopped there
for good): it is now bounded at 30 s. Not mobs, but in the mirror tick: the headless
client's tick, 7–24 ms (the mods' frame-start hooks up to 19 ms in the first minutes, ~5 ms of
HUD).

## Hooks in Horizon's shared files (after the upstream sync)

**Since bridge-p0 (2026-11-06):** Horizon's own files have no `cfg(feature = "java-mods")` left: each
calls a hook that exists in both builds (a `Game` method or associated function; free-function
hooks became associated ones, `Game::java_fluid(state)`), whose plain answer is in
`game/java_hooks/<area>.rs` (one file per C PR) and whose body is in a feature-only file.
`java_server.rs` is split by area (`java_server/{tick,blocks,players,menus,commands,items,entities}.rs`).
The lower crates' bridge children have neutral names: render `assets/mod_fluids.rs`,
`biome/mod_biomes.rs`, `datapack/mod_jars.rs`, `hud/mod_textures.rs`, `mesher/mod_blocks.rs`,
`options_ui/mod_rows.rs`, `particles/mod_particles.rs`, `streaming/mod_tickets.rs`; client lib
`item_render/mod_quads.rs`, `lan/conn/tap.rs`, `lan/containers/mod_windows.rs`,
`lan/data/mod_biomes.rs`, `lan/guest/mod_abilities.rs`, `lan/hub/mod_guests.rs`,
`landing/routed_fall.rs`, `menu/crafted_takes.rs`, `mod_content/own_states.rs`,
`save/mod_presets.rs`, `title/create_world/mod_presets.rs`, `vanillagen/oracle.rs`. The
launcher's fork fallback is `launcher/src/java_variant/fork.rs`. A new hook: its call in
Horizon's file, its stub in `java_hooks/<area>.rs`, its body in a feature-only file.

**Since bridge-upstream-prep-2 (2026-11-05):** logic left Horizon's files for `<file>/java.rs`
child modules and `game/java_*.rs`; Game's `java_*` fields are one `java: JavaGame`
(`game/java_mods.rs`); `routed_hit` is in `java_server.rs`; the plain build compiles again
(`java_mods/off.rs`). Every remaining hook, file by file, and the PR series:
[java-bridge-upstream.md](java-bridge-upstream.md). New code goes in our files the same way:
a child module next to the file it hooks, one-line calls in Horizon's file.

Since the 2026-11-01 sync with upstream `main`, the bridge's calls from Horizon's own files are
one-line calls into `game/java_server.rs` (upstream deleted `server_mods.rs` with WebAssembly code
mods: our half of it is that file now). Where they are, and what upstream's native mod hook points
(docs/native-mods.md, `game/native_hooks.rs`) could replace later. Switched (mixins round 5): our
call sits where the native one is, so Horizon's files carry one hook point, not two. The others
need the hook's exact place checked against what the mirror expects.

| Our hook (where) | Native hook point that could replace it |
|---|---|
| `tick_java_server` (game.rs `fn tick`, after `native_tick`) | `native_server_tick` (API 4) end; joins and leaves: `native_player_logged_in/out` (API 57) |
| `apply_java_level_writes` (levels.rs, before each level's tick) | `native_level_tick_pre` (API 23) |
| ~~`RANDOM_TICK_HOOK`~~ | **switched**: `java_random_tick` in `GameLevel::before_random_tick` (game.rs), before `native_random_tick`; horizon-world is upstream's again (`seasons`, `mixins1`) |
| `SET_HOOK` (render `world.rs` `set`) | `native_block_changed` (only `Level.setBlock` writes; ours sees every write) |
| ~~`HURT_HOOK`~~ | **switched**: `java_hurt` first in native_hooks.rs `hurt_server` (`health::HURT_SERVER`, past hurtServer's guards, where Fabric's `ALLOW_DAMAGE` fires); `health.rs` is upstream's again (`hurt`) |
| `java_break` / `java_block_event` | `java_break` already sits just before `native_destroy_block`; placing: **switched**, `java_block_event(.., true)` right before `native_place_block` (round 6; the Java side doesn't use placements yet: event kind 4). A vein miner's whole-break-in-Java has no native equivalent |
| `java_attack_block` | **switched**: in the closure that asks `native_left_click_block` (lan_actions, after reach, spawn protection and the border); a cancel leaves the block to the ack (`punch`) |
| `java_use_block`, `java_use_item` (lan_actions) | `native_right_click_block` (API 21), `native_use_item_on` / `native_use_item` (API 13) |
| `java_attack_mob`, `java_entity_attack`, `java_entity_interact` | `java_attack_mob` already follows `native_attack_entity`'s cancel. The packet-level two have no fit: see "A proposed hook point" below |
| `java_item_touch` (item pickup) | `native_item_picked_up` (API 40, cancel only; Clumps' take-part has none) |
| `note_java_death_cause` (lan_actions, a player's death on the server) | `native_player_died` (API 40) |
| `local_java_command`, `guest_java_command`, `send_java_commands` | `native_register_commands` / `native_perform_command` (API 8): usage strings, not Brigadier trees; the Java tree is merged after the native one (hub `mod_commands`) |

| ~~`note_java_death*`~~ | **switched** (round 6): `java_player_died(uuid, message key, conn)` right before `native_player_died`, in `guest_die` and `death_message`: the message's damage type, else the tracker's last hit (`death`) |
| `local_java_command`, `guest_java_command`, `send_java_commands` | already beside the native decision: `guest_command` asks native mods' `native_command_level`, then ours; the window asks `client_mod_command`, then ours. `native_perform_command` runs later in the guest's swap with usage strings, not Brigadier trees, so ours stays where the command is taken |
| `java_player_chat` (chat.rs, lan.rs) | none (native has no server chat hook) |
| `packet_tap::begin`/`keep` (lan/conn.rs: the memory connection's login end, `PlayerSender`), `java_world_closing` (integrated.rs, the server's stop) | none (no hook sees a connection's bytes) |
| `java_select_slot`, `java_ride`, `java_orb_touch`, `java_crafted`, `java_tickets` (chunk tickets in streaming), precipitation | none |
| Screens (`Screen::JavaMod`), REI's overlay, HUD mods | `native_mod_screen*`/`native_screen_*` (API 19/20), `native_panel_*` (API 2), `native_hud`/`native_health_hud` (API 27): layouts of their own, not our drawn-op frames |
| Mod particles (`ParticleEngine::add_mod`) | upstream's `ParticleOptions::mod_particle` (native particle types): `add_mod` now uses its sprite sets |
| Mod mobs drawn (`java_entity_geometry`, shadows) | `native_mob_submit` (draws Horizon's own mobs only) |
| Creative tab pages, item components of content mods | upstream's own now (NeoForge-style pages, `component_patch`): ours dropped |

### A proposed hook point: an entity the level doesn't hold

`ServerGamePacketListenerImpl.handleInteract` finds the entity by id; when the level has none,
vanilla does nothing. The bridge's entities (a Java mod's own mobs, ids from
`java_entities::FIRST_ID`) are such ids, so `lan_actions` asks `java_entity_attack` /
`java_entity_interact` before Horizon's own handling (`S::Attack(p) if ...`). A native-style
hook point there would let any server mod own entities Horizon doesn't simulate, and the
bridge would hook it instead of keeping its own match guards:

```rust
/// A player's attack (`hand` None) or use of entity `id`, which no level of this server
/// holds (`handleInteract` finds nothing): a server mod's own entity. Set `claimed` to take
/// it; nothing else runs then. Server-side, the helpers read the acting player. Proposal.
pub(super) fn native_unknown_entity_interact(&mut self, id: i32, hand: Option<u8>, location: Option<[f32; 3]>, claimed: &mut bool) {}
```

Called in `guest_packet` before `S::Attack`/`S::Interact`'s own handling, for ids the level
doesn't hold. The bridge's copies of Horizon's mobs (`java_vanilla_entity`, when mods patch
`Player.attack`/`interactOn`) are Horizon's entities, so they'd go through
`native_attack_entity`/`native_interact_entity` with a "take the whole action" answer instead
of a cancel: a second, smaller proposal (a `routed: &mut bool` beside `canceled`).

Upstream also moved the client's world, rendering and screens into `crates/horizon-client-render`
(`hud.rs`, `particles.rs`, `mesher.rs`, `streaming.rs`, `options_ui.rs`, `chunk.rs`, `biome.rs`,
`world.rs`): our changes there moved with them.

Since sync 3 (2026-11-02): the process's `main` is `entry.rs` (`main.rs` only calls it), so
Java's exit is there; a window never ticks its own player (upstream deleted the in-frame
tick), so deaths reach the mods only through the server's (`note_java_death_cause`); a Java
horse-style mob's jump bar draws in `draw_jump_bar`. pack-6's small `BakedQuad` keeps no
sprite name: Fusion's connecting textures find their sheet by the face's layer, and
`BakedFace::force_translucent` keeps a model's forced translucency for OptiFine tiles.

### Content preparation time (2026-11-01, bridge-worldgen-3)

A new set of jars builds its content (`horizon_jvm::content::build` → port-pack's inventory,
oracle, carry, then `mod_content::build`). With the 110-jar pack (2 cores) that was 173 s, and
~140 s of it was one Rust function on the main thread: `oracle::own_states`, which gave each
mod block state a vanilla shape by `bounds_distance` against **every** vanilla state (~28k) —
mods' ~40k states × 28k. Vanilla has only a few hundred distinct bounds pairs, so the search
now runs over those (`ShapeIndex::distinct`, least name kept per pair, the same tie-break) and
memoizes per bounds pair (`ShapeIndex::nearest`). 173 s → 107 s; the content mods it writes
are byte-identical (`diff -r` of two caches: only scratch paths, map order, and the Java
oracle's sampled drop counts differ, run to run). What's left, in order: the Java oracle
(class loading, ~65 s), `mod_content::tables` (~15 s), the facts JSON read (~10 s).
Sample it with gdb (`gdb -p $(pgrep -x horizon-client) -batch -ex bt`, every 2 s).

### Java's start, cached between runs (2026-11-02, bridge-worldgen-5)

The "class loading" above is Fabric's start (the same in a content build and a normal
start): JFR of the 110-jar pack's main thread, 2 cores: ~29% Knot making class bytes (mixin
transforms 18%, finding each class's bytes across 260 jars 10%), ~27% `initCache` of every
block state's shape cache (Fabric registry sync's `initShapeCache`, then EnergizedPower's and
BetterEnd's blocks), the rest mods' entrypoints; plus HotSpot parsing, verifying and linking
~30k classes, which JFR doesn't see. Turning verification off changed nothing.

- **Class archive (CDS)**: the JVM starts with `-XX:+AutoCreateSharedArchive
  -XX:SharedArchiveFile=.horizon-cache/java-classes-<stamp of the jars>.jsa`
  (`java_mods::class_archive`; other sets' archives are removed; `HORIZON_JAVA_CLASS_ARCHIVE=0`:
  none). The JVM writes it at its exit, so the game now ends through Java's
  `System.exit` (`horizon_jvm::exit`, last thing in `entry::main` since upstream's lib split, a 90 s watchdog). Mods' classes
  go in too (Knot's: an archived class is used when the bytes Knot makes have the same CRC,
  so a changed jar or mixin just loads normally). 110 jars: Fabric started in 89–92 s →
  79–81 s here, join 166 s → ~150 s; the first quit of a set takes ~25 s more (a ~250 MB
  file; logged). With one small mod it changes nothing measurable. A dump while the game
  runs (`jcmd … VM.cds dynamic_dump`) crashed a worldgen thread in libjvm: only at exit.
- **TerraBlender's `possibleBiomes`** (`BiomeSourceMixin`, `HorizonBiomeMemo`): its
  `appendDeferredBiomesList` replaces vanilla's memoized supplier with one that builds a new
  set on every call, and Fabric's biome modifications call it per (modifier, biome). Memoized
  again: the server's start hooks 3.3 s → 2.2 s (logged: "the server's start hooks …";
  `HORIZON_JAVA_BIOME_MEMO=0` to compare). Smaller than the ~10 s guessed in worldgen 1.
- `has_java_mods` listed and resolved every jar each call, every 2 ms while the spawn was
  searched; it is cached now.

Left on the path to the join (cached run, 2 cores): Fabric's start ~80 s (mods' entrypoints
and Knot; a cache of mixed class bytes would skip the transforms, but Mixin's plugins,
synthetic classes and our census watch transforms happen: not tried), then ~30 s of the
spawn's worldgen (`settle_spawn`, Java's protos with the models' reload beside), ~25 s more
for the 3×3 spawn chunks.

### Far terrain of the big pack (2026-11-02, bridge-worldgen-5)

110 jars, far terrain 256, headless on 2 cores (llvmpipe):
- At render distance 12 nothing far is drawn for minutes, with or without mods: on a machine
  of four cores or fewer `Lod` holds its meshing while the near terrain loads
  (`set_near_loading`, upstream's), and Java's near worldgen keeps it loading. Not a bridge
  bug; on a desktop it doesn't hold. Test far terrain here at render distance 5. `horizon-ctl
  status` now says it: `lod.near_loading` and `near_loading_parts` (generating, decorating,
  viewing, finishing, lighting, meshing, queued).
- **Wrong land**: Tectonic (and Lithostitched, Enderscape) ship density functions; Tectonic
  replaces `minecraft:overworld/continents`, `depth`… So the mods' land isn't vanilla's
  noise, but far terrain draws Horizon's vanilla noise: ocean where the near chunks are land,
  a seam at the render distance. Tried (branch `bridge-worldgen-5-farnoise-wip`, not merged):
  a `vanillaNoise` check (no mod pack with noise settings, density functions or noises) and,
  when it fails, far columns from Java. Correct land, but far too slow: `getBaseColumn` and
  `getBaseHeight` build a NoiseChunk a column (~80 ms warm with this pack's routers, then 2–7
  ms), and the router's `finalDensity` through `RandomState.getSampler(…).sampleValue`
  (uncached context) was slower still (~0.4 s a column). The way that would work: Horizon's own
  noise reading the pack's density-function JSON (Tectonic is data only), as it reads
  vanilla's.
- Elsewhere far terrain follows the mods (BOP: land, biomes, tops), without trees (known).

### Far terrain from the mods' noise (2026-11-02, bridge-worldgen-6)

Done the way above: Horizon's own noise with the pack's worldgen data. On the first far
column of a dimension the mods generate, `HorizonWorldgen.lodNoise` says whether its land is
vanilla's noise (`vanillaNoise`: a noise generator with the dimension's vanilla settings and
no mod data pack with noise settings, density functions or noises). If not,
`HorizonNoiseExport` writes `<world>/data/horizon_java_noise/<dimension>/` as a data pack,
from the **loaded** registries (configs, overlays and mixins applied):
- the noise settings (router inline), every density function they reach and the noises
  those name, with vanilla's codecs (`DensityFunction.CODEC`, `NormalNoise.DIRECT_CODEC`);
- a mod's density function type is written as the vanilla one it is in disguise: a constant
  (range of one value, Tectonic's `config_constant`), `config_clamp` → `max(min(x, max),
  min)`, `config_noise` → `shiftedNoise2d · multiplier + offset`. Any other type fails the
  export (logged), and far terrain keeps vanilla's noise. References a mod node hides from
  `rewriteChildren` (the clamp's bounds) are followed as they're written;
- the material rule stays vanilla's (mods merge theirs into their own form: Biolith's
  `base`/`sources`); far tops come from the mods' rules through `lodTops` as before;
- **seeds**: Tectonic's `NoisesMixin` seeds `tectonic:parameter/<x>` as `minecraft:<x>`. The
  export compares each noise the level's random state made with one made from each candidate
  id and writes `"horizon_seed": "<id>"` when it isn't the noise's own; Horizon's noise reads
  it (`worldgen::noise_seed`, `DensityGraph::register_noise_seeded`). Without it the far land
  stood ~30 blocks too high.
Then `java_worldgen::NoiseLand` makes a second `VanillaTerrain` over the world's packs plus
this one (structures off) and `JavaLod` samples the land from it.

Checked exact: `HORIZON_JAVA_NOISE_EXPORT_CHECK=1` compares the mods' router with the
written one in Java (0.0 over 200 points) and logs the settings' debug functions at five
points (`HORIZON_JAVA_NOISE_PROBE=id,…,noise:id` adds functions or raw noises);
`cargo run -p horizon-client --example noise_pack_probe -- <resources> <pack> 1234 [ids]`
prints Horizon's at the same points: identical (N −0.458/−0.155/−0.458/−0.458/0.042) with the
110-jar pack (Tectonic, Lithostitched). Screens: the far ring continues the near land, no seam.

Cost (2 cores): export ~2 s, the second generator's data 13–44 s on a far-terrain thread
(far terrain waits; near isn't affected), and memory: a second `GeneratorData` (RSS ~5.0 →
~5.9 GB with far terrain on, some of it far tiles). To do: share the first `GeneratorData`'s
parts and rebuild only noises, densities and settings.

The quit through Java (class archive), measured with Farmer's Delight (2 cores): quit from a
world 3 s (7 s the run that writes the archive), from the title screen 1.3 s, SIGTERM 3.4 s,
SIGINT 2.4 s, a crash (SIGSEGV) 9.4 s; a dedicated server's `stop` 6.4 s (writing its
archive). All end the process. A quit before Java and Fabric have started goes the way
without Java (`horizon_jvm::exit` waits for `BRIDGE`), so no archive of half the classes.

### Mods' structure templates in Horizon's generation (2026-11-04, bridge-worldgen-7)

A dimension the mods leave alone is generated by Horizon's own code, which read templates,
template pools and processor lists only from the prepared vanilla data: a mod replacing
vanilla's templates (The Sift's `data/minecraft/structure/ancient_city/city_center/
city_center_{1,2,3}.nbt`) was ignored. Now:
- `WorldgenOverrides` takes data packs' `data/<ns>/structure/<path>.nbt` (`PackTemplates`: id
  → pack and file, read lazily through the pack's reader when a structure places it), and
  `StructureResources::template` reads the top pack's file before the prepared one, as
  vanilla's `ResourceManagerTemplateSource` does. World data packs get it too.
- The Java mods' jars are packs below the world's (`java_worldgen::mod_structure_packs`,
  `DataPack::archive(..).structures_only()`): only their templates, `worldgen/template_pool`
  and `worldgen/processor_list` go to Horizon's generator (the rest of their worldgen is the
  Java side's).
Not done: `/place template` still reads only the world's `generated/` and the prepared
templates (`commands/place.rs`).

### Mods' new structures in Horizon's generation (2026-11-04, bridge-worldgen-8)

A mod that only adds structures leaves its dimension to Horizon's generator. Two paths:
- **Vanilla structure types** (jigsaw: Towns and Towers' villages, Energized Power's
  factories). They already generated: the content conversion merges the mods'
  `worldgen/structure`, `structure_set`, pools, processors and biome tags into the content
  root's bundles (`mod_content.rs`, `WORLDGEN_BUNDLES`). The jars-as-packs path of worldgen 7
  now also gives `worldgen/structure`, `structure_set`, `feature`, `placed_feature` and
  `tags/worldgen/biome` (`datapack::STRUCTURE_FILES`), so a mod whose content isn't carried
  over (a failed content mod) still has its structures. Same ids, same JSON: no difference
  when both are there.
- **A mod's own structure type** (Friends & Foes' illusioner shack, citadel, iceologer
  cabin; Ecologics' desert ruin) was the gap: Horizon's generator has no code for it
  (`StructureKind::Unsupported`), so it never generated. Now the generator lists them
  (`StructureRegistry::unsupported`: the dimension's possible sets' structures of unknown
  types or placements; logged as "N structures can't generate here") and
  `JavaTerrain::start` passes them to `HorizonWorldgen.start`: such a dimension is generated
  by the mods (log: "has <structure> (<type>), a structure Horizon's generator can't make",
  then "generates through the mods (friendsandfoes)"). Cost: Java worldgen for that
  dimension (as with any mod's biomes or features).
Regress `newstructures` (T&T: Horizon's generator, /locate, the village's bell) and
`structuretypes` (Friends & Foes: the mods' generator, /locate, the shack's brewing stand).
Not done: Cristel Lib's placement config (T&T's spacing 48/24 by default) changes the sets
on the Java side only; Horizon's generator places T&T's sets as their JSON says (51/12 and
48/12). Jigsaw structures using a mod's own pool element or processor types still go to
Horizon's generator (the element is skipped, the processor list fails).

### Mods' own dimensions as Horizon levels (2026-11-05, bridge-worldgen-9)

A mod's dimension (a `dimension`/`dimension_type` in its data, or registered in code: both
end up as level stems in the mirror's registries) is a level of Horizon's server, as
`MinecraftServer.createLevels` makes one per stem:
- **Which**: once Fabric has started, `java_mods::register_extra_levels` asks
  `HorizonServer.extraLevels` (every `LEVEL_STEM` but vanilla's three: id, type, build range,
  skylight, ceiling, coordinate scale) and registers each as a `chunk::ExtraLevel`
  (process-wide, like the stems). The game's `Dim` has `Dim::Extra(index)`; `Dim::known(id)`
  is Some only for a level the server has, `Dim::all()` lists vanilla's three then the extra
  ones (ticking order, `/execute in` suggestions, closing), `dim.vanilla()` is the level it's
  drawn as (ceiling or no skylight: the Nether, else the Overworld), `coordinate_scale()`.
- **Generated by the mods**: `make_streaming` gives Horizon's generator a stem of the type's
  build range over the Overworld's noise (`extra_level_stem`: it only lays out the chunks, and
  makes one if Java fails); `HorizonWorldgen.modded` always says a non-vanilla level is the
  mods', so `JavaTerrain` generates it all (The Sift's multi-noise, a mining dimension's flat
  generator).
- **Saved** under `dimensions/<ns>/<path>` (`save::dimension_dir`, as vanilla), the player's
  `Dimension` too; a world saved there waits for Java at open (`java_mods::level_named`), and a
  joining player's saved level is kept when the server has it (`lan/playerdata.rs`).
- **Reached** by `/execute in` (and `if dimension`), `/tp`, and mods' teleports (`ServerPlayer
  .teleport` → `Natives.teleport` → `execute in … tp`, as before). The mirror makes their
  levels with vanilla's (`HorizonServer.levelIds`), so `server.getLevel(key)` finds them and
  writes into them (a teleporter placing its way back) wait for the chunk.
- **Clients**: the dimension type registry gets the extra levels' types after the data's own
  (`lan/data.rs`: `extra_dimension_types`, data from the vanilla type it looks like with its own
  shape), and Login/Respawn name the level's own type (`dimension_type_of`). A Horizon client of
  another process registers a level it doesn't know from the packet (`remote.rs`,
  `client_level`).
- **Stand-in chunks** (`HorizonWorldgen.standIn`): a mod's `level.getChunk(x, z)` that loads,
  in a level Horizon hasn't loaded there (vanilla blocks and generates it: a portal mod finding
  its exit in a level nobody is in), gets Horizon's save of the chunk (`HorizonChunkLoads
  .saved`, the region file) or, in a level the mods generate, the mods' generation of it (to
  FEATURES, in the cache Horizon's own generation then takes it from: the same chunk; the
  level's generation starts there if Horizon hasn't opened it). Horizon is asked to load it;
  until it has, block reads, `hasChunk` and `isLoaded` answer from the stand-in. Before, the
  mirror answered null and the mod failed ("Should always be able to create a chunk!").
- **The server's start hooks before its first tick** (`HorizonWorldgen.readyBeforeTicks`): a
  world reopened in a level whose chunks are saved starts its generation ticks later; by then
  Fabric's biome modifications failed ("This dynamic registries instance has already been
  modified") and weren't applied anywhere. And a dimension's generation starting mid-tick no
  longer calls `world()` again (the tick names `dimensions/<ns>/<path>`, inside the world: a
  ConcurrentModificationException failed the start, and the level generated as vanilla's).
- **The Java side's world folder is the Overworld's** whichever level is in place
  (`java_server.rs`: the tick passed `self.save.root`, the level in place's folder). A player
  alone in another level (The Sift, the Nether) ticks only that level, and the mirror took the
  change of folder for a new world: its mods' entities unloaded mid-iteration (a
  ConcurrentModificationException every tick) and its stores moved to that level's folder.
  Existing saves keep theirs (the Overworld's folder is where they always were); region reads
  (`HorizonChunkLoads.region`) go from the world's folder above it.
- **Mods' own advancement grants** on a mirrored player (The Sift's rift on arrival): the
  mirror's `PlayerAdvancements` has the player list and the tree (null: the NPE failed the
  mod's teleport); its "has made the advancement" line is dropped (Horizon announces its own).
Checked headless (The Sift + Every Mining Dimension, `/tmp/hzt/rift.sh`-style runs):
`/execute in the_sift:the_sift run tp` lands on The Sift's terrain; EMD's teleporter block (mod
code) goes to its flat mining level, places its return teleporter there, and back; The Sift's
rift item (mod entity touched → `RiftManager` finds the main portal in The Sift → teleport)
takes the player to The Sift's main portal; quit in The Sift, reopen: still there, and new
chunks are the mods'.
Not done: The Sift's horn (needs an ancient city's portal frame: not set up headless);
entities (not players) changing level through mod code; the sky of an extra level is the
Overworld's or the Nether's (its own `sky_color`/fog attributes aren't read); a mod's dimension
effects (custom sky renderers) are client code the bridge doesn't run; The Sift's portal
surface draws as streaks from inside (client).

## Working on it

- **The task queue is [java-bridge-tasks.md](java-bridge-tasks.md)**: take your area's next task, follow its loop, add a report there.

- One task a branch (`bridge-<task>` off `java-bridge`); keep PR-sized steps.
- Commit as the user (`iaxivers`) with the Co-Authored-By line; never commit `.local/`.
- Write comments like the code around: what vanilla does and why the mirror does it.

- **Mods' own packet types (26.3 trap).** Not every mod talks through custom payloads: Create
  registers ~100 packet records into the play protocol (a GameProtocols mixin), each handling
  itself (`handle(listener)`), and checks its thread with `PacketUtils.ensureRunningOnSameThread
  (packet, listener, minecraft.packetProcessor())`. A dropped one fails silently: the click
  "works" on the client and nothing happens on the server (an arm placed with no points).
- **Shapes that depend on the entity (trap).** Horizon carries one collision shape per state; a
  mod block's `getCollisionShape(…, EntityCollisionContext)` may answer per entity (Create's
  crushing wheel controller is empty for the item it processes). Such items are moved by the
  mirror while held (`HorizonItems`).
- **What the mirror's constructors skipped (trap).** The server, levels and chunk maps are
  allocated: their final fields are null unless set. `MinecraftServer.getRunningThread()` was
  null, so code that moves itself onto the server thread unless already on it (Chunky's
  `getChunkAtAsync`: `supplyAsync` onto the server) moved itself again on every run, forever;
  it answers Horizon's server thread now. `ChunkMap.mainThreadExecutor` is the server. Fields
  mods' mixins add and initialise in a constructor are null too: `emptyCollections` makes
  collections and atomics; anything else stays null.

- **26.3's mouse buttons are SDL's** (left 1, middle 2, right 3, back 4, forward 5), in
  `InputConstants.Type.MOUSE` and `MouseButtonInfo`: GLFW's 0/1/2 pressed the wrong bindings.
- **A mixin's final fields can't be written from another class** (`IllegalAccessError`, even a
  nestmate): `HorizonMixinInit` writes them through reflection.
- **Client mixins' injections aren't in `HorizonHooks.HOOKS`** (only their constructors are):
  fire them with `fireMerged`, which reads the merged handlers in the class itself.
- **Custom geometry is drawn after the submit** (`submitCustomGeometry`'s renderer runs at the
  feature dispatch): record it at the submission's end, not at the call.

- **GeckoLib and partial tick 1.0 (26.3 trap).** GeckoLib treats a partial tick of exactly 1.0
  as a paused game and freezes every animation. The bridge renders at 0.999
  (`HorizonEntityRender.PARTIAL_TICK`); never pass 1.0F to a mod's render state.

- **Ridden mobs are client-authoritative (26.3).** `Player.isClientAuthoritative` is true, so
  on a server `LivingEntity.travelRidden` zeroes a ridden mob's motion and waits for the
  rider's client. Mirrored players have no Java client: `RiderAuthorityMixin` makes them
  non-authoritative and the mirror moves the mob. Never call `ServerPlayer.rideTick` on a
  mirrored player: it runs `ServerPlayer.tick`.

- **Horizon's mobs in the mirror (`HorizonNeighbours`).** The mods' mobs only see entities the
  mirrored level lists. Horizon's own mobs near players are shadows there (same id as
  Horizon's), refreshed each tick. A hurt to a shadow goes to Horizon. Shadows are not ticked:
  never `tick()` one, and don't add them to `HorizonEntities` (it would save and tick them).

- **Far mods' mobs are drawn less often.** `HorizonEntityRender.drawEvery`: past 24 blocks from
  every player an entity's look is made every 2nd tick, past 48 every 4th (its position each
  tick; the game keeps the look). Animations far away run at 10 or 5 Hz.

- **Horizon's mobs see mod mobs as vanilla kinds.** Horizon's AI knows only vanilla species, so
  each mod living entity is listed as the vanilla type it is taken for
  (`HorizonNeighbours.like`) with its own id, box and health. A hit on one goes to the mirror
  (`java_mob_hits_entity`).
- **A Java use's blocks are set before the click is answered.** A mod's `BlockItem.useOn`
  places its block in the mirror; the `SetBlock` comes back as an action. It used to wait for
  the next tick, while the click's correction (`UseItemOn`'s two `BlockUpdate`s) and its
  acknowledgement went out at once: the player's game, which had predicted the block, got air
  and kept it (regress `placed` polls the block 40 times after the click: it read air once,
  then the stove). `java_use_block`/`java_use_item` now apply the level's writes at once
  (`apply_java_use_writes`), as vanilla's `useItemOn` sets the block in the handler.
- **Every state's mesh and light, offline:** `cargo build --profile fast -p
  horizon-client-render --example state_mesh_scan`, then `target/fast/examples/state_mesh_scan
  <content root>` (a run's `.horizon-cache/mod-content`) meshes and lights each state alone on
  stone and prints its quads, and any panic (a worker's panic loses the job silently). The
  81-jar pack: 110,458 states, no panic, every mod block with a model meshes (~1 min).
- **Writes into unloaded chunks wait.** Vanilla's `setBlock` loads the chunk; Horizon's refused
  it. A mod's write into a chunk Horizon hasn't loaded now waits (both sides) until it loads.
- **Horizon's projectiles on mod mobs.** Arrows, tridents, snowballs, fireballs meet the mods'
  living entities (`java_projectile_targets`, from the snapshot's `seen`; one a player rides is
  left out) and the hit goes to the mirror (`HorizonNeighbours.hurtByProjectile`): a
  projectile of the same entity type, never added to the level, stands where Horizon's is, flies
  as it flies and is owned by the shooter (the mirrored player, or the Horizon mob's shadow), so
  the source is vanilla's own (`arrow`, `trident`, `thrown`, `fireball`), knockback follows the
  flight and the mob strikes back. Not done: Punch, tipped arrows' effects, piercing through.
- **A right-click on a mod mob ends the click (the client has no copy to ask).** Except with an
  item aimed past entities (`used_past_entities`: bow, crossbow, trident, throwables, shield,
  spyglass...): the Interact is sent and the item is used too, as vanilla's PASS goes on to
  `useItem`. Before, a bow drawn at a mod mob did nothing.
- **A mod's projectile already hits players and Horizon's mobs**: it is a Java entity in the
  mirror, its `ProjectileUtil` search finds the mirrored players and the shadows, and their
  hurts go to Horizon (regress `spit`, `spitsheep`: crittermod's spitter and its Pebble).
- **Block reads don't call JNI to check the view.** `HorizonServer.section` asked
  `Natives.threadEpoch` on every read (a mob's collisions read thousands a tick); the counter
  is now read in place from a direct buffer over Rust's thread-local
  (`Natives.threadEpochBuffer`). It was ~13% of all mod entity ticks in jcmd samples (whales,
  skeleton creepers).
- **No JFR in the bridge's JVM here**: `-XX:StartFlightRecording` in `HORIZON_JVM_OPTIONS` is
  accepted but `jcmd <pid> JFR.*` is unknown. Sample instead: `jcmd $(pgrep -x horizon-client)
  Thread.print` in a loop (the server thread is a `Thread-N` attached from Rust; `pgrep -f`
  matches your own shell too).
- **A mod's property on vanilla blocks (26.3 trap).** The Sift adds `ichorlogged` to every
  leaves block with a mixin on its state definition, so the oracle's states of vanilla leaves
  carry it and a carried block's shape (`sift_rubble`: `acacia_leaves[…,ichorlogged=false,…]`)
  wasn't one of Horizon's: the whole content mod failed to load ("content mods not loaded"),
  and none of its blocks existed (an override template's mod blocks came out as air).
  `mod_content::shape_state` falls back to the vanilla state with the rest of the properties.
- **The breaker's own client gets no 2001 (26.3).** `Block.playerWillDestroy` sends its break
  sound and particles with `levelEvent(player, 2001, …)`, which skips that player: their client
  plays them itself. A doubled break sound is a 2001 (or a sound packet) reaching the breaker;
  count what the window's client receives, not what the server sends (bridge-trello-1).
- **26.3's template palettes are `id`/`properties`**; templates from older DataVersions
  (mods', 4903 in The Sift) still say `Name`/`Properties`. `StructureTemplate::from_nbt` reads
  both; nothing datafixes a pack template's blocks (vanilla's STRUCTURE fix type isn't
  ported), so renamed blocks of very old templates stay unknown.
- **A packet pipeline of the headless client (26.3 trap).** After a terminal packet
  (LoginFinished, FinishConfiguration) `ProtocolSwapHandler` swaps the decoder for
  `UnconfiguredPipelineHandler.Inbound` ("inbound_config") until `setupInboundProtocol`'s task
  comes back through the outbound pipeline: switch in the terminal packet's own handling, as
  vanilla's listeners do. Fabric's networking ends the old listener's session at
  `validateListener` and on `channelInactive`: listeners made without their constructors have
  none, so `HorizonPacketTap.unlisten` clears the connection's listener first.
- **Mods that apply their mixin configs themselves** (ReplayMod's launcher entrypoint) are
  missing from the census (`HorizonHooks.fire`, read from `fabric.mod.json`): fire such
  handlers with `fireMerged`, which reads the merged class.
