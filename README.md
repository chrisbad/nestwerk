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
