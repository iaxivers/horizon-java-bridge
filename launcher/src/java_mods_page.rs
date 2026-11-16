//! The selected instance's Java mods on Home (`horizon_launcher::java_mods`): the Fabric jars
//! in its `mods-java/`, which of them failed in the last game, Add (or drop jars on the tab),
//! Open folder and Remove (to the trash, after a confirmation). The tab shows only for an
//! instance whose "Fabric 26.3 mods (beta)" switch is on (Launch…, `java_variant`): Java mods
//! are opt-in, off by default.
use super::*;
use gpui::ExternalPaths;
use horizon_launcher::java_mods::{self, Failure, JavaMod};
use horizon_launcher::java_variant;
use std::path::PathBuf;

#[derive(Default)]
pub(super) struct JavaModsTab {
    home: Option<PathBuf>,
    list: Vec<JavaMod>,
    failures: Vec<Failure>,
    fresh: bool,
    /// The jar whose Remove waits for its confirmation, by file name.
    confirm_remove: Option<String>,
    /// What the last action did, or why it failed.
    note: Option<Result<String, String>>,
    /// The Fabric 26.3 mods switch's download, while it runs and once it ended.
    build: Option<JavaBuild>,
    /// Play's download of the Fabric mods build (or Java 25), while Play waits for it.
    play_progress: Option<f32>,
    /// Turning the switch on waits for the player to confirm `java_variant::WARNING` (once per
    /// instance: `java_mods_warned`).
    confirm_on: bool,
}

/// The switch's download of a version's Java mods build: the version, and how it went once done.
#[derive(Clone, Debug, Default)]
pub(super) struct JavaBuild {
    tag: String,
    done: Option<Result<(), String>>,
}

impl JavaModsTab {
    /// Read again on the next frame: a game may have run (its log) or the folder changed.
    pub(super) fn stale(&mut self) {
        self.fresh = false;
    }
}

impl Launcher {
    fn load_java_mods(&mut self, home: PathBuf) {
        if self.java_mods.home.as_ref() != Some(&home) {
            // Another instance: its own list, the switch's download kept.
            self.java_mods = JavaModsTab {
                home: Some(home.clone()),
                build: self.java_mods.build.take(),
                ..JavaModsTab::default()
            };
        }
        if !self.java_mods.fresh {
            self.java_mods.list = java_mods::list(&home);
            self.java_mods.failures = java_mods::failures(&home);
            self.java_mods.fresh = true;
        }
    }

    /// Whether the selected instance plays Fabric 26.3 mods (the tab shows).
    pub(super) fn has_java_mods_tab(&self) -> bool {
        self.selected_instance().is_some_and(|i| i.java_mods)
    }

    /// Turns the selected instance's Fabric 26.3 mods on or off, saved at once. On: first the
    /// warning (`java_variant::WARNING`) unless the player confirmed it for this instance
    /// before, then its `mods-java/` is made, and its version's Java mods build downloads unless
    /// it's there.
    pub(super) fn flip_java_mods(&mut self) {
        let Some(i) = self.selected_instance() else {
            return;
        };
        if !i.java_mods && !i.java_mods_warned {
            self.java_mods.confirm_on = true;
            return;
        }
        self.set_java_mods(!i.java_mods);
    }

    /// The warning's answer: on, and remembered for this instance; or left off.
    fn answer_java_mods_warning(&mut self, on: bool) {
        self.java_mods.confirm_on = false;
        if on {
            self.set_java_mods(true);
        }
    }

    fn set_java_mods(&mut self, on: bool) {
        let Some(i) = self.selected_instance().cloned() else {
            return;
        };
        let edited = instances::edit(&i.id, |m| {
            m.java_mods = on;
            // Only the confirmation leads here with the warning unread.
            m.java_mods_warned |= on;
        });
        if let Err(e) = edited {
            self.java_mods.build = Some(JavaBuild {
                tag: String::new(),
                done: Some(Err(e)),
            });
            return;
        }
        self.reload_instances();
        self.java_mods.build = None;
        if !on {
            return;
        }
        let _ = std::fs::create_dir_all(java_mods::dir(&i.home));
        self.java_mods.stale();
        let Some(tag) = self.selected().cloned() else {
            return;
        };
        if java_variant::is_installed(&tag) {
            return;
        }
        self.java_mods.build = Some(JavaBuild {
            tag: tag.clone(),
            done: None,
        });
        let tx = self.tx.clone();
        std::thread::spawn(move || {
            let result = java_variant::ensure(&tag, |_| {});
            let _ = tx.send(Msg::JavaBuild(tag, result));
        });
    }

    /// Why the selected instance's Fabric 26.3 mods can't be turned on now, if they can't: no
    /// Java mods build for this system, or no signed-in account that owns the game. Turning
    /// them off is never refused.
    pub(super) fn java_mods_lock(&self) -> Option<&'static str> {
        let i = self.selected_instance()?;
        if i.java_mods {
            return None;
        }
        if !java_variant::available() {
            return Some(tr("No build for this platform"));
        }
        if self.owner != Some(true) {
            return Some(tr("Sign in with a Minecraft account first."));
        }
        None
    }

    /// What Home's switches say under their name, and whether it is a failure: why it can't be
    /// turned on, the build's download, or On and Off.
    pub(super) fn java_mods_state(&self) -> (String, bool) {
        if let Some(reason) = self.java_mods_lock() {
            return (reason.to_string(), false);
        }
        let on = self.selected_instance().is_some_and(|i| i.java_mods);
        match self.java_mods.build.as_ref().map(|b| &b.done) {
            Some(None) => (tr("Downloading the Fabric mods build…").to_string(), false),
            Some(Some(Err(e))) => (e.clone(), true),
            _ => (tr(if on { "On" } else { "Off" }).to_string(), false),
        }
    }

    /// Play's download for the Fabric mods moved on (`ensure_at_play`).
    pub(super) fn java_play_progress(&mut self, p: f32) {
        self.java_mods.play_progress = Some(p);
    }

    /// How far Play's download for the Fabric mods is, while Play waits for it.
    pub(super) fn java_play_progress_shown(&self) -> Option<f32> {
        self.starting_client
            .as_ref()
            .and(self.java_mods.play_progress)
    }

    /// Play's start is over (the game started, or Play stopped): no download bar.
    pub(super) fn java_play_done(&mut self) {
        self.java_mods.play_progress = None;
    }

    /// Play stopped before the game: the Fabric mods build or Java 25 can't be had. Said in
    /// the window, as any other reason Play can't start.
    pub(super) fn java_play_stopped(&mut self, why: String) {
        self.java_play_done();
        if self.starting_client.take().is_some() {
            self.client_error = Some(why);
            self.starting_pack = None;
            self.joining = None;
            self.quick_play = None;
        }
    }

    /// A switch's download ended.
    pub(super) fn java_build_done(&mut self, tag: String, result: Result<(), String>) {
        if let Some(build) = self.java_mods.build.as_mut().filter(|b| b.tag == tag) {
            build.done = Some(result);
        }
    }

    /// Launch…'s Fabric 26.3 mods switch, for a Horizon instance on a system with the build.
    pub(super) fn java_mods_switch(&self, cx: &mut Context<Self>) -> Option<Div> {
        let t = self.theme;
        let i = self.selected_instance()?;
        if i.java.is_some() || !java_variant::available() {
            return None;
        }
        let row = div()
            .id("launch-switch-java-mods")
            .flex()
            .items_center()
            .gap(px(10.))
            .cursor_pointer()
            .child(switch("launch-switch-java-mods-s", i.java_mods, &t))
            .child(
                text(theme::LABEL)
                    .text_color(t.ink_soft)
                    .child(tr("Fabric 26.3 mods (beta)")),
            )
            .on_click(cx.listener(|this, _: &ClickEvent, _, cx| {
                this.flip_java_mods();
                cx.notify();
            }));
        let about = text(theme::CAPTION).text_color(t.muted).child(tr(
            "Runs the Fabric mods in this instance's mods-java folder. Off, the game never starts \
             Java. On, once you're signed in with an account that owns Minecraft, the launcher \
             downloads Horizon's build with Java mods, and Java 25 if you have none.",
        ));
        let state = self.java_mods.build.as_ref().map(|b| match &b.done {
            None => text(theme::CAPTION)
                .text_color(t.muted)
                .child(tr("Downloading the Fabric mods build…").to_string()),
            Some(Ok(())) => text(theme::CAPTION)
                .text_color(t.muted)
                .child(tr("The Fabric mods build is ready.").to_string()),
            Some(Err(e)) => text(theme::CAPTION).text_color(t.danger).child(e.clone()),
        });
        Some(
            div()
                .flex()
                .flex_col()
                .gap(px(8.))
                .child(caps(tr("Mods")).text_color(t.muted))
                .child(row)
                .child(about)
                .children(state),
        )
    }

    /// The warning before Fabric 26.3 mods turn on for the selected instance, over the page.
    pub(super) fn java_mods_warning(&mut self, cx: &mut Context<Self>) -> Option<Stateful<Div>> {
        use horizon_theme::glass::{ink, radius};
        if !self.java_mods.confirm_on {
            return None;
        }
        let t = self.theme;
        let name = self.selected_instance()?.name.clone();
        let title = div()
            .font_weight(FontWeight::SEMIBOLD)
            .text_size(px(22.))
            .line_height(px(28.))
            .text_color(if t.glass {
                glass_ui::white(ink::FULL)
            } else {
                t.ink
            })
            .child(tr(java_variant::WARNING_TITLE).to_string());
        let line = |en: &str, strong: bool| {
            let color = match (t.glass, strong) {
                (true, true) => glass_ui::white(ink::FULL),
                (true, false) => glass_ui::white(ink::BODY),
                (false, true) => t.ink,
                (false, false) => t.ink_soft,
            };
            text(theme::LABEL)
                .text_color(color)
                .child(tr(en).to_string())
        };
        let answers = div()
            .flex()
            .items_center()
            .gap(px(8.))
            .mt(px(8.))
            .child(
                button(
                    "java-mods-warning-on",
                    "Turn on Fabric mods",
                    Kind::Primary,
                    &t,
                )
                .on_click(cx.listener(|this, _: &ClickEvent, _, cx| {
                    this.answer_java_mods_warning(true);
                    cx.notify();
                })),
            )
            .child(
                button("java-mods-warning-cancel", "Cancel", Kind::Ghost, &t).on_click(
                    cx.listener(|this, _: &ClickEvent, _, cx| {
                        this.answer_java_mods_warning(false);
                        cx.notify();
                    }),
                ),
            );
        let body = div()
            .flex()
            .flex_col()
            .gap(px(12.))
            // A set width: text measured at it wraps where it is drawn.
            .w(px(520.))
            .max_w_full()
            .p(px(32.))
            .child(
                text(theme::CAPTION)
                    .text_color(if t.glass {
                        glass_ui::white(ink::QUIET)
                    } else {
                        t.muted
                    })
                    .child(name),
            )
            .child(title)
            .child(line(java_variant::WARNING[0], true))
            .child(line(java_variant::WARNING[1], true))
            .child(line(java_variant::WARNING[2], false))
            .child(answers);
        let card = if t.glass {
            glass_ui::dialog_plate(radius::MODAL)
        } else {
            div()
                .rounded(px(8.))
                .bg(t.panel)
                .border_1()
                .border_color(t.raised)
        }
        .id("java-mods-warning-card")
        .occlude()
        .child(body);
        let scrim = if t.glass {
            glass_ui::scrim()
        } else {
            t.page.opacity(0.8)
        };
        Some(
            div()
                .id("java-mods-warning")
                .absolute()
                .top_0()
                .left_0()
                .size_full()
                .flex()
                .items_center()
                .justify_center()
                .p(px(24.))
                .occlude()
                .on_scroll(|_, _, cx| cx.stop_propagation())
                .children(t.glass.then(glass_ui::scrim_backdrop))
                .child(
                    div()
                        .absolute()
                        .top_0()
                        .left_0()
                        .size_full()
                        .bg(scrim)
                        .on_mouse_down(
                            gpui::MouseButton::Left,
                            cx.listener(|this, _: &MouseDownEvent, _, cx| {
                                this.answer_java_mods_warning(false);
                                cx.notify();
                            }),
                        ),
                )
                .child(card),
        )
    }

    /// Home's Java mods tab and its count, when the selected instance has the switch on.
    pub(super) fn java_mods_tab_entry(&mut self) -> Option<(home_tabs::HomeTab, Option<usize>)> {
        self.has_java_mods_tab()
            .then(|| (home_tabs::HomeTab::JavaMods, Some(self.java_mods_count())))
    }

    /// The Fabric 26.3 mods tab in the glass look: its buttons above its list (the flat view's
    /// strip end).
    pub(super) fn glass_java_mods(&mut self, cx: &mut Context<Self>) -> Option<AnyElement> {
        self.java_mods_section(cx).map(|(actions, list)| {
            div()
                .flex()
                .flex_col()
                .gap(px(8.))
                .child(actions)
                .child(list)
                .into_any_element()
        })
    }

    /// Whether Play (`start_client`) gets the version's Java mods build first: Fabric 26.3 mods
    /// on for the selected instance, and no pack starting.
    pub(super) fn java_build_at_play(&self) -> bool {
        self.starting_pack.is_none() && self.has_java_mods_tab()
    }

    /// How many Java mods the selected instance has.
    pub(super) fn java_mods_count(&mut self) -> usize {
        let Some(home) = self.selected_instance().map(|i| i.home.clone()) else {
            return 0;
        };
        self.load_java_mods(home);
        self.java_mods.list.len()
    }

    pub(super) fn add_java_mods(&mut self, home: PathBuf, paths: Vec<PathBuf>) {
        let had = java_mods::list(&home);
        let mut added = Vec::new();
        let mut failed = Vec::new();
        for path in paths {
            match java_mods::add(&home, &path) {
                Ok(m) => {
                    if let Some(wants) = &m.wrong_minecraft {
                        failed.push(wrong_minecraft(&m.file, wants));
                    }
                    if let Some(other) = had
                        .iter()
                        .find(|h| h.id.is_some() && h.id == m.id && h.file != m.file)
                    {
                        failed.push(trf(
                            "{file} is the same mod as {other}: keep only one of them.",
                            &[("file", &m.file), ("other", &other.file)],
                        ));
                    }
                    added.push(m.title().to_string());
                }
                Err(e) => failed.push(e),
            }
        }
        if !added.is_empty() && !failed.is_empty() {
            failed.insert(0, trf("Added {names}.", &[("names", &added.join(", "))]));
        }
        self.java_mods.note = Some(if failed.is_empty() {
            Ok(trf(
                "Added {names}: they load the next time the game starts.",
                &[("names", &added.join(", "))],
            ))
        } else {
            Err(failed.join("\n"))
        });
        self.java_mods.stale();
    }

    fn choose_java_mods(&mut self, home: PathBuf, cx: &mut Context<Self>) {
        let paths = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: true,
            prompt: Some(tr("Add").into()),
        });
        cx.spawn(async move |this, cx| {
            if let Ok(Ok(Some(paths))) = paths.await {
                let _ = this.update(cx, |this, cx| {
                    this.add_java_mods(home, paths);
                    cx.notify();
                });
            }
        })
        .detach();
    }

    /// "Copy bug report": the selected instance's report (`java_bug_report`) to the clipboard.
    fn copy_bug_report(&mut self, cx: &mut Context<Self>) {
        let Some(i) = self.selected_instance().cloned() else {
            return;
        };
        let text = horizon_launcher::java_bug_report::for_instance(&i);
        copy_to_clipboard(cx, gpui::ClipboardItem::new_string(text));
        let said = trf(
            "Bug report copied. Paste it in a new issue at {link}, with what you did.",
            &[("link", horizon_launcher::java_bug_report::ISSUES)],
        );
        // In place of the toast's "Copied <the whole report>".
        crate::glass_toast::say(said.clone());
        self.java_mods.note = Some(Ok(said));
        cx.notify();
    }

    fn remove_java_mod(&mut self, home: PathBuf, file: String) {
        self.java_mods.confirm_remove = None;
        self.java_mods.note = Some(
            java_mods::remove(&home, &file)
                .map(|m| trf("{name} moved to the trash.", &[("name", m.title())])),
        );
        self.java_mods.stale();
    }

    /// Home's Java mods tab: its buttons (beside the tabs) and its list; None without an
    /// instance.
    pub(super) fn java_mods_section(
        &mut self,
        cx: &mut Context<Self>,
    ) -> Option<(Div, Stateful<Div>)> {
        let t = self.theme;
        let home = self.selected_instance()?.home.clone();
        self.load_java_mods(home.clone());
        let folder = java_mods::dir(&home);
        let logs = home.join("logs");
        let pick = home.clone();
        let buttons = div()
            .flex()
            .items_center()
            .gap(px(6.))
            .child(
                button("java-mods-add", "Add", Kind::Ghost, &t).on_click(cx.listener(
                    move |this, _: &ClickEvent, _, cx| {
                        this.choose_java_mods(pick.clone(), cx);
                    },
                )),
            )
            .child(
                button("java-mods-folder", "Open folder", Kind::Ghost, &t)
                    .on_click(move |_, _, cx| cx.open_with_system(&folder)),
            )
            .child(
                button("java-mods-report", "Copy bug report", Kind::Ghost, &t)
                    .on_click(cx.listener(|this, _: &ClickEvent, _, cx| this.copy_bug_report(cx))),
            )
            .child(
                button("java-mods-logs", "Open logs folder", Kind::Ghost, &t).on_click(
                    move |_, _, cx| {
                        let _ = std::fs::create_dir_all(&logs);
                        crate::glass_toast::open(cx, &logs);
                    },
                ),
            )
            .child(
                button("java-mods-refresh", "Refresh", Kind::Ghost, &t).on_click(cx.listener(
                    |this, _: &ClickEvent, _, cx| {
                        this.java_mods.stale();
                        cx.notify();
                    },
                )),
            );
        let mut section = div().flex().flex_col().gap(px(8.));
        match &self.java_mods.note {
            Some(Ok(m)) => {
                section = section.child(text(theme::CAPTION).text_color(t.muted).child(m.clone()))
            }
            Some(Err(e)) => section = section.child(notice(e.clone(), &t)),
            None => {}
        }
        // Failures not tied to one mod (Java or Fabric didn't start) come first.
        for failure in self.java_mods.failures.iter().filter(|f| f.name.is_none()) {
            section = section.child(notice(failure.text.clone(), &t));
        }
        for (id, files) in java_mods::duplicates(&self.java_mods.list) {
            section = section.child(notice(
                trf(
                    "{files} are the same mod ({id}): Fabric won't start with both. Keep only \
                     one of them.",
                    &[("files", &files.join(", ")), ("id", &id)],
                ),
                &t,
            ));
        }
        if self.java_mods.list.is_empty() {
            section = section.child(text(theme::CAPTION).text_color(t.muted).child(tr(
                "No Java mods yet: add Fabric mods' jars, or drop them here.",
            )));
        }
        let rows = self
            .java_mods
            .list
            .clone()
            .into_iter()
            .enumerate()
            .map(|(i, m)| {
                let failed: Vec<&Failure> = self
                    .java_mods
                    .failures
                    .iter()
                    .filter(|f| f.name.as_deref() == Some(m.title()))
                    .collect();
                let detail = match &m.version {
                    Some(v) => format!("{v} · {}", m.file),
                    None => m.file.clone(),
                };
                let mut info = div()
                    .flex()
                    .flex_col()
                    .flex_1()
                    .min_w_0()
                    .child(
                        blocks::pixel(15., FontWeight::MEDIUM)
                            .text_color(t.ink)
                            .truncate()
                            .child(m.title().to_string()),
                    )
                    .child(
                        text(theme::CAPTION)
                            .text_color(t.muted)
                            .truncate()
                            .child(detail),
                    );
                for f in &failed {
                    info = info.child(
                        text(theme::CAPTION)
                            .text_color(t.danger)
                            .child(f.text.clone()),
                    );
                }
                let wrong = m
                    .wrong_minecraft
                    .as_ref()
                    .map(|w| wrong_minecraft(&m.file, w));
                if let Some(wrong) = &wrong {
                    info = info.child(
                        text(theme::CAPTION)
                            .text_color(t.danger)
                            .child(wrong.clone()),
                    );
                }
                let mut actions = div().flex().items_center().gap(px(6.));
                if self.java_mods.confirm_remove.as_deref() == Some(m.file.as_str()) {
                    let (home, file) = (home.clone(), m.file.clone());
                    actions = actions
                        .child(
                            button(
                                SharedString::from(format!("java-mod-keep-{i}")),
                                "Keep",
                                Kind::Ghost,
                                &t,
                            )
                            .on_click(cx.listener(
                                |this, _: &ClickEvent, _, cx| {
                                    this.java_mods.confirm_remove = None;
                                    cx.notify();
                                },
                            )),
                        )
                        .child(
                            button(
                                SharedString::from(format!("java-mod-trash-{i}")),
                                "Move to trash",
                                Kind::Secondary,
                                &t,
                            )
                            .on_click(cx.listener(
                                move |this, _: &ClickEvent, _, cx| {
                                    this.remove_java_mod(home.clone(), file.clone());
                                    cx.notify();
                                },
                            )),
                        );
                } else {
                    let file = m.file.clone();
                    actions = actions.child(
                        button(
                            SharedString::from(format!("java-mod-remove-{i}")),
                            "Remove",
                            Kind::Ghost,
                            &t,
                        )
                        .on_click(cx.listener(
                            move |this, _: &ClickEvent, _, cx| {
                                this.java_mods.confirm_remove = Some(file.clone());
                                cx.notify();
                            },
                        )),
                    );
                }
                div()
                    .flex()
                    .items_center()
                    .gap(px(12.))
                    .px(px(10.))
                    .py(px(6.))
                    .border_2()
                    .border_color(if failed.is_empty() && wrong.is_none() {
                        t.outline
                    } else {
                        t.danger
                    })
                    .bg(t.well)
                    .child(info)
                    .child(actions)
            });
        let drop_home = home.clone();
        Some((
            buttons,
            div()
                .id("java-mods-drop")
                .border_2()
                .border_color(gpui::transparent_black())
                .drag_over::<ExternalPaths>(move |s, _, _, _| s.border_color(t.accent))
                .on_drop(cx.listener(move |this, paths: &ExternalPaths, _, cx| {
                    this.add_java_mods(drop_home.clone(), paths.paths().to_vec());
                    // Not the window's import as well.
                    cx.stop_propagation();
                    cx.notify();
                }))
                .child(section.children(rows)),
        ))
    }
}

/// What the tab says of a jar made for another Minecraft version.
fn wrong_minecraft(file: &str, wants: &str) -> String {
    trf(
        "{file} is made for Minecraft {wants}, not {version}: it can't start in Horizon. Get its \
         version for {version}.",
        &[
            ("file", file),
            ("wants", wants),
            ("version", java_mods::MINECRAFT),
        ],
    )
}

/// Play's thread, before the session: with Fabric 26.3 mods on ([`Launcher::java_build_at_play`]),
/// the version's Java mods build first, when it isn't there yet, its lines in the game log and
/// its download on Play's bar. Why it can't be had stops Play (`Msg::JavaPlayStopped`, shown
/// in the window): the game would refuse the mods anyway.
pub(super) fn ensure_at_play(java_build: bool, tag: &str, tx: &Sender<Msg>) -> Result<(), String> {
    if !java_build {
        return Ok(());
    }
    let progress = |p: f32| {
        let _ = tx.send(Msg::JavaPlayProgress(p));
    };
    let log = |line: &str| {
        let _ = tx.send(Msg::Line(Job::Client, line.to_string()));
    };
    java_variant::ensure_with(tag, &progress, &log)?;
    // Plug and play: the mods the jars need and nobody added come from Modrinth first.
    let dir = java_mods::dir(&horizon_launcher::instances::selected().home);
    let report = horizon_launcher::java_deps::resolve(&horizon_launcher::browser::modrinth::Modrinth, &dir, &log);
    for line in report.lines() {
        log(&line);
    }
    Ok(())
}
