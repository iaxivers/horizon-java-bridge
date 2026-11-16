//! `horizon-launcher java-mods`: Fabric 26.3 mods (beta), docs/java-bridge.md. The switch of an
//! instance (`java_variant`) and its mods-java/ (`java_mods`), and Play's Java mods build.
use super::*;

#[derive(Subcommand)]
pub(super) enum JavaModsCmd {
    /// The jars, with their Fabric names and versions
    List {
        /// The instance (default: the one Play starts)
        #[arg(long)]
        instance: Option<String>,
    },
    /// Copy a Fabric mod's jar into mods-java/
    Add {
        jar: PathBuf,
        #[arg(long)]
        instance: Option<String>,
    },
    /// Send a jar to the trash (its file name, Fabric id or name)
    Remove {
        name: String,
        #[arg(long)]
        instance: Option<String>,
    },
    /// Download from Modrinth the mods the jars need and nobody added (also done at Play)
    Deps {
        #[arg(long)]
        instance: Option<String>,
    },
    /// What failed with the Java mods in the instance's last game (its log)
    Failed {
        #[arg(long)]
        instance: Option<String>,
    },
    /// A bug report to paste in an issue: versions, system, Java, jars, the log's tail and
    /// errors, the last crash (secrets taken out; nothing is uploaded)
    Report {
        #[arg(long)]
        instance: Option<String>,
    },
    /// Whether the instance plays Fabric 26.3 mods (beta), and its version's Java mods build
    Status {
        #[arg(long)]
        instance: Option<String>,
    },
    /// Turn Fabric 26.3 mods (beta) on: the instance plays the version's Java mods build
    /// (downloaded now) and runs the jars of its mods-java/
    On {
        #[arg(long)]
        instance: Option<String>,
    },
    /// Turn them off: the plain build, no Java started, whatever mods-java/ holds
    Off {
        #[arg(long)]
        instance: Option<String>,
    },
    /// Download the version's Fabric mods build again, the newest that fits it (an installed
    /// build is never replaced otherwise, but by a new version)
    Update {
        /// The installed version (default: the one the instance plays)
        #[arg(long)]
        version: Option<String>,
        #[arg(long)]
        instance: Option<String>,
    },
    /// Use a Java mods build of your own (a horizon-client built with --features java-mods) as an
    /// installed version's, in place of a download
    UseBuild {
        program: PathBuf,
        /// The installed version (default: the one the instance plays)
        #[arg(long)]
        version: Option<String>,
        #[arg(long)]
        instance: Option<String>,
    },
}

/// `java-mods`: an instance's mods-java/ (no subcommand: `list`).
pub(super) fn java_mods(action: Option<JavaModsCmd>, as_json: bool) -> Result<(), String> {
    let action = action.unwrap_or(JavaModsCmd::List { instance: None });
    use horizon_launcher::{java_mods, java_variant};
    let pick = |instance: &Option<String>| -> Result<instances::Instance, String> {
        Ok(match instance {
            Some(name) => instances::find(name)?,
            None => instances::selected(),
        })
    };
    let home = |instance: &Option<String>| -> Result<PathBuf, String> { Ok(pick(instance)?.home) };
    let status = |i: &instances::Instance| {
        let tag = i.resolve_version(None).ok();
        let build = tag.as_deref().is_some_and(java_variant::is_installed);
        if as_json {
            json(serde_json::json!({
                "instance": i.id, "on": i.java_mods, "version": tag,
                "java_build_installed": build, "available": java_variant::available(),
            }));
        } else {
            let state = if i.java_mods { "on" } else { "off" };
            let build = match (&tag, build) {
                (Some(tag), true) => format!("; {tag}'s Java mods build is installed"),
                (Some(tag), false) => format!("; {tag} has no Java mods build installed"),
                (None, _) => String::new(),
            };
            println!("Fabric 26.3 mods (beta) are {state} for {}{build}.", i.name);
        }
    };
    let mod_json = |m: &java_mods::JavaMod| {
        serde_json::json!({
            "file": m.file, "id": m.id, "name": m.name, "version": m.version, "size": m.size,
        })
    };
    match action {
        JavaModsCmd::List { instance } => {
            let home = home(&instance)?;
            let mods = java_mods::list(&home);
            if as_json {
                json(serde_json::json!({
                    "folder": java_mods::dir(&home),
                    "mods": mods.iter().map(mod_json).collect::<Vec<_>>(),
                }));
            } else if mods.is_empty() {
                println!("No Java mods in {}.", java_mods::dir(&home).display());
            } else {
                for m in &mods {
                    let version = m.version.as_deref().unwrap_or("");
                    println!("{}  {version}  ({})", m.title(), m.file);
                }
            }
        }
        JavaModsCmd::Add { jar, instance } => {
            let added = java_mods::add(&home(&instance)?, &jar)?;
            if as_json {
                json(mod_json(&added));
            } else {
                println!("Added {} ({}).", added.title(), added.file);
            }
        }
        JavaModsCmd::Deps { instance } => {
            let dir = java_mods::dir(&home(&instance)?);
            let report = horizon_launcher::java_deps::resolve(
                &horizon_launcher::browser::modrinth::Modrinth,
                &dir,
                &|l| eprintln!("{l}"),
            );
            if as_json {
                json(serde_json::json!({"added": report.added, "unresolved": report.unresolved}));
            } else if report.added.is_empty() && report.unresolved.is_empty() {
                println!("Every mod's required mods are there.");
            } else {
                for line in report.lines() {
                    println!("{line}");
                }
            }
        }
        JavaModsCmd::Remove { name, instance } => {
            let removed = java_mods::remove(&home(&instance)?, &name)?;
            if as_json {
                json(mod_json(&removed));
            } else {
                println!("Moved {} to the trash.", removed.file);
            }
        }
        JavaModsCmd::Status { instance } => status(&pick(&instance)?),
        JavaModsCmd::Report { instance } => {
            let text = horizon_launcher::java_bug_report::for_instance(&pick(&instance)?);
            if as_json {
                json(serde_json::json!({ "report": text }));
            } else {
                print!("{text}");
            }
        }
        JavaModsCmd::On { instance } => {
            let i = pick(&instance)?;
            if i.java.is_some() {
                return Err(format!(
                    "{} plays Minecraft: Java Edition, not Horizon.",
                    i.name
                ));
            }
            if !java_variant::available() {
                return Err(
                    "Fabric mods have no build for this system yet (Linux and Windows only)."
                        .into(),
                );
            }
            if !i.java_mods_warned {
                // The window's confirmation, said once: the command itself is the answer.
                eprintln!(
                    "{}",
                    horizon_launcher::i18n::tr(java_variant::WARNING_TITLE)
                );
                for line in java_variant::WARNING {
                    eprintln!("  {}", horizon_launcher::i18n::tr(line));
                }
            }
            let i = instances::edit(&i.id, |m| {
                m.java_mods = true;
                m.java_mods_warned = true;
            })?;
            // The version's build now, so Play doesn't wait for it. A version installed later
            // gets its own with it (`java_variant::follow`); Play fetches a missing one.
            if let Ok(tag) = i.resolve_version(None) {
                if let Err(e) =
                    java_variant::ensure(&tag, progress(format!("Fabric mods build of {tag}")))
                {
                    eprintln!("{e}");
                }
            }
            status(&i);
        }
        JavaModsCmd::Off { instance } => {
            let i = instances::edit(&pick(&instance)?.id, |m| m.java_mods = false)?;
            status(&i);
        }
        JavaModsCmd::Update { version, instance } => {
            let i = pick(&instance)?;
            let tag = i.resolve_version(version.as_deref())?;
            java_variant::update(&tag, progress(format!("Fabric mods build of {tag}")))?;
            status(&i);
        }
        JavaModsCmd::UseBuild {
            program,
            version,
            instance,
        } => {
            let i = pick(&instance)?;
            let tag = i.resolve_version(version.as_deref())?;
            java_variant::use_build(&tag, &program)?;
            status(&i);
        }
        JavaModsCmd::Failed { instance } => {
            let failures = java_mods::failures(&home(&instance)?);
            if as_json {
                json(serde_json::json!(failures
                    .iter()
                    .map(|f| serde_json::json!({ "mod": f.name, "text": f.text }))
                    .collect::<Vec<_>>()));
            } else if failures.is_empty() {
                println!("Nothing failed in the last game's log.");
            } else {
                for f in &failures {
                    println!("{}", f.text);
                }
            }
        }
    }
    Ok(())
}

/// Play with Fabric 26.3 mods on: the version's Java mods build first, its download shown.
pub(super) fn ensure_build(instance: &instances::Instance, tag: &str) -> Result<(), String> {
    if instance.java_mods {
        let label = format!("Fabric mods build of {tag}");
        horizon_launcher::java_variant::ensure(tag, progress(label))?;
    }
    Ok(())
}
