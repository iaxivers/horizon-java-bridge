# Java bridge performance

## The bridge switched off (maintainers' condition c, bridge-ownership-1)

What the bridge costs a player who doesn't use it: startup and frame time with `--benchmark standard`
(release builds, `docs/benchmark.md`), three ways: upstream's main; this branch built without the
`java-mods` feature; this branch built with it as the release builds it
(`HORIZON_BRIDGE_PLATFORM=source`), the switch off (no `--java-mods`) and a jar in `mods-java/`.

```sh
tools/java_bridge/perf_off.sh 5          # builds the three, runs each 5 times alternating, prints the table
SKIP_BUILD=1 tools/java_bridge/perf_off.sh 5   # again, same binaries
```

What runs with the switch off, by reading the code: `opted_in()` once (arguments and two variables),
one `mods-java/` listing for the log line, nothing else: no JVM, no thread, no library loaded, no
file of the bridge written. The build adds the bridge's code and its carried sources to the binary.

Machine: AMD Ryzen 5 9600X, AMD Radeon RX 9070 XT (RADV, Vulkan), Fedora 43 (kernel 6.19.12). Upstream
a27b7f20f, fork 8cff8dbc4. `--benchmark standard`, mean ± stdev over 5 runs each (frames: over every scene's
flight). Measured 2026-11-05 with `tools/java_bridge/perf_off.sh 5`.

| | Upstream main | java-bridge, no feature | java-mods build, switch off |
|---|---|---|---|
| Runs | 5 | 5 | 5 |
| Start to title (ms) | 772 ± 259 | 687 ± 8 | 676 ± 22 |
| Start to in-world (ms) | 2678 ± 300 | 2492 ± 68 | 2462 ± 110 |
| Average FPS | 3699 ± 338 | 3707 ± 383 | 3597 ± 379 |
| Frame p50 (ms) | 0.25 ± 0.02 | 0.25 ± 0.02 | 0.25 ± 0.02 |
| Frame p99 (ms) | 0.74 ± 0.13 | 0.74 ± 0.16 | 0.77 ± 0.16 |
| 1% low FPS | 856 ± 161 | 847 ± 161 | 827 ± 169 |
| Peak RSS (MB) | 1119 ± 11 | 1116 ± 15 | 1114 ± 12 |
| Binary (MB) | 89.9 | 87.9 | 93.4 |

**Reading it:** every difference is inside one standard deviation, so with the switch off the bridge costs
nothing measurable in start time, frame time or memory. The only real cost is the binary: +3.5 MB for the
`java-mods` build (the bridge's code and its Java sources). The plain build (no feature), which is what upstream
releases ship, is the same as main.

## The 81-jar test pack (2026-11-05, bridge-perf-1)

What a big Fabric pack costs Horizon: start time, memory, server tick time, and where they go.
Measured headless with `tools/java_bridge/perf_measure.sh`. Start with this page before tuning.

**Machine:** cloud container, 2 shared cores, 7.6 GB RAM, no GPU: Horizon's renderer and the mods'
GPU device (the headless client's) both run on **llvmpipe**, on the same 2 cores. A desktop is
faster everywhere, and much faster for the mods' GPU work (see "HUD" below). Compare runs on one
machine, not across machines.

**Pack:** `tools/java_bridge/get_test_pack.py` → 81 jars (72 run, 166 mods with nested jars).
Seed 1234, creative. Each run: join, stand 60 s, sprint forward 30 s, stand 20 s.

```sh
MODS=~/hz-test/mods-java CACHE=/tmp/pack-cache tools/java_bridge/perf_measure.sh pack   # the pack
MODS=~/hz-test/fapi tools/java_bridge/perf_measure.sh fapi                               # Fabric API alone
tools/java_bridge/perf_measure.sh vanilla                                                # no Java (JAVA_MODS=0)
```

It writes `/tmp/hz-perf/<name>/`: `/tick query` at each phase (`*.tick`), RSS (`*.status`), the
JVM's heap (`*.heap`) and native memory by area (`*.nmt`, `-XX:NativeMemoryTracking=summary`),
the live heap's classes after a full GC (`histo.txt`), the GC log, a JFR recording (`perf.jfr`:
CPU and native samples, allocations), the game's log with `HORIZON_JAVA_PROFILE=1`, screenshots.
jcmd attaches through `-XX:+StartAttachListener` (the bridge's JVM runs with `-Xrs`, so SIGQUIT,
which attach uses otherwise, is ignored). `launch.sh` now takes `JAVA_MODS=0` (the Java side off).

### The numbers

| | Vanilla Horizon | Fabric API alone | Pack, before | Pack, with the tap fix | Pack, fix + `-Xmx3g` |
|---|---|---|---|---|---|
| Start to joined | 10 s | 43–47 s | 182 s cold (content made: 52 s), 196 s warm | 209 s | 199 s |
| RSS standing / after walking | 1.49 / 1.63 GB | 2.26 / 2.45 GB | 4.42 / 4.24 GB | 4.06 / 4.18 GB | 3.93 / 4.95 GB |
| JVM committed (NMT) | — | 0.71 GB | 2.34–2.57 GB | 2.11–2.42 GB | — |
| Java heap: max / live after full GC | — | 1.5 GB / ~0.28 GB | 1.5 GB / 0.64–0.67 GB | 1.5 GB / 0.72–0.91 GB | 3 GB / 0.91–0.96 GB |
| Native "Other" (direct buffers) | — | ~0 | 250 → **795 MB** | **5 MB** | 5 MB |
| Tick avg / P95 / P99, standing (60 s) | 11.5 / 18 / 29 ms | 16.6 / 29 / 86 ms | 169 / 499 / 701 ms | **129** / 249 / 354 ms | 114 / 222 / 363 ms |
| Tick avg / P95 / P99, sprinting (30 s) | 12.4 / 22 / 25 ms | 19.5 / 33 / 49 ms | 243 / 654 / **3060** ms | **155** / 293 / **2080** ms | 114 / 212 / 288 ms |
| Tick avg / P95 / P99, standing after | 12.9 / 20 / 24 ms | 16.8 / 26 / 34 ms | 192 / 554 / 1019 ms | **108** / 211 / 293 ms | 100 / 215 / 237 ms |
| GC pauses (JFR window, ~2.5 min) | — | — | 158, 9.2 s, max 1.98 s | 198, 11.0 s, max 1.85 s | 108, 6.7 s, max 0.23 s |

`/tick query` covers the last 100 ticks of each phase. On 2 cores everything competes: the noise between two runs of the same jars is ±15% on tick
averages. RSS minus the JVM's committed memory is Horizon's own side (Rust, llvmpipe, the mods'
content, LWJGL's untracked mallocs): ~1.5 GB vanilla, ~1.7–1.8 GB with the pack.

### Where the server tick goes (pack, standing, after the fix)

`HORIZON_JAVA_PROFILE=1`, per server tick (`Java profile (mirror tick)`):

- **The headless client: 110–115 ms.** Its HUD (`client: HUD`) 50–78 ms, its tick
  (`client: tick`, the mods' client ticks) 19–26 ms, frame end ~6 ms.
- Mod entities 7.5 ms, their snapshot 4.3 ms, spawner 1.8 ms, the rest <1 ms each.
- Walking adds the mods' worldgen, called on the server thread: TERRAIN 75–118 ms a tick
  (145–675 ms a chunk), structure starts and references 34–100 ms, features 9–42 ms.

JFR on the server thread (Java and native samples):

- **HUD:** Java side ~40% of the client's samples. `HorizonGraphics.shrink` (downsizing a
  window-sized GPU target before sending it) is the biggest leaf. Then gson writing the frame,
  Base64 of image pixels, Xaero's minimap, JourneyMap, MiniHUD and Jade. Native side: the mods'
  GPU passes (`Gpu.endPass`, `clear`) and readbacks (`Gpu.readTexture`), here on llvmpipe, i.e.
  CPU. **This needs measuring on a real GPU** before anyone optimises it.
- **VoxelMap: ~16% of the server thread's Java time.** Its `MapChunk.isSurroundedByLoaded` asks
  for client chunks. Each answer is a `HorizonChunks` copy filled whole (98k blocks, lithium's
  state tracking on each set), refilled every time Horizon's chunk revision changes (any block
  set: random ticks, grass).
- Allocation: 37% of the pack's allocation pressure is vanilla's own `WorldGenRegion` constructor
  (`EnvironmentAttributeSystem` baked per step, all biomes' layers). Vanilla does the same per
  step. Its biome layer holds the region's `BiomeManager`, so it can't be shared per level.

### Where the memory goes (pack)

- **The JVM: 2.1–2.6 GB committed.** Java heap 1.2–1.5 GB committed (the cap is 25% of RAM:
  1.5 GB here), metaspace 212 MB, the CDS archive 228 MB (mapped from its file), code 86 MB,
  GC structures 66 MB, symbols 44 MB, class space 51 MB. JFR's own buffers (53 MB) are
  measurement overhead.
- **Live heap: 0.64–0.96 GB.** By package, shallow sizes after a full GC (the arrays belong to
  all of these):
  - The data fixer: **36–220 MB**, the biggest swing. Up to 6 rule chains for mods' old
    structure files are kept (`HorizonDataFixer.MAX_RULES`), ~35 MB each.
  - Client models: ~37 MB (BakedQuads ~26 MB: 357k quads, ModelPart vertices). The model
    bake keeps them for the mods' renderers and item models.
  - Structure templates: ~42 MB (StructureBlockInfo 20 MB + their BlockPos). The template
    manager's cache of every template loaded.
  - Mixin's class nodes: ASM 28 MB + Mixin 8 MB. Mixin keeps them, as in any Fabric game.
  - Block states and shapes: ~36 MB. Strings: ~25 MB (deduplicated).
  - Fabric API alone: ~0.28 GB live, of which client models 19 MB.
- **The mirror's chunks: small when idle** (PalettedContainers ~10 MB live). They churn: chunk
  copies are refilled whole on every revision.
- The JVM's heap uses a ceiling, not a target. Churn (worldgen regions, chunk copies, HUD frames
  as JSON strings) fills the 1.5 GB cap within minutes, and G1 then runs a **full compaction:
  1.85–1.98 s, in every run of the pack**. That is the P99 above.

### Where the start goes (pack, warm content cache, 196 s)

| Phase | Time |
|---|---|
| Horizon's content and first frame (mods' content from the cache) | 19 s |
| JVM + Knot + mixins (`Minecraft bootstrap done in 19.7 s`) | ~28 s |
| Mods' entrypoints (Fabric loader started: 166 mods) | ~26 s |
| Server data (5346 recipes, 4738 advancements) | 10 s |
| **Spawn area generated through Java** (TerraBlender, BOP: the overworld is Java's) | ~85 s |
| Headless client: atlases and model bake before the join | ~22 s |

Cold adds the content conversion (52 s), but here it overlapped. Cold and warm joined within
the noise (182 vs 196 s). The CDS archive is written at the first run's end and used after.

### Fixed in this branch

1. **The packet tap kept a broken packet's bytes for good** (`HorizonPacketTap.dropLeftover`).
   The tap runs when a mod mixes into `Connection`. In this pack that is FrozenLib's QSL
   `ConnectionMixin`, a registry-sync patch, not a recorder. A packet the decoder couldn't read
   whole (here a >1 MiB payload: "Payload may not be larger than 1048576 bytes") left its 2.3 MB
   rest in the `ByteToMessageDecoder`. Every later packet was appended to it: a new zeroed direct
   buffer of megabytes per packet. That was 31% of the server thread's Java time
   (`SegmentFactories.initNativeMemory`), up to 795 MB of native memory, and every later packet
   misread for the mods ("found 2325608 bytes extra", identifiers made of pixel data). Now each
   body's leftover is skipped (each body is one whole packet). Native "Other" 795 → 5 MB,
   average ticks 169/243/192 → 129/155/108 ms (standing, sprinting, after). ReplayMod's recording still passes (`regress.py replaymod`).
2. **HUD sprite lookups searched every jar each frame** (`HorizonSprites.texture`). A "directory"
   atlas source asks the class loader, which walks all 81 jars' zip indexes. This ran per sprite
   per frame: ~5% of the HUD's Java time. Now each sprite's answer is kept (the jars don't
   change while the game runs).
3. `HORIZON_JAVA_TAP=0` turns the tap off, for a pack whose connection mixins aren't recorders.
   The tap stays on by default (no change). After fix 1 the tap itself costs little here
   (tap off: 145/143/117 ms against 129/155/108, within the noise).

### The 3 biggest cheap wins (next)

1. **Raise the JVM's heap ceiling for big packs** (not done: it trades memory). With `-Xmx3g`
   the 1.9 s full compaction never happened: sprinting P99 2080 → 288 ms, GC pauses 11.0 → 6.7 s, max
   1.85 s → 0.23 s. The cost is +0.5–0.8 GB RSS at peak, given back as the heap shrinks
   (`MaxHeapFreeRatio=30`). A floor of 3 GB (or `-XX:MaxRAMPercentage=40`) when RAM ≥ 8 GB
   would do it. A player can set `HORIZON_JVM_OPTIONS=-Xmx3g` today. Pair it with win 2, so the
   extra room goes to churn, not caches.
2. **Keep fewer data-fixer chains** (`HorizonDataFixer.MAX_RULES` 6 → 1 or 2). Its own comment
   says a chain is rarely used twice. The live heap swung 36 → 220 MB from this alone. One
   constant. Check first how long rebuilding a chain takes during worldgen (the spawn area's
   85 s).
3. **Refill VoxelMap-style client chunk copies less.** `HorizonChunks.held` refills a whole copy
   (98k blocks) on any revision change. Following the change (as `kept` does with `written`), or
   keying revisions per section, would cut most of the 16%. Also cheap: answer a chunk's
   loaded-ness with no copy where a mod only asks that (`has` exists; VoxelMap's
   `isSurroundedByLoaded` goes through `getChunk`).

Worth a look after those, not cheap: the HUD on a real GPU first. If it's still big, draw the
HUD's GPU images at 10 Hz like big targets (`PIP_INTERVAL`), send image pixels as binary, not
Base64 in JSON, and downsize in Rust's readback. Also: generate the spawn area while the client
bakes its models (85 + 22 s, one after the other now), and drop the template manager's cache
once mods' templates are exported.

### Traps found

- jcmd can't attach to the bridge's JVM without `-XX:+StartAttachListener` (`-Xrs`).
- `GC.class_histogram -all` counts dead objects too (it skips the full GC): use it without `-all`.
- The heap's `used` right after a run is mostly garbage: read the live set from the GC log's
  `Pause Full (Heap Inspection Initiated GC) A->B`, B.
- `jfr print --json` of a few minutes is ~700 MB; parse the text form (`jfr print --events …`).
- JFR's Java samples miss the mods' GPU work: read `jdk.NativeMethodSample` too (`Gpu.*`).
- `lab.py decompile` was OOM-killed on 7.6 GB; `javap -c` on `.local/minecraft/26.3/jars/*.jar`
  answers single questions.

## Bridge vs real Fabric (2026-11-10, bridge-bench)

The same mods, seed and scripted scenario on a real Fabric 26.3 dedicated server and on Horizon's
dedicated server with the bridge, measured the same way: `tools/java_bridge/bench_vs_fabric.py`.
It answers the merge bar (less memory, lower MSPT, no broken saves, Fabric API parity on
tick-sensitive item transfer) with numbers.

**Like for like:** both sides are dedicated servers (a real Fabric *client* needs a GPU and a
login, so the client side isn't compared). Real Fabric: Fabric's installer, downloaded at run time
from maven.fabricmc.net into the scratch folder (with the loader the bridge pins, 0.20.1), and
vanilla's `server.jar` from `.local`; nothing of it is committed. Both run the bridge's pinned
Fabric API (`crates/horizon-jvm/fabric-api.tsv`; Horizon skips the folder's own). Horizon:
`horizon-client --dedicated --nogui`, the jars in `mods-java/`. By default it runs the headless
client as players get it (mods' screens and HUDs); `--horizon-java-side server` runs Fabric
without client code (`HORIZON_JAVA_SIDE=server`), closer to what a Fabric server does.

**The scenario** (console commands through a FIFO, identical on both; built at y 200 in the air,
force-loaded):

1. Start: launch to ready. Horizon's "Done" comes *before* its Java side on a warm start (the
   mods' content cached), so its side also waits for the mods' start hooks; the table shows both.
2. Frozen (`/tick freeze`): 48 vanilla hopper lines (chest → 8 hoppers → chest), 24 Create chute
   lines (chest → hopper → 4 chutes → chest), 16 Create kinetic lines (creative motor + 8 shafts).
3. **Parity:** `/tick step 200`, then every container's items and every kinetic block's speed
   (`/data get block`), diffed between the sides. Dumped again 5 s later, still frozen: anything
   that moved ticks through `/tick freeze`.
4. **Load:** unfrozen 120 s (items flowing), `/tick query` every 5 s; then a bot flies out over
   new chunks for 90 s (`horizon-bots --fabric`: it answers Fabric API's registry sync, which
   refuses plain clients once mods add blocks).
5. **Save:** frozen, dumped, `save-all flush`, `stop`, restarted on the same folder, dumped:
   every block entity back with its id and data keys, every line holding the same items in total.
6. **Cross-open** (`--cross-open`): Horizon's saved world opened by the real Fabric server, the
   same check (a player taking the world back to Java).

MSPT: each `/tick query` covers the last 100 ticks; avg is the mean of the windows, P50/P95 the
median window's, P99 the worst window's. RSS: the server process (Horizon's holds its JVM).
Heap: `jcmd GC.heap_info` (Horizon's JVM: `-XX:+StartAttachListener`, set by the script).

```sh
# Once: the game with the bridge (java-mods-dev, as server_test.sh) and the bots
cargo build --profile fast -p horizon-client -p horizon-client-lib -p horizon-bots --features horizon-client/java-mods-dev
# Fabric API + Create (any mods folder works; without a Create jar only hopper lines run)
python3 tools/java_bridge/bench_vs_fabric.py --mods ~/hz-test/bench-mods --cross-open
# The same, Fabric without client code on Horizon's side (closer to a Fabric server's work)
python3 tools/java_bridge/bench_vs_fabric.py --mods ~/hz-test/bench-mods --cross-open --horizon-java-side server
```

It writes `/tmp/hz-bench/last/report.md` (the table), `report.json` (every dump), both servers'
logs and the bot's report; `--keep` keeps the servers' folders, `--out` moves the results,
`--fabric-cache DIR` keeps Fabric's installer between runs. Sizes: `--lines`, `--chute-lines`,
`--kinetic-lines`, `--parity-ticks`, `--run-seconds`, `--explore-seconds`, `--xmx` (the same
heap cap for both JVMs; default: the JVM's own, 25% of RAM). About 15 minutes with the defaults.

### Smoke test (cloud: 2 shared cores, 7.8 GB, no GPU; NOT the numbers to quote)

Fabric API 0.165.0 + Create Fly 6.0.9 (unofficial 3), seed 1234, the default scenario with 60 s
of items and 60 s of exploring. Horizon d2af71b731 + this branch's fixes. On these 2 cores both
servers fight the bot and each other's leftovers; only the user's desktop numbers count.

| | Real Fabric | Horizon + bridge | Horizon, `--horizon-java-side server` |
|---|---|---|---|
| Start, launch to ready (s) | 18.0 | 31.3 (cold: content made) | 29.3 |
| Restart, launch to ready (s; its own "Done") | 17.0 (2.4) | 20.5 (6.7) | 17.6 (6.3) |
| MSPT avg / P50 / P95 / P99, items running | 0.7 / 0.6 / 0.9 / 31.1 | 2.9 / 2.6 / 4.6 / 23.7 | 2.8 / 2.4 / 4.4 / 37.9 |
| MSPT avg / P50 / P95 / P99, exploring | 14.3 / 14.0 / 24.5 / 541.9 | 17.6 / 15.6 / 34.9 / 57.6 | 9.9 / 9.0 / 21.3 / 45.8 |
| RSS items / exploring (MB) | 886 / 946 | 1273 / 1559 | 916 / 1127 |
| Peak RSS (MB) | 1048 | 1807 | 1313 |
| Java heap used / committed, exploring (MB) | 484 / 739 | 660 / 708 | 472 / 513 |
| Chunks the bot got exploring | 1543 | 2092 | 2143 |
| Parity diffs (200 ticks, 792 block entities) | — | 32 | — |
| Moved while frozen | 0 | 0 | 0 |
| Save check (restart) | OK | 24 lines lost an item | OK |
| Cross-open in real Fabric | — | 312 problems | — |

Reading it (cloud): with items running Horizon's tick costs ~2 ms more (the bridge's per-tick
work: the mirror's tick, syncing Horizon's hoppers with the mods' blocks); exploring, Horizon
sends more chunks and its worst windows are far lower (Fabric's P99 is its chunk generation on
the server thread). Memory: with the headless client Horizon is +0.4–0.8 GB; without it, close to
Fabric. The headless client is what makes mods' screens and HUDs work for players; it is the
first place to look for memory and tick time (see "Where the server tick goes" above).

### What it found (bugs)

Fixed in this branch:

1. **`/tick freeze` didn't stop the mods.** The mirror ticked mods' block entities, scheduled
   ticks, entities and spawner while Horizon's world was frozen (Create's chutes kept dropping
   items after `/tick step` ended). Now `events::game_time` carries whether the world runs
   (`Game::tick`'s `running`), `HorizonServer.tickBody` skips those when it doesn't, and the
   mirror's `TickRateManager` says frozen (`runsNormally`, `isEntityFrozen` for mods).
2. **`/data get block` on a mod's block entity said "not a block entity".** Horizon's command
   only looked in its own world; it now asks the mirror (`HorizonServer.blockEntityData`:
   the live one's `saveWithFullMetadata`, or the saved one not made yet).

Open (the harness shows them; each needs its own fix):

3. **Items lost on a warm restart.** Horizon's dedicated server prints "Done" and ticks the world
   ~14 s before its Java side is up when the mods' content is cached. In that window a vanilla
   hopper facing a mod block (a Create chute) pushes one item into it and the item is gone: one
   copper ingot lost in each of the 24 chute lines; the hopper then stops and fills up (32 items).
   Fix: hold the world's ticks (or at least transfers into mods' blocks) until the Java side is
   up, as joins already are.
4. **Mods' block entities aren't in the region files** (`horizon-java/block_entities/*.nbt`
   only). Real Fabric opening Horizon's world finds Create's blocks with fresh block entities:
   kinetic networks and speeds lost (`Network`, `Source`, `Speed` 16 → 0), the items inside
   chutes lost (each line 4 items short: the 3 in its chutes, and bug 3's; a mod's chest or
   machine would lose everything it holds). Fix:
   write the mods' block entities into their chunks' `block_entities` too.
5. **Create chutes one tick off in some chunks.** After 200 stepped ticks, the 16 chute lines in
   chunks z = 6 and 8 have one item still in the last chute where Fabric has it in the chest
   (22 vs 23); the 8 lines in chunk z = 7, every hopper line and every kinetic line match exactly.
   Probably the order in which the mirror makes or first ticks those block entities.
6. Minor: Horizon's `/data get block` on a hopper leaves out `TransferCooldown` (vanilla always
   writes it; Horizon's region files have it).

### Traps

- **Real Fabric refuses a plain client** once mods add registry entries ("requires Fabric Loader
  and Fabric API installed on your client"): `horizon-bots --fabric` sends `minecraft:register`
  for `fabric:registry/sync` and acknowledges the sync (`fabric:registry/sync/complete`).
- A bot flying at spawn + 24 hits mountains and the server pulls it back every tick (silently, no
  "moved wrongly" line): the harness flies it 200 blocks up.
- Vanilla's `/forceload add` loads the chunks at once; Horizon's loads them in the background:
  wait until the area answers (`setblock` → "That position is not loaded" until then).
- `pause-when-empty-seconds=0` or vanilla's server stops ticking a minute after the last player.
- 26.3's `/time query gametime` says "The game time is N tick(s)".
