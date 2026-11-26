# ThorBro – Design-Regeln

Verbindlich für jede neue Seite. Native App (`companion/.../ui/`) und Web-Panel (`assets/web`) müssen dasselbe können.

**Zwei Designs nebeneinander:** Das alte Design (Seiten in `ui/*.kt`, z. B. `HomeScreen`, `GamesScreen`) bleibt
unverändert. Das neue liegt in `ui/next/` (`NewRoot`, `HomeScreenNew`, `GamesScreenNew` …, Paket weiterhin
`wtf.lennard.thorbro.ui`). `UiMode.newDesign` (Pref `ui.newDesign`, Standard an) wählt das Root; Schalter im neuen
Design unter System › Allgemein, im alten unter Einstellungen. Neue Seiten ins neue Design bauen und die Logik der
alten wiederverwenden (`GameDetail`, `GameSaves`, `SendSheet`, `FriendsPanel`, `ControllerPanels` …), nie kopieren
und auseinanderlaufen lassen.

**Immer vom neuesten Stand ausgehen:** Redesigns und Seiten-Umbauten starten vom Zweig, der gerade auf dem Thor läuft
(`GET /api/update` → `current`), nicht von einem alten `main`. Vorher `git diff main...<zweig> --stat` lesen und jede
Oberfläche auflisten, damit kein Feature verloren geht.

Bausteine: `Design.kt` (Bereiche, TopBar, Schalter, Einstellungs-Zeilen, Aktivität), `next/NewGames.kt`
(`ArtTile`, `ArtGrid`), `Console.kt` (Kacheln, Menüs, Buttons), `Common.kt` (Farben, Schrift, Fokus-Look).

## Bereiche & Navigation
Fünf Bereiche (`Area`), umgeschaltet mit **L1/R1** in der `TopBar`:
Start · Spiele (Bibliothek, Store) · Sync · Zusammen · System (Allgemein, Controller, Treiber).
Jede Seite gehört zu genau einem Bereich. Mehrere Seiten → `PageRow` unter der TopBar. Neue Seite = Eintrag in
`Area.pages` + `Screen.short`. Navigation über `Nav.go/area/step`, nie über eigenen Zustand.
Freunde sind kein Bereich und keine Kacheln: nur das Seitenpanel (L2 oder Knopf rechts in der TopBar).

## Tasten
| Taste | Wirkung |
|---|---|
| A | auswählen / starten |
| B | eine Ebene zurück (Ordner → Konsolen → Start; Dialoge schließen sich selbst) |
| X / Y | Details / Nebenaktion des fokussierten Elements |
| L1 / R1 | voriger / nächster Bereich |
| L2 | Freunde |
| Select | Aktivitäten (Drawer, Pille in der TopBar) |

Seiten fangen L1/R1 nur ab, wenn sie sie wirklich brauchen (Controller-Tester, L+R-Reihenfolge).

## Aufbau einer Seite
1. `NewTopBar(area)` – Bereiche, Aktivitäts-Pille, Freunde, Uhr, WLAN, Akku
2. `PageRow` – nur bei mehreren Seiten im Bereich
3. Inhalt mit Startfokus auf der wichtigsten Aktion
4. `ButtonHints` – nur die Tasten, die hier etwas tun

## Spiele, Artwork, Ordner
- Konsolen sind **Ordner**: quadratische Ordner-Kachel (Lasche oben, Cover-Collage gedimmt, Konsolen-Logo, Anzahl).
  A öffnet den Ordner, B schließt ihn.
- Jedes Spiel (Bibliothek, Store, Spielstände) ist ein **quadratisches Artwork** (`ArtTile`, 150 dp, im Raster leicht
  gestreckt). Bild füllt immer das Quadrat (Crop); App-Icons von Ports zentriert (`fit`). Titel **unter** dem Bild,
  eine Zeile, darunter eine kurze Info-Zeile. Kein Text läuft über, kein Text liegt auf dem Bild.
- Store wie ein Laden: Banner oben, eine Reihe pro Konsole, Suche, eine Seite pro Spiel mit Größe, „passt / passt nicht“
  und **einem** Knopf „Herunterladen“ mit Fortschritt direkt darunter.

## Bausteine – wann welcher
- `ArtTile` = ein Spiel / Inhalt mit Bild. `ConsoleTile` = Aktion ohne Bild (nur im alten Design und in Ausnahmen).
- `SettingRow` in `SettingsGroup` = Einstellungen. An/aus = `SettingRow(on = …)` mit echtem Schalter, Wert = `value`.
  Nie „… an“/„… aus“ in einen Titel schreiben.
- `BigButton(primary = true)` = **die eine Hauptaktion** der Ansicht. Höchstens eine.
- `ConsoleMenu` = seltene Aktionen, Bestätigungen, Auswahl aus wenigen Werten.
- Seltenes als eine Reihe kleiner `BigButton`s unten, nicht als Kachelwand.

## Keine Deko, keine Kachelwände
- **Keine dekorativen Status-Chips** („RomM verbunden“, „Server läuft“, „Auto-Sync an“). Zustand nur dort, wo er eine
  Entscheidung beeinflusst, als eine ruhige Textzeile (ein Punkt + Text) – z. B. oben auf Sync.
- **Keine Kachelwände aus technischen Einzel-Einträgen** (eine Kachel pro Emulator, Speicherort, Lobby, Freund).
  Solche Dinge gehören zum Spiel (z. B. Spielstand zurücksetzen → Spiel → Spielstände) oder in eine Liste.
- Kein Zwei-Bildschirm-/Cockpit-Modus. Der untere Bildschirm gehört dem Launcher.

## Fortschritt
Fortschritt gehört **an die Aktion** (Balken unter dem Knopf, `progress` am Artwork). Zusätzlich läuft jede
Hintergrundaufgabe in der Aktivitäts-Pille (TopBar) und im Drawer (Select). Keine schwebenden Fortschrittskarten.

## Texte
Deutsch, kurz, Wörter des Spielers („Spielstand“, „Auslagern“), keine Technik-Begriffe (kein „Token“, „Sync-Job“).
Eine Zeile Titel, höchstens eine Zeile Info. Keine Textwände – Details in ein `ConsoleMenu`.

## Fokus
- **Ein** Fokusstopp pro Element: `Modifier.fx(...)` (enthält `clickable`), nie zusätzlich `.focusable()`.
- Reine Anzeige (Panels, TopBar) ist nicht fokussierbar; `Modifier.tap` für Touch ohne Fokus.
- Beim Öffnen einer Seite bekommt die wichtigste Aktion den Fokus; aus einem Ordner zurück der Ordner von vorher.
- Platz für die Fokus-Anhebung lassen (`contentPadding`).

## Farben & Schrift
Nur Tokens aus `Common.kt`: `Accent`, `OnAccent`, `Panel`, `GlassFill`, `GlassLine`, `Muted`, `Ok`, `Bad`, `Bg`;
Schrift `Nunito`. Hintergrund `glassBackground()`; Hero-Seiten legen Artwork darunter mit dunklem Verlauf.

## Prüfen
Auf dem Thor ansehen: `scripts/push-update.sh`, dann `GET /api/debug/screencap` (listet die Displays, braucht Shizuku)
und `GET /api/debug/screencap?display=<id>` (PNG). Ohne Shizuku: Bildschirm-Spiegel (`/api/screen/start`, am Thor
„Jetzt starten“, dann `/api/screen/frame.jpg`). Jede Seite, auch geöffnete Ordner und Detailseiten.

## Checkliste für neue Seiten
- [ ] Gehört zu genau einem Bereich; Eintrag in `Area`/`Screen.short`
- [ ] `NewTopBar` + (`PageRow`) + `ButtonHints`
- [ ] Genau eine primäre Aktion, keine Deko-Chips, keine Kachelwand
- [ ] Spiele als quadratische `ArtTile`, Titel darunter
- [ ] An/aus = `SettingRow(on = …)`
- [ ] Fortschritt an der Aktion und in der Pille
- [ ] Fokus: Startfokus, ein Stopp pro Element, B/L1/R1 verhalten sich
- [ ] Texte deutsch und kurz, nur Tokens aus `Common.kt`
- [ ] Web-Panel hat dasselbe
- [ ] Auf dem Thor geprüft (Screenshot)
