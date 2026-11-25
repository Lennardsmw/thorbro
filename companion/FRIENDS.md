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
