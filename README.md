# ThorBro

Steuert den AYN Thor (oder einen anderen Android-Handheld) vom Mac, PC oder Browser aus:
Spiele und ROMs hochladen, APKs installieren, Apps starten und verwalten, Dateien durchsuchen.
Idee nach [PS5Upload](https://github.com/phantomptr/ps5upload); eigener Code, nichts übernommen.

## Aufbau

```
Browser / später Desktop-App (Tauri)
        │  HTTP, Kopplungscode als Bearer-Token
        ▼
companion/  Android-App auf dem Thor (Kotlin + Ktor CIO, Port 7330)
            ├─ Vordergrund-Dienst, im LAN per mDNS als _thorbro._tcp angekündigt
            ├─ /api/...      Gerät, Apps, Dateien, Upload, Installation
            └─ /             Web-Oberfläche (assets/web), direkt vom Thor ausgeliefert
```

## Desktop-App (Mac, Windows, Linux)

`desktop/app` (Tauri): findet ThorBro im Netzwerk, koppelt per Code, öffnet die Oberfläche des Thor
in einem Fenster und gleicht Spielstände (z. B. Ocarina of Time über nxnative) im Hintergrund mit
RomM ab – beim Speichern und alle 5 Minuten. Läuft in der Menüleiste und startet mit dem Computer.
Downloads: Releases `desktop-v*`. Selbst bauen: `cd desktop/app && npm install && npx tauri build`.

## Bauen und installieren

```bash
cd companion
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Danach aktualisiert sich ThorBro selbst: `scripts/release.sh` veröffentlicht ein GitHub-Release,
der Thor installiert es beim nächsten Check (alle 6 h) oder über „Nach Updates suchen“.
Ohne Release geht es auch direkt über WLAN: `scripts/push-update.sh`.

Auf dem Thor ThorBro öffnen und die drei Berechtigungen erteilen. Dann im Browser
`http://<thor-ip>:7330` öffnen und den angezeigten Code eingeben.

## API (Kurzfassung)

Alles unter `/api` außer `/api/ping` braucht `Authorization: Bearer <code>` (oder `?token=`).

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/info` | Gerät, Akku, Speicher, Berechtigungen |
| GET | `/api/apps` · `/api/apps/{pkg}/icon` | Apps mit Launcher-Eintrag |
| POST | `/api/apps/{pkg}/launch` · `/uninstall` | Starten / Deinstallations-Dialog |
| GET/DELETE | `/api/files?path=` | Ordner auflisten / löschen |
| GET | `/api/files/raw?path=` | Datei herunterladen |
| POST | `/api/files/mkdir` · `/api/files/move` | Ordner anlegen / verschieben |
| GET | `/api/upload?path=&size=` | Stand eines Uploads: `{offset, exists, free}` |
| PUT | `/api/upload?path=&offset=` | Rohdaten anhängen; 409 + `{offset}` bei falschem Offset |
| POST | `/api/upload/finish?path=&size=&overwrite=` | `.thorbro-part` umbenennen |
| POST | `/api/install?path=` · GET `/api/install/{id}` | APK installieren (Bestätigung auf dem Thor) |

Pfade sind auf den internen Speicher und SD-Karten beschränkt.

## Funktionen

- Upload-Warteschlange mit Fortsetzen, ROMs landen im passenden Konsolen-Ordner, APKs werden installiert
- ROMs-Tab: alle ROMs nach Konsole, mit Covern, Infos und Konsolen-Icons
  (zuerst aus Cocoon, sonst aus libretro-thumbnails und libretro-database)
- Apps starten und deinstallieren, Dateibrowser
- Selbst-Update aus GitHub-Releases, optional automatisch

Schrift in der App: [Nunito](https://github.com/googlefonts/nunito) (SIL Open Font License 1.1).

## Lizenz

[MIT](LICENSE). Beiträge sind willkommen, siehe [CONTRIBUTING.md](CONTRIBUTING.md).
