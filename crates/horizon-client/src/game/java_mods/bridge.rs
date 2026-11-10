//! The bridge itself (feature `java-mods`): see java_mods.rs.

use super::super::Game;
use std::path::{Path, PathBuf};

/// Game and Fabric API classes loaded once through Fabric, to check the setup works.
const PROBE_CLASSES: &[&str] = &[
    "net/minecraft/world/level/block/Blocks",
    "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents",
];

/// Whether Fabric mods run in this game (`horizon_jvm::gate`): started by the launcher for an
/// owner (`--java-mods` and its owner record in `HORIZON_JAVA_MODS_OWNER`, confirmed with the
/// Minecraft services), or, in a developer's build (`java-mods-dev`), a harness
/// (`HORIZON_JAVA_MODS=1`). Otherwise a game built with Java mods plays as the plain one: no Java
/// started, nothing downloaded (no Fabric, no Java), whatever `mods-java/` holds. `--java-mods`
/// without an owner the game confirms says the launcher is needed, once; with jars in
/// `mods-java/` the game then stops ([`mod_jars`]): a modded world opened without its mods loses
/// their blocks and items.
pub(in crate::game) fn opted_in() -> bool {
    static ON: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ON.get_or_init(|| {
        use horizon_jvm::gate::{self, OptIn};
        // Read at the process's start already (`entry.rs`): the record is fresh there.
        let opt_in = gate::decision();
        match opt_in {
            OptIn::NoOwner(why) => {
                log::warn!(
                    "Java mods: off: {} ({})",
                    gate::NEEDS_LAUNCHER,
                    why.detail()
                );
            }
            OptIn::Launcher => {
                log::info!(
                    "Java mods: on (the launcher's owner, confirmed by the Minecraft services)"
                )
            }
            #[cfg(feature = "java-mods-dev")]
            OptIn::Developer => {
                log::info!("Java mods: on for a developer's run ({}=1)", gate::DEV_VAR)
            }
            _ => {}
        }
        opt_in.runs()
    })
}

/// `mods-java/` and its jars when the player opted in; its jars are none otherwise (said once in
/// the log when it holds some).
pub(in crate::game) fn mod_jars() -> (PathBuf, Vec<PathBuf>) {
    let dir = Game::options_path().with_file_name(horizon_jvm::DIR);
    if opted_in() {
        let jars = run_jars(&dir);
        return (dir, jars);
    }
    static SAID: std::sync::Once = std::sync::Once::new();
    SAID.call_once(|| {
        let held = horizon_jvm::jars(&dir).len();
        // Asked for (`--java-mods`) but refused: stop rather than open the world without them.
        if let horizon_jvm::gate::OptIn::NoOwner(why) = horizon_jvm::gate::decision() {
            if held > 0 {
                not_started(&format!(
                    "{} ({})",
                    horizon_jvm::gate::NEEDS_LAUNCHER,
                    why.detail()
                ));
            } else {
                eprintln!("{} ({})", horizon_jvm::gate::NEEDS_LAUNCHER, why.detail());
            }
            return;
        }
        if held > 0 {
            log::info!(
                "Java mods: off ({held} jars in {}): the launcher runs them, with Fabric 26.3 mods on",
                horizon_jvm::DIR
            );
        }
    });
    (dir, Vec::new())
}

impl Game<'_> {
    /// Starts the JVM and Fabric if the player opted in and `mods-java/` holds jars. No jars,
    /// no JVM: one folder read.
    pub(in crate::game) fn start_java_mods(&mut self) {
        let (dir, jars) = mod_jars();
        if jars.is_empty() {
            return;
        }
        let _ = horizon_client::world::SET_HOOK.set(super::super::java_server::java_block_set);
        // Started already while the resources loaded (`java_content`), as a rule.
        spawn_start(dir, jars);
    }
}

/// The process's start (entry.rs): Fabric mods' owner record (`horizon_jvm::gate`) is read
/// now, while it is fresh, beside the start; the bridge waits for the answer when it needs it.
pub(crate) fn owner_record_early() {
    if std::env::args().any(|a| a == horizon_jvm::gate::ARG) {
        let _ = std::thread::Builder::new()
            .name("Java mods owner".into())
            .spawn(horizon_jvm::gate::decision);
    }
}

/// The process's end (entry.rs): with Java mods it ends through Java's exit (its class
/// archive is written).
pub(crate) fn exit_through_java() {
    horizon_jvm::exit(0, std::time::Duration::from_secs(90), crate::logging::flush);
}

/// Starts Java and Fabric now, beside the rest of the resources' load, once the content root
/// is there (built or reused: a build beside Java's start outgrew 6 GB for a 110-jar pack,
/// the load after it doesn't): the JVM's start, over a minute for a big pack, no longer
/// waits for the window's resources. `HORIZON_JAVA_EARLY_START=0` waits as before.
pub(in crate::game) fn start_early() {
    if std::env::var("HORIZON_JAVA_EARLY_START").is_ok_and(|v| v == "0") {
        return;
    }
    let (dir, jars) = mod_jars();
    if !jars.is_empty() {
        spawn_start(dir, jars);
    }
}

/// Starts the JVM and Fabric on the "Java mods" thread, once a process: from [`start_early`]
/// once the content root is there, else from `start_java_mods`.
fn spawn_start(dir: PathBuf, jars: Vec<PathBuf>) {
    if SPAWNED.swap(true, std::sync::atomic::Ordering::SeqCst) {
        return;
    }
    // The window's own player's packets are kept until Java says whether its client reads
    // them (a join's configuration may come before Java's start ends).
    horizon_client::lan::packet_tap::undecided();
    let root = checkout();
    let side = bridge_side();
    let spawned = std::thread::Builder::new()
        .name("Java mods".into())
        .spawn(move || {
            start(&root, &dir, jars, side);
        });
    if let Err(e) = spawned {
        log::warn!("Java mods: no thread to start them on: {e}");
        SETTLED.store(true, std::sync::atomic::Ordering::SeqCst);
    }
}

/// The side Fabric runs as: the client's, with the platform's headless client (no window, no
/// GPU) for mods' client code, whose screens Horizon shows; the server mirror runs beside it.
/// A dedicated server too: its players' mod screens are drawn there and sent to their games,
/// as a LAN host's guests' are. `HORIZON_JAVA_SIDE=server` runs Fabric as a dedicated
/// server's (no client code: lighter, and mods' screens show only as plain menus or not at all).
fn bridge_side() -> horizon_jvm::Side {
    if std::env::var("HORIZON_JAVA_SIDE").is_ok_and(|s| s == "server") {
        horizon_jvm::Side::Server
    } else {
        horizon_jvm::Side::Client
    }
}

/// The checkout (or install) holding research-lock.json and the prepared jars: `HORIZON_ROOT`,
/// else the folder the game runs in when it has research-lock.json (a checkout), else the
/// install above `--resources` (`<install>/.local/horizon-client/<version>`: the launcher
/// starts the game in the player's game folder with its version's resources).
fn checkout() -> PathBuf {
    if let Some(root) = std::env::var_os("HORIZON_ROOT").filter(|r| !r.is_empty()) {
        return PathBuf::from(root);
    }
    let here = std::env::current_dir().unwrap_or_default();
    if here.join("research-lock.json").is_file() {
        return here;
    }
    install_root(std::env::args()).unwrap_or(here)
}

/// The install above `--resources` in `args`, if it holds research-lock.json.
fn install_root(args: impl Iterator<Item = String>) -> Option<PathBuf> {
    let mut args = args.peekable();
    let mut resources = None;
    while let Some(arg) = args.next() {
        if let Some(value) = arg.strip_prefix("--resources=") {
            resources = Some(PathBuf::from(value));
        } else if arg == "--resources" {
            resources = args.next().map(PathBuf::from);
        }
    }
    // Absolute, its links kept (an install may link its resources from elsewhere).
    let resources = std::path::absolute(resources?).ok()?;
    resources
        .ancestors()
        .nth(3)
        .filter(|root| root.join("research-lock.json").is_file())
        .map(Path::to_path_buf)
}

/// The jars of `dir` (`mods-java/`) Fabric loads: all but the renderer mods Horizon doesn't
/// need (Sodium, Iris: Horizon has its own renderer) and the mods that need them
/// (`horizon_jvm::renderers`). Worked out once for a set of jars.
fn run_jars(dir: &Path) -> Vec<PathBuf> {
    let jars = horizon_jvm::jars(dir);
    let mut last = SPLIT.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((all, kept, _)) = last.as_ref()
        && *all == jars
    {
        return kept.clone();
    }
    let (kept, left) = horizon_jvm::renderers::split(&jars);
    // Horizon's pinned Fabric API, always: a player's own (another version) is skipped.
    let (theirs, kept): (Vec<PathBuf>, Vec<PathBuf>) =
        kept.into_iter().partition(|j| own_fabric_api(j));
    for jar in &theirs {
        log::info!(
            "Java mods: {} skipped: Horizon brings its own Fabric API (the file is left as it is)",
            jar.file_name().unwrap_or_default().to_string_lossy()
        );
    }
    *last = Some((jars, kept.clone(), left));
    kept
}

/// Whether `jar` is Fabric API (its id `fabric-api`, or a file named `fabric-api…`), which
/// Horizon brings pinned ([`horizon_jvm::libs::ensure_fabric_api`]).
fn own_fabric_api(jar: &Path) -> bool {
    let named = jar.file_name().is_some_and(|n| {
        n.to_string_lossy()
            .to_ascii_lowercase()
            .starts_with("fabric-api")
    });
    named || horizon_jvm::renderers::mod_id(jar).as_deref() == Some("fabric-api")
}

/// The last [`run_jars`]: all jars, the ones Fabric loads, the ones left out.
#[allow(clippy::type_complexity)]
static SPLIT: std::sync::Mutex<
    Option<(
        Vec<PathBuf>,
        Vec<PathBuf>,
        Vec<horizon_jvm::renderers::LeftOut>,
    )>,
> = std::sync::Mutex::new(None);

/// The mods [`run_jars`] left out.
fn left_out() -> Vec<horizon_jvm::renderers::LeftOut> {
    SPLIT
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .as_ref()
        .map(|(_, _, left)| left.clone())
        .unwrap_or_default()
}

/// The content mods made from the Java mods in `mods-java/` (their blocks, items and assets,
/// horizon-jvm's `content`), for the game's content root over `base` (the prepared tables):
/// read back from the cache when the jars are the ones they were made from, else made now,
/// with Java and Fabric started for it (they stay up for the game).
pub(in crate::game) fn java_content(base: &Path) -> Vec<PathBuf> {
    let (dir, jars) = mod_jars();
    if jars.is_empty() || neoforge_install().is_some() {
        // NeoForge (prototype): no content conversion yet.
        return Vec::new();
    }
    let cache = super::super::mods::horizon_cache().join("java-content");
    if let Some(folders) = horizon_jvm::content::cached(&jars, &cache) {
        return folders;
    }
    let started = std::time::Instant::now();
    log::info!("Java mods: making their content (once for this set of mods)");
    if !start(&checkout(), &dir, jars.clone(), bridge_side()) {
        return Vec::new();
    }
    match horizon_jvm::content::build(&jars, base, &cache, &|l| log::info!("{l}")) {
        Ok(folders) => {
            log::info!(
                "Java mods: {} content mod(s) made in {} ms",
                folders.len(),
                started.elapsed().as_millis()
            );
            folders
        }
        Err(e) => {
            log::warn!("Java mods: their content was not made: {e}");
            Vec::new()
        }
    }
}

/// Puts the creative tabs the Java mods' content build exported (their tabs, their items in
/// vanilla's, the search index) over the prepared ones in the game's content root `out`.
pub(in crate::game) fn java_creative(out: &Path) {
    let cache = super::super::mods::horizon_cache().join("java-content");
    // The mods' block tints, for the mesher (BOP's flowering leaves take the foliage colour).
    if let Some(tints) = horizon_jvm::content::tints(&cache) {
        match std::fs::read_to_string(&tints)
            .map_err(|e| e.to_string())
            .and_then(|t| horizon_client::mesher::set_mod_tints(&t).map_err(|e| e.to_string()))
        {
            Ok(n) => log::info!("Java mods: tints of {n} block(s)"),
            Err(e) => log::warn!("Java mods: their block tints were not read: {e}"),
        }
    }
    let Some(exported) = horizon_jvm::content::creative(&cache) else {
        return;
    };
    let at = out.join(horizon_jvm::content::CREATIVE);
    // The content root links the prepared file: the link goes, the export takes its place.
    let _ = std::fs::remove_file(&at);
    if let Err(e) = std::fs::copy(&exported, &at) {
        log::warn!("Java mods: their creative tabs were not added: {e}");
    }
}

/// Starts the JVM and Fabric (once: again, the running one), then logs what loaded. Runs on
/// the "Java mods" thread, or before the resources load when the mods' content is made. Whether
/// they run.
fn start(root: &Path, dir: &Path, jars: Vec<PathBuf>, side: horizon_jvm::Side) -> bool {
    let started = start_once(root, dir, jars, side);
    let wanted = started && horizon_jvm::events::wants_packets();
    horizon_client::lan::packet_tap::want(wanted);
    if wanted {
        log::info!(
            "Java mods: the client reads its connection's packets (a recording mod): the window's player's are passed"
        );
    }
    if started {
        register_extra_levels();
    }
    SETTLED.store(true, std::sync::atomic::Ordering::SeqCst);
    started
}

/// The mods' dimensions (level stems beyond vanilla's three) become levels of Horizon's
/// server (`chunk::ExtraLevel`, `Dim::Extra`), as `MinecraftServer.createLevels` makes one
/// per stem: `/execute in`, `/tp`, mods' teleports and saved players reach them.
fn register_extra_levels() {
    let levels = match horizon_jvm::events::extra_levels() {
        Ok(levels) => levels,
        Err(e) => {
            log::warn!("Java mods: their dimensions were not read: {e}");
            return;
        }
    };
    for l in levels {
        let shape = match horizon_client::chunk::LevelShape::new(
            l.min_y,
            l.height,
            l.logical_height,
            l.has_skylight,
            l.has_ceiling,
        ) {
            Ok(shape) => shape,
            Err(e) => {
                log::warn!("Java mods: the dimension {} can't be a level: {e}", l.id);
                continue;
            }
        };
        // Drawn as the vanilla level its type is most like: a ceiling or no skylight as the
        // Nether, else the Overworld.
        let looks_like = if l.has_ceiling || !l.has_skylight {
            "minecraft:the_nether"
        } else {
            "minecraft:overworld"
        };
        let level = horizon_client::chunk::ExtraLevel {
            id: Box::leak(l.id.clone().into_boxed_str()),
            dimension_type: Box::leak(l.dimension_type.clone().into_boxed_str()),
            shape,
            coordinate_scale: l.coordinate_scale,
            looks_like,
        };
        if horizon_client::chunk::extra_level_named(&l.id) == Some(level) {
            continue;
        }
        match horizon_client::chunk::register_extra_level(level) {
            Some(_) => log::info!(
                "Java mods: the dimension {} is a level (type {}, y {}..{})",
                l.id,
                l.dimension_type,
                l.min_y,
                l.min_y + l.height
            ),
            None => log::warn!("Java mods: too many dimensions: {} has no level", l.id),
        }
    }
}

/// The level `id` names, waiting for the Java mods' start when it isn't one of the server's
/// yet (a world saved in a mod's dimension opens before Java has said what they are).
pub(in crate::game) fn level_named(id: &str) -> super::super::Dim {
    if let Some(dim) = super::super::Dim::known(id) {
        return dim;
    }
    if has_java_mods() {
        wait_for_java(std::time::Duration::from_secs(300));
    }
    super::super::Dim::from_id(id)
}

/// The window and the Java mods' GPU device use one Vulkan instance, whichever makes it first:
/// a second instance's device enumeration crashed in the Vulkan loader (Fedora 43, RADV, the
/// user's PC, 2026-11-05: the game died opening its window after Java's device was up).
fn share_vulkan_instance() {
    let _ = horizon_client::gfx::SHARED_INSTANCE.set(horizon_jvm::gpu::instance);
    let _ = horizon_jvm::gpu::SHARED_INSTANCE.set(horizon_client::gfx::own_instance);
}

/// Set once Java's start is under way (its thread spawned).
static SPAWNED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Set once Java's start has ended, whether it started or not.
static SETTLED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Waits (up to `limit`) for the Java mods' start to end: whether the platform mod runs.
pub(in crate::game) fn wait_for_java(limit: std::time::Duration) -> bool {
    let until = std::time::Instant::now() + limit;
    while !SETTLED.load(std::sync::atomic::Ordering::SeqCst) && std::time::Instant::now() < until {
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    horizon_jvm::events::ready()
}

/// Whether the Java mods' start is still pending: a server doesn't tick its levels until it
/// ends (a vanilla server loads its mods before its first tick; a hopper pushing into a mod's
/// block before the mods are up lost the item: bridge-benchfix).
pub(in crate::game) fn java_starting() -> bool {
    SPAWNED.load(std::sync::atomic::Ordering::SeqCst)
        && !SETTLED.load(std::sync::atomic::Ordering::SeqCst)
}

/// Whether the Java mods' start is still pending, or their data-pack load functions haven't
/// run on the mirror yet: a new player waits (`Game::hold_join`), as a vanilla server runs
/// them before anyone joins (Simply OneBlock's sets the world spawn and its block).
pub(in crate::game) fn java_loading() -> bool {
    if !has_java_mods() {
        return false;
    }
    if !SETTLED.load(std::sync::atomic::Ordering::SeqCst) {
        return true;
    }
    horizon_jvm::events::ready() && !horizon_jvm::events::functions_ready()
}

/// Whether `mods-java/` holds jars (the Java mods start, or started).
pub(in crate::game) fn has_java_mods() -> bool {
    // Asked every few milliseconds while the spawn is searched: listing and resolving a
    // big pack's jars each time took a core of the two here. Java runs once a process, so
    // once it runs the answer stays; before, it's looked again at most every two seconds.
    if horizon_jvm::get().is_some() {
        return true;
    }
    static LAST: std::sync::Mutex<Option<(std::time::Instant, bool)>> = std::sync::Mutex::new(None);
    let mut last = LAST.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((at, has)) = *last
        && at.elapsed() < std::time::Duration::from_secs(2)
    {
        return has;
    }
    let has = !mod_jars().1.is_empty();
    *last = Some((std::time::Instant::now(), has));
    has
}

/// The class archive for this set of jars (`horizon_jvm::Config::class_archive`):
/// `.horizon-cache/java-classes-<stamp>.jsa`, the archives of other sets removed (each is
/// ~250 MB for a 110-jar pack). `HORIZON_JAVA_CLASS_ARCHIVE=0`: none.
fn class_archive(jars: &[PathBuf]) -> Option<PathBuf> {
    if std::env::var_os("HORIZON_JAVA_CLASS_ARCHIVE").is_some_and(|v| v == "0") {
        return None;
    }
    let cache = super::super::mods::horizon_cache();
    let name = format!("java-classes-{}.jsa", horizon_jvm::content::stamp(jars));
    if let Ok(entries) = std::fs::read_dir(&cache) {
        for entry in entries.flatten() {
            let other = entry.file_name().to_string_lossy().into_owned();
            if other.starts_with("java-classes-") && other != name {
                let _ = std::fs::remove_file(entry.path());
            }
        }
    }
    Some(cache.join(name))
}

/// The player's mods can't run. The game stops rather than going on without them: they'd think
/// the mods were loaded, and a world opened without its mods loses their blocks and items.
/// `HORIZON_JAVA_OPTIONAL=1` goes on without them. Exits with [`horizon_jvm::NOT_STARTED_EXIT`].
fn not_started(reason: &str) {
    log::error!("Java mods: not started: {reason}");
    log::logger().flush();
    if std::env::var("HORIZON_JAVA_OPTIONAL").is_ok_and(|v| v == "1") {
        log::warn!("Java mods: going on without them (HORIZON_JAVA_OPTIONAL)");
        return;
    }
    eprintln!("Your Java mods couldn't start, so the game stopped: {reason}");
    std::process::exit(horizon_jvm::NOT_STARTED_EXIT)
}

fn start_once(root: &Path, dir: &Path, jars: Vec<PathBuf>, side: horizon_jvm::Side) -> bool {
    share_vulkan_instance();
    if horizon_jvm::get().is_some() {
        return true;
    }
    // One JVM a process: after a failed start, another can't be made.
    static TRIED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
    if TRIED.swap(true, std::sync::atomic::Ordering::SeqCst) {
        return false;
    }
    let started = std::time::Instant::now();
    let work = root.join(".local/bridge");
    #[cfg(feature = "java-mods-prototypes")]
    if let Some(install) = neoforge_install() {
        return start_neoforge(&work, &install, &jars, started);
    }
    let fabric = fabric(root, dir, &work, &jars, side);
    let fabric = match fabric {
        Ok(fabric) => Some(fabric),
        Err(e) => {
            not_started(&format!("Fabric can't start: {e}"));
            None
        }
    };
    let archive = class_archive(&jars);
    let config = |home| horizon_jvm::Config {
        home,
        work: work.clone(),
        jars: jars.clone(),
        class_path: Vec::new(),
        fabric,
        class_archive: archive.clone(),
    };
    // The game downloads no Java: the launcher fetches the version's own, after its sign-in.
    let bridge = match horizon_jvm::find_java_home()
        .map_err(|e| {
            format!(
                "{e}. Start the game from the Horizon launcher with Fabric 26.3 mods on (it gets \
                 Java {} once you're signed in), or install a JDK {}",
                horizon_jvm::JAVA_MAJOR,
                horizon_jvm::JAVA_MAJOR
            )
        })
        .and_then(|home| horizon_jvm::start(&config(home)))
    {
        Ok(bridge) => bridge,
        Err(e) => {
            not_started(&e);
            return false;
        }
    };
    match &archive {
        Some(a) if a.exists() => log::info!("Java mods: classes mapped from {}", a.display()),
        Some(a) => log::info!(
            "Java mods: the classes this run loads are archived when the game quits ({}): the next start maps them",
            a.display()
        ),
        None => {}
    }
    log::info!(
        "Java mods: JVM {} from {} and {} started in {} ms, {} jar(s) in {}",
        bridge.java_version(),
        bridge.home().display(),
        if bridge.fabric() {
            "Fabric"
        } else {
            "no Fabric"
        },
        started.elapsed().as_millis(),
        jars.len(),
        dir.display()
    );
    if bridge.fabric() {
        let mods: Vec<&str> = bridge
            .mods()
            .iter()
            .map(String::as_str)
            .filter(|m| !m.starts_with("fabric-") || m.starts_with("fabric-api "))
            .collect();
        log::info!("Java mods: Fabric loaded {}", mods.join(", "));
        // Renderer mods left out: noted, and told to the players as they join.
        let mut left: Vec<String> = left_out().iter().map(|l| l.line()).collect();
        // Mods that load but only partly work here (Flashback).
        left.extend(horizon_jvm::renderers::partial_lines(bridge.mods()));
        for line in &left {
            log::info!("Java mods: {line}");
        }
        if !left.is_empty()
            && let Err(e) = horizon_jvm::events::not_needed(&left)
        {
            log::warn!("Java mods: the left-out mods weren't told: {e}");
        }

        for class in PROBE_CLASSES {
            if let Err(e) = bridge.check_class(class) {
                log::warn!("Java mods: {class} failed to load: {e}");
            }
        }
    }
    true
}

/// `HORIZON_NEOFORGE`: a folder NeoForge's installer ran in (`--installServer`), set up by
/// `tools/java_bridge/neoforge/run_proto.sh`. The mods in `mods-java/` then load with
/// NeoForge instead of Fabric: a prototype (docs/java-bridge-neoforge.md) that loads them and
/// stops there, with no mirror, content or screens yet. Only in builds with the prototypes
/// (`java-mods-prototypes`): release builds run Fabric only.
#[cfg(not(feature = "java-mods-prototypes"))]
fn neoforge_install() -> Option<PathBuf> {
    None
}
#[cfg(feature = "java-mods-prototypes")]
fn neoforge_install() -> Option<PathBuf> {
    std::env::var_os("HORIZON_NEOFORGE")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
}

/// NeoForge's start (prototype): FML's game folder is `.local/bridge/neoforge-game`, whose
/// `mods/` holds copies of the jars in `mods-java/` and of the platform mod the setup built.
#[cfg(feature = "java-mods-prototypes")]
fn start_neoforge(
    work: &Path,
    install: &Path,
    jars: &[PathBuf],
    started: std::time::Instant,
) -> bool {
    let game_dir = work.join("neoforge-game");
    let mods = game_dir.join("mods");
    let linked = (|| -> std::io::Result<()> {
        let _ = std::fs::remove_dir_all(&mods);
        std::fs::create_dir_all(&mods)?;
        let platform = install.join("horizon/horizon-neo-platform.jar");
        for jar in jars.iter().chain(std::iter::once(&platform)) {
            if let Some(name) = jar.file_name() {
                std::fs::copy(jar, mods.join(name))?;
            }
        }
        Ok(())
    })();
    if let Err(e) = linked {
        not_started(&format!("NeoForge's mods folder: {e}"));
        return false;
    }
    // The game downloads no Java (launcher only): the launcher's, or an installed JDK.
    let bridge = match horizon_jvm::find_java_home().and_then(|home| {
        horizon_jvm::neoforge::start(&horizon_jvm::neoforge::NeoForge {
            home,
            work: work.to_path_buf(),
            install: install.to_path_buf(),
            game_dir: game_dir.clone(),
        })
    }) {
        Ok(bridge) => bridge,
        Err(e) => {
            not_started(&format!("NeoForge: {e}"));
            return false;
        }
    };
    log::info!(
        "Java mods: JVM {} and NeoForge started in {} ms; loaded {}",
        bridge.java_version(),
        started.elapsed().as_millis(),
        bridge.mods().join(", ")
    );
    true
}

/// Fabric's part of the config: the prepared game jar and libraries, and Horizon's pinned Fabric
/// API (a player's own is skipped, [`run_jars`]).
fn fabric(
    root: &Path,
    dir: &Path,
    work: &Path,
    jars: &[PathBuf],
    side: horizon_jvm::Side,
) -> Result<horizon_jvm::Fabric, String> {
    let mut add_mods = vec![horizon_jvm::libs::ensure_fabric_api(
        &work.join("fabric-api"),
    )?];
    // With jars left out (`run_jars`: renderers, a player's own Fabric API), Fabric reads no
    // folder: the jars it loads are listed.
    let mods = dir.to_path_buf();
    let mods = if horizon_jvm::jars(dir).len() == jars.len() {
        mods
    } else {
        let none = work.join("no-mods");
        std::fs::create_dir_all(&none).map_err(|e| format!("{}: {e}", none.display()))?;
        add_mods.extend(jars.iter().cloned());
        none
    };
    Ok(horizon_jvm::Fabric {
        game_jar: horizon_jvm::facade::game_jar(root)?,
        libraries: horizon_jvm::facade::game_libraries(root)?,
        add_mods,
        platform: true,
        mods,
        game_dir: dir.parent().map(Path::to_path_buf).unwrap_or_default(),
        version: game_version(root)?,
        side,
    })
}

/// The game version research-lock.json pins.
fn game_version(root: &Path) -> Result<String, String> {
    let text = std::fs::read_to_string(root.join("research-lock.json"))
        .map_err(|e| format!("research-lock.json: {e}"))?;
    let json: serde_json::Value =
        serde_json::from_str(&text).map_err(|e| format!("research-lock.json: {e}"))?;
    json.pointer("/minecraft/version")
        .and_then(serde_json::Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| "research-lock.json has no minecraft version".into())
}
