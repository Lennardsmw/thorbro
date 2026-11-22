#!/usr/bin/env python3
"""
ThorBro save sync for macOS / Linux: keeps save folders of native ports (e.g. Ocarina of Time via
nxnative) in sync with RomM, the same way ThorBro does on the handheld. Newest save wins; the
older state is kept as a backup.

  thorbro-sync.py login https://games.example.org   sign in (approve the shown code in RomM)
  thorbro-sync.py sync                               sync once (upload or download)
  thorbro-sync.py install                            run automatically (macOS LaunchAgent)

Config: ~/.config/thorbro/sync.json (created by `login`, chmod 600).
"""
import hashlib, io, json, os, subprocess, sys, time, urllib.parse, urllib.request, uuid, zipfile
from datetime import datetime, timezone

CONF = os.path.expanduser("~/.config/thorbro/sync.json")
BACKUPS = os.path.expanduser("~/Library/Application Support/ThorBro/save-backups")
DEFAULT_ITEMS = [{
    "name": "Ocarina of Time (Banana Native)",
    "dir": "~/Library/Application Support/nxnative/0400486024DEC000/save",
    "rom_file": "Ocarina of Time (Banana Native).port",
    "platform": "ports",
    "process": "Ocarina of Time",   # don't write while the game runs
}]


def load():
    try:
        with open(CONF) as f:
            return json.load(f)
    except FileNotFoundError:
        return {"items": DEFAULT_ITEMS}


def save(c):
    os.makedirs(os.path.dirname(CONF), exist_ok=True)
    with open(CONF, "w") as f:
        json.dump(c, f, indent=2)
    os.chmod(CONF, 0o600)


def api(c, method, path, body=None, raw=None, headers=None, auth=True):
    h = {"User-Agent": "ThorBro-Sync", "Accept": "application/json"}
    if auth and c.get("token"):
        h["Authorization"] = "Bearer " + c["token"]
    data = None
    if body is not None:
        data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    if raw is not None:
        data = raw
    h.update(headers or {})
    req = urllib.request.Request(c["url"].rstrip("/") + path, data=data, headers=h, method=method)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def login(url):
    c = load()
    c["url"] = url.rstrip("/")
    c.setdefault("device", str(uuid.uuid4()))
    init = json.loads(api(c, "POST", "/api/auth/device/init", {
        "client_device_identifier": c["device"], "name": "ThorBro Sync (" + os.uname().nodename + ")",
        "client": "ThorBro", "platform": sys.platform, "requested_scopes": ["me.read", "platforms.read", "roms.read", "assets.read", "assets.write"],
    }, auth=False))
    print("In RomM bestätigen:", c["url"] + init["verification_path_complete"])
    print("Code:", init["user_code"])
    until = time.time() + init["expires_in"]
    while time.time() < until:
        time.sleep(max(2, init.get("interval", 5)))
        try:
            tok = json.loads(api(c, "POST", "/api/auth/device/token", {"device_code": init["device_code"]}, auth=False))
        except urllib.error.HTTPError:
            continue
        if tok.get("access_token"):
            c["token"] = tok["access_token"]; c["device_id"] = tok.get("device_id")
            save(c); print("Angemeldet."); return
    sys.exit("Zeit abgelaufen, bitte erneut versuchen.")


def folder_hash(d):
    """same hash as ThorBro on the device: relative path + content of every file, sorted by path"""
    md = hashlib.md5()
    files = sorted(os.path.join(r, f) for r, _, fs in os.walk(d) for f in fs)
    for p in files:
        md.update(os.path.relpath(p, d).replace(os.sep, "/").encode())
        with open(p, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                md.update(chunk)
    return md.hexdigest()


def newest_mtime(d):
    return max((os.path.getmtime(os.path.join(r, f)) for r, _, fs in os.walk(d) for f in fs), default=0)


def zip_folder(d):
    buf = io.BytesIO()
    top = os.path.basename(d.rstrip("/"))
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for r, _, fs in os.walk(d):
            for f in fs:
                p = os.path.join(r, f)
                z.write(p, top + "/" + os.path.relpath(p, d).replace(os.sep, "/"))
    return buf.getvalue()


def unzip_stripped(data, d):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for e in z.infolist():
            rel = e.filename.split("/", 1)[1] if "/" in e.filename else e.filename
            if not rel or e.is_dir():
                continue
            t = os.path.realpath(os.path.join(d, rel))
            if not t.startswith(os.path.realpath(d) + os.sep):
                continue
            os.makedirs(os.path.dirname(t), exist_ok=True)
            with open(t, "wb") as f:
                f.write(z.read(e))


def running(name):
    return subprocess.run(["pgrep", "-f", name], capture_output=True).returncode == 0 if name else False


def find_rom(c, platform, rom_file):
    for p in json.loads(api(c, "GET", "/api/platforms")):
        if p.get("fs_slug") == platform:
            page = json.loads(api(c, "GET", f"/api/roms?platform_ids={p['id']}&limit=500"))
            for r in page.get("items", []):
                if r.get("fs_name") == rom_file:
                    return r["id"]
    return None


def sync():
    c = load()
    if not c.get("token"):
        sys.exit("Erst anmelden: thorbro-sync.py login <RomM-Adresse>")
    for it in c["items"]:
        d = os.path.expanduser(it["dir"])
        if not os.path.isdir(d):
            print(it["name"] + ": Ordner fehlt, übersprungen"); continue
        rom = find_rom(c, it["platform"], it["rom_file"])
        if rom is None:
            print(it["name"] + ": noch nicht in RomM (ThorBro legt den Eintrag beim ersten Abgleich an)"); continue
        saves = json.loads(api(c, "GET", f"/api/saves?rom_id={rom}"))
        # only the default slot: other slots belong to ThorBro profiles on a Thor
        newest = max((x for x in saves if not x.get("slot")), key=lambda s: s.get("updated_at") or "", default=None)
        local_hash = folder_hash(d)
        state = c.setdefault("state", {}).setdefault(it["name"], {})
        remote_key = f"{newest['id']}@{newest['updated_at']}" if newest else None
        local_changed = local_hash != state.get("local")
        remote_changed = remote_key is not None and remote_key != state.get("remote")
        if not local_changed and not remote_changed:
            print(it["name"] + ": aktuell"); continue
        download = remote_changed and not local_changed
        if remote_changed and local_changed:  # both changed (or first run): the newer one wins
            dt = datetime.fromisoformat(newest["updated_at"].replace("Z", "+00:00"))
            remote_t = (dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)).timestamp()
            download = remote_t > newest_mtime(d) + 30
        if download:
            if running(it.get("process")):
                print(it["name"] + ": neuerer Stand in RomM, aber das Spiel läuft – später"); continue
            os.makedirs(BACKUPS, exist_ok=True)
            with open(os.path.join(BACKUPS, time.strftime("%Y%m%d-%H%M%S") + "-" + it["name"] + ".zip"), "wb") as f:
                f.write(zip_folder(d))
            unzip_stripped(api(c, "GET", f"/api/saves/{newest['id']}/content"), d)
            state["local"] = folder_hash(d); state["remote"] = remote_key
            print(it["name"] + ": neuerer Stand aus RomM geholt (alter Stand gesichert)")
        else:
            boundary = "thorbro%d" % time.time()
            # the device goes into the name, so RomM and ThorBro show where a save comes from
            host = "".join(" " if ch in '\\/:*?"<>|()[]' else ch for ch in os.uname().nodename.removesuffix(".local")).strip() or "Computer"
            name = os.path.basename(d.rstrip("/")) + " (" + " ".join(host.split())[:40] + ").zip"
            body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"saveFile\"; filename=\"{name}\"\r\n"
                    "Content-Type: application/octet-stream\r\n\r\n").encode() + zip_folder(d) + f"\r\n--{boundary}--\r\n".encode()
            # no device_id: RomM would then also require the devices.write scope
            q = urllib.parse.urlencode({"rom_id": rom, "emulator": it["platform"], "overwrite": "true"})
            res = json.loads(api(c, "POST", "/api/saves?" + q, raw=body, headers={"Content-Type": "multipart/form-data; boundary=" + boundary}))
            state["local"] = local_hash; state["remote"] = f"{res['id']}@{res['updated_at']}"
            print(it["name"] + ": hochgeladen")
        save(c)


def install():
    c = load()
    me = os.path.realpath(__file__)
    label = "wtf.lennard.thorbro-sync"
    watch = "".join(f"<string>{os.path.expanduser(i['dir'])}</string>" for i in c["items"])
    plist = f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>{label}</string>
  <key>ProgramArguments</key><array><string>/usr/bin/python3</string><string>{me}</string><string>sync</string></array>
  <key>WatchPaths</key><array>{watch}</array>
  <key>StartInterval</key><integer>300</integer>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>{os.path.expanduser('~/Library/Logs/thorbro-sync.log')}</string>
  <key>StandardErrorPath</key><string>{os.path.expanduser('~/Library/Logs/thorbro-sync.log')}</string>
</dict></plist>"""
    path = os.path.expanduser(f"~/Library/LaunchAgents/{label}.plist")
    with open(path, "w") as f:
        f.write(plist)
    subprocess.run(["launchctl", "unload", path], capture_output=True)
    subprocess.run(["launchctl", "load", path], check=True)
    print("Läuft jetzt automatisch (bei jeder Änderung und alle 5 Minuten). Log: ~/Library/Logs/thorbro-sync.log")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "sync"
    if cmd == "login" and len(sys.argv) > 2:
        login(sys.argv[2])
    elif cmd == "sync":
        sync()
    elif cmd == "install":
        install()
    else:
        print(__doc__)
