//! Self-update from the GitHub releases of ThorBro Desktop (tags "desktop-v1.2.3").
use crate::sync;
use serde_json::{json, Value};
use std::path::PathBuf;

const RELEASES: &str = "https://api.github.com/repos/Lennardsmw/thorbro/releases?per_page=30";
pub const CURRENT: &str = env!("CARGO_PKG_VERSION");

fn newer(a: &str, b: &str) -> bool {
    let p = |s: &str| s.split(['.', '-']).map(|x| x.parse::<u64>().unwrap_or(0)).collect::<Vec<_>>();
    let (a, b) = (p(a), p(b));
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
        if x != y { return x > y; }
    }
    false
}

/// the installer file for this platform in a release
fn asset_for(assets: &[Value]) -> Option<(String, String)> {
    let want = if cfg!(target_os = "macos") { "universal.dmg" } else if cfg!(target_os = "windows") { "-setup.exe" } else { ".AppImage" };
    assets.iter().find(|a| a["name"].as_str().map(|n| n.ends_with(want)).unwrap_or(false))
        .map(|a| (a["name"].as_str().unwrap_or("").to_string(), a["browser_download_url"].as_str().unwrap_or("").to_string()))
}

/// {current, latest, available, name, url}
pub fn check() -> Value {
    let r = sync::client().get(RELEASES).header("Accept", "application/vnd.github+json").send()
        .and_then(|r| r.json::<Vec<Value>>());
    let Ok(list) = r else { return json!({"current": CURRENT, "error": "GitHub nicht erreichbar"}) };
    let best = list.iter()
        .filter(|r| r["tag_name"].as_str().map(|t| t.starts_with("desktop-v")).unwrap_or(false) && !r["draft"].as_bool().unwrap_or(false))
        .filter_map(|r| {
            let v = r["tag_name"].as_str()?.trim_start_matches("desktop-v").to_string();
            let (name, url) = asset_for(r["assets"].as_array()?)?;
            Some((v, name, url))
        })
        .max_by(|a, b| if newer(&a.0, &b.0) { std::cmp::Ordering::Greater } else { std::cmp::Ordering::Less });
    match best {
        Some((v, name, url)) => json!({"current": CURRENT, "latest": v, "available": newer(&v, CURRENT), "name": name, "url": url}),
        None => json!({"current": CURRENT, "latest": CURRENT, "available": false}),
    }
}

fn download(url: &str, name: &str) -> Result<PathBuf, String> {
    let dir = std::env::temp_dir().join("thorbro-update");
    let _ = std::fs::create_dir_all(&dir);
    let path = dir.join(name);
    let client = reqwest::blocking::Client::builder().timeout(std::time::Duration::from_secs(600)).user_agent("ThorBro-Desktop").build().map_err(|e| e.to_string())?;
    let bytes = client.get(url).send().and_then(|r| r.error_for_status()).and_then(|r| r.bytes()).map_err(|e| format!("Download: {e}"))?;
    std::fs::write(&path, &bytes).map_err(|e| e.to_string())?;
    Ok(path)
}

fn run(cmd: &str, args: &[&str]) -> Result<(), String> {
    let s = std::process::Command::new(cmd).args(args).status().map_err(|e| e.to_string())?;
    if s.success() { Ok(()) } else { Err(format!("{cmd} fehlgeschlagen")) }
}

/// downloads and installs the newest version; returns when the new version is ready to start
pub fn install() -> Result<String, String> {
    let c = check();
    if !c["available"].as_bool().unwrap_or(false) { return Err("Keine neuere Version".into()); }
    let (name, url, latest) = (c["name"].as_str().unwrap_or(""), c["url"].as_str().unwrap_or(""), c["latest"].as_str().unwrap_or(""));
    let file = download(url, name)?;

    #[cfg(target_os = "macos")]
    {
        // replace the running ThorBro.app with the one from the disk image, then start it again
        let exe = std::env::current_exe().map_err(|e| e.to_string())?;
        let bundle = exe.ancestors().find(|p| p.extension().map(|e| e == "app").unwrap_or(false)).ok_or("ThorBro.app nicht gefunden")?.to_path_buf();
        let mount = std::env::temp_dir().join("thorbro-update-mount");
        let _ = std::fs::create_dir_all(&mount);
        run("hdiutil", &["attach", "-nobrowse", "-quiet", "-mountpoint", mount.to_str().unwrap(), file.to_str().unwrap()])?;
        let new_app = std::fs::read_dir(&mount).map_err(|e| e.to_string())?.flatten().map(|e| e.path())
            .find(|p| p.extension().map(|e| e == "app").unwrap_or(false));
        let result = (|| -> Result<(), String> {
            let new_app = new_app.ok_or("Keine App im Update gefunden")?;
            let staged = bundle.with_extension("app.new");
            let _ = std::fs::remove_dir_all(&staged);
            run("ditto", &[new_app.to_str().unwrap(), staged.to_str().unwrap()])?;
            let old = bundle.with_extension("app.old");
            let _ = std::fs::remove_dir_all(&old);
            std::fs::rename(&bundle, &old).map_err(|e| format!("Kann die App nicht ersetzen ({e}). Liegt sie in „Programme“?"))?;
            std::fs::rename(&staged, &bundle).map_err(|e| e.to_string())?;
            let _ = std::fs::remove_dir_all(&old);
            Ok(())
        })();
        let _ = run("hdiutil", &["detach", "-quiet", mount.to_str().unwrap()]);
        result?;
        std::process::Command::new("open").args(["-n", bundle.to_str().unwrap()]).spawn().map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "windows")]
    {
        // the installer replaces and restarts ThorBro
        std::process::Command::new(&file).arg("/P").spawn().map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "linux")]
    {
        let target = std::env::var("APPIMAGE").map(PathBuf::from).map_err(|_| "Nur das AppImage aktualisiert sich selbst; das .deb bitte neu installieren".to_string())?;
        std::fs::copy(&file, &target).map_err(|e| e.to_string())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&target, std::fs::Permissions::from_mode(0o755));
        }
        std::process::Command::new(&target).spawn().map_err(|e| e.to_string())?;
    }
    Ok(latest.to_string())
}

#[cfg(test)]
mod tests {
    #[test]
    fn versions() {
        assert!(super::newer("0.3.0", "0.2.9"));
        assert!(!super::newer("0.2.0", "0.2.0"));
        assert!(super::newer("1.0.0", "0.10.4"));
    }
    #[test]
    fn finds_desktop_release() {
        let c = super::check();
        println!("{c}");
        assert!(c["latest"].as_str().is_some());
    }
}
