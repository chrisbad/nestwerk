# Updates aus der App – Design

## Warum
Heute aktualisiert man Nestwerk per SSH mit `update.sh`. Der Admin soll stattdessen in Nestwerk sehen, dass es eine
neue Version gibt, was sie ändert, und sie mit einem Klick einspielen – ohne dass Daten verloren gehen. Geht etwas
schief, läuft danach wieder die alte Version mit den alten Daten.

## Ziele
- Versionsprüfung mit zwei Kanälen: **Releases** (Tags `vX.Y.Z`, Standard) und **main** (jeder Push auf main).
- Hinweis für Admins (Benachrichtigung + Admin-Seite „Updates“) mit Änderungen.
- Ein Klick: Sicherung mit der alten Version → neue Compose-Dateien → neues Image → Neustart → warten bis gesund.
- Automatischer Rückfall auf alte Version und alte Daten, wenn die neue Version nicht gesund wird.
- Fortschritt und Ergebnis sichtbar; Protokoll jedes Laufs.
- Ein Test sichert ab, dass Migrationen Bestandsdaten erhalten.

## Nicht-Ziele
- Kein Downgrade auf Wunsch (nur automatischer Rückfall direkt nach einem Fehlschlag).
- Keine automatischen nächtlichen Updates (nur Hinweis + Klick).
- Kein Update des zentralen nginx-proxy.

## Anti-Ziele
- Die Web-App selbst bekommt **keinen** Zugriff auf den Docker-Socket.
- Ein fehlgeschlagenes Update darf nie mit leerer oder halb migrierter Datenbank enden.

## Ausgangslage
- Repo und Image sind privat (`chrisbad/nestwerk`, `ghcr.io/chrisbad/nestwerk`); der Server ist bei ghcr.io angemeldet
  (install.sh fragt bei Bedarf nach einem Token und führt `docker login` aus).
- Release-Workflow: Tag `vX.Y.Z` → Image `X.Y.Z`, `X.Y`, `latest`, App-Version `X.Y.Z`;
  Push auf main → Image `main`, App-Version `0.0.0-<sha7>`.
- `update.sh` sichert per `--nestwerk.backup.run-and-exit=true`, zieht das Image und startet neu.
- Restore gibt es nur in der App (RestoreService); er lehnt neuere Sicherungen ab und baut ältere neu auf.

## Lösung

### Versionen eindeutig machen
- Workflow erzeugt für main-Pushes zusätzlich den unveränderlichen Tag `sha-<sha7>`. Damit ist jede Version ein
  fester Tag; `NESTWERK_VERSION` in `.env` ist danach immer `X.Y.Z` oder `sha-<sha7>`, und der Rückfall setzt einfach
  den vorherigen Wert.
- Die laufende Version kommt aus `BuildProperties` (`X.Y.Z` bzw. `0.0.0-<sha7>`).

### Versionsprüfung (App, Paket `update`)
- `UpdateSettings` (in `app_settings`): Kanal, GitHub-Token (verschlüsselt mit `SecretBox`, nötig solange das Repo
  privat ist; Rechte: Contents + Actions lesen), zuletzt gemeldete Version.
- `ReleaseSource` (Schnittstelle, Implementierung `GitHubReleaseSource` über `RestClient`, im Test ein Fake):
  - Releases: neuestes Release → Version, Release-Notes (Markdown), Tag.
  - main: letzter erfolgreicher Lauf von `release.yml` auf main → Commit-SHA; Änderungen = Commit-Nachrichten aus
    der Compare-API zwischen laufendem SHA und neuem SHA.
  - Dateien einer Version: `compose.prod.yaml`, `compose.proxy.yaml`, `update.sh` über die Contents-API am Tag/SHA.
- `UpdateCheck` prüft alle 6 Stunden und auf Knopfdruck. Neue Version → Benachrichtigung an alle mit
  `UPDATE_MANAGE` (einmal je Version), neuer Benachrichtigungstyp `UPDATE_AVAILABLE`.
- Version „neuer?“: Releases per SemVer-Vergleich; main per SHA-Ungleichheit (und nur, wenn der Lauf nach dem Build
  der laufenden Version fertig wurde). Auf einer `dev`-Version (lokal) wird nicht geprüft.

### Auftrag an den Updater
- Gemeinsames Volume `updater` (App: `/data/updater`, Updater: `/work`). Keine Netzwerkschnittstelle, kein Token:
  nur App und Updater hängen das Volume ein.
- Die App legt `jobs/<id>/` an mit `request.json` (Zielversion als Image-Tag, Kanal, wer, wann) und `files/` (die
  Compose-Dateien und `update.sh` der Zielversion, von der App über die GitHub-API geholt), zuletzt `ready`.
- Der Updater schreibt `jobs/<id>/status.json` (Schritt, Zustand, Zeitstempel, Meldung) und `log.txt`.
- Die App liest den Status (Admin-Seite pollt per htmx) und übernimmt das Ergebnis nach dem Neustart in die Tabelle
  `update_runs` (Version vorher/nachher, Zustand, Protokoll, Sicherungsdatei, wer). Benachrichtigung über das
  Ergebnis (`UPDATE_DONE`, `UPDATE_FAILED`).
- Es läuft höchstens ein Auftrag gleichzeitig.

### Updater-Container
- Eigenes Image `ghcr.io/chrisbad/nestwerk-updater` aus `updater/` (Basis `docker:cli` mit Compose-Plugin, ein
  Bash-Skript), im selben Workflow mit denselben Tags gebaut.
- Dienst `updater` in `compose.prod.yaml`: `/var/run/docker.sock`, Installationsverzeichnis unter **demselben Pfad**
  wie auf dem Host (`${NESTWERK_DIR}:${NESTWERK_DIR}`, damit relative Pfade in den Compose-Dateien stimmen),
  Docker-Anmeldung des Hosts read-only (`${DOCKER_CONFIG_DIR:-/root/.docker}`), Volume `updater`. Kein Port.
- Ablauf eines Auftrags:
  1. **Sicherung** mit dem laufenden Image: `docker compose run --rm --no-deps -T app
     --nestwerk.backup.run-and-exit=true --nestwerk.backup.result-file=/data/updater/jobs/<id>/backup` → Dateiname.
     Schlägt sie fehl, wird abgebrochen (anders als `update.sh` keine Rückfrage).
  2. **Stand merken**: `.env`, Compose-Dateien und `update.sh` nach `jobs/<id>/previous/`; höchste Flyway-Version
     per `psql` im db-Container.
  3. **Dateien tauschen**: neue Dateien aus `files/` ins Installationsverzeichnis, `NESTWERK_VERSION` setzen.
     `.env` wird sonst nicht verändert – neue Variablen müssen in den Compose-Dateien einen Standardwert haben.
  4. **Starten**: `docker compose pull app updater`, `docker compose up -d --wait --wait-timeout 300 db app`.
  5. **Erfolg** → Status `DONE`. Hat sich der Updater selbst geändert, ersetzt er sich als letzten Schritt
     (`docker compose up -d --no-deps updater`); der Status ist dann schon geschrieben.
  6. **Fehlschlag** (Pull, Start oder Gesundheit) → **Rückfall**: alte Dateien zurück, `NESTWERK_VERSION` alt.
     Ist die Flyway-Version gestiegen, wird mit dem alten Image die Sicherung aus Schritt 1 vollständig eingespielt
     (`--nestwerk.backup.restore-and-exit=<datei>`), danach `up -d --wait db app`. Status `ROLLED_BACK` mit Meldung;
     gelingt auch das nicht: `FAILED` mit Protokoll und Hinweis auf manuelle Schritte.
- Beim Start erkennt der Updater einen unterbrochenen Auftrag (Status `RUNNING`) und meldet ihn als `FAILED`.

### Neue Befehle in der App
- `--nestwerk.backup.result-file=<pfad>`: `BackupCommand` schreibt den Dateinamen der Sicherung dorthin.
- `--nestwerk.backup.restore-and-exit=<datei>`: neues `RestoreCommand`; spielt die Sicherung **immer mit leerem
  Schema** ein (Flyway `clean` → bis zur Version der Sicherung migrieren → laden → auf den Stand dieser App
  migrieren). So passt sie auch, wenn eine neuere Version schon migriert hatte.

### Oberfläche
- Admin-Seite **Updates** (Recht `UPDATE_MANAGE`, neu in `AdminPermission`): laufende Version, Kanal-Wahl,
  GitHub-Token, verfügbare Version mit Änderungen (Markdown wie bei ToDos), Knopf „Jetzt aktualisieren“ (mit
  Bestätigung), Fortschritt der Schritte, Liste früherer Läufe mit Protokoll.
- Ohne Updater (lokal, alte Installation): Hinweis „Updater nicht eingerichtet“ mit Anleitung; Prüfung und Hinweis
  funktionieren trotzdem.
- Während des Neustarts zeigt die Seite „Nestwerk wird aktualisiert …“ und lädt neu, sobald `/actuator/health`
  wieder antwortet.
- Für alle anderen: `proxy/nestwerk-vhost.conf` bekommt eine Wartungsseite für 502/503/504 (inline per
  `return 503 '<html>…'`, lädt sich alle 5 s neu).

### Installation und Übergang
- `install.sh` schreibt `NESTWERK_DIR` in `.env`. `update.sh` holt künftig auch Compose-Dateien und `update.sh` der
  Zielversion und ergänzt `NESTWERK_DIR`, falls es fehlt.
- Bestehende Installationen (homeserver): einmalig neue `compose.prod.yaml`, `compose.proxy.yaml`, `update.sh`
  einspielen und `update.sh` ausführen; danach geht alles über die App. Anleitung im README.

### Migrationen sicher halten
- Test `MigrationUpgradeTest` (Testcontainers): migriert eine leere DB bis V18, lädt `src/test/resources/db/
  upgrade/V18-sample.sql` (repräsentative Daten aller Module), migriert auf den neuesten Stand und prüft, dass die
  Zeilen erhalten sind und die App-Lesepfade funktionieren. Regel im README: Migrationen nur additiv bzw. mit
  Datenübernahme; bei jedem Release ein neuer Snapshot.
- Updater-Skript: `shellcheck` im Workflow; Ablauf manuell lokal mit `compose.prod.yaml` und zwei Tags geprüft
  (Erfolg und absichtlich kaputte Version → Rückfall).

## Entscheidungen, die ich getroffen habe
- Auftrag über ein gemeinsames Volume statt HTTP mit Token: weniger bewegliche Teile, gleiche Sicherheit.
- `sha-<sha7>`-Tags für main, damit der Rückfall auch im main-Kanal eine feste Version hat.
- Die App holt die Compose-Dateien (sie hat den GitHub-Token); der Updater braucht nur Docker.
- `.env` wird nur bei `NESTWERK_VERSION` (und einmalig `NESTWERK_DIR`) angefasst.
- Sicherung fehlgeschlagen → Abbruch statt Rückfrage.
- Wartungsseite über den Proxy statt eines eigenen Containers.

## Dem Bauenden überlassen
- Aufteilung der Klassen im Paket `update`, Polling-Intervalle, Darstellung der Schritte.
