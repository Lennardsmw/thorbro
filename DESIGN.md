# ThorBro – Design-Regeln

Verbindlich für jede neue Seite. Native App (`companion/.../ui/`) und Web-Panel (`assets/web`) müssen dasselbe können. Bausteine: `Design.kt` (Bereiche, TopBar, Chips, Schalter, Aktivität, Cockpit), `Console.kt` (Kacheln, Menüs, Buttons), `Common.kt` (Farben, Schrift, Fokus-Look).

## Bereiche & Navigation
Fünf Bereiche (`Area`), umgeschaltet mit **L1/R1** in der `TopBar`:
Start · Spiele (Bibliothek, Store) · Sync · Zusammen (Multiplayer, Streaming) · System (Allgemein, Controller, Treiber).
Jede Seite gehört zu genau einem Bereich. Mehrere Seiten → `PageRow` (Chips unter der TopBar). Neue Seite = Eintrag in `Area.pages` + `Screen.short`. Navigation läuft über `Nav.go/area/step`, nie über eigenen Zustand.

## Tasten
| Taste | Wirkung |
|---|---|
| A | auswählen / starten |
| B | zurück (Start ist die Wurzel; Dialoge schließen sich selbst) |
| X / Y | Details / Nebenaktion des fokussierten Elements |
| L1 / R1 | voriger / nächster Bereich |
| L2 | Freunde |
| Select | Aktivitäten (Drawer, Pille in der TopBar) |
| Start | Menü des Elements, falls vorhanden |

Seiten fangen L1/R1 nur ab, wenn sie sie wirklich brauchen (Controller-Tester).

## Aufbau einer Seite
1. `TopBar(area)` – Bereiche, Aktivitäts-Pille, Uhr, WLAN, Akku
2. `PageRow` – nur bei mehreren Seiten im Bereich
3. Inhalt – `TileGrid`, `Shelves` oder eigenes Layout, mit Fokus-Start (`FocusOnStart` / `firstFocus`)
4. `ButtonHints` – nur die Tasten, die hier etwas tun

## Bausteine – wann welcher
- `ConsoleTile` = **Aktion** (öffnet, startet, wählt).
- `ToggleTile` = **an/aus**. Nie ein `ConsoleTile` mit „… an/aus“ im Titel.
- `StatusChip` (+ `Tone`) = **Zustand** (verbunden, läuft, passt nicht).
- `BigButton(primary = true)` = **die eine Hauptaktion** der Seite oder des Panels. Höchstens eine pro Ansicht.
- `ConsoleMenu` = **seltene Aktionen** („⋯ Mehr“), Bestätigungen, Details.
- `FocusCard` = Karte mit Fokusrahmen für Listen/Zeilen.
- Seltenes nie als dauerhafte Kachel neben der Hauptaktion.

## Status zuerst
Der Zustand steht oben und sichtbar als Chips/Panel, nicht in Untertiteln. Danach die Aktion. „Verbunden · Auto-Sync an · letzter Sync vor 5 min“ vor „Jetzt synchronisieren“.

## Fortschritt
Fortschritt gehört **an die Aktion** (Balken unter dem Button, `progress` an der Kachel). Zusätzlich läuft jede Hintergrundaufgabe in der Aktivitäts-Pille (TopBar) und im Drawer (Select). Keine schwebenden Fortschrittskarten.

## Zweiter Bildschirm (Modus B)
Jede Seite registriert `CockpitActions(titel) { listOf(CockpitAction(...)) }` mit ihren wichtigsten Aktionen (erste = `primary`). Dialoge registrieren eigene; der zuletzt registrierte gewinnt. Ohne Registrierung zeigt der zweite Bildschirm nur die Bereiche.

## Texte
Deutsch, kurz, Wörter des Spielers („Spielstand“, „Auslagern“), keine Technik-Begriffe (kein „Token“, „Sync-Job“). Kachel: Titel ≤ 2 Zeilen, höchstens eine Statuszeile. Keine Textwände – Details in ein `ConsoleMenu`.

## Fokus
- **Ein** Fokusstopp pro Element: `Modifier.fx(...)` (enthält `clickable`), nie zusätzlich `.focusable()`.
- Reine Anzeige (Panels, Chips, TopBar) ist nicht fokussierbar; `Modifier.tap` für Touch ohne Fokus.
- Beim Öffnen einer Seite bekommt die wichtigste Aktion den Fokus.
- Größen ohne Rand-Abschnitt: Platz für Fokus-Anhebung (`contentPadding`).

## Farben & Schrift
Nur Tokens aus `Common.kt`: `Accent`, `OnAccent`, `Panel`, `GlassFill`, `GlassLine`, `Muted`, `Ok`, `Bad`, `Bg`; Schrift `Nunito`. Töne über `Tone`. Hintergrund `glassBackground()`; Hero-Seiten legen Artwork darunter mit dunklem Verlauf. Keine eigenen Hex-Farben in Seiten.

## Web-Panel
Jede Funktion gehört in **beide** Oberflächen (Regel 6 in `CLAUDE.md`). Gleiche Bereiche, gleiche Namen, gleiche Texte; Status oben, eine Hauptaktion.

## Checkliste für neue Seiten
- [ ] Gehört zu genau einem Bereich; Eintrag in `Area`/`Screen.short`
- [ ] `TopBar` + (`PageRow`) + `ButtonHints`
- [ ] Status sichtbar als Chips/Panel, genau eine primäre Aktion
- [ ] An/aus = `ToggleTile`, Zustand = `StatusChip`
- [ ] Fortschritt an der Aktion und in der Pille
- [ ] `CockpitActions` registriert
- [ ] Fokus: Startfokus, ein Stopp pro Element, B/L1/R1 verhalten sich
- [ ] Texte deutsch und kurz
- [ ] Nur Tokens aus `Common.kt`
- [ ] Web-Panel hat dasselbe
- [ ] Auf dem Thor geprüft (Screenshot)
