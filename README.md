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

Voraussetzung: Docker mit Compose-Plugin, Git.

```bash
git clone <repo-url> nestwerk && cd nestwerk
cp .env.example .env        # Passwörter anpassen!
docker compose -f compose.prod.yaml up -d --build
```

- Die App lauscht standardmäßig nur auf `127.0.0.1:8080` (`APP_BIND` in `.env`).
  Der Reverse Proxy (nginx, Traefik, Caddy …) terminiert HTTPS und leitet dorthin weiter;
  er sollte `X-Forwarded-For`/`-Proto`/`-Host` setzen.
- Der Datenbankport wird nicht veröffentlicht, die Daten liegen im Volume `db-data`.
- Der erste Admin wird aus `NESTWERK_ADMIN_USERNAME`/`NESTWERK_ADMIN_PASSWORD` angelegt,
  solange keine Benutzer existieren. Weitere Konten legt der Admin unter `/admin/users` an.

### Benachrichtigungen

Benachrichtigungen erscheinen immer in der Glocke. Zusätzlich:

- **E-Mail:** `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASSWORD` und `MAIL_FROM` in der `.env` setzen.
  Jeder Benutzer hinterlegt seine Adresse unter „Meine Benachrichtigungswege“.
- **Push aufs Handy:** funktioniert, sobald Nestwerk über HTTPS erreichbar ist und `NESTWERK_BASE_URL`
  gesetzt ist. Die nötigen Schlüssel erzeugt Nestwerk beim ersten Bedarf selbst. Auf dem iPhone muss
  Nestwerk über „Teilen → Zum Home-Bildschirm“ installiert sein.
- Welche Wege standardmäßig gelten, legt ein Administrator unter „Benachrichtigungen einstellen“ fest.

### Haushalt und Wand-Tablet

- Der Putzplan plant drei Wochen im Voraus. Wer im Kalender an einem Termin mit dem Häkchen „Abwesend“
  teilnimmt, wird beim Verteilen übersprungen.
- **Wand-Tablet koppeln:** Unter „Wand-Tablets“ (Benutzermenü, Recht „Wand-Tablets koppeln“) einen Code
  erzeugen, auf dem Tablet `https://<nestwerk>/tablet/pair` öffnen und den Code eingeben. Das Tablet sieht
  danach nur den Putzplan von heute und morgen; abgemeldet wird es dort mit einem Klick.
- Abgehakt wird am Tablet mit der PIN des Mitglieds. Jeder setzt sie unter „PIN fürs Tablet“, für Kinder
  ohne Konto geht das in der Mitgliederverwaltung.
- Abfuhrkalender lassen sich bei einer Hausarbeit mit festen Terminen als `.ics`-Datei oder Abo-Adresse
  übernehmen; Abos werden täglich aktualisiert.

### Update

```bash
git pull
docker compose -f compose.prod.yaml up -d --build
```

### Backup

Datenbank:

```bash
docker compose -f compose.prod.yaml exec -T db \
  pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > backup.sql
```

Anhänge (Dateien an ToDos, Kommentaren und Kontakten liegen im Volume `attachments`):

```bash
docker compose -f compose.prod.yaml run --rm --no-deps -v "$PWD":/backup --entrypoint "" app \
  tar czf /backup/anhaenge.tgz -C /data/attachments .
```

Datenbank und Anhänge gehören zusammen: Ein Anhang ohne passenden Datensatz wird nachts automatisch
entfernt, ein Datensatz ohne Datei liefert beim Herunterladen einen Fehler.
