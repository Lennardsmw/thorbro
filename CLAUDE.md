# ThorBro – Arbeiten mit mehreren Chats gleichzeitig

Mehrere Chats/Sessions arbeiten parallel an verschiedenen Features. Damit sich niemand gegenseitig
Code, Versionen oder den Thor kaputtmacht, gelten diese Regeln für **jeden** Chat:

1. **Nie im Haupt-Checkout arbeiten.** `~/dev/thorbro` bleibt immer auf `main` und sauber.
   Jedes Feature bekommt einen eigenen Worktree:
   ```sh
   scripts/feature.sh <name>        # -> ~/dev/thorbro-<name>, Zweig feature/<name>, von main
   ```
   Fremde Worktrees (`git worktree list`) nicht anfassen, nicht darin committen, keine Zweige löschen.

2. **Versionsnummer nie von Hand ändern.** `versionName`/`versionCode` in `build.gradle.kts` werden
   berechnet: `VERSION` (Release-Version) + `-<zweig>+<commit>`, versionCode = Build-Zeit.
   `VERSION` ändert nur `scripts/release.sh` auf `main`.

3. **Auf den Thor nur mit `scripts/push-update.sh`.** Das Skript sperrt den Thor 30 min für den Zweig,
   der zuletzt aufgespielt hat. Steht dort ein anderer Zweig, bricht es ab – nicht mit `FORCE=1`
   übergehen, ohne dass der User es sagt. Welcher Build drauf ist: ThorBro → Einstellungen (Titel)
   oder `GET /api/update` → `current`.

4. **Fertig → `scripts/land.sh`** im Feature-Worktree (alles committet): rebased auf `main`, baut,
   spult `main` vor. Bei Konflikten: lösen, `git rebase --continue`, `land.sh` nochmal.
   `scripts/land.sh --remove` entfernt danach Worktree und Zweig.

5. **Release** (nur nach Absprache mit dem User): auf `main` `scripts/release.sh <neue Version>`.

6. Jede Funktion gehört in **beide** Oberflächen: Web-Panel (`assets/web`) und die native Thor-App
   (`ui/*.kt`, Konsolen-Stil: Kacheln/`ConsoleTile`, `TileGrid`, `Shelves`, `ConsoleMenu`, `BigButton`).

7. Commits und Repo-Dateien ohne Claude-/Anthropic-Hinweise.
