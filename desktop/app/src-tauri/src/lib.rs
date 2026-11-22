mod config;
mod leds;
mod sync;
mod update;

use serde_json::{json, Value};
use std::sync::Mutex;
use std::time::Duration;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

static LAST: Mutex<Option<(u64, Vec<String>)>> = Mutex::new(None);
static PENDING_LOGIN: Mutex<Option<Value>> = Mutex::new(None);
static UPDATE: Mutex<Option<Value>> = Mutex::new(None);
static UPDATE_ITEM: std::sync::OnceLock<MenuItem<tauri::Wry>> = std::sync::OnceLock::new();

fn check_updates() -> Value {
    let u = update::check();
    if let Some(item) = UPDATE_ITEM.get() {
        let text = if u["available"].as_bool().unwrap_or(false) { format!("Update {} installieren", u["latest"].as_str().unwrap_or("")) } else { "Nach Updates suchen".into() };
        let _ = item.set_text(text);
    }
    *UPDATE.lock().unwrap() = Some(u.clone());
    u
}

fn install_and_restart(app: &AppHandle) -> Result<String, String> {
    let v = update::install()?;
    app.exit(0);
    Ok(v)
}

#[tauri::command]
async fn check_update() -> Value { tauri::async_runtime::spawn_blocking(check_updates).await.unwrap_or_default() }

#[tauri::command]
async fn install_update(app: AppHandle) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || install_and_restart(&app)).await.map_err(|e| e.to_string())?
}

fn do_sync() -> Vec<String> {
    let lines = sync::run();
    *LAST.lock().unwrap() = Some((sync::now_secs(), lines.clone()));
    lines
}

#[tauri::command]
fn status() -> Value {
    let c = config::load();
    let last = LAST.lock().unwrap().clone();
    json!({
        "thors": c.thors.iter().map(|t| json!({"name": t.name, "address": t.address})).collect::<Vec<_>>(),
        "romm": { "url": c.romm_url, "connected": !c.romm_token.is_empty(), "pending": PENDING_LOGIN.lock().unwrap().clone() },
        "items": c.items.iter().map(|i| json!({"name": i.name, "dir": i.dir, "exists": std::path::Path::new(&i.dir).is_dir()})).collect::<Vec<_>>(),
        "auto": c.auto_sync,
        "last": last.map(|(t, l)| json!({"at": t, "lines": l})),
        "version": update::CURRENT,
        "update": UPDATE.lock().unwrap().clone(),
    })
}

/// looks for ThorBro on the local network (it announces itself as _thorbro._tcp)
#[tauri::command]
async fn discover() -> Vec<Value> {
    tauri::async_runtime::spawn_blocking(discover_blocking).await.unwrap_or_default()
}

fn discover_mdns() -> Vec<Value> {
    {
        let mut out: Vec<Value> = vec![];
        let Ok(d) = mdns_sd::ServiceDaemon::new() else { return out };
        let Ok(rx) = d.browse("_thorbro._tcp.local.") else { return out };
        let until = std::time::Instant::now() + Duration::from_secs(2);
        while let Some(left) = until.checked_duration_since(std::time::Instant::now()) {
            match rx.recv_timeout(left) {
                Ok(mdns_sd::ServiceEvent::ServiceResolved(info)) => {
                    let name = info.get_fullname().split("._thorbro").next().unwrap_or("ThorBro").to_string();
                    for ip in info.get_addresses_v4() {
                        let address = format!("{}:{}", ip, info.get_port());
                        if !out.iter().any(|o| o["address"] == address) { out.push(json!({"name": name, "address": address})); }
                    }
                }
                Ok(_) => {}
                Err(_) => break,
            }
        }
        let _ = d.shutdown();
        out
    }
}

/// mDNS first; if that finds nothing (blocked multicast, other network setups) ask every address
/// of the local /24 networks for ThorBro on port 7330
fn discover_blocking() -> Vec<Value> {
    let found = discover_mdns();
    if !found.is_empty() { return found; }
    scan_subnets()
}

fn scan_subnets() -> Vec<Value> {
    use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
    let mut bases: Vec<[u8; 3]> = vec![];
    if let Ok(ifs) = local_ip_address::list_afinet_netifas() {
        for (_, ip) in ifs {
            if let IpAddr::V4(v4) = ip {
                let o = v4.octets();
                let private = o[0] == 10 || (o[0] == 172 && (16..32).contains(&o[1])) || (o[0] == 192 && o[1] == 168);
                if private && !bases.contains(&[o[0], o[1], o[2]]) { bases.push([o[0], o[1], o[2]]); }
            }
        }
    }
    let hits = std::sync::Arc::new(Mutex::new(vec![]));
    let mut threads = vec![];
    for b in bases {
        for chunk in (1u8..=254).collect::<Vec<_>>().chunks(4) {
            let chunk = chunk.to_vec(); let hits = hits.clone();
            threads.push(std::thread::spawn(move || {
                for h in chunk {
                    let ip = Ipv4Addr::new(b[0], b[1], b[2], h);
                    let addr = SocketAddr::new(IpAddr::V4(ip), 7330);
                    if TcpStream::connect_timeout(&addr, Duration::from_millis(300)).is_err() { continue; }
                    let ping = sync::client().get(format!("http://{ip}:7330/api/ping")).timeout(Duration::from_secs(2)).send()
                        .and_then(|r| r.json::<Value>());
                    if let Ok(p) = ping {
                        if p["name"] == "ThorBro" {
                            hits.lock().unwrap().push(json!({"name": format!("ThorBro {}", p["model"].as_str().unwrap_or("")), "address": format!("{ip}:7330")}));
                        }
                    }
                }
            }));
        }
    }
    for t in threads { let _ = t.join(); }
    let v = hits.lock().unwrap().clone(); v
}

#[cfg(test)]
mod tests {
    #[test]
    fn discovery() {
        println!("mdns: {:?}", super::discover_mdns());
        println!("scan: {:?}", super::scan_subnets());
    }
}

/// checks the pairing code shown on the handheld and remembers this Thor
#[tauri::command]
async fn pair(address: String, code: String) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let address = address.trim().trim_start_matches("http://").trim_end_matches('/').to_string();
        let address = if address.contains(':') { address } else { format!("{address}:7330") };
        let code = code.trim().to_uppercase();
        let r = sync::client().get(format!("http://{address}/api/info")).bearer_auth(&code).send().map_err(|e| format!("Thor nicht erreichbar: {e}"))?;
        if r.status().as_u16() == 401 { return Err("Kopplungscode stimmt nicht".into()); }
        let info: Value = r.json().map_err(|e| e.to_string())?;
        let mut c = config::load();
        let name = format!("{} {}", info["manufacturer"].as_str().unwrap_or(""), info["model"].as_str().unwrap_or("Thor")).trim().to_string();
        c.thors.retain(|t| t.address != address);
        c.thors.push(config::Thor { name: name.clone(), address: address.clone(), token: code });
        config::save(&c);
        Ok(json!({"name": name, "address": address}))
    }).await.map_err(|e| e.to_string())?
}

#[tauri::command]
fn forget_thor(address: String) {
    let mut c = config::load();
    c.thors.retain(|t| t.address != address);
    config::save(&c);
}

fn thor_answers(address: &str, token: &str) -> bool {
    sync::client().get(format!("http://{address}/api/info")).bearer_auth(token).timeout(Duration::from_secs(3)).send()
        .map(|r| r.status().is_success()).unwrap_or(false)
}

/// the saved address if the Thor answers there; otherwise looks for it again (its IP may have changed)
/// and remembers the new address. Without this an unreachable Thor opened as a blank white window.
fn reachable_address(address: &str) -> Result<String, String> {
    let c = config::load();
    let t = c.thors.iter().find(|t| t.address == address).ok_or("unbekannter Thor")?.clone();
    if thor_answers(&t.address, &t.token) { return Ok(t.address); }
    for found in discover_blocking() {
        let Some(a) = found["address"].as_str() else { continue };
        if a != t.address && thor_answers(a, &t.token) {
            let mut c = config::load();
            c.thors.retain(|x| x.address != a);
            for x in c.thors.iter_mut().filter(|x| x.address == t.address) { x.address = a.to_string(); }
            config::save(&c);
            return Ok(a.to_string());
        }
    }
    Err(format!("{} ist unter {} nicht erreichbar. Ist der Thor an, ThorBro dort geöffnet und im selben WLAN (oder mit NetBird verbunden)?", t.name, t.address))
}

/// opens ThorBro's own interface from the handheld in a window
#[tauri::command]
async fn open_thor(app: AppHandle, address: String) -> Result<String, String> {
    let address = tauri::async_runtime::spawn_blocking(move || reachable_address(&address)).await.map_err(|e| e.to_string())??;
    let c = config::load();
    let t = c.thors.iter().find(|t| t.address == address).ok_or("unbekannter Thor")?;
    let url = format!("http://{}/?token={}", t.address, t.token);
    let label = format!("thor-{}", address.replace(['.', ':'], "-"));
    if let Some(w) = app.get_webview_window(&label) { let _ = w.show(); let _ = w.set_focus(); return Ok(address); }
    WebviewWindowBuilder::new(&app, label, WebviewUrl::External(url.parse().map_err(|e| format!("{e}"))?))
        .title(format!("ThorBro – {}", t.name)).inner_size(1180.0, 800.0).build().map_err(|e| e.to_string())?;
    Ok(address)
}

/// RomM device sign-in: returns the code to approve in RomM, then waits for it in the background
#[tauri::command]
async fn romm_login(url: String) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let mut c = config::load();
        c.romm_url = url.trim().trim_end_matches('/').to_string();
        config::save(&c);
        let init: Value = sync::client().post(format!("{}/api/auth/device/init", c.romm_url))
            .json(&json!({
                "client_device_identifier": c.device, "name": format!("ThorBro Desktop ({})", hostname()),
                "client": "ThorBro", "platform": std::env::consts::OS,
                "requested_scopes": ["me.read", "platforms.read", "roms.read", "assets.read", "assets.write"],
            })).send().map_err(|e| format!("RomM nicht erreichbar: {e}"))?.json().map_err(|e| e.to_string())?;
        let pending = json!({"code": init["user_code"], "verify": format!("{}{}", c.romm_url, init["verification_path_complete"].as_str().unwrap_or(""))});
        *PENDING_LOGIN.lock().unwrap() = Some(pending.clone());
        let device_code = init["device_code"].as_str().unwrap_or("").to_string();
        let interval = init["interval"].as_u64().unwrap_or(5).max(2);
        let expires = init["expires_in"].as_u64().unwrap_or(600);
        let base = c.romm_url.clone();
        std::thread::spawn(move || {
            let until = sync::now_secs() + expires;
            while sync::now_secs() < until {
                std::thread::sleep(Duration::from_secs(interval));
                let r = sync::client().post(format!("{base}/api/auth/device/token")).json(&json!({"device_code": device_code})).send();
                if let Ok(r) = r {
                    if let Ok(v) = r.json::<Value>() {
                        if let Some(tok) = v["access_token"].as_str() {
                            let mut c = config::load();
                            c.romm_token = tok.to_string();
                            config::save(&c);
                            break;
                        }
                    }
                }
            }
            *PENDING_LOGIN.lock().unwrap() = None;
        });
        Ok(pending)
    }).await.map_err(|e| e.to_string())?
}

#[tauri::command]
async fn sync_now() -> Vec<String> {
    tauri::async_runtime::spawn_blocking(do_sync).await.unwrap_or_default()
}

#[tauri::command]
fn pads() -> Vec<Value> { leds::list() }

#[tauri::command]
fn pad_color(path: String, rgb: u32) -> Result<(), String> { leds::set(&path, rgb) }

/// the Thor's own stick LEDs, through ThorBro on the handheld (needs Shizuku there)
#[tauri::command]
async fn thor_leds(address: String, on: Option<bool>, rgb: Option<u32>) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let c = config::load();
        let t = c.thors.iter().find(|t| t.address == address).ok_or("unbekannter Thor")?;
        let mut url = format!("http://{}/api/leds/thor?", t.address);
        if let Some(o) = on { url += &format!("on={}&", if o { 1 } else { 0 }); }
        if let Some(c) = rgb { url += &format!("color={c:06x}"); }
        let r = sync::client().post(url).bearer_auth(&t.token).send().map_err(|e| format!("Thor nicht erreichbar: {e}"))?;
        r.json::<Value>().map_err(|e| e.to_string())
    }).await.map_err(|e| e.to_string())?
}

#[tauri::command]
fn set_auto(on: bool) {
    let mut c = config::load();
    c.auto_sync = on;
    config::save(&c);
}

pub(crate) fn hostname() -> String { sysinfo::System::host_name().unwrap_or_else(|_| "Computer".into()) }

/// syncs every 5 minutes and shortly after a game wrote its save folder
fn background() {
    use notify::{RecursiveMode, Watcher};
    std::thread::spawn(|| {
        let (tx, rx) = std::sync::mpsc::channel();
        let mut watcher = notify::recommended_watcher(move |_res: notify::Result<notify::Event>| { let _ = tx.send(()); }).ok();
        let mut watched: Vec<String> = vec![];
        let mut last_run = 0u64;
        loop {
            let c = config::load();
            if let Some(w) = watcher.as_mut() {
                for it in &c.items {
                    if !watched.contains(&it.dir) && std::path::Path::new(&it.dir).is_dir() {
                        if w.watch(std::path::Path::new(&it.dir), RecursiveMode::Recursive).is_ok() { watched.push(it.dir.clone()); }
                    }
                }
            }
            // a change: wait until the game finished writing (no events for 30 s)
            let changed = rx.recv_timeout(Duration::from_secs(60)).is_ok();
            if changed { while rx.recv_timeout(Duration::from_secs(30)).is_ok() {} }
            let due = sync::now_secs() - last_run >= 300;
            if c.auto_sync && !c.romm_token.is_empty() && (changed || due) {
                do_sync();
                last_run = sync::now_secs();
            }
        }
    });
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .invoke_handler(tauri::generate_handler![status, discover, pair, forget_thor, open_thor, romm_login, sync_now, set_auto, pads, pad_color, thor_leds, check_update, install_update])
        .setup(|app| {
            // start with the computer, once; the user can turn it off in the system settings
            let al = app.autolaunch();
            if !al.is_enabled().unwrap_or(true) { let _ = al.enable(); }
            if std::env::args().any(|a| a == "--hidden") {
                if let Some(w) = app.get_webview_window("main") { let _ = w.hide(); }
            }
            let open = MenuItem::with_id(app, "open", "ThorBro öffnen", true, None::<&str>)?;
            let sync_item = MenuItem::with_id(app, "sync", "Spielstände jetzt synchronisieren", true, None::<&str>)?;
            let upd = MenuItem::with_id(app, "update", "Nach Updates suchen", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Beenden", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &sync_item, &upd, &quit])?;
            let _ = UPDATE_ITEM.set(upd);
            TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("ThorBro")
                .menu(&menu)
                .on_menu_event(|app, e| match e.id.as_ref() {
                    "open" => { if let Some(w) = app.get_webview_window("main") { let _ = w.show(); let _ = w.set_focus(); } }
                    "sync" => { std::thread::spawn(do_sync); }
                    "update" => {
                        let app = app.clone();
                        std::thread::spawn(move || {
                            let available = UPDATE.lock().unwrap().as_ref().and_then(|u| u["available"].as_bool()).unwrap_or(false);
                            if available { let _ = install_and_restart(&app); } else { check_updates(); }
                        });
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .build(app)?;
            background();
            // look for a new version now and every 6 hours
            std::thread::spawn(|| loop { check_updates(); std::thread::sleep(Duration::from_secs(6 * 3600)); });
            Ok(())
        })
        .on_window_event(|w, e| {
            // closing the main window keeps ThorBro running in the menu bar / tray
            if let tauri::WindowEvent::CloseRequested { api, .. } = e {
                if w.label() == "main" { api.prevent_close(); let _ = w.hide(); }
            }
        })
        .run(tauri::generate_context!())
        .expect("ThorBro konnte nicht starten");
}
