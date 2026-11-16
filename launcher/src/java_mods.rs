//! The game folder's Java mods (`mods-java/`): Fabric mods a game built with Java mods runs
//! (docs/java-bridge.md). The launcher lists them with their Fabric names, adds jars, sends them
//! to the trash, and reads from the game's last log which of them failed.
use std::io::Read;
use std::path::{Path, PathBuf};

/// The folder's name in the game folder, as the game reads it (`horizon_jvm::DIR`).
pub const DIR: &str = "mods-java";
/// The Minecraft version the bridge runs Fabric mods for: a jar whose `depends.minecraft`
/// excludes it can't start (Fabric refuses the whole set).
pub const MINECRAFT: &str = "26.3";

/// One jar of `mods-java/`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JavaMod {
    /// Its file name in the folder.
    pub file: String,
    /// `fabric.mod.json`'s id, name and version, when the jar has them.
    pub id: Option<String>,
    pub name: Option<String>,
    pub version: Option<String>,
    pub size: u64,
    /// `fabric.mod.json`'s `depends.minecraft`, as written, when it excludes [`MINECRAFT`]:
    /// a jar made for another Minecraft version. None when it fits or can't be told.
    pub wrong_minecraft: Option<String>,
}

impl JavaMod {
    /// What to call it: its Fabric name, else its file name.
    pub fn title(&self) -> &str {
        self.name.as_deref().unwrap_or(&self.file)
    }
}

/// What the game's last log says went wrong with its Java mods.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Failure {
    /// The mod named, when the line names one ("[Java mods] <mod> failed in <where>: …").
    pub name: Option<String>,
    /// The line, without its time and thread.
    pub text: String,
}

/// `home`'s `mods-java/`.
pub fn dir(home: &Path) -> PathBuf {
    home.join(DIR)
}

/// The jars in `home`'s `mods-java/`, by file name.
pub fn list(home: &Path) -> Vec<JavaMod> {
    let mut mods: Vec<JavaMod> = std::fs::read_dir(dir(home))
        .map(|entries| {
            entries
                .flatten()
                .map(|e| e.path())
                .filter(|p| p.is_file() && loaded_jar(p))
                .map(|p| describe(&p))
                .collect()
        })
        .unwrap_or_default();
    mods.sort_by_key(|m| m.file.to_lowercase());
    mods
}

fn is_jar(path: &Path) -> bool {
    path.extension()
        .is_some_and(|e| e.eq_ignore_ascii_case("jar"))
}

/// A jar the game loads from the folder: Fabric's folder scan takes a lower-case `.jar` only.
fn loaded_jar(path: &Path) -> bool {
    path.extension().is_some_and(|e| e == "jar")
}

/// `name` with a lower-case `.jar`, as the folder keeps it (`Foo.JAR` → `Foo.jar`).
fn folder_name(name: &std::ffi::OsStr) -> std::ffi::OsString {
    Path::new(name).with_extension("jar").into_os_string()
}

/// What removing a jar says while the game holds it (Windows keeps a jar Java has open).
pub const CLOSE_GAME_FIRST: &str = "Close the game first: it has {mod} open.";

/// A jar's file name, size and Fabric facts.
fn describe(path: &Path) -> JavaMod {
    let meta = fabric_meta(path);
    let field = |key: &str| {
        meta.as_ref()
            .and_then(|m| m.get(key))
            .and_then(serde_json::Value::as_str)
            .map(str::to_string)
    };
    JavaMod {
        file: path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default(),
        id: field("id"),
        name: field("name"),
        version: field("version"),
        size: std::fs::metadata(path).map(|m| m.len()).unwrap_or(0),
        wrong_minecraft: meta
            .as_ref()
            .and_then(|m| m.pointer("/depends/minecraft"))
            .filter(|wants| runs_on(wants, MINECRAFT) == Some(false))
            .map(|wants| match wants {
                serde_json::Value::Array(any) => any
                    .iter()
                    .filter_map(serde_json::Value::as_str)
                    .collect::<Vec<_>>()
                    .join(" or "),
                other => other.as_str().unwrap_or_default().to_string(),
            }),
    }
}

/// Whether `file` is a Fabric mod's jar: a `.jar` with a `fabric.mod.json` that parses.
pub fn is_fabric_mod(file: &Path) -> bool {
    is_jar(file) && fabric_meta(file).is_some()
}

/// The mods two or more jars of `mods` are, by Fabric id: (the id, their file names). Fabric
/// refuses to start with two copies of a mod.
pub fn duplicates(mods: &[JavaMod]) -> Vec<(String, Vec<String>)> {
    let mut by_id: std::collections::BTreeMap<&str, Vec<String>> = Default::default();
    for m in mods {
        if let Some(id) = m.id.as_deref() {
            by_id.entry(id).or_default().push(m.file.clone());
        }
    }
    by_id
        .into_iter()
        .filter(|(_, files)| files.len() > 1)
        .map(|(id, files)| (id.to_string(), files))
        .collect()
}

/// Whether a mod whose `depends.minecraft` is `wants` (a version predicate, or a list of them
/// any of which will do) runs on Minecraft `version`, as Fabric's resolver reads it: `*`,
/// `26.3`, `=26.3`, `>=26.3`, `<27`, `~26.3` (26.3.*), `^26.3` (26.*), `26.3.x`, several
/// joined by spaces (all must hold). None when it can't be told (a form this doesn't read).
pub fn runs_on(wants: &serde_json::Value, version: &str) -> Option<bool> {
    let have = Version::parse(version)?;
    match wants {
        serde_json::Value::String(all) => {
            let mut out = Some(true);
            for term in all.split_whitespace() {
                match term_holds(term, &have) {
                    Some(false) => return Some(false),
                    None => out = None,
                    Some(true) => {}
                }
            }
            out
        }
        serde_json::Value::Array(any) => {
            let mut out = Some(false);
            for one in any {
                match runs_on(one, version) {
                    Some(true) => return Some(true),
                    None => out = None,
                    Some(false) => {}
                }
            }
            out
        }
        _ => None,
    }
}

/// Two pre-release parts as SemVer (and Fabric's SemanticVersionImpl) compare them: part by
/// part on the dots, numbers as numbers (`6.0.10` after `6.0.9`: JourneyMap `26.3-6.0.10` meets
/// MapFrontiers' `>=26.3-6.0.9`), a number before a word, words as text, fewer parts first.
fn pre_cmp(a: &str, b: &str) -> std::cmp::Ordering {
    let (mut x, mut y) = (a.split('.'), b.split('.'));
    loop {
        match (x.next(), y.next()) {
            (None, None) => return std::cmp::Ordering::Equal,
            (None, Some(_)) => return std::cmp::Ordering::Less,
            (Some(_), None) => return std::cmp::Ordering::Greater,
            (Some(p), Some(q)) => {
                let o = match (p.parse::<u64>(), q.parse::<u64>()) {
                    (Ok(m), Ok(n)) => m.cmp(&n),
                    (Ok(_), Err(_)) => std::cmp::Ordering::Less,
                    (Err(_), Ok(_)) => std::cmp::Ordering::Greater,
                    (Err(_), Err(_)) => p.cmp(q),
                };
                if o.is_ne() {
                    return o;
                }
            }
        }
    }
}

/// A version as Fabric compares them: numbers, then a pre-release (`-rc.1`) before the release.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Version {
    numbers: Vec<u64>,
    pre: Option<String>,
}

impl Version {
    fn parse(text: &str) -> Option<Self> {
        let text = text.split('+').next()?;
        let (numbers, pre) = match text.split_once('-') {
            Some((n, p)) => (n, Some(p.to_string())),
            None => (text, None),
        };
        let numbers = numbers
            .split('.')
            .map(|n| n.parse().ok())
            .collect::<Option<Vec<u64>>>()?;
        Some(Self { numbers, pre })
    }

    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        let len = self.numbers.len().max(other.numbers.len());
        let at = |v: &Self, i: usize| v.numbers.get(i).copied().unwrap_or(0);
        (0..len)
            .map(|i| at(self, i).cmp(&at(other, i)))
            .find(|o| o.is_ne())
            .unwrap_or_else(|| match (&self.pre, &other.pre) {
                (None, None) => std::cmp::Ordering::Equal,
                (None, Some(_)) => std::cmp::Ordering::Greater,
                (Some(_), None) => std::cmp::Ordering::Less,
                (Some(a), Some(b)) => pre_cmp(a, b),
            })
    }

    /// The first version past every one starting with this one's first `len` numbers
    /// (`26.3`, 2 → `26.4`).
    fn bumped(&self, len: usize) -> Self {
        let mut numbers: Vec<u64> = (0..len.max(1))
            .map(|i| self.numbers.get(i).copied().unwrap_or(0))
            .collect();
        if let Some(last) = numbers.last_mut() {
            *last += 1;
        }
        Self { numbers, pre: None }
    }
}

/// One space-free predicate of `depends.minecraft` against `have`.
fn term_holds(term: &str, have: &Version) -> Option<bool> {
    use std::cmp::Ordering::*;
    if term == "*" || term.eq_ignore_ascii_case("x") {
        return Some(true);
    }
    let (op, rest) = [">=", "<=", ">", "<", "=", "~", "^"]
        .into_iter()
        .find_map(|op| term.strip_prefix(op).map(|r| (op, r)))
        .unwrap_or(("", term));
    // `26.3.x`, `26.x`, `1.21.*`: the numbers before the wildcard must match.
    let parts: Vec<&str> = rest.split('.').collect();
    if let Some(wild) = parts
        .iter()
        .position(|p| p.eq_ignore_ascii_case("x") || *p == "*")
    {
        if !op.is_empty() && op != "=" {
            return None;
        }
        let prefix = parts[..wild]
            .iter()
            .map(|n| n.parse().ok())
            .collect::<Option<Vec<u64>>>()?;
        return Some(
            prefix
                .iter()
                .enumerate()
                .all(|(i, n)| have.numbers.get(i).copied().unwrap_or(0) == *n),
        );
    }
    let want = Version::parse(rest)?;
    let order = have.cmp(&want);
    Some(match op {
        ">=" => order != Less,
        "<=" => order != Greater,
        ">" => order == Greater,
        "<" => order == Less,
        "" | "=" => order == Equal,
        // ~26.3: 26.3 up to 26.4; ~26: 26 up to 27.
        "~" => order != Less && have.cmp(&want.bumped(want.numbers.len().min(2))) == Less,
        // ^26.3: 26.3 up to 27; ^0.5.1: up to 0.6.
        _ => {
            let first = want
                .numbers
                .iter()
                .position(|n| *n != 0)
                .unwrap_or(want.numbers.len().saturating_sub(1));
            order != Less && have.cmp(&want.bumped(first + 1)) == Less
        }
    })
}

/// The jar's `fabric.mod.json`, if it has one that parses.
fn fabric_meta(path: &Path) -> Option<serde_json::Value> {
    let mut archive = zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()?;
    let entry = archive.by_name("fabric.mod.json").ok()?;
    let mut text = String::new();
    entry.take(1 << 20).read_to_string(&mut text).ok()?;
    // Some mods' files carry raw line breaks inside strings, which Fabric's reader accepts.
    let text: String = text
        .chars()
        .map(|c| if c == '\n' || c == '\r' { ' ' } else { c })
        .collect();
    serde_json::from_str(&text).ok()
}

/// Copies the Fabric mod `jar` into `home`'s `mods-java/`. A jar without `fabric.mod.json` (not
/// a Fabric mod: a Forge or NeoForge one) is refused, and so is a file name already there.
pub fn add(home: &Path, jar: &Path) -> Result<JavaMod, String> {
    if !jar.is_file() || !is_jar(jar) {
        return Err(format!("{} is not a .jar file", jar.display()));
    }
    if fabric_meta(jar).is_none() {
        return Err(format!(
            "{} is not a Fabric mod (it has no fabric.mod.json): Horizon runs Fabric mods",
            jar.display()
        ));
    }
    let name = jar.file_name().ok_or("The jar has no file name")?;
    let folder = dir(home);
    std::fs::create_dir_all(&folder).map_err(|e| format!("{}: {e}", folder.display()))?;
    let name = folder_name(name);
    let target = folder.join(&name);
    if target.exists() {
        return Err(format!("{} is already in {DIR}", name.to_string_lossy()));
    }
    std::fs::copy(jar, &target).map_err(|e| format!("{}: {e}", target.display()))?;
    Ok(describe(&target))
}

/// Sends `file` (a file name in `mods-java/`, or the mod's Fabric id or name) to the trash.
pub fn remove(home: &Path, which: &str) -> Result<JavaMod, String> {
    let found = list(home)
        .into_iter()
        .find(|m| {
            m.file == which
                || m.id.as_deref() == Some(which)
                || m.name
                    .as_deref()
                    .is_some_and(|n| n.eq_ignore_ascii_case(which))
        })
        .ok_or_else(|| format!("No Java mod {which} in {DIR}"))?;
    let path = dir(home).join(&found.file);
    let held = || crate::i18n::trf(CLOSE_GAME_FIRST, &[("mod", found.title())]);
    if held_open(&path) {
        return Err(held());
    }
    crate::desktop_seams::to_trash(&path).map_err(|e| if held_open(&path) { held() } else { e })?;
    Ok(found)
}

/// Whether another program (the game's Java) has `path` open: on Windows, which then refuses to
/// move it, the file can't be opened for itself alone. Elsewhere a held file moves freely.
fn held_open(path: &Path) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        let alone = std::fs::OpenOptions::new()
            .read(true)
            .share_mode(0)
            .open(path);
        matches!(alone, Err(e) if crate::java_variant::in_use(&e))
    }
    #[cfg(not(windows))]
    {
        let _ = path;
        false
    }
}

/// What went wrong with the Java mods in the game's last run (`logs/latest.log` in `home`):
/// the mods that failed while it ran, as the game told the players, and a start that failed.
pub fn failures(home: &Path) -> Vec<Failure> {
    let log = std::fs::read_to_string(home.join("logs").join("latest.log")).unwrap_or_default();
    failures_in(&log)
}

fn failures_in(log: &str) -> Vec<Failure> {
    let mut out: Vec<Failure> = Vec::new();
    // Inside Fabric's resolution report, whose lines after its first ("Incompatible mods
    // found!") carry no time or thread: "\t - Mod 'Name' (id) 1.0 requires …".
    let mut report = false;
    for line in log.lines() {
        let record = line.starts_with('[') && line.contains("]: ");
        if report && !record {
            if let Some(failure) = report_line(line) {
                if !out.contains(&failure) {
                    out.push(failure);
                }
            }
            continue;
        }
        report = false;
        // "[12:00:00] [Server thread/INFO]: ..." → the message.
        let message = line.split_once("]: ").map_or(line, |(_, m)| m).trim();
        if message.contains("Incompatible mods found")
            || message.contains("Mod resolution failed")
            || message.contains("incompatible mod set")
        {
            report = true;
        }
        let failure = if let Some(rest) = message.split_once("[Java mods] ").map(|(_, r)| r) {
            let Some((name, _)) = rest.split_once(" failed in ") else {
                continue;
            };
            Failure {
                name: Some(name.trim().to_string()),
                text: rest.trim().to_string(),
            }
        } else if let Some(rest) = message
            .strip_prefix("Java mods: ")
            .filter(|r| r.contains(" isn't needed in Horizon"))
        {
            // Left out on purpose (a renderer or performance mod: Horizon is the engine): said, not an error.
            Failure {
                name: rest.split(" isn't needed in Horizon").next().map(|n| n.trim().to_string()),
                text: rest.trim().to_string(),
            }
        } else if let Some(rest) = message
            .strip_prefix("Java mods: ")
            .filter(|r| r.contains(" can't be read (left out)"))
        {
            // One mod's broken recipe or advancement (data made for another mod's version): left out.
            Failure {
                name: rest.split("'s ").next().map(|n| n.trim().to_string()),
                text: rest.split(": ").next().unwrap_or(rest).trim().to_string(),
            }
        } else if message.starts_with("Java mods: not started")
            || message.starts_with("Java mods: Fabric can't start")
            || message.contains("Incompatible mods found")
            || message.contains("Mod resolution failed")
        {
            Failure {
                name: None,
                text: message.to_string(),
            }
        } else {
            continue;
        };
        if !out.contains(&failure) {
            out.push(failure);
        }
    }
    out
}

/// A line of Fabric's resolution report worth showing: "- Mod 'Name' (id) …" names the mod
/// (its row in the tab is marked), "- Install fabric-api, any version." and the like don't.
fn report_line(line: &str) -> Option<Failure> {
    let text = line.trim().strip_prefix("- ")?.trim();
    if text.is_empty() {
        return None;
    }
    let name = text
        .strip_prefix("Mod '")
        .and_then(|rest| rest.split_once("' (").map(|(name, _)| name.to_string()));
    Some(Failure {
        name,
        text: text.to_string(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn left_out_mods_and_broken_data_are_reported_plainly() {
        let log = "[01:00:00] [Render thread/INFO]: Java mods: Lithium isn't needed in Horizon: Horizon's own engine is already fast (left out)\n\
                   [01:00:01] [Render thread/INFO]: Java mods: justenoughbreeding's recipe justenoughbreeding:breeding/x can't be read (left out): Failed to parse\n";
        let f = failures_in(log);
        assert_eq!(f.len(), 2);
        assert_eq!(f[0].name.as_deref(), Some("Lithium"));
        assert!(f[0].text.contains("isn't needed in Horizon"));
        assert_eq!(f[1].name.as_deref(), Some("justenoughbreeding"));
        assert!(f[1].text.ends_with("can't be read (left out)"));
    }

    fn jar(path: &Path, meta: Option<&str>) {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        let options = zip::write::SimpleFileOptions::default();
        if let Some(meta) = meta {
            zip.start_file("fabric.mod.json", options).unwrap();
            std::io::Write::write_all(&mut zip, meta.as_bytes()).unwrap();
        }
        zip.start_file("a/B.class", options).unwrap();
        zip.finish().unwrap();
    }

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("hz-java-mods-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn adds_lists_and_removes_fabric_jars() {
        let root = scratch("add");
        let home = root.join("home");
        let fd = root.join("FarmersDelight.jar");
        jar(
            &fd,
            Some(r#"{"id":"farmersdelight","name":"Farmer's Delight","version":"3.6"}"#),
        );
        let forge = root.join("forge-only.jar");
        jar(&forge, None);
        let added = add(&home, &fd).unwrap();
        assert_eq!(added.title(), "Farmer's Delight");
        assert!(add(&home, &fd).unwrap_err().contains("already"));
        assert!(add(&home, &forge).unwrap_err().contains("not a Fabric mod"));
        let listed = list(&home);
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id.as_deref(), Some("farmersdelight"));
        // SAFETY of the test: the trash is a scratch folder (desktop_seams).
        unsafe { std::env::set_var(crate::desktop_seams::TRASH_ENV, root.join("trash")) };
        remove(&home, "farmersdelight").unwrap();
        assert!(list(&home).is_empty());
        assert!(root.join("trash/FarmersDelight.jar").is_file());
        assert!(remove(&home, "farmersdelight").is_err());
        let _ = std::fs::remove_dir_all(&root);
    }

    /// Fabric's folder scan skips `Foo.JAR`: added, it is renamed `Foo.jar`, and one put there
    /// by hand is not listed as a mod the game runs.
    #[test]
    fn an_upper_case_jar_is_added_as_the_game_loads_it() {
        let root = scratch("upper");
        let home = root.join("home");
        let upper = root.join("Upper.JAR");
        jar(&upper, Some(r#"{"id":"upper"}"#));
        assert_eq!(add(&home, &upper).unwrap().file, "Upper.jar");
        assert!(dir(&home).join("Upper.jar").is_file());
        std::fs::copy(&upper, dir(&home).join("Other.JAR")).unwrap();
        let files: Vec<String> = list(&home).into_iter().map(|m| m.file).collect();
        assert_eq!(files, ["Upper.jar"]);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn reads_failures_from_the_log() {
        let log = "\
[10:00:00] [Server thread/INFO]: System chat: [Java mods] Farmer's Delight failed in the HUD: NullPointerException: x (the game goes on; the log has more)
[10:00:01] [Server thread/INFO]: System chat: [Java mods] Farmer's Delight failed in the HUD: NullPointerException: x (the game goes on; the log has more)
[10:00:02] [Render thread/WARN]: Java mods: not started: no Java 25
[10:00:03] [Server thread/INFO]: System chat: hello
";
        let found = failures_in(log);
        assert_eq!(found.len(), 2);
        // The owner gate's refusal stops the game the same way (`not_started`): the tab says why.
        let refused = "[10:00:00] [main/WARN]: Java mods: off: Fabric 26.3 mods run only when Horizon's launcher starts the game (no owner record from the launcher)
[10:00:00] [main/ERROR]: Java mods: not started: Fabric 26.3 mods run only when Horizon's launcher starts the game (no owner record from the launcher)
";
        let gate = failures_in(refused);
        assert_eq!(gate.len(), 1, "{gate:?}");
        assert!(gate[0].text.contains("no owner record"));
        assert_eq!(found[0].name.as_deref(), Some("Farmer's Delight"));
        assert!(found[1].text.starts_with("Java mods: not started"));
    }

    /// Fabric's report spreads over lines with no time or thread: each "- Mod '…'" names its
    /// mod, a suggestion is kept unnamed, and the next log record ends the report.
    #[test]
    fn reads_the_mods_fabrics_resolution_report_names() {
        let log = "\
[10:00:00] [Java mods/WARN]: Java mods: Fabric can't start: Incompatible mods found!
net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!
A potential solution has been determined, this may resolve your problem:
\t - Install fabric-language-kotlin, version 1.13.0 or later.
More details:
\t - Mod 'Farmer's Delight' (farmersdelight) 3.6 requires version 1.21.1 of 'Minecraft' (minecraft), but only the wrong version is present: 26.3!
\t - Mod 'Croptopia' (croptopia) 4.0 requires any version of fabric-language-kotlin, which is missing!
[10:00:01] [Render thread/INFO]: - Mod 'Not a report' (no) 1.0
";
        let found = failures_in(log);
        let names: Vec<_> = found.iter().map(|f| f.name.as_deref()).collect();
        assert_eq!(
            names,
            [None, None, Some("Farmer's Delight"), Some("Croptopia")],
            "{found:#?}"
        );
        assert!(found[1].text.starts_with("Install fabric-language-kotlin"));
        assert!(found[2].text.contains("requires version 1.21.1"));
    }

    fn on_26_3(wants: serde_json::Value) -> Option<bool> {
        runs_on(&wants, "26.3")
    }

    #[test]
    fn compares_pre_release_parts_as_numbers() {
        let wants = serde_json::json!(">=26.3-6.0.9");
        assert_eq!(runs_on(&wants, "26.3-6.0.10"), Some(true));
        assert_eq!(runs_on(&wants, "26.3-6.0.9"), Some(true));
        assert_eq!(runs_on(&wants, "26.3-6.0.8"), Some(false));
        assert_eq!(runs_on(&serde_json::json!(">=1.0-rc.2"), "1.0-rc.10"), Some(true));
        assert_eq!(runs_on(&serde_json::json!(">=1.0-beta"), "1.0-alpha"), Some(false));
    }

    #[test]
    fn reads_which_minecraft_versions_a_mod_runs_on() {
        use serde_json::json;
        for yes in [
            "*",
            "26.3",
            "=26.3",
            "26.3.0",
            ">=26.3",
            ">=26.3-alpha.1",
            ">26.2",
            "<=26.3",
            "<27",
            "~26.3",
            "~26",
            "^26.1",
            "26.3.x",
            "26.x",
            ">=26.2 <26.4",
            ">=1.21",
        ] {
            assert_eq!(on_26_3(json!(yes)), Some(true), "{yes}");
        }
        for no in [
            "1.21.x",
            "1.21.1",
            "~1.21",
            "^1.21",
            ">26.3",
            "<26.3",
            ">=26.4",
            "~26.4",
            "26.4.x",
            ">=1.21 <1.22",
            "26.3-rc.1",
        ] {
            assert_eq!(on_26_3(json!(no)), Some(false), "{no}");
        }
        // A list: any of them will do.
        assert_eq!(on_26_3(json!(["1.21.x", "~26.3"])), Some(true));
        assert_eq!(on_26_3(json!(["1.21.x", "1.20.1"])), Some(false));
        // What this can't read is never called wrong.
        assert_eq!(on_26_3(json!("weird-thing")), None);
        assert_eq!(on_26_3(json!([">=1.21.x"])), None);
        assert_eq!(on_26_3(json!(3)), None);
    }

    #[test]
    fn marks_jars_for_another_minecraft_and_two_copies_of_a_mod() {
        let root = scratch("versions");
        let home = root.join("home");
        let old = root.join("old.jar");
        jar(
            &old,
            Some(r#"{"id":"old","depends":{"minecraft":["1.21.x","1.20.1"]}}"#),
        );
        let new = root.join("new.jar");
        jar(
            &new,
            Some(r#"{"id":"new","depends":{"minecraft":"~26.3"}}"#),
        );
        let copy = root.join("new-copy.jar");
        jar(&copy, Some(r#"{"id":"new","depends":{"minecraft":"*"}}"#));
        assert_eq!(
            add(&home, &old).unwrap().wrong_minecraft.as_deref(),
            Some("1.21.x or 1.20.1")
        );
        assert_eq!(add(&home, &new).unwrap().wrong_minecraft, None);
        add(&home, &copy).unwrap();
        assert!(is_fabric_mod(&new));
        assert!(!is_fabric_mod(&root.join("missing.jar")));
        assert_eq!(
            duplicates(&list(&home)),
            [(
                "new".to_string(),
                vec!["new-copy.jar".to_string(), "new.jar".to_string()]
            )]
        );
        let _ = std::fs::remove_dir_all(&root);
    }
}
