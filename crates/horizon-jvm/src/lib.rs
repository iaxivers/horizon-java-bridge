//! Java mods inside Horizon: the Java mod bridge.
//!
//! The game starts one JVM per process, and only when `mods-java/` (beside `options.txt`)
//! holds at least one `.jar`. With no jars nothing here runs: no library is loaded and no
//! thread is made. The JVM is the player's own Java (Minecraft's runtime, `JAVA_HOME` or the
//! PATH, found the way `horizon-prepare` finds it), loaded at run time from its `libjvm`, so
//! the game links against no JDK.
//!
//! What runs in it:
//! - the bridge's own jar (`java/dev/horizonlab/bridge/`, built by `build.rs`, embedded in the
//!   binary and written to the work folder), with Rust natives on `dev.horizonlab.bridge.Natives`;
//! - with a game jar (the player's own, [`facade::game_jar`]), Fabric Loader ([`libs`])
//!   through `HorizonGameProvider`: it finds the mods in `mods-java/` and Fabric API, applies
//!   their Mixins to the game's classes, bootstraps the game's registries and runs the mods'
//!   `main` and `client`/`server` entrypoints.
//!
//! A JVM can be created once per process (JNI allows no second one after a destroy), so
//! [`start`] is idempotent: later calls return the running bridge.

#[cfg(feature = "prototypes")]
pub mod boot112;
pub mod content;
pub mod device;
pub mod events;
pub mod facade;
pub mod gate;
pub mod gpu;
pub mod libs;
#[cfg(feature = "prototypes")]
pub mod neoforge;
pub mod platform;
pub mod renderers;
pub mod runtime;
pub mod world;

use jni::objects::{JClass, JObject, JObjectArray, JString, JValue};
use jni::{JNIEnv, JavaVM, NativeMethod, sys};
use std::ffi::{CString, c_void};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

/// The folder of Java mods, beside `options.txt`.
pub const DIR: &str = "mods-java";

/// The Java version mods for Minecraft 26.3 are built for (`research-lock.json`'s `java_major`).
pub const JAVA_MAJOR: i64 = 25;

/// Overrides the Java used, whatever its version (a developer's JDK). Its value is a Java home:
/// the folder holding `bin/` and `lib/`.
pub const JAVA_HOME_VAR: &str = "HORIZON_JAVA_HOME";

/// The game's exit code when the player's Java mods can't start (its log says why).
pub const NOT_STARTED_EXIT: i32 = 78;

/// The bridge's jar, built by build.rs; empty in a release build, which carries [`BRIDGE_SRC`].
const BRIDGE_JAR: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/horizon-bridge.jar"));
/// The bridge's sources (`java/dev/` and its service file) in a release build
/// (`HORIZON_BRIDGE_PLATFORM=source`), compiled on the player's machine against the Fabric Loader
/// fetched there ([`platform::ensure_bridge`]): the build machine reads nothing of Fabric's.
/// Empty when the jar is built in.
const BRIDGE_SRC: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/horizon-bridge-src.zip"));
/// The platform mod (`java/platform/`, Fabric mod `horizon`), built by build.rs against the
/// prepared game jar and Fabric API; empty when the checkout had no prepared game to build it
/// against.
const PLATFORM_JAR: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/horizon-platform.jar"));
/// The platform mod's sources, when the build had no game to build it against (a release
/// build): built on the player's machine ([`platform::ensure`]); empty when the jar is built in.
const PLATFORM_SRC: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/horizon-platform-src.zip"));

static BRIDGE: OnceLock<Bridge> = OnceLock::new();
/// Held while a JVM is being made, so two threads never both try.
static STARTING: Mutex<()> = Mutex::new(());
/// Where `Natives.log` lines go instead of the game log (tests).
static SINK: Mutex<Option<fn(&str)>> = Mutex::new(None);

/// Which half of the game the mods run in: their `client` or `server` entrypoints.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Side {
    Client,
    Server,
}

/// What [`start`] runs.
pub struct Config {
    /// The Java home to load the JVM from ([`find_java_home`]).
    pub home: PathBuf,
    /// The bridge's own files: its jar, and Fabric Loader's in `lib/`.
    pub work: PathBuf,
    /// The jars in `mods-java/` ([`jars`]).
    pub jars: Vec<PathBuf>,
    /// More class path entries (tests put a facade there to load it without Fabric).
    pub class_path: Vec<PathBuf>,
    /// Fabric Loader runs only with a game jar.
    pub fabric: Option<Fabric>,
    /// A class archive (CDS) the JVM maps at start and writes at its exit when missing or
    /// stale (`-XX:+AutoCreateSharedArchive`): the classes it loaded, the mods' too, parsed
    /// and verified once. Written only when the process ends through [`exit`].
    pub class_archive: Option<PathBuf>,
}

/// Fabric Loader's part of [`Config`].
pub struct Fabric {
    /// The game jar ([`facade::game_jar`], or a facade, [`facade::ensure`]).
    pub game_jar: PathBuf,
    /// The game's libraries ([`facade::game_libraries`]), on Fabric's class path beside it.
    pub libraries: Vec<PathBuf>,
    /// Mod jars to load besides the folder's: Fabric API ([`libs::ensure_fabric_api`]).
    pub add_mods: Vec<PathBuf>,
    /// Loads the platform mod (game events for mods, [`events`]) when this build has it. Off
    /// for a game jar that isn't Minecraft (the bridge's own tests).
    pub platform: bool,
    /// The folder Fabric reads mods from (`mods-java/`).
    pub mods: PathBuf,
    /// The game folder: Fabric writes `config/` there.
    pub game_dir: PathBuf,
    /// The game version Horizon plays.
    pub version: String,
    pub side: Side,
}

/// The running JVM.
pub struct Bridge {
    vm: JavaVM,
    java_version: String,
    home: PathBuf,
    /// The mods Fabric loaded, `id version`, built-in ones included; empty without Fabric.
    mods: Vec<String>,
    fabric: bool,
    class_archive: Option<PathBuf>,
}

impl Bridge {
    /// The JVM, for attaching other threads.
    pub fn vm(&self) -> &JavaVM {
        &self.vm
    }
    /// `Runtime.version()` of the running JVM, as Java reported it.
    pub fn java_version(&self) -> &str {
        &self.java_version
    }
    /// The Java home the JVM was loaded from.
    pub fn home(&self) -> &Path {
        &self.home
    }
    /// Whether Fabric Loader started.
    pub fn fabric(&self) -> bool {
        self.fabric
    }
    /// The mods Fabric loaded, as `id version`: `minecraft`, `java` and `fabricloader` too.
    pub fn mods(&self) -> &[String] {
        &self.mods
    }
    /// Loads (and initializes) a class by its binary name, `net/minecraft/...`: through
    /// Fabric's class loader when Fabric runs (so Mixins apply), else the system one.
    pub fn check_class(&self, name: &str) -> Result<(), String> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|e| format!("attach: {e}"))?;
        let found = self.find_class(&mut env, name);
        match found {
            Ok(class) => {
                let _ = env.delete_local_ref(class);
                Ok(())
            }
            Err(e) => Err(describe(&mut env, e)),
        }
    }
    /// A game or mod class, through Fabric's class loader when Fabric runs.
    pub fn find_class<'local>(
        &self,
        env: &mut JNIEnv<'local>,
        name: &str,
    ) -> jni::errors::Result<JClass<'local>> {
        if !self.fabric {
            return env.find_class(name);
        }
        let name = env.new_string(name)?;
        let class = env
            .call_static_method(
                "dev/horizonlab/bridge/fabric/FabricBoot",
                "gameClass",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                &[JValue::Object(&name)],
            )?
            .l()?;
        Ok(JClass::from(class))
    }
}

/// The running bridge, if [`start`] succeeded.
pub fn get() -> Option<&'static Bridge> {
    BRIDGE.get()
}

/// Ends the process through the JVM (`System.exit(code)`) when one runs, so it exits as
/// a Java program does: its shutdown hooks run and `-XX:+AutoCreateSharedArchive` writes
/// the class archive the next start maps ([`class_archive`]). Call it last, with
/// everything saved: it doesn't return when a JVM runs. `flush` runs before Java's exit
/// (the game's log). A watchdog ends the process after
/// `limit` if Java's exit hangs (a mod's shutdown hook waiting for a thread that's gone).
pub fn exit(code: i32, limit: std::time::Duration, flush: impl FnOnce()) {
    // Unset until Java and Fabric have started: a quit before then (from the title screen,
    // at once) ends as without Java, and no archive of half the classes is written.
    let Some(bridge) = BRIDGE.get() else { return };
    if let Some(archive) = bridge.class_archive.as_ref().filter(|a| !a.exists()) {
        log::info!(
            "Java mods: archiving the classes this run loaded for the next start ({}; once per set of mods)",
            archive.display()
        );
    }
    flush();
    std::thread::spawn(move || {
        std::thread::sleep(limit);
        std::process::exit(code);
    });
    if let Ok(mut env) = bridge.vm.attach_current_thread_permanently() {
        let _ = env.call_static_method("java/lang/System", "exit", "(I)V", &[JValue::Int(code)]);
    }
}

/// Sends `Natives.log` lines to `sink` instead of the game log.
pub fn set_log_sink(sink: Option<fn(&str)>) {
    *SINK.lock().unwrap_or_else(|e| e.into_inner()) = sink;
}

/// The `.jar` files in `dir`, sorted by name, as absolute paths. A missing folder has none.
/// Lower-case `.jar` only, as Fabric's folder scan takes them (`Foo.JAR` it skips, and the
/// launcher renames such a jar when it adds it).
pub fn jars(dir: &Path) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut jars: Vec<PathBuf> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_file() && p.extension().is_some_and(|e| e == "jar"))
        // Without Windows' `\?\` prefix: Java's paths reject it, and these go to Fabric.
        .map(horizon_prepare::java::real_path)
        .collect();
    jars.sort();
    jars
}

/// `path` without Windows' verbatim prefix (`\\?\C:\...`, `\\?\UNC\server\...`), which Rust's
/// `canonicalize` adds. HotSpot can't take it: loaded from `\\?\C:\...\jvm.dll`, it finds no
/// `lib\modules` and stops at start ("jimage file name is null", a Windows tester, Oracle JDK 25).
pub fn plain_path(path: &Path) -> PathBuf {
    horizon_prepare::java::without_verbatim(path.to_path_buf())
}

/// The Java home to run mods with: `HORIZON_JAVA_HOME`, else a Java [`JAVA_MAJOR`] or newer
/// from `JAVA_HOME`, the PATH, Minecraft's own runtimes (`horizon_prepare::java`) or the ones
/// Horizon's launcher downloaded ([`runtime::runtime_dir`]).
pub fn find_java_home() -> Result<PathBuf, String> {
    if let Some(home) = std::env::var_os(JAVA_HOME_VAR).filter(|h| !h.is_empty()) {
        return Ok(plain_path(Path::new(&home)));
    }
    let minecraft = horizon_prepare::artifacts::minecraft_dir(None);
    let mut runtimes = horizon_prepare::java::minecraft_runtimes(&minecraft.join("runtime"));
    if let Some(dir) = runtime::runtime_dir() {
        runtimes.extend(horizon_prepare::java::minecraft_runtimes(&dir));
    }
    // Only Minecraft's and the launcher's runtimes, none installed on the system: how a player
    // with no Java sees it (tools/java_bridge/install_test.sh).
    if std::env::var("HORIZON_JAVA_NO_SYSTEM").is_ok_and(|v| v == "1") {
        return runtimes
            .into_iter()
            .find(|home| runtime::release_major(home).is_some_and(|m| m >= JAVA_MAJOR))
            .ok_or_else(|| {
                format!("no Java {JAVA_MAJOR} among Minecraft's runtimes (HORIZON_JAVA_NO_SYSTEM)")
            });
    }
    let jdk = horizon_prepare::java::find_runtime(JAVA_MAJOR, &runtimes)?;
    let java = horizon_prepare::java::real_path(jdk.java);
    java.parent()
        .and_then(Path::parent)
        .map(Path::to_path_buf)
        .ok_or_else(|| format!("{} is not inside a Java home", java.display()))
}

/// The JVM library inside a Java home.
pub fn libjvm(home: &Path) -> Result<PathBuf, String> {
    let home = &plain_path(home);
    let candidates = [
        "lib/server/libjvm.so",
        "lib/server/libjvm.dylib",
        "bin/server/jvm.dll",
        // Java 8 layouts, so the error below names the real problem (too old) less often.
        "jre/lib/amd64/server/libjvm.so",
        "jre/lib/server/libjvm.dylib",
    ];
    candidates
        .iter()
        .map(|c| home.join(c))
        .find(|p| p.is_file())
        .ok_or_else(|| {
            format!(
                "no JVM library in {} (looked for lib/server/libjvm.so, .dylib, bin/server/jvm.dll)",
                home.display()
            )
        })
}

/// Writes an embedded jar to `work/name` when it differs.
/// A folder of `horizon-bridge.jar` and `horizon-platform.jar` to run in place of the built-in
/// ones (bridge development; see docs/java-bridge.md).
pub const DEV_JARS: &str = "HORIZON_JAVA_JARS";

fn write_jar(work: &Path, name: &str, bytes: &[u8]) -> Result<PathBuf, String> {
    // Developing the bridge: jars rebuilt on their own (tools/java_bridge/java.sh) are used in
    // place of the ones built into the game, so a Java change needs no game rebuild.
    if let Some(dir) = std::env::var_os(DEV_JARS) {
        let path = Path::new(&dir).join(name);
        if path.is_file() {
            return Ok(path);
        }
    }
    std::fs::create_dir_all(work).map_err(|e| format!("{}: {e}", work.display()))?;
    let path = work.join(name);
    if std::fs::read(&path).ok().as_deref() != Some(bytes) {
        std::fs::write(&path, bytes).map_err(|e| format!("{}: {e}", path.display()))?;
    }
    Ok(path)
}

/// The bridge's jar in `work`: a developer's (`HORIZON_JAVA_JARS`), the one built in, or the one
/// built here from its sources (a release build) against Fabric Loader, fetched into
/// `work/lib` on this machine. `home`: the Java that compiles it.
pub(crate) fn bridge_jar(work: &Path, home: &Path) -> Result<PathBuf, String> {
    let dev = std::env::var_os(DEV_JARS).map(|d| Path::new(&d).join("horizon-bridge.jar"));
    if !BRIDGE_JAR.is_empty() || dev.as_ref().is_some_and(|d| d.is_file()) {
        return write_jar(work, "horizon-bridge.jar", BRIDGE_JAR);
    }
    let fabric_libs = libs::ensure(&work.join("lib"))?;
    platform::ensure_bridge(BRIDGE_SRC, home, work, &fabric_libs, &|line| {
        log::info!("{line}")
    })
}

/// Whether this build carries the platform mod (game events for mods).
pub fn has_platform() -> bool {
    !PLATFORM_JAR.is_empty() || !PLATFORM_SRC.is_empty()
}

/// The platform mod's jar: a developer's (`HORIZON_JAVA_JARS`), the one built in, or the one
/// built here from its sources.
fn platform_jar(
    config: &Config,
    fabric: &Fabric,
    bridge: &Path,
    fabric_libs: &[PathBuf],
) -> Result<PathBuf, String> {
    let dev = std::env::var_os(DEV_JARS).map(|d| Path::new(&d).join("horizon-platform.jar"));
    if !PLATFORM_JAR.is_empty() || dev.as_ref().is_some_and(|d| d.is_file()) {
        return write_jar(&config.work, "horizon-platform.jar", PLATFORM_JAR);
    }
    let fabric_api = libs::ensure_fabric_api(&config.work.join("fabric-api"))?;
    platform::ensure(
        PLATFORM_SRC,
        &config.home,
        &config.work,
        bridge,
        fabric_libs,
        &fabric_api,
        &fabric.game_jar,
        &fabric.libraries,
        &|line| log::info!("{line}"),
    )
}

type CreateJavaVm =
    unsafe extern "system" fn(*mut *mut sys::JavaVM, *mut *mut c_void, *mut c_void) -> sys::jint;

/// Starts the JVM, registers the natives, says hello and, with [`Config::fabric`], starts
/// Fabric Loader. Returns the running bridge; once one runs, every call returns it.
pub fn start(config: &Config) -> Result<&'static Bridge, String> {
    let _starting = STARTING.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(bridge) = BRIDGE.get() {
        return Ok(bridge);
    }
    let bridge_jar = bridge_jar(&config.work, &config.home)?;
    let mut class_path = vec![bridge_jar.clone()];
    let mut fabric_libs = Vec::new();
    if config.fabric.is_some() {
        fabric_libs = libs::ensure(&config.work.join("lib"))?;
        class_path.extend(fabric_libs.iter().cloned());
    }
    class_path.extend(config.class_path.iter().cloned());
    let log_config = write_jar(&config.work, "log4j2-horizon.xml", LOG4J_CONFIG.as_bytes())?;
    let vm = create_vm(
        &config.home,
        &class_path,
        config.class_archive.as_deref(),
        &log_config,
        &[],
    )?;
    let mut env = vm
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach: {e}"))?;
    let java_version = boot(&mut env, &config.jars).map_err(|e| describe(&mut env, e))?;
    let mods = match &config.fabric {
        Some(fabric) => {
            let mut add_mods = fabric.add_mods.clone();
            let platform = fabric.platform && has_platform();
            if platform {
                add_mods.push(platform_jar(config, fabric, &bridge_jar, &fabric_libs)?);
            }
            let mods =
                start_fabric(&mut env, fabric, &add_mods).map_err(|e| describe(&mut env, e))?;
            if platform {
                prepare_data(&mut env, &config.work.join("datapacks"))
                    .map_err(|e| describe(&mut env, e))?;
            }
            mods
        }
        None => Vec::new(),
    };
    Ok(BRIDGE.get_or_init(|| Bridge {
        vm,
        java_version,
        home: config.home.clone(),
        mods,
        fabric: config.fabric.is_some(),
        class_archive: config.class_archive.clone(),
    }))
}

/// Windows: `bin\server\jvm.dll` needs DLLs that sit in the Java home's `bin` (the C runtime
/// Temurin ships, `vcruntime140.dll`), which Windows looks for in the program's folder, the
/// system's and the PATH, not beside the DLL (java.exe finds them as its own folder's). The
/// Java home's `bin` goes first on the PATH before the JVM loads.
#[cfg(windows)]
fn dll_search_path(home: &Path) {
    let bin = home.join("bin");
    let old = std::env::var_os("PATH").unwrap_or_default();
    if std::env::split_paths(&old).any(|p| p == bin) {
        return;
    }
    let paths = std::iter::once(bin).chain(std::env::split_paths(&old));
    if let Ok(joined) = std::env::join_paths(paths) {
        // SAFETY: std takes its environment lock on Windows; the JVM, which reads it, isn't
        // loaded yet.
        unsafe { std::env::set_var("PATH", joined) };
    }
}

/// A JVM option's bytes. JNI reads options in the system's code page, not UTF-8 (on Windows; a
/// folder or user name like `Ivér` otherwise breaks the class path).
#[cfg(windows)]
fn option_bytes(option: &str) -> Vec<u8> {
    code_page_bytes(option).unwrap_or_else(|| option.as_bytes().to_vec())
}

#[cfg(not(windows))]
fn option_bytes(option: &str) -> Vec<u8> {
    option.as_bytes().to_vec()
}

/// `text` in the system's code page; `None` when it has a character the code page lacks.
#[cfg(windows)]
fn code_page_bytes(text: &str) -> Option<Vec<u8>> {
    use windows_sys::Win32::Globalization::{
        CP_ACP, GetACP, WC_NO_BEST_FIT_CHARS, WideCharToMultiByte,
    };
    const CP_UTF8: u32 = 65001;
    // SAFETY: GetACP has no arguments or preconditions.
    if text.is_ascii() || unsafe { GetACP() } == CP_UTF8 {
        return Some(text.as_bytes().to_vec());
    }
    let wide: Vec<u16> = text.encode_utf16().collect();
    let mut out = vec![0u8; wide.len() * 4];
    let mut lost = 0;
    // SAFETY: both buffers are valid for the lengths given, and `lost` for writing.
    let written = unsafe {
        WideCharToMultiByte(
            CP_ACP,
            WC_NO_BEST_FIT_CHARS,
            wide.as_ptr(),
            wide.len() as i32,
            out.as_mut_ptr(),
            out.len() as i32,
            std::ptr::null(),
            &mut lost,
        )
    };
    if written <= 0 || lost != 0 {
        return None;
    }
    out.truncate(written as usize);
    Some(out)
}

/// A path the JVM can read, in an option or as its own home: without Windows' `\\?\` prefix, as
/// it is, else, with a character the system's code page lacks, the file's short name
/// (`C:\\Users\\ABCDEF~1\\...`), which is plain ASCII. HotSpot and Java's launchers read their
/// options and their own location through the code page.
#[cfg(windows)]
pub(crate) fn option_path(path: &Path) -> Result<PathBuf, String> {
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    use windows_sys::Win32::Storage::FileSystem::GetShortPathNameW;
    let short = |path: &Path| {
        let wide: Vec<u16> = path.as_os_str().encode_wide().chain([0]).collect();
        let mut short = vec![0u16; 32768];
        // SAFETY: `wide` ends in a NUL, and `short` is valid for the length given.
        let written =
            unsafe { GetShortPathNameW(wide.as_ptr(), short.as_mut_ptr(), short.len() as u32) };
        if written == 0 || written as usize >= short.len() {
            return None;
        }
        short.truncate(written as usize);
        Some(PathBuf::from(std::ffi::OsString::from_wide(&short)))
    };
    pick_option_path(&plain_path(path), |s| code_page_bytes(s).is_some(), short)
}

#[cfg(not(windows))]
pub(crate) fn option_path(path: &Path) -> Result<PathBuf, String> {
    Ok(plain_path(path))
}

/// [`option_path`]'s choice: `path` when the code page can spell it (`spellable`), else its
/// `short` name when that can be spelled. Windows gives the long name back as the short one
/// where 8.3 names are off (a setting per drive): then no spelling of the path reaches Java
/// intact, and passing it on would garble the class path into "class not found" errors.
#[cfg_attr(not(windows), allow(dead_code))]
fn pick_option_path(
    path: &Path,
    spellable: impl Fn(&str) -> bool,
    short: impl FnOnce(&Path) -> Option<PathBuf>,
) -> Result<PathBuf, String> {
    let ok = |p: &Path| p.to_str().is_some_and(&spellable);
    if ok(path) {
        return Ok(path.to_path_buf());
    }
    match short(path) {
        Some(short) if ok(&short) => Ok(short),
        _ => Err(format!(
            "{}: this path has characters Java can't read on this system (Windows' language \
             for non-Unicode programs lacks them) and Windows keeps no short name for it. Move \
             the launcher's data folder (or this file) to a folder whose name has only plain \
             letters, or turn on \"Beta: Use Unicode UTF-8\" in Windows' region settings",
            path.display()
        )),
    }
}

/// Java's log4j config: the console only. Mojang's (in the game's libraries) adds a file
/// appender at `logs/latest.log` of the working folder, which on a dedicated server is the
/// server's folder, where Horizon writes its own `latest.log`: the two overwrote each other's
/// lines. Once Knot runs, the platform's `HorizonLog` sends the lines to Horizon's log instead.
const LOG4J_CONFIG: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<!-- Horizon's Java mod bridge: log4j's config (no file: lines go to Horizon's log). -->
<Configuration status="WARN">
  <Appenders>
    <Console name="SysOut" target="SYSTEM_OUT">
      <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]: [java] (%logger{1}) %msg{nolookups}%n"/>
    </Console>
  </Appenders>
  <Loggers>
    <Root level="info">
      <AppenderRef ref="SysOut"/>
    </Root>
  </Loggers>
</Configuration>
"#;

fn create_vm(
    home: &Path,
    class_path: &[PathBuf],
    archive: Option<&Path>,
    log_config: &Path,
    extra: &[String],
) -> Result<JavaVM, String> {
    // HotSpot finds its home (`lib\modules`) from where its library was loaded, read through
    // the code page: a home under a user name like `Ivér` on a system without it is loaded
    // by its short name.
    let home = &option_path(home)?;
    let path = libjvm(home)?;
    #[cfg(windows)]
    dll_search_path(home);
    // SAFETY: loading libjvm runs its initialisers, which only set up the library itself.
    let library = unsafe { libloading::Library::new(&path) }
        .map_err(|e| format!("could not load {}: {e}", path.display()))?;
    // SAFETY: JNI_CreateJavaVM has this signature in every JVM (jni.h).
    let create: CreateJavaVm = unsafe {
        *library
            .get::<CreateJavaVm>(b"JNI_CreateJavaVM\0")
            .map_err(|e| format!("{} has no JNI_CreateJavaVM: {e}", path.display()))?
    };
    // A JVM never unloads: keep its library mapped for the life of the process.
    std::mem::forget(library);

    let class_path: Vec<PathBuf> = class_path
        .iter()
        .map(|p| option_path(p))
        .collect::<Result<_, _>>()?;
    let joined = std::env::join_paths(&class_path).map_err(|e| format!("class path: {e}"))?;
    let mut options = vec![
        // Leave SIGINT, SIGTERM and SIGHUP to the game: Ctrl+C and shutdown stay Horizon's.
        "-Xrs".to_string(),
        "-Dfile.encoding=UTF-8".into(),
        "-Dhorizon.bridge=1".into(),
        format!(
            "-Dlog4j2.configurationFile={}",
            option_path(log_config)?.display()
        ),
        format!("-Djava.class.path={}", joined.to_string_lossy()),
        // The heap gives back what it doesn't use: G1 keeps all it ever grew to otherwise (a
        // 107-jar pack: 555 MB live in 1.2-1.5 GB committed), and the game's own memory is
        // in the same process. Shrinks after full GCs and concurrent cycles; the periodic cycle
        // lets an idle heap shrink too.
        "-XX:+UseG1GC".into(),
        "-XX:MinHeapFreeRatio=10".into(),
        "-XX:MaxHeapFreeRatio=30".into(),
        "-XX:G1PeriodicGCInterval=30000".into(),
        // Mods' identifiers and the mirror's names repeat: ~1.3M strings in a big pack.
        "-XX:+UseStringDeduplication".into(),
    ];
    if let Some(archive) = archive {
        options.push("-XX:+AutoCreateSharedArchive".into());
        // It may not exist yet, and only what exists has a short name: its folder does.
        let archive = match (archive.parent(), archive.file_name()) {
            (Some(dir), Some(name)) => option_path(dir)?.join(name),
            _ => option_path(archive)?,
        };
        options.push(format!("-XX:SharedArchiveFile={}", archive.display()));
        // Classes from signed jars (Fabric's own sat4j) can't be archived: said once each.
        options.push("-Xlog:cds=off".into());
        options.push("-Xlog:cds+dynamic=off".into());
    }
    // The loader's own (NeoForge's `--add-opens`, its library folder).
    options.extend(extra.iter().cloned());
    // A player's own options last, so they win over ours (`HORIZON_JVM_OPTIONS="-Xmx3g"`).
    if let Ok(extra) = std::env::var("HORIZON_JVM_OPTIONS") {
        options.extend(extra.split_whitespace().map(str::to_string));
    }
    let options: Vec<CString> = options
        .into_iter()
        .map(|o| CString::new(option_bytes(&o)).map_err(|_| "a NUL in a JVM option".to_string()))
        .collect::<Result<_, _>>()?;
    let mut raw: Vec<sys::JavaVMOption> = options
        .iter()
        .map(|o| sys::JavaVMOption {
            optionString: o.as_ptr() as *mut _,
            extraInfo: std::ptr::null_mut(),
        })
        .collect();
    let mut args = sys::JavaVMInitArgs {
        version: sys::JNI_VERSION_1_8,
        nOptions: raw.len() as sys::jint,
        options: raw.as_mut_ptr(),
        ignoreUnrecognized: sys::JNI_FALSE,
    };
    let mut vm: *mut sys::JavaVM = std::ptr::null_mut();
    let mut env: *mut c_void = std::ptr::null_mut();
    // SAFETY: the pointers are valid for the call; `options` outlives it.
    let code = unsafe { create(&mut vm, &mut env, &mut args as *mut _ as *mut c_void) };
    if code != sys::JNI_OK || vm.is_null() {
        return Err(format!(
            "JNI_CreateJavaVM failed ({code}) with the Java at {}",
            home.display()
        ));
    }
    // SAFETY: `vm` is the JVM just created.
    unsafe { JavaVM::from_raw(vm) }.map_err(|e| format!("JavaVM: {e}"))
}

fn strings<'local>(
    env: &mut JNIEnv<'local>,
    items: &[String],
) -> jni::errors::Result<JObjectArray<'local>> {
    let array = env.new_object_array(
        items.len() as sys::jsize,
        "java/lang/String",
        JObject::null(),
    )?;
    for (i, item) in items.iter().enumerate() {
        let s = env.new_string(item)?;
        env.set_object_array_element(&array, i as sys::jsize, &s)?;
        env.delete_local_ref(s)?;
    }
    Ok(array)
}

/// Registers the natives and runs `Boot.hello`.
fn boot(env: &mut JNIEnv, jars: &[PathBuf]) -> jni::errors::Result<String> {
    let natives = env.find_class("dev/horizonlab/bridge/Natives")?;
    env.register_native_methods(
        &natives,
        &[
            NativeMethod {
                name: "log".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: native_log as *mut c_void,
            },
            NativeMethod {
                name: "tell".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_tell as *mut c_void,
            },
            NativeMethod {
                name: "itemsChanged".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: native_items_changed as *mut c_void,
            },
            NativeMethod {
                name: "overlay".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_overlay as *mut c_void,
            },
            NativeMethod {
                name: "playerMotion".into(),
                sig: "(Ljava/lang/String;DDD)V".into(),
                fn_ptr: native_player_motion as *mut c_void,
            },
            NativeMethod {
                name: "teleport".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;DDDFF)V".into(),
                fn_ptr: native_teleport as *mut c_void,
            },
            NativeMethod {
                name: "abilities".into(),
                sig: "(Ljava/lang/String;IIFF)V".into(),
                fn_ptr: native_abilities as *mut c_void,
            },
            NativeMethod {
                name: "tabName".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_tab_name as *mut c_void,
            },
            NativeMethod {
                name: "openMenu".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_open_menu as *mut c_void,
            },
            NativeMethod {
                name: "screenFrame".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_screen_frame as *mut c_void,
            },
            NativeMethod {
                name: "overlayFrame".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_overlay_frame as *mut c_void,
            },
            NativeMethod {
                name: "hudFrame".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_hud_frame as *mut c_void,
            },
            NativeMethod {
                name: "view".into(),
                sig: "(Ljava/lang/String;F)V".into(),
                fn_ptr: native_view as *mut c_void,
            },
            NativeMethod {
                name: "clientScreen".into(),
                sig: "(Ljava/lang/String;Z)V".into(),
                fn_ptr: native_client_screen as *mut c_void,
            },
            NativeMethod {
                name: "blockEntity".into(),
                sig: "(Ljava/lang/String;III)Ljava/lang/String;".into(),
                fn_ptr: native_block_entity as *mut c_void,
            },
            NativeMethod {
                name: "setBlockEntity".into(),
                sig: "(Ljava/lang/String;IIILjava/lang/String;)V".into(),
                fn_ptr: native_set_block_entity as *mut c_void,
            },
            NativeMethod {
                name: "light".into(),
                sig: "(Ljava/lang/String;III)I".into(),
                fn_ptr: native_light as *mut c_void,
            },
            NativeMethod {
                name: "lightSection".into(),
                sig: "(Ljava/lang/String;III[B)Z".into(),
                fn_ptr: native_light_section as *mut c_void,
            },
            NativeMethod {
                name: "setFood".into(),
                sig: "(Ljava/lang/String;IF)V".into(),
                fn_ptr: native_set_food as *mut c_void,
            },
            NativeMethod {
                name: "menuContent".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)V"
                    .into(),
                fn_ptr: native_menu_content as *mut c_void,
            },
            NativeMethod {
                name: "blockState".into(),
                sig: "(Ljava/lang/String;III)Ljava/lang/String;".into(),
                fn_ptr: native_block_state as *mut c_void,
            },
            NativeMethod {
                name: "chunkBlocks".into(),
                sig: "(Ljava/lang/String;III[I)[Ljava/lang/String;".into(),
                fn_ptr: native_chunk_blocks as *mut c_void,
            },
            NativeMethod {
                name: "chunkBiomes".into(),
                sig: "(Ljava/lang/String;III[I)[Ljava/lang/String;".into(),
                fn_ptr: native_chunk_biomes as *mut c_void,
            },
            NativeMethod {
                name: "sectionIds".into(),
                sig: "(Ljava/lang/String;III[I)I".into(),
                fn_ptr: native_section_ids as *mut c_void,
            },
            NativeMethod {
                name: "chunkRevision".into(),
                sig: "(Ljava/lang/String;II)J".into(),
                fn_ptr: native_chunk_revision as *mut c_void,
            },
            NativeMethod {
                name: "surface".into(),
                sig: "(Ljava/lang/String;II)I".into(),
                fn_ptr: native_surface as *mut c_void,
            },
            NativeMethod {
                name: "freshChunks".into(),
                sig: "(Ljava/lang/String;)[I".into(),
                fn_ptr: native_fresh_chunks as *mut c_void,
            },
            NativeMethod {
                name: "hurtPlayer".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;FDDDLjava/lang/String;)V".into(),
                fn_ptr: native_hurt_player as *mut c_void,
            },
            NativeMethod {
                name: "hurtMob".into(),
                sig: "(Ljava/lang/String;ILjava/lang/String;FDDDIZ)V".into(),
                fn_ptr: native_hurt_mob as *mut c_void,
            },
            NativeMethod {
                name: "stateName".into(),
                sig: "(I)Ljava/lang/String;".into(),
                fn_ptr: native_state_name as *mut c_void,
            },
            NativeMethod {
                name: "threadEpoch".into(),
                sig: "()J".into(),
                fn_ptr: native_thread_epoch as *mut c_void,
            },
            NativeMethod {
                name: "threadEpochBuffer".into(),
                sig: "()Ljava/nio/ByteBuffer;".into(),
                fn_ptr: native_thread_epoch_buffer as *mut c_void,
            },
            NativeMethod {
                name: "viewEpoch".into(),
                sig: "()J".into(),
                fn_ptr: native_view_epoch as *mut c_void,
            },
            NativeMethod {
                name: "setBlock".into(),
                sig: "(Ljava/lang/String;IIILjava/lang/String;)V".into(),
                fn_ptr: native_set_block as *mut c_void,
            },
            NativeMethod {
                name: "blockEntityTicker".into(),
                sig: "(Ljava/lang/String;IIIZ)V".into(),
                fn_ptr: native_block_entity_ticker as *mut c_void,
            },
            NativeMethod {
                name: "setBlockNow".into(),
                sig: "(Ljava/lang/String;IIILjava/lang/String;I)[I".into(),
                fn_ptr: native_set_block_now as *mut c_void,
            },
            NativeMethod {
                name: "dropItem".into(),
                sig: "(Ljava/lang/String;DDDDDDLjava/lang/String;I)V".into(),
                fn_ptr: native_drop_item as *mut c_void,
            },
            NativeMethod {
                name: "levelEvent".into(),
                sig: "(Ljava/lang/String;IIIIILjava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_level_event as *mut c_void,
            },
            NativeMethod {
                name: "spawnEntity".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_spawn_entity as *mut c_void,
            },
            NativeMethod {
                name: "setSlot".into(),
                sig: "(Ljava/lang/String;ILjava/lang/String;)V".into(),
                fn_ptr: native_set_slot as *mut c_void,
            },
            NativeMethod {
                name: "packet".into(),
                sig: "(Ljava/lang/String;[B)V".into(),
                fn_ptr: native_packet as *mut c_void,
            },
            NativeMethod {
                name: "devicePayload".into(),
                sig: "(Ljava/lang/String;[B)V".into(),
                fn_ptr: native_device_payload as *mut c_void,
            },
            NativeMethod {
                name: "modFluids".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: native_mod_fluids as *mut c_void,
            },
            NativeMethod {
                name: "modItemModels".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: native_mod_item_models as *mut c_void,
            },
            NativeMethod {
                name: "broadcast".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: native_broadcast as *mut c_void,
            },
            NativeMethod {
                name: "gameRule".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_game_rule as *mut c_void,
            },
            NativeMethod {
                name: "worldSpawn".into(),
                sig: "(Ljava/lang/String;IIIFF)V".into(),
                fn_ptr: native_world_spawn as *mut c_void,
            },
            NativeMethod {
                name: "chunkTickets".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
                fn_ptr: native_chunk_tickets as *mut c_void,
            },
            NativeMethod {
                name: "allocate".into(),
                sig: "(Ljava/lang/Class;)Ljava/lang/Object;".into(),
                fn_ptr: native_allocate as *mut c_void,
            },
        ],
    )?;
    gpu::register(env)?;
    let jars: Vec<String> = jars
        .iter()
        .map(|j| j.to_string_lossy().into_owned())
        .collect();
    let array = strings(env, &jars)?;
    let version = env
        .call_static_method(
            "dev/horizonlab/bridge/Boot",
            "hello",
            "([Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&array)],
        )?
        .l()?;
    let version = JString::from(version);
    Ok(env.get_string(&version)?.into())
}

/// Runs `FabricBoot.start`: Knot, mod discovery, Mixin, entrypoints.
fn start_fabric(
    env: &mut JNIEnv,
    fabric: &Fabric,
    add_mods: &[PathBuf],
) -> jni::errors::Result<Vec<String>> {
    let side = env.new_string(match fabric.side {
        Side::Client => "client",
        Side::Server => "server",
    })?;
    let version = env.new_string(&fabric.version)?;
    // Java strings (UTF-16 through JNI): any character goes, but no `\\?\` path (Java's
    // `Path` refuses it).
    let java = |p: &Path| plain_path(p).to_string_lossy().into_owned();
    let game_jar = env.new_string(java(&fabric.game_jar))?;
    let mods = env.new_string(java(&fabric.mods))?;
    let game_dir = env.new_string(java(&fabric.game_dir))?;
    let libraries: Vec<String> = fabric.libraries.iter().map(|l| java(l)).collect();
    let libraries = strings(env, &libraries)?;
    let add_mods: Vec<String> = add_mods.iter().map(|m| java(m)).collect();
    let add_mods = strings(env, &add_mods)?;
    let loaded = env
        .call_static_method(
            "dev/horizonlab/bridge/fabric/FabricBoot",
            "start",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;",
            &[
                JValue::Object(&side),
                JValue::Object(&version),
                JValue::Object(&game_jar),
                JValue::Object(&libraries),
                JValue::Object(&mods),
                JValue::Object(&add_mods),
                JValue::Object(&game_dir),
            ],
        )?
        .l()?;
    let loaded = JObjectArray::from(loaded);
    let count = env.get_array_length(&loaded)?;
    let mut out = Vec::with_capacity(count as usize);
    for i in 0..count {
        let item = JString::from(env.get_object_array_element(&loaded, i)?);
        out.push(env.get_string(&item)?.into());
        env.delete_local_ref(item)?;
    }
    Ok(out)
}

/// Loads the server's data (vanilla's data pack and the mods'): `HorizonData.prepare`, in the
/// platform mod, through Fabric's class loader.
fn prepare_data(env: &mut JNIEnv, datapacks: &Path) -> jni::errors::Result<()> {
    let name = env.new_string("dev.horizonlab.platform.HorizonData")?;
    let class = env
        .call_static_method(
            "dev/horizonlab/bridge/fabric/FabricBoot",
            "gameClass",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            &[JValue::Object(&name)],
        )?
        .l()?;
    let datapacks = env.new_string(datapacks.to_string_lossy())?;
    env.call_static_method(
        JClass::from(class),
        "prepare",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&datapacks)],
    )?;
    Ok(())
}

/// A JNI error as text; a Java exception pending is cleared and given as its `toString()`,
/// with its cause's.
pub(crate) fn describe(env: &mut JNIEnv, error: jni::errors::Error) -> String {
    if !matches!(error, jni::errors::Error::JavaException) {
        return error.to_string();
    }
    let Ok(throwable) = env.exception_occurred() else {
        return error.to_string();
    };
    let _ = env.exception_clear();
    let mut text = Vec::new();
    let mut current: JObject = throwable.into();
    for _ in 0..8 {
        if current.is_null() {
            break;
        }
        let line = env
            .call_method(&current, "toString", "()Ljava/lang/String;", &[])
            .and_then(|v| v.l())
            .and_then(|s| env.get_string(&JString::from(s)).map(String::from))
            .unwrap_or_else(|_| "a Java exception (its toString() failed)".into());
        text.push(line);
        current = match env
            .call_method(&current, "getCause", "()Ljava/lang/Throwable;", &[])
            .and_then(|v| v.l())
        {
            Ok(cause) => cause,
            Err(_) => break,
        };
    }
    let _ = env.exception_clear();
    text.join(", caused by ")
}

fn emit(line: &str) {
    match *SINK.lock().unwrap_or_else(|e| e.into_inner()) {
        Some(sink) => sink(line),
        None => log::info!("[java] {line}"),
    }
}

/// `dev.horizonlab.bridge.Natives.log(String)`.
extern "system" fn native_log(mut env: JNIEnv, _class: JClass, text: JString) {
    // A panic must not unwind into the JVM.
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(text) = env.get_string(&text) {
            emit(&String::from(text));
        }
    }));
}

/// `Natives.itemsChanged(String json)`.
extern "system" fn native_items_changed(mut env: JNIEnv, _class: JClass, json: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(json) = env.get_string(&json) else {
            return;
        };
        events::push(events::Action::ItemsChanged { json: json.into() });
    }));
}

/// `Natives.overlay(String uuid, String text)`.
extern "system" fn native_overlay(mut env: JNIEnv, _class: JClass, uuid: JString, text: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(text)) = (env.get_string(&uuid), env.get_string(&text)) else {
            return;
        };
        let (uuid, text) = (String::from(uuid), String::from(text));
        events::push(events::Action::Overlay { uuid, text });
    }));
}

/// `Natives.tell(String uuid, String text)`.
extern "system" fn native_tell(mut env: JNIEnv, _class: JClass, uuid: JString, text: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(text)) = (env.get_string(&uuid), env.get_string(&text)) else {
            return;
        };
        let (uuid, text) = (String::from(uuid), String::from(text));
        events::push(events::Action::Tell { uuid, text });
    }));
}

/// `Natives.playerMotion(String uuid, double x, double y, double z)`.
extern "system" fn native_player_motion(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    x: sys::jdouble,
    y: sys::jdouble,
    z: sys::jdouble,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid) else {
            return;
        };
        events::push(events::Action::PlayerMotion {
            uuid: uuid.into(),
            velocity: [x, y, z],
        });
    }));
}

/// `Natives.teleport(String uuid, String dimension, double x, y, z, float yaw, pitch)`.
#[allow(clippy::too_many_arguments)]
extern "system" fn native_teleport(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    dimension: JString,
    x: sys::jdouble,
    y: sys::jdouble,
    z: sys::jdouble,
    yaw: sys::jfloat,
    pitch: sys::jfloat,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(dimension)) = (env.get_string(&uuid), env.get_string(&dimension)) else {
            return;
        };
        events::push(events::Action::Teleport {
            uuid: uuid.into(),
            dimension: dimension.into(),
            position: [x, y, z],
            yaw,
            pitch,
        });
    }));
}

/// `Natives.abilities(String uuid, int gameMode, int flags, float flyingSpeed, float
/// walkingSpeed)`.
extern "system" fn native_abilities(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    game_mode: sys::jint,
    flags: sys::jint,
    flying_speed: sys::jfloat,
    walking_speed: sys::jfloat,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(uuid) = env.get_string(&uuid) {
            events::push(events::Action::Abilities {
                uuid: uuid.into(),
                game_mode,
                flags: flags as i8,
                flying_speed,
                walking_speed,
            });
        }
    }));
}

/// `Natives.tabName(String uuid, String json)`: json is null for the player's own name.
extern "system" fn native_tab_name(mut env: JNIEnv, _class: JClass, uuid: JString, json: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid).map(String::from) else {
            return;
        };
        let json = if json.is_null() {
            None
        } else {
            env.get_string(&json).ok().map(String::from)
        };
        events::push(events::Action::TabName { uuid, json });
    }));
}

/// `Natives.gameRule(String id, String value)`.
extern "system" fn native_game_rule(mut env: JNIEnv, _class: JClass, id: JString, value: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let (Ok(id), Ok(value)) = (env.get_string(&id), env.get_string(&value)) {
            events::push(events::Action::GameRule {
                id: id.into(),
                value: value.into(),
            });
        }
    }));
}

/// `Natives.worldSpawn(String dimension, int x, int y, int z, float yaw, float pitch)`.
extern "system" fn native_world_spawn(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    x: i32,
    y: i32,
    z: i32,
    yaw: f32,
    pitch: f32,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(dimension) = env.get_string(&dimension) {
            events::push(events::Action::WorldSpawn {
                dimension: String::from(dimension),
                position: [x, y, z],
                yaw,
                pitch,
            });
        }
    }));
}

/// `Natives.chunkTickets(String dimension, String chunks)`: "x,z;x,z;...".
extern "system" fn native_chunk_tickets(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    chunks: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(dimension), Ok(chunks)) = (env.get_string(&dimension), env.get_string(&chunks))
        else {
            return;
        };
        let chunks = parse_chunks(&String::from(chunks));
        events::push(events::Action::ChunkTickets {
            dimension: String::from(dimension),
            chunks,
        });
    }));
}

/// "x,z;x,z;..." as chunk positions (malformed entries skipped).
fn parse_chunks(text: &str) -> Vec<(i32, i32)> {
    text.split(';')
        .filter_map(|pair| {
            let (x, z) = pair.split_once(',')?;
            Some((x.trim().parse().ok()?, z.trim().parse().ok()?))
        })
        .collect()
}

/// `Natives.openMenu(String uuid, String menu, String title)`: title may be null.
extern "system" fn native_open_menu(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    menu: JString,
    title: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(menu)) = (env.get_string(&uuid), env.get_string(&menu)) else {
            return;
        };
        let (uuid, menu) = (String::from(uuid), String::from(menu));
        let title = if title.is_null() {
            None
        } else {
            env.get_string(&title).ok().map(String::from)
        };
        events::push(events::Action::OpenMenu { uuid, menu, title });
    }));
}

/// `Natives.screenFrame(String uuid, String frame)`.
extern "system" fn native_screen_frame(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    frame: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(frame)) = (env.get_string(&uuid), env.get_string(&frame)) else {
            return;
        };
        events::push(events::Action::ScreenFrame {
            uuid: uuid.into(),
            frame: frame.into(),
        });
    }));
}

/// `Natives.hudFrame(String uuid, String frame)`.
extern "system" fn native_hud_frame(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    frame: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(frame)) = (env.get_string(&uuid), env.get_string(&frame)) else {
            return;
        };
        events::push(events::Action::HudFrame {
            uuid: uuid.into(),
            frame: frame.into(),
        });
    }));
}

/// `Natives.view(String uuid, float scale)`.
extern "system" fn native_view(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    scale: jni::sys::jfloat,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid) else {
            return;
        };
        events::push(events::Action::View {
            uuid: uuid.into(),
            scale,
        });
    }));
}

/// `Natives.overlayFrame(String uuid, String frame)`.
extern "system" fn native_overlay_frame(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    frame: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(uuid), Ok(frame)) = (env.get_string(&uuid), env.get_string(&frame)) else {
            return;
        };
        events::push(events::Action::OverlayFrame {
            uuid: uuid.into(),
            frame: frame.into(),
        });
    }));
}

/// `Natives.clientScreen(String uuid, boolean open)`.
extern "system" fn native_client_screen(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    open: jni::sys::jboolean,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid) else {
            return;
        };
        events::push(events::Action::ClientScreen {
            uuid: uuid.into(),
            open: open != 0,
        });
    }));
}

/// `Natives.blockEntity(String dimension, int x, int y, int z)`: SNBT, or null.
extern "system" fn native_block_entity<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    x: jni::sys::jint,
    y: jni::sys::jint,
    z: jni::sys::jint,
) -> JString<'local> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return JString::from(JObject::null());
        };
        match world::block_entity(&dimension, x, y, z) {
            Some(snbt) => env
                .new_string(snbt)
                .unwrap_or_else(|_| JString::from(JObject::null())),
            None => JString::from(JObject::null()),
        }
    }))
    .unwrap_or_else(|_| JString::from(JObject::null()))
}

/// `Natives.setBlockEntity(String dimension, int x, int y, int z, String snbt)`.
extern "system" fn native_set_block_entity(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    x: jni::sys::jint,
    y: jni::sys::jint,
    z: jni::sys::jint,
    snbt: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(dimension), Ok(snbt)) = (env.get_string(&dimension), env.get_string(&snbt)) else {
            return;
        };
        events::push(events::Action::SetBlockEntity {
            dimension: dimension.into(),
            position: [x, y, z],
            snbt: snbt.into(),
        });
    }));
}

/// `Natives.light(String dimension, int x, int y, int z)`: -1 where no chunk is loaded.
extern "system" fn native_light(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    x: jni::sys::jint,
    y: jni::sys::jint,
    z: jni::sys::jint,
) -> jni::sys::jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return -1;
        };
        world::light(&dimension, x, y, z).map_or(-1, i32::from)
    }))
    .unwrap_or(-1)
}

/// `Natives.lightSection(String dimension, int sx, int sy, int sz, byte[] out)`: a section's
/// light, 4096 bytes in vanilla's index order (y, z, x), each packed as `light` gives it; false
/// (and `out` untouched) where a block has none (no chunk, or outside a call that lent the world).
extern "system" fn native_light_section(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    sx: jni::sys::jint,
    sy: jni::sys::jint,
    sz: jni::sys::jint,
    out: jni::objects::JByteArray,
) -> jni::sys::jboolean {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return 0;
        };
        let mut light = [0i8; 4096];
        for (i, slot) in light.iter_mut().enumerate() {
            let (x, y, z) = (i as i32 & 15, i as i32 >> 8, (i as i32 >> 4) & 15);
            match world::light(&dimension, sx * 16 + x, sy * 16 + y, sz * 16 + z) {
                Some(v) => *slot = v as i8,
                None => return 0,
            }
        }
        u8::from(env.set_byte_array_region(&out, 0, &light).is_ok())
    }))
    .unwrap_or(0)
}

/// `Natives.setFood(String uuid, int food, float saturation)`.
extern "system" fn native_set_food(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    food: jni::sys::jint,
    saturation: jni::sys::jfloat,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(uuid) = env.get_string(&uuid).map(String::from) {
            events::push(events::Action::Food {
                uuid,
                food,
                saturation,
            });
        }
    }));
}

/// `Natives.menuContent(String uuid, String menu, String title, String[] items, String carried)`.
extern "system" fn native_menu_content(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    menu: JString,
    title: JString,
    items: JObjectArray,
    carried: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid).map(String::from) else {
            return;
        };
        let mut string = |s: &JString| -> Option<String> {
            (!s.is_null())
                .then(|| env.get_string(s).ok().map(String::from))
                .flatten()
        };
        let open = string(&menu).map(|m| (m, string(&title)));
        let carried = string(&carried);
        let items = if items.is_null() {
            None
        } else {
            let n = env.get_array_length(&items).unwrap_or(0);
            let mut out = Vec::with_capacity(n as usize);
            for i in 0..n {
                let item = env
                    .get_object_array_element(&items, i)
                    .ok()
                    .filter(|o| !o.is_null())
                    .and_then(|o| env.get_string(&JString::from(o)).ok().map(String::from));
                out.push(item);
            }
            Some(out)
        };
        events::push(events::Action::MenuContent {
            uuid,
            open,
            items,
            carried,
        });
    }));
}

/// `Natives.blockState(String dimension, int x, int y, int z)`: Horizon's block there, or
/// null where no chunk is loaded (or outside a call that lent the world).
extern "system" fn native_block_state<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    x: sys::jint,
    y: sys::jint,
    z: sys::jint,
) -> sys::jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return std::ptr::null_mut();
        };
        match world::block(&dimension, x, y, z) {
            Some(state) => env
                .new_string(state)
                .map(JString::into_raw)
                .unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// `Natives.chunkBlocks(String dimension, int cx, int cz, int minY, int[] out)`: a loaded
/// chunk's blocks at once (`world::Blocks::column`), or null.
extern "system" fn native_chunk_blocks<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    cx: sys::jint,
    cz: sys::jint,
    min_y: sys::jint,
    out: jni::objects::JIntArray<'local>,
) -> sys::jobjectArray {
    chunk_palette(env, dimension, cx, cz, min_y, out, world::column)
}

/// `Natives.chunkBiomes(String dimension, int cx, int cz, int minY, int[] out)`: a loaded
/// chunk's biomes at once (`world::Blocks::column_biomes`), or null.
extern "system" fn native_chunk_biomes<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    cx: sys::jint,
    cz: sys::jint,
    min_y: sys::jint,
    out: jni::objects::JIntArray<'local>,
) -> sys::jobjectArray {
    chunk_palette(env, dimension, cx, cz, min_y, out, world::column_biomes)
}

type ColumnRead = fn(&str, i32, i32, i32, &mut [i32]) -> Option<Vec<String>>;

fn chunk_palette<'local>(
    mut env: JNIEnv<'local>,
    dimension: JString<'local>,
    cx: sys::jint,
    cz: sys::jint,
    min_y: sys::jint,
    out: jni::objects::JIntArray<'local>,
    read: ColumnRead,
) -> sys::jobjectArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return std::ptr::null_mut();
        };
        let Ok(len) = env.get_array_length(&out) else {
            return std::ptr::null_mut();
        };
        let mut indices = vec![0i32; len as usize];
        let Some(palette) = read(&dimension, cx, cz, min_y, &mut indices) else {
            return std::ptr::null_mut();
        };
        if env.set_int_array_region(&out, 0, &indices).is_err() {
            return std::ptr::null_mut();
        }
        let Ok(array) = env.new_object_array(
            palette.len() as sys::jsize,
            "java/lang/String",
            JObject::null(),
        ) else {
            return std::ptr::null_mut();
        };
        for (i, name) in palette.iter().enumerate() {
            let Ok(s) = env.new_string(name) else {
                return std::ptr::null_mut();
            };
            let _ = env.set_object_array_element(&array, i as sys::jsize, &s);
            let _ = env.delete_local_ref(s);
        }
        array.into_raw()
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// `Natives.sectionIds(String dimension, int cx, int sy, int cz, int[] out)`: a section's
/// state ids (`world::section_ids`): 1, 0 where its chunk isn't loaded, -1 when the view can't.
extern "system" fn native_section_ids<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    cx: sys::jint,
    sy: sys::jint,
    cz: sys::jint,
    out: jni::objects::JIntArray<'local>,
) -> sys::jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return -1;
        };
        let mut ids = [0i32; 4096];
        match world::section_ids(&dimension, cx, sy, cz, &mut ids) {
            Some(true) => {
                if env.set_int_array_region(&out, 0, &ids).is_err() {
                    return -1;
                }
                1
            }
            Some(false) => 0,
            None => -1,
        }
    }))
    .unwrap_or(-1)
}

/// `Natives.chunkRevision(String dimension, int cx, int cz)`: `world::chunk_revision`.
extern "system" fn native_surface<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    x: sys::jint,
    z: sys::jint,
) -> sys::jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return i32::MIN;
        };
        world::surface(&dimension, x, z).unwrap_or(i32::MIN)
    }))
    .unwrap_or(i32::MIN)
}

extern "system" fn native_fresh_chunks<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
) -> sys::jintArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return std::ptr::null_mut();
        };
        let fresh = world::take_fresh(&dimension);
        let Ok(array) = env.new_int_array(fresh.len() as i32) else {
            return std::ptr::null_mut();
        };
        if env.set_int_array_region(&array, 0, &fresh).is_err() {
            return std::ptr::null_mut();
        }
        array.into_raw()
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[allow(clippy::too_many_arguments)]
extern "system" fn native_hurt_player<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    uuid: JString<'local>,
    damage_type: JString<'local>,
    amount: sys::jfloat,
    x: sys::jdouble,
    y: sys::jdouble,
    z: sys::jdouble,
    by: JString<'local>,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let by = (!by.is_null())
            .then(|| env.get_string(&by).map(String::from).ok())
            .flatten();
        let (Ok(uuid), Ok(damage_type)) = (
            env.get_string(&uuid).map(String::from),
            env.get_string(&damage_type).map(String::from),
        ) else {
            return;
        };
        let from = (!x.is_nan()).then_some([x, y, z]);
        events::push(events::Action::HurtPlayer {
            uuid,
            damage_type,
            amount,
            from,
            by,
        });
    }));
}

#[allow(clippy::too_many_arguments)]
extern "system" fn native_hurt_mob<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    id: sys::jint,
    damage_type: JString<'local>,
    amount: sys::jfloat,
    x: sys::jdouble,
    y: sys::jdouble,
    z: sys::jdouble,
    by: sys::jint,
    reset_invulnerable: sys::jboolean,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(dimension), Ok(damage_type)) = (
            env.get_string(&dimension).map(String::from),
            env.get_string(&damage_type).map(String::from),
        ) else {
            return;
        };
        events::push(events::Action::HurtMob {
            dimension,
            id,
            damage_type,
            amount,
            from: (!x.is_nan()).then_some([x, y, z]),
            by: (by >= 0).then_some(by),
            reset_invulnerable: reset_invulnerable != 0,
        });
    }));
}

extern "system" fn native_chunk_revision<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    cx: sys::jint,
    cz: sys::jint,
) -> sys::jlong {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return -1;
        };
        world::chunk_revision(&dimension, cx, cz)
    }))
    .unwrap_or(-1)
}

/// `Natives.stateName(int id)`: the state a section id is (`world::state_name`), or null.
extern "system" fn native_state_name<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: sys::jint,
) -> sys::jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match world::state_name(id) {
            Some(name) => env
                .new_string(name)
                .map(JString::into_raw)
                .unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// `Natives.threadEpoch()`: this thread's view changes so far (`world::thread_epoch`).
extern "system" fn native_thread_epoch(_env: JNIEnv, _class: JClass) -> sys::jlong {
    world::thread_epoch()
}

/// `Natives.threadEpochBuffer()`: this thread's view counter as a direct buffer
/// (`world::thread_epoch_cell`), so Java reads it without a call. Null if JNI refuses one.
extern "system" fn native_thread_epoch_buffer(mut env: JNIEnv, _class: JClass) -> sys::jobject {
    let cell = world::thread_epoch_cell();
    // SAFETY: the cell is this thread's thread-local, alive as long as the thread; the buffer is
    // read on this thread only (HorizonServer.Sections is per thread) and never written by Java.
    match unsafe { env.new_direct_byte_buffer(cell.cast::<u8>(), 8) } {
        Ok(b) => b.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// `Natives.viewEpoch()`: the number of the world view lent last (`world::epoch`).
extern "system" fn native_view_epoch(_env: JNIEnv, _class: JClass) -> sys::jlong {
    world::epoch()
}

/// `Natives.setBlock(String dimension, int x, int y, int z, String state)`.
extern "system" fn native_set_block(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    x: sys::jint,
    y: sys::jint,
    z: sys::jint,
    state: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(dimension), Ok(state)) = (env.get_string(&dimension), env.get_string(&state))
        else {
            return;
        };
        events::push(events::Action::SetBlock {
            dimension: dimension.into(),
            position: [x, y, z],
            state: state.into(),
        });
    }));
}

/// `Natives.blockEntityTicker(String dimension, int x, int y, int z, boolean up)`.
extern "system" fn native_block_entity_ticker(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    x: sys::jint,
    y: sys::jint,
    z: sys::jint,
    up: sys::jboolean,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension) else {
            return;
        };
        world::block_entity_ticker(&String::from(dimension), [x, y, z], up != 0);
    }));
}

/// `Natives.setBlockNow(String dimension, int x, int y, int z, String state, int flags)`:
/// `Level.setBlock` made in Horizon's world inside this call (`world::set_block`), as
/// [`world::Written::flat`]; null where the view can't (Java queues it with `setBlock`).
#[allow(clippy::too_many_arguments)]
extern "system" fn native_set_block_now<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dimension: JString<'local>,
    x: sys::jint,
    y: sys::jint,
    z: sys::jint,
    state: JString<'local>,
    flags: sys::jint,
) -> sys::jintArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(dimension), Ok(state)) = (
            env.get_string(&dimension).map(String::from),
            env.get_string(&state).map(String::from),
        ) else {
            return std::ptr::null_mut();
        };
        let Some(written) = world::set_block(&dimension, x, y, z, &state, flags) else {
            return std::ptr::null_mut();
        };
        let flat = written.flat();
        let Ok(array) = env.new_int_array(flat.len() as i32) else {
            return std::ptr::null_mut();
        };
        if env.set_int_array_region(&array, 0, &flat).is_err() {
            return std::ptr::null_mut();
        }
        array.into_raw()
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// `Natives.dropItem(String dimension, double x, y, z, double vx, vy, vz, String item, int count)`.
#[allow(clippy::too_many_arguments)]
extern "system" fn native_drop_item(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    x: sys::jdouble,
    y: sys::jdouble,
    z: sys::jdouble,
    vx: sys::jdouble,
    vy: sys::jdouble,
    vz: sys::jdouble,
    item: JString,
    count: sys::jint,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(dimension), Ok(item)) = (env.get_string(&dimension), env.get_string(&item)) else {
            return;
        };
        events::push(events::Action::DropItem {
            dimension: dimension.into(),
            position: [x, y, z],
            velocity: [vx, vy, vz],
            item: item.into(),
            count,
        });
    }));
}

/// `Natives.levelEvent(String dimension, int type, int x, int y, int z, int data, String state,
/// String except)`.
#[allow(clippy::too_many_arguments)]
extern "system" fn native_level_event(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    id: sys::jint,
    x: sys::jint,
    y: sys::jint,
    z: sys::jint,
    data: sys::jint,
    state: JString,
    except: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(dimension) = env.get_string(&dimension).map(String::from) else {
            return;
        };
        let state = if state.is_null() {
            None
        } else {
            env.get_string(&state).ok().map(String::from)
        };
        let except = if except.is_null() {
            None
        } else {
            env.get_string(&except).ok().map(String::from)
        };
        events::push(events::Action::LevelEvent {
            dimension,
            id,
            position: [x, y, z],
            data,
            state,
            except,
        });
    }));
}

/// `Natives.spawnEntity(String dimension, String snbt)`: a vanilla entity a mod added.
extern "system" fn native_spawn_entity(
    mut env: JNIEnv,
    _class: JClass,
    dimension: JString,
    snbt: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (Ok(dimension), Ok(snbt)) = (env.get_string(&dimension), env.get_string(&snbt)) else {
            return;
        };
        events::push(events::Action::SpawnEntity {
            dimension: dimension.into(),
            snbt: snbt.into(),
        });
    }));
}

/// `Natives.setSlot(String uuid, int slot, String item)`: item is SNBT, or null for empty.
extern "system" fn native_set_slot(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    slot: sys::jint,
    item: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid).map(String::from) else {
            return;
        };
        let item = if item.is_null() {
            None
        } else {
            env.get_string(&item).ok().map(String::from)
        };
        if let Ok(slot) = usize::try_from(slot) {
            events::push(events::Action::SetSlot { uuid, slot, item });
        }
    }));
}

/// `Natives.packet(String uuid, byte[] data)`.
extern "system" fn native_packet(
    mut env: JNIEnv,
    _class: JClass,
    uuid: JString,
    data: jni::objects::JByteArray,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(uuid) = env.get_string(&uuid).map(String::from) else {
            return;
        };
        if let Ok(data) = env.convert_byte_array(&data) {
            events::push(events::Action::Packet { uuid, data });
        }
    }));
}

/// `Natives.devicePayload(String channel, byte[] body)`: a device mod's message from this
/// game's own client half to the server it is connected to ([`device`]).
extern "system" fn native_device_payload(
    mut env: JNIEnv,
    _class: JClass,
    channel: JString,
    data: jni::objects::JByteArray,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(channel) = env.get_string(&channel).map(String::from) else {
            return;
        };
        if let Ok(data) = env.convert_byte_array(&data) {
            device::push_out(channel, data);
        }
    }));
}

/// The Java mods' fluids as the client's models draw them (`Natives.modFluids`, once the
/// models load): `{"<liquid block id>": {"still": sprite, "flow": sprite, "tint": argb,
/// "translucent": bool}}`. Taken by the game that meshes ([`take_mod_fluids`]).
static MOD_FLUIDS: Mutex<Option<String>> = Mutex::new(None);

/// The mods' fluids' looks Java sent since the last call (see [`MOD_FLUIDS`]).
pub fn take_mod_fluids() -> Option<String> {
    MOD_FLUIDS.lock().ok()?.take()
}

/// `Natives.modFluids(String json)`.
extern "system" fn native_mod_fluids(mut env: JNIEnv, _class: JClass, json: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let (Ok(json), Ok(mut slot)) = (env.get_string(&json), MOD_FLUIDS.lock()) {
            *slot = Some(json.into());
        }
    }));
}

/// Mods' items drawn with the quads their own item model types make (`Natives.modItemModels`,
/// once the models load): `{"<item id>": {"quads": [{"p": [12], "uv": [8], "s": sprite, "d":
/// direction, "c": argb, "t": translucent, "e": emission}], "block_light": bool, "particle":
/// sprite, "display": {"<context>": [rotation, translation, scale]}}}`. Taken by the game that
/// draws items ([`take_mod_item_models`]).
static MOD_ITEM_MODELS: Mutex<Option<String>> = Mutex::new(None);

/// The mods' item models Java sent since the last call (see [`MOD_ITEM_MODELS`]).
pub fn take_mod_item_models() -> Option<String> {
    MOD_ITEM_MODELS.lock().ok()?.take()
}

/// `Natives.modItemModels(String json)`.
extern "system" fn native_mod_item_models(mut env: JNIEnv, _class: JClass, json: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let (Ok(json), Ok(mut slot)) = (env.get_string(&json), MOD_ITEM_MODELS.lock()) {
            *slot = Some(json.into());
        }
    }));
}

/// `Natives.broadcast(String text)`.
extern "system" fn native_broadcast(mut env: JNIEnv, _class: JClass, text: JString) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(text) = env.get_string(&text) {
            events::push(events::Action::Broadcast { text: text.into() });
        }
    }));
}

/// `Natives.allocate(Class)`: an instance made without running a constructor (JNI
/// `AllocObject`), for the game objects the platform mod mirrors Horizon's state into.
extern "system" fn native_allocate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    of: JClass<'local>,
) -> sys::jobject {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        env.alloc_object(&of)
            .map(JObject::into_raw)
            .unwrap_or(std::ptr::null_mut())
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod plain_path_tests {
    use super::{pick_option_path, plain_path};

    /// A code page without `é` (as Windows-1251 lacks it), with or without 8.3 names.
    #[test]
    fn an_unspellable_path_goes_by_its_short_name_or_fails_clearly() {
        let ascii = |s: &str| s.is_ascii();
        let long = Path::new(r"C:\Users\Ivér\AppData\Roaming\horizon-launcher\a.jar");
        let short = PathBuf::from(r"C:\Users\IVR~1\AppData\Roaming\HORIZO~1\a.jar");
        // Spellable as it is: kept, no short name asked for.
        let plain = Path::new(r"C:\Users\me\a.jar");
        assert_eq!(
            pick_option_path(plain, ascii, |_| panic!("asked for a short name")),
            Ok(plain.to_path_buf())
        );
        assert_eq!(
            pick_option_path(long, ascii, |_| Some(short.clone())),
            Ok(short)
        );
        // 8.3 names off: Windows hands the long name back; no short name at all.
        let off = pick_option_path(long, ascii, |p| Some(p.to_path_buf())).unwrap_err();
        assert!(off.contains("Ivér") && off.contains("Move"), "{off}");
        assert!(pick_option_path(long, ascii, |_| None).is_err());
    }
    use std::path::{Path, PathBuf};

    #[test]
    fn windows_verbatim_prefixes_are_dropped_and_other_paths_kept() {
        assert_eq!(
            plain_path(Path::new(r"\\?\C:\Program Files\Java\jdk-25")),
            PathBuf::from(r"C:\Program Files\Java\jdk-25")
        );
        assert_eq!(
            plain_path(Path::new(r"\\?\UNC\server\share\jdk")),
            PathBuf::from(r"\\server\share\jdk")
        );
        assert_eq!(
            plain_path(Path::new("/usr/lib/jvm/java-25")),
            PathBuf::from("/usr/lib/jvm/java-25")
        );
        assert_eq!(
            plain_path(Path::new(r"\\?\Volume{x}\jdk")),
            PathBuf::from(r"\\?\Volume{x}\jdk")
        );
    }
}
