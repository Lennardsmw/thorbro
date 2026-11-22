# Mitmachen bei ThorBro

Beiträge sind willkommen – Fehlerberichte, Ideen und Pull Requests.

## Lizenz der Beiträge

ThorBro steht unter der [MIT-Lizenz](LICENSE). Mit einem Pull Request erklärst du, dass du den Code
selbst geschrieben hast (oder ihn unter einer MIT-kompatiblen Lizenz weitergeben darfst) und dass er
unter der MIT-Lizenz Teil von ThorBro wird. Fremden Code bitte nur mit kompatibler Lizenz übernehmen
und die Quelle im Commit nennen.

## Ablauf

1. Issue aufmachen oder ein bestehendes kommentieren, bevor du Größeres baust.
2. Fork, eigener Zweig (`feature/<name>` oder `fix/<name>`).
3. Bauen und auf einem Gerät testen:
   ```bash
   cd companion
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Pull Request mit kurzer Beschreibung: was, warum, wie getestet.

## Regeln für Code

- Jede Funktion gehört in **beide** Oberflächen: die native Compose-UI auf dem Gerät
  (`companion/app/src/main/java/wtf/lennard/thorbro/ui/`) und die Web-Oberfläche (`companion/app/src/main/assets/web`).
- Akku schonen: keine dauerhaften Wake-/WLAN-Locks, keine Polling-Schleifen im Hintergrund.
  In Compose `WhileVisible { … }` statt `LaunchedEffect(Unit) { while (true) … }`;
  Hintergrundarbeit pausiert bei ausgeschaltetem Bildschirm (`Power.interactive`) und bei wenig Akku (`Power.low`).
- Keine Zugangsdaten, Tokens oder Kopplungscodes im Code oder in Logs.
- Kommentare kurz und auf Englisch, Texte in der App auf Deutsch.
