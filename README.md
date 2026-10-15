# Nestwerk

Spring-Boot-Webanwendung (Java 25, Maven, Thymeleaf, PostgreSQL, Flyway) für den Betrieb per Docker Compose.

## Lokal entwickeln

Voraussetzungen: JDK 25, Docker.

```bash
./mvnw spring-boot:run
```

`spring-boot-docker-compose` startet die Datenbank aus `compose.yaml` automatisch.
Beim ersten Start muss ein Admin-Passwort gesetzt sein:

```bash
NESTWERK_ADMIN_PASSWORD=changeme ./mvnw spring-boot:run
```

Tests (benötigen laufendes Docker für Testcontainers): `./mvnw verify`

## Auf dem Linuxserver installieren

Voraussetzung: ein Linux-Server (Debian/Ubuntu getestet) mit Root-Zugang, eine Domain, die per DNS auf den
Server zeigt, und offene Ports 80/443. Docker installiert das Skript bei Bedarf selbst.

1. Unter [Releases](https://github.com/chrisbad/nestwerk/releases) das Paket `nestwerk-install-vX.Y.Z.tar.gz`
   herunterladen und auf den Server kopieren.
2. Auf dem Server:

   ```bash
   tar xzf nestwerk-install-*.tar.gz && cd nestwerk
   sudo ./install.sh
   ```

Das Skript fragt nach Domain, E-Mail für Let's Encrypt, Admin-Namen und optional einer externen Backup-Platte.
Alle Passwörter und den `NESTWERK_SECRET_KEY` erzeugt es selbst und legt sie in `/opt/nestwerk/.env` ab (diese
Datei gut aufbewahren). Am Ende zeigt es Adresse und Admin-Passwort.

- **Reverse Proxy für alle Dienste:** Läuft auf dem Server noch keiner, richtet das Skript unter `/opt/proxy` einen
  zentralen [nginx-proxy](https://github.com/nginx-proxy/nginx-proxy) mit `acme-companion` ein. Er belegt 80/443
  und holt die Zertifikate automatisch. Ist ein anderer Proxy auf 80/443 aktiv, lauscht Nestwerk nur auf
  `127.0.0.1:8080` (`APP_BIND`). Dein Proxy leitet die Domain dorthin und setzt `X-Forwarded-For`/`-Proto`/`-Host`.
- **Image:** Das Image kommt fertig von `ghcr.io/chrisbad/nestwerk`. Solange das Paket privat ist, fragt das
  Skript einmal nach einem GitHub-Token mit dem Recht `read:packages` und meldet den Server damit an.
- Der Datenbankport wird nicht veröffentlicht, die Daten liegen im Volume `db-data`.
- Der erste Admin wird aus `NESTWERK_ADMIN_USERNAME`/`NESTWERK_ADMIN_PASSWORD` angelegt,
  solange keine Benutzer existieren. Weitere Konten legt der Admin unter `/admin/users` an.

### Weiteren Dienst hinter den Proxy hängen

Jeder Container im Docker-Netz `proxy` mit diesen Variablen bekommt seine Domain samt Zertifikat:

```yaml
services:
  wiki:
    image: …
    environment:
      VIRTUAL_HOST: wiki.example.org
      VIRTUAL_PORT: "3000"          # Port im Container
      LETSENCRYPT_HOST: wiki.example.org
    networks: [default, proxy]
networks:
  proxy:
    external: true
```

Eigene nginx-Einstellungen je Domain liegen in `/opt/proxy/vhost.d/<domain>`. Für Nestwerk legt das Skript dort
größere Upload-Grenzen und längere Zeitlimits für Sicherungen an.

### Umzug auf einen neuen Server

Auf dem alten Server unter „Sicherungen“ eine Sicherung erstellen und herunterladen. Auf dem neuen Server
`install.sh` ausführen, als Admin anmelden, die Sicherung unter „Sicherungen“ hochladen und wiederherstellen.
Danach gelten die Benutzer und Passwörter aus der Sicherung. Zugangsdaten der Sicherungsziele (WebDAV/SFTP) sind
mit dem alten `NESTWERK_SECRET_KEY` verschlüsselt. Den Wert aus der alten `.env` übernehmen oder die Zugangsdaten
neu eintragen.

### Benachrichtigungen

Benachrichtigungen erscheinen immer in der Glocke. Zusätzlich:

- **E-Mail:** `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASSWORD` und `MAIL_FROM` in der `.env` setzen.
  Jeder Benutzer hinterlegt seine Adresse unter „Meine Benachrichtigungswege“.
- **Push aufs Handy:** funktioniert, sobald Nestwerk über HTTPS erreichbar ist und `NESTWERK_BASE_URL`
  gesetzt ist. Die nötigen Schlüssel erzeugt Nestwerk beim ersten Bedarf selbst. Auf dem iPhone muss
  Nestwerk über „Teilen → Zum Home-Bildschirm“ installiert sein.
- Welche Wege standardmäßig gelten, legt ein Administrator unter „Benachrichtigungen einstellen“ fest.

### Haushalt und Wand-Tablet

- Der Haushaltsplan plant drei Wochen im Voraus. Wer im Kalender an einem Termin mit dem Häkchen „Abwesend“
  teilnimmt, wird beim Verteilen übersprungen.
- **Wand-Tablet koppeln:** Unter „Wand-Tablets“ (Benutzermenü, Recht „Wand-Tablets koppeln“) einen Code
  erzeugen, auf dem Tablet `https://<nestwerk>/tablet/pair` öffnen und den Code eingeben. Das Tablet sieht
  danach nur den Haushaltsplan von heute und morgen; abgemeldet wird es dort mit einem Klick.
- Abgehakt wird am Tablet mit der PIN des Mitglieds. Jeder setzt sie unter „PIN fürs Tablet“, für Kinder
  ohne Konto geht das in der Mitgliederverwaltung.
- Abfuhrkalender lassen sich bei einer Hausarbeit mit festen Terminen als `.ics`-Datei oder Abo-Adresse
  übernehmen; Abos werden täglich aktualisiert.

### KI-Anbindung (MCP)

Nestwerk enthält einen MCP-Server unter `https://<nestwerk>/mcp`. Darüber kann ein KI-Assistent wie Claude
Termine, ToDos, den Haushaltsplan, Kontakte und Benachrichtigungen lesen und bearbeiten. Löschen kann er nichts.

- Der Assistent handelt immer als ein Benutzer und hat genau dessen Rechte. Ein Administrator gibt einer Rolle das
  Recht „KI-Zugang nutzen“. Danach legt jeder Benutzer unter „KI-Zugänge“ (Benutzermenü) ein eigenes Token an.
  Das Token wird nur einmal angezeigt und lässt sich jederzeit widerrufen.
- Claude Code verbinden:

  ```bash
  claude mcp add --transport http nestwerk https://<nestwerk>/mcp --header "Authorization: Bearer nw_…"
  ```

  Andere Clients brauchen die Adresse und den Header `Authorization: Bearer <token>`. Die Seite „KI-Zugänge“ zeigt
  passende Schnipsel mit der Adresse aus `NESTWERK_BASE_URL`.
- Der Server arbeitet zustandslos. Am Reverse Proxy ist nichts Besonderes nötig, außer dass der Header
  `Authorization` durchgereicht wird.

### Update

```bash
sudo /opt/nestwerk/update.sh          # neueste Version
sudo /opt/nestwerk/update.sh 1.4.0    # bestimmte Version
```

`update.sh` sichert zuerst mit der laufenden Version (Art „vor einem Update“), lädt dann das neue Image und startet
neu. Datenbank-Migrationen laufen beim Start automatisch.

### Sicherungen

Sicherungen verwaltet ein Administrator (Recht „Sicherungen verwalten“) unter „Sicherungen“ im Benutzermenü:

- **Jetzt sichern** oder **regelmäßig** (täglich oder wöchentlich zu einer Uhrzeit). Eine Sicherung ist eine
  ZIP-Datei mit allen Tabellen und Anhängen. Sie liegt im Volume `backups` und lässt sich herunterladen.
- **Aufbewahrung:** die letzten N Sicherungen, dazu je eine pro Woche und Monat. Hochgeladene Sicherungen bleiben.
- **Zusätzliche Ziele:** externe Platte (`NESTWERK_EXTERNAL_BACKUP_HOST_DIR` in `.env`, Nestwerk schreibt nur
  bei vorhandener Markierungsdatei), WebDAV/Nextcloud und SFTP. Kopien dorthin lassen sich mit einer Passphrase
  verschlüsseln (`.zip.enc`).
- **Wiederherstellen:** aus der Liste oder nach dem Hochladen einer Datei, auch einer verschlüsselten Kopie. Vorher
  legt Nestwerk automatisch eine Sicherheitskopie an. Ältere Sicherungen werden auf den aktuellen Stand migriert.
- Bei einem Fehlschlag gibt es eine Benachrichtigung. Die Startseite warnt, wenn die letzte Sicherung älter als drei
  Tage ist.

### Export und Import

- **Kalender:** „Export“ in der Kalenderleiste liefert alle sichtbaren Termine als `.ics`. „Import“ übernimmt
  `.ics`-Dateien, etwa aus Google oder Outlook. Gleiche UIDs werden aktualisiert statt doppelt angelegt.
- **Kontakte:** über „Austausch“ als vCard oder CSV (Excel). Der Import erkennt CSV aus Nestwerk, Google und Outlook
  und ergänzt vorhandene Kontakte.
- **ToDos:** über „Austausch“ die aktuelle Ansicht als CSV. Der Import nimmt dasselbe Format, mit ID wird ein
  vorhandenes ToDo aktualisiert.

Jeder Import zeigt erst eine Vorschau und übernimmt erst nach Bestätigung.

### Notfall: Sicherung von Hand

Falls die App nicht startet, lassen sich Datenbank und Anhänge auch direkt sichern:

```bash
cd /opt/nestwerk
docker compose exec -T db pg_dump -U nestwerk nestwerk > backup.sql
docker compose run --rm --no-deps -v "$PWD":/backup --entrypoint "" app \
  tar czf /backup/anhaenge.tgz -C /data/attachments .
```
