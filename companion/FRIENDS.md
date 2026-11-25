# Freunde-Netzwerk – Protokoll v1

Gilt für ThorBro (`app/.../Pals.kt`) und RomBro (`core/.../friends/MacFriends.kt`). Beide müssen byte-gleich bleiben.

## Identität
- EC P-256-Schlüsselpaar pro Gerät (Prefs `pals.pub`/`pals.priv`, X.509 / PKCS#8, base64url ohne Padding).
- Karte: `{"v":1,"id","name","pub","app"}`, `id` = erste 8 Byte SHA-256(pub) als Hex.
- Postfach: ntfy.sh-Topic `tbf-` + Hex(SHA-256("thorbro inbox v1" ‖ pub))[0:12 Byte].

## Nachricht (ein ntfy-Post aufs Postfach des Empfängers, mit Cache, also 12 h abholbar)
`tb1.<eph-pub>.<nonce12>.<AES-256-GCM(sealed)>`, Schlüssel = HMAC-SHA256(ECDH(eph, Empfänger), "thorbro msg v1").
`sealed = {"m": <JSON-Text>, "s": ECDSA-SHA256(Absender, m)}`, `m = {"from": Karte, "to": Empfänger-id, "ts", "mid", "t", …}`.
Empfänger prüft: Signatur, `id` passt zu `pub`, `to` = eigene id, `ts` höchstens 24 h alt, `mid` nicht doppelt.
Abholen: `GET https://ntfy.sh/<topic>/json?since=<letzte ntfy-id>` (Stream).

Typen: `req` (Anfrage, optional `tok`, `via`), `acc` (angenommen), `card` (Name geändert), `bye` (entfernt).
Weitere Typen (Party, Chat) laufen über `Pals.handlers` / `MacFriends.handlers` und nur von Freunden.

## Freund werden
- **Code** (8 Tasten ↑↓←→ABXY, 15 min): Schlüssel k = PBKDF2-SHA256(code, "thorbro friend code v1", 100 000, 256 bit);
  Topic `tbf-c-` + Hex(HMAC(k,"topic"))[0:12]; Inhalt `nonce.AES-GCM(HMAC(k,"key"), json)`, ohne Cache.
  Eingeber sendet `{"t":"pair","n":nonce,"card"}` alle 4 s, Anzeiger antwortet `{"t":"ok","re":n,"card"}`. Beide sind sofort Freunde.
- **Datei** `<Name>.thorfriend` = Karte + `tok` (einmalig, 7 Tage). Importeur schickt `req` mit `tok` → automatisch angenommen.
  ThorBro und RomBro übernehmen solche Dateien aus `Download/` bzw. `~/Downloads` beim Öffnen des Freunde-Menüs.
- **In der Nähe**: `GET /api/netplay/lobby` (Port 7330, mDNS `_thorbro._tcp`) liefert `card`; „Hinzufügen“ schickt `req`,
  der andere nimmt an (fragen beide, werden sie sofort Freunde).

## Status (online, Party)
- Jedes Gerät postet seinen Status nur bei Änderungen und alle 3 h auf sein Status-Topic
  `tbf-s-` + Hex(SHA-256("thorbro status v1" ‖ pub))[0:12]; Freunde lesen mit (kostet nichts – ntfy.sh erlaubt
  ohne Konto nur 250 Posts pro Tag und IP, also sparsam senden).
- Inhalt `ts1.<nonce>.<AES-GCM(HMAC(SHA-256(pub), "thorbro status key v1"), {"m", "s"})>`, `m` = `{"id","name","ts","on","app","party"?,"open"?}`.
- „online“ = `on` und jünger als 3,5 h. Alle Topics (Postfach + Status aller Freunde) laufen in einem Stream
  (`/t1,t2,…/json?since=<Zeit>`), neu aufgebaut, wenn sich die Freundesliste ändert.

## Party
- Der Leiter hält die Party: im Status `party = {id, leader, open, members:[{id,name}]}`; Mitglieder senden `party = {id, leader}`.
- Nur der Leiter lädt ein: `inv {party, members}` → Freund. Beitreten: `join {party}` → Leiter (eingeladen oder Party offen),
  Leiter trägt ein und postet seinen Status. `leave {party}` → Leiter. Abgelehnt: `nope {why}`.
- Ein Mitglied, das im Status des Leiters nicht (mehr) vorkommt (nach 90 s Wartezeit), verlässt die Party von selbst.
- Ohne Party, aber „Party offen“: Status `open: true` – Freunde sehen das, beitreten geht erst, wenn eine Party besteht.

## Party-Netz (zusammen spielen, auch übers Internet)
Mehrspieler läuft nur noch über die Party. Kein Spielserver-Schalter, kein Online-Code, keine Lobby-/PIN-Suche.

**Räume, immer an** (jedes Gerät, ThorBro und RomBro):

| Emulator | Emulator verbindet sich mit | Weiterleitung (Relay) | eigener Raum |
|---|---|---|---|
| Azahar (3DS) | 127.0.0.1:24872 | UDP 127.0.0.1:24872 | UDP 24873 (`libazaharroom.so` / `azahar --room`) |
| Eden (Switch, LDN) | 127.0.0.1:24875 | UDP 127.0.0.1:24875 | UDP 24876 (`libedenroom.so` / `eden-room`, aus Eden v0.2.1 gebaut: `scripts/build-eden-room.sh`) |

Der Emulator tritt einmal 127.0.0.1 bei (Azahar/Eden merken sich das; RomBro trägt es in die qt-config ein und
klickt „Direkt verbinden“ über die Bedienungshilfen). Das Relay führt dorthin, wo gespielt wird:
- keine Party oder selbst Leiter → eigener Raum,
- Mitglied → durch den Party-Tunnel in den Raum des Leiters,
- (alt, nur noch intern) beigetretene Lobby → direkt an deren Adresse.
Jeder Emulator-Socket bekommt einen eigenen Weg, der Raum sieht also einen festen Mitspieler pro Gerät.

**Status des Leiters** (`party` im Status, siehe oben) enthält zusätzlich
`room = {port: 24873, eden: 24876, addrs: [WLAN, Wi-Fi Direct, NetBird 100.116.x …], net: true}` (`net` = Tunnel offen) und
beim laufenden Spiel `game = {system, title, mode, pin, addrs, azUser?, azConsole?}`.

**Tunnel** (`Tunnel.kt`, identisch in ThorBro und RomBro `netplay/Tunnel.kt`), Version 2:
- Der Leiter hält, solange er eine Party mit Mitgliedern leitet, einen `Tunnel.Host` auf UDP **24874** (belegt → beliebiger Port)
  mit einem Geheimnis pro Party (16 Byte, in `party.mine.net`). Erlaubte Ziele: nur die eigenen Raum-Ports 24873 und 24876.
- Pakete: `"TB" ver(2) type session(8) counter(8) nonce(12) | AES-256-GCM | tag(16)`, Header als AAD.
  Schlüssel je Richtung = HMAC-SHA256(Geheimnis, "thorbro tunnel v2 guest->host" bzw. "… host->guest"). Wiederholte Zähler
  (64er-Fenster) werden verworfen; alles andere, was den Port erreicht, wird ohne Antwort weggeworfen.
- Typen: 1 HELLO (Mitglied → Leiter, Nutzlast 2 Byte = Raum-Port), 2 WELCOME, 3 DATA, 4 PING (alle ~12 s ohne Verkehr),
  5 PUNCH (Leiter → Mitglied, öffnet den eigenen Router), 6 BYE, 7 FULL (mehr als 15 Mitglieds-Sockets).
- Eine Sitzung pro Emulator-Socket; der Leiter reicht sie von einem eigenen lokalen Socket an 127.0.0.1:<Raum-Port> weiter.

**Signalisierung** über Pals-Nachrichten (verschlüsselt, signiert, nur zwischen Freunden, wie oben):
- `knock {party, c: [Adressen]}` Mitglied → Leiter, sobald es laut Status in der Party ist und `room.net` gesetzt ist.
  `c` = eigene Adressen des Tunnel-Sockets: STUN (öffentlich IPv4:Port), alle IPv4 der Schnittstellen (WLAN, NetBird, …),
  globale IPv6. Höchstens alle 20 s, höchstens 8 pro Party; erneut, wenn der Tunnel 25 s nicht antwortet.
- Leiter prüft: Absender ist Mitglied, Party-id stimmt, Nachricht < 5 min alt (ntfy liefert nach Neustart alte nach).
  Dann Hole-Punching zu allen `c` (bei öffentlichem IPv4 auch Ports +-8 … +400 für Mobilfunk-NAT), STUN neu, Antwort
  `net {party, s: Geheimnis (base64url), c: [Adressen des Leiters]}`.
- Mitglied öffnet `Tunnel.Guest` auf demselben Socket (die Adressen aus dem knock bleiben gültig), sendet HELLO an alle
  Adressen gleichzeitig und behält die erste, die antwortet: gleiches WLAN, NetBird oder Internet. Danach führen
  127.0.0.1:24872 und 127.0.0.1:24875 durch den Tunnel.

**Spielstart**: Der Leiter startet ein Spiel (irgendein Frontend) → Lobby → `game` im Status. Ein Mitglied, das eine Adresse
aus `game.addrs` per HTTP (7330) erreicht, tritt wie bisher bei (ROM vom Leiter, Emulator als Gast). Sonst, bei 3DS/Switch:
das Spiel wird mit dem eigenen ROM gleichen Namens gestartet (Spieldateien gehen nicht durch den Tunnel), der Raum läuft
über den Tunnel. RetroArch und PPSSPP gehen nur im gleichen Netz (WLAN, Wi-Fi Direct, NetBird).

## Geschenke (Spielstände und Spiele an Freunde)
`Gifts.kt` / `GiftWire.kt` (pure JVM, gehört später auch in RomBro). Alles läuft über Pals-Nachrichten, nur zwischen Freunden.

**Nachrichten**
- `gift {gid, kind: save|game|both, system, title, file, size?, sha1?, packed?, save?: {size, sha1, time, name, profile, att?: {u, k, until}},
  ways: {lan: ["ip:7330", …], tunnel: bool, romm?: {srv, id}}, tok, exp}` Absender → Freund. `gid` = 8 Byte hex, `exp` = 7 Tage.
  `tok` (16 Byte base64url) öffnet nur dieses Geschenk. `srv` = Hex(SHA-256("thorbro romm v1 " ‖ RomM-URL klein))[0:8 Byte].
  ntfy hält Nachrichten 12 h: der Absender schickt `gift` erneut, wenn der Freund wieder online ist und die letzte älter als 11 h ist.
- `gift-ok {gid, c: [Adressen]}` Empfänger → Absender: möchte den Tunnel. `c` wie bei `knock`.
- `gift-net {gid, s, c, port, lan}` Absender → Empfänger: Geheimnis (16 Byte) eines eigenen `Tunnel.Host` nur für dieses Geschenk,
  dessen Adressen, das Ziel `port` (der GiftWire-Server auf 127.0.0.1) und frische LAN-Adressen.
- `gift-no {gid}` abgelehnt · `gift-done {gid}` angekommen · `gift-want {gid}` Empfänger möchte zum Spielstand auch das Spiel.

**Dateien** (`part` = `save` | `game`; ein Ordner-Spiel wird als unkomprimiertes Zip verschickt, `packed: true`)
- lokal: `GET http://<lan>/api/gift/<gid>/<part>?k=<tok>` mit `Range: bytes=a-b` (206). Ohne Kopplungscode.
- RomM: nur wenn `romm.srv` gleich dem eigenen ist; `GET /api/roms/<id>/content/<fs_name>` mit eigenem Login und Range.
- Tunnel (v2, wie Party-Netz, eigenes Geheimnis): im Tunnel Datagramme `'Q' part(1: 1=save, 2=game) offset(8) count(2)` →
  bis zu 64 Antworten `'D' part offset(8) Daten(≤1150)`. Der Empfänger fragt Fenster an (wächst bei Antworten, halbiert bei Verlust),
  fragt Verlorenes nach 700 ms erneut.
- Spielstand ≤ 15 MB zusätzlich als ntfy-Anhang: `att.u` = URL, Inhalt = nonce(12) ‖ AES-256-GCM(`att.k`, save.zip).

**Laden**: Empfänger testet die erlaubten Wege gleichzeitig mit je 4 MB ab dem ersten fehlenden Byte und nimmt den schnellsten;
bricht einer ab, macht der nächste an derselben Stelle weiter. Am Ende SHA-1 prüfen. Daten sparen: „Nur lokal“ = nur `lan`;
„Mobil sparen“ = Spiele über Mobilfunk nur bis `giftMaxMobileMb` (Standard 500 MB).

**Spielstand** = Profil-Paket (`manifest.json` mit den Pfaden des Absenders + Dateien `i` bzw. `i/…`). Der Empfänger schreibt das
Manifest auf seine eigenen Pfade um (vorhandener Spielstand, sonst wo der Emulator sucht), legt es als Backup „Geschenk von …“ ab und
setzt es bei „Übernehmen“ ein (der eigene Stand wird vorher gesichert).
