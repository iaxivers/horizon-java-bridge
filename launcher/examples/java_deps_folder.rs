//! Runs the Java mods dependency resolver on any folder of jars (no instance, no launcher data):
//! `cargo run --example java_deps_folder -- <folder>`. Used by UMP's automatic tests.
fn main() {
    let dir = std::env::args().nth(1).expect("a folder of Fabric jars");
    let report = horizon_launcher::java_deps::resolve(
        &horizon_launcher::browser::modrinth::Modrinth,
        std::path::Path::new(&dir),
        &|l| eprintln!("{l}"),
    );
    for line in report.lines() {
        println!("{line}");
    }
    if report.added.is_empty() && report.updated.is_empty() && report.problems.is_empty() && report.unresolved.is_empty() {
        println!("Every mod's required mods are there.");
    }
}
