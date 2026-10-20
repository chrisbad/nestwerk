# Nestwerk

Spring-Boot-Webanwendung (Java 25, Maven, Thymeleaf, PostgreSQL, Flyway) für den Betrieb per Docker Compose.

## Lokal entwickeln

Voraussetzungen: JDK 25, Docker.

```bash
./mvnw -pl nestwerk-app -am compile spring-boot:run
```

`spring-boot-docker-compose` startet die Datenbank aus `compose.yaml` automatisch.
Beim ersten Start muss ein Admin-Passwort gesetzt sein:

```bash
NESTWERK_ADMIN_PASSWORD=changeme ./mvnw -pl nestwerk-app -am compile spring-boot:run
```

Tests (benötigen laufendes Docker für Testcontainers): `./mvnw verify`

## Modulstruktur

Der Build ist ein Maven-Multi-Module-Projekt. Die Abhängigkeiten laufen nur in eine Richtung (kein Zyklus):

| Modul | Inhalt | hängt ab von |
|---|---|---|
| `nestwerk-core` | Benutzer, Rollen, Rechte/ACL, Familienmitglieder, App-Einstellungen | – |
| `nestwerk-calendar` | Kalender, Termine, Blocker (Abwesenheiten, Ruhezeiten) | core |
| `nestwerk-notification` | Benachrichtigungen, Mail und Web-Push | core, calendar |
| `nestwerk-contact` | Kontakte | core |
| `nestwerk-todo` | ToDos | core, notification, contact |
| `nestwerk-household` | Haushaltsplan, Belohnungen, Tablet-Geräte | core, calendar, notification |
| `nestwerk-kitchen` | Rezepte, Vorrat, Essensplan, Einkaufsliste | core, calendar, notification |
| `nestwerk-attachment` | Dateianhänge | core, contact, todo, kitchen |
| `nestwerk-school` | Schuljahre, Stundenplan, Hausaufgaben, Klassenarbeiten, Ferien, Noten, Elternbriefe | core, calendar, notification, todo, attachment |
| `nestwerk-transfer` | Import/Export (iCalendar, vCard, CSV) | core, calendar, contact, todo |
| `nestwerk-maintenance` | Datensicherung und Updates | core, notification, attachment |
| `nestwerk-web` | Controller, Formulare, Templates, statische Dateien | alle Fachmodule |
| `nestwerk-ai` | MCP-Server, OAuth 2.1, API-Tokens | web und alle Fachmodule |
| `nestwerk-app` | Startklasse, Konfiguration, Flyway-Migrationen, `application.yaml`, alle Tests | ai |

Rechte melden Module über `PermissionSource` an den Katalog in `core`; so braucht `core` die Rechte-Enums der anderen
Module nicht zu kennen. Die Tests liegen gebündelt in `nestwerk-app`, weil sie die vollständige Anwendung samt
Datenbankmigrationen benötigen. Das ausführbare JAR entsteht unter `nestwerk-app/target/`.

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

### Zeiten (Arbeit, Schlaf, Ruhe, Freizeit)

- Unter „Zeiten“ trägt jede Person wiederkehrende Zeiten ein: Art, Uhrzeit (über Mitternacht möglich), Wochentage,
  gültig ab/bis und einzelne Ausnahmetage (Urlaub). Rechte wie bei Terminen.
- **Beim Einplanen:** *Nur anzeigen*, *Warnen* (das Formular fragt einmal nach und schlägt freie Zeiten vor) oder
  *Sperren* (für andere; man selbst bekommt höchstens eine Warnung). Ausgewählte Personen dürfen trotzdem einplanen.
  Das gilt für Formular, Ziehen im Kalender, MCP und iCal-Import.
- **Sichtbarkeit:** mit Titel, nur „belegt“ oder unsichtbar; Betroffene und Admins sehen immer den Titel.
  Im Kalender erscheinen Zeiten als Bänder; beim Ziehen im Zeitraster zeigt das Raster gesperrte Zeiten der
  betroffenen Personen schraffiert.
- **Ruhe-Modus:** Push und Mail warten bis zum Ende der Zeit. **Feiertage:** Zeiten mit „an Feiertagen frei“
  entfallen an den gesetzlichen Feiertagen des eingestellten Bundeslands.
- **Ruhe-Ziele:** z. B. „mind. 2 freie Abende“ oder „mind. 6 Stunden Ruhe pro Woche“; Stand auf „Zeiten“ und der
  Startseite, Warnung beim Einplanen, wenn ein Ziel kippen würde.

### Haushalt und Wand-Tablet

- Der Haushaltsplan plant drei Wochen im Voraus. Wer im Kalender an einem Termin mit dem Häkchen „Abwesend“
  teilnimmt, wird beim Verteilen übersprungen.
- Jede Aufgabe bekommt eine Uhrzeit: die früheste freie Lücke der zuständigen Person im Zeitfenster der Hausarbeit
  (frühestens/spätestens, sonst 07:00 bis Fälligkeit bzw. 21:00), außerhalb von Terminen, Zeiten mit Warnung oder
  Sperre und anderen Hausarbeiten. Wer keine Lücke hat, wird übersprungen; hat niemand Zeit, bleibt sie offen
  („niemand frei“). Eingeplante Aufgaben stehen auch im Kalender der Person.
- **Wand-Tablet koppeln:** Unter „Wand-Tablets“ (Benutzermenü, Recht „Wand-Tablets koppeln“) einen Code
  erzeugen, auf dem Tablet `https://<nestwerk>/tablet/pair` öffnen und den Code eingeben. Das Tablet sieht
  danach nur den Haushaltsplan von heute und morgen; abgemeldet wird es dort mit einem Klick.
- Abgehakt wird am Tablet mit der PIN des Mitglieds. Jeder setzt sie unter „PIN fürs Tablet“, für Kinder
  ohne Konto geht das in der Mitgliederverwaltung.
- Abfuhrkalender lassen sich bei einer Hausarbeit mit festen Terminen als `.ics`-Datei oder Abo-Adresse
  übernehmen; Abos werden täglich aktualisiert.

### Küche

- **Rezepte und Lebensmittel:** Zu jedem Rezept zeigt Nestwerk Kalorien, Eiweiß, Fett und Kohlenhydrate pro Portion und
  rechnet die Zutaten auf eine andere Portionenzahl um. Die Nährwerte stammen aus einem Grundstock von rund 150
  Lebensmitteln, aus Open Food Facts (Barcode-Scan) oder eigener Pflege. Zutaten, die sich nicht umrechnen lassen,
  werden markiert („Nährwerte unvollständig“) und nicht als 0 kcal gezählt.
- **Rezept-Import:** Ein Link zu einer Rezeptseite (auch Cookidoo-Seiten, die öffentlich lesbar sind) füllt das
  Rezeptformular vor; gespeichert wird erst nach dem Prüfen. Ein Login bei Cookidoo ist nicht nötig.
- **Essensplan:** Wochenansicht mit frei einstellbaren Mahlzeiten, Mitessern und Portionen, Drag & Drop und
  „Woche auffüllen“. Kalorienziele (Küche → Einstellungen) liefern Portionsvorschläge und die Tagesbilanz je Person.
- **Vorrat:** Menge, Ort und MHD; „Gekocht“ zeigt vorher, was abgebucht wird (ältestes MHD zuerst), Mengen sind
  korrigierbar. Täglich um 8 Uhr kommt eine Sammelnachricht, wenn etwas in den nächsten zwei Tagen abläuft.
- **Einkauf:** „Aus Plan erzeugen“ = Bedarf aller ungekochten Mahlzeiten minus Vorrat, dazu freie Artikel. Gekaufte
  Artikel werden über „Einräumen“ in den Vorrat übernommen.
- **Bring!:** Unter Küche → Einstellungen E-Mail und Passwort des Bring!-Kontos eintragen und eine Liste wählen. Das
  Passwort wird verschlüsselt gespeichert (Schlüssel wie bei den Sicherungen). „An Bring! senden“ schickt die offenen
  Artikel; was bei Bring! nicht mehr „zu kaufen“ ist, gilt als gekauft (Knopf und alle 15 Minuten). Nestwerk nutzt die
  inoffizielle Web-API von Bring!; fällt sie aus, bleibt die Einkaufsliste unverändert und die Seite zeigt eine Meldung.
- **Rechte:** `KITCHEN_VIEW`, `KITCHEN_RECIPES_EDIT`, `KITCHEN_PLAN`, `KITCHEN_SHOPPING`, `KITCHEN_SETTINGS`; die Rolle
  „Benutzer“ bekommt alle außer den Einstellungen.
- **KI:** Über MCP legt ein Assistent Rezepte aus Foto oder Text an (`create_recipe` mit Zutaten als Textzeilen), plant
  Mahlzeiten und liest Einkaufsliste und Vorrat (`search_foods`, `plan_meal`, `shopping_list`, `list_pantry` u. a.). Auch Ändern ist möglich (`update_recipe`, `move_meal`, `set_meal_eaters`), ebenso Auffüllen und Kochen (`fill_meal_plan`, `cook_meal`), Einkauf (`generate_shopping_list`, `toggle_shopping_item`, `stow_shopping_item`, Bring!) und Vorrat (`add_pantry_item`, `update_pantry_item`, `take_from_pantry`). Den Haushaltsplan befüllt er mit `create_chore`, `update_chore`, `add_chore_date`, `assign_chore_task` und `book_chore`.

### KI-Anbindung (MCP)

Nestwerk enthält einen MCP-Server unter `https://<nestwerk>/mcp`. Darüber kann ein KI-Assistent wie Claude
Termine, ToDos, den Haushaltsplan, die Küche, Kontakte, Zeiten und Benachrichtigungen lesen und bearbeiten. Löschen
geht nur mit ausdrücklich dafür vorgesehenen, als destruktiv markierten Tools: Termine (`delete_event`) und
Terminkategorien (`delete_event_category`); dazu Verknüpfungen lösen, einzelne Terminvorkommen absagen und feste
Haushalts-Termine entfernen. Termine lassen sich wie im Kalender auf neue Zeiten legen (`reschedule_event`,
`reschedule_event_series`), Hausarbeiten auf eine andere Uhrzeit (`reschedule_chore_task`).

- **Umfang:** ToDos mit Unterstruktur, Verknüpfungen, Beobachtern, Wiedervorlage und Verlauf; Termine samt
  einzelner Serienvorkommen; Kontakte; Haushaltsplan mit Auswertung, Gutschrift-Korrekturen, Belohnungen und
  iCal-Abos; Küche inklusive Einstellungen, Rezept-Import per Adresse und Barcode-Suche; Zeiten (Blocker) und
  Ruhe-Ziele; Benachrichtigungs-Einstellungen; Anhänge (Text lesen, kleine Dateien hinzufügen); Export und Import
  als Text mit Vorschau.
- **Verwaltung:** Mit den passenden Verwaltungsrechten auch Benutzerrollen, Familienmitglieder, Rollen und
  Zugriffsregeln, Sicherungsplan, Updates und globale Benachrichtigungs-Einstellungen. Es gelten dieselben Regeln
  gegen Rechteausweitung wie in der Weboberfläche.
- **Bewusst nur in der Weboberfläche:** Passwörter und Benutzer anlegen, KI-Token, Bring!-Anmeldung, Zugangsdaten
  für Sicherungsziele, Verschlüsselungs-Passphrase, Wiederherstellen und Löschen von Sicherungen, PINs und
  Tablet-Kopplung, Push-Geräte einrichten.

- Der Assistent handelt immer als ein Benutzer und hat genau dessen Rechte. Ein Administrator gibt einer Rolle das
  Recht „KI-Zugang nutzen“. Danach verbindet sich jeder Benutzer auf einem von zwei Wegen:
- **Mit dem Nestwerk-Login (OAuth 2.1):** Der Client bekommt nur die Adresse. Nestwerk ist zugleich Authorization
  Server: Der Client findet ihn über `/.well-known/oauth-protected-resource/mcp`, registriert sich selbst
  (`/oauth2/register`, RFC 7591), der Benutzer meldet sich im Browser an und stimmt zu. Zugriffstoken gelten eine
  Stunde, Refresh-Tokens 90 Tage und werden bei jeder Nutzung erneuert. Unter „KI-Zugänge → Verbundene Assistenten“
  lässt sich jede Verbindung trennen. Für claude.ai (Konnektoren) muss Nestwerk per HTTPS aus dem Internet
  erreichbar sein; Claude Code klappt auch im Heimnetz:

  ```bash
  claude mcp add --transport http nestwerk https://<nestwerk>/mcp
  # danach in Claude Code: /mcp → nestwerk → Authenticate
  ```

- **Mit Zugangstoken:** Unter „KI-Zugänge“ ein eigenes Token anlegen. Es wird nur einmal angezeigt und lässt sich
  jederzeit widerrufen.

  ```bash
  claude mcp add --transport http nestwerk https://<nestwerk>/mcp --header "Authorization: Bearer nw_…"
  ```

  Andere Clients brauchen die Adresse und den Header `Authorization: Bearer <token>`. Die Seite „KI-Zugänge“ zeigt
  passende Schnipsel mit der Adresse aus `NESTWERK_BASE_URL`.
- Der MCP-Server arbeitet zustandslos. Am Reverse Proxy ist nichts Besonderes nötig, außer dass der Header
  `Authorization` durchgereicht wird und `X-Forwarded-Proto`/`X-Forwarded-Host` stimmen – daraus bildet Nestwerk
  die OAuth-Adressen.

### Update

**In Nestwerk:** Benutzermenü → „Updates“ (Recht „Updates verwalten“). Nestwerk prüft alle 6 Stunden, ob es eine
neue Version gibt, und meldet sie den Admins. Ein Klick auf „Jetzt aktualisieren“ beauftragt den Dienst `updater`:

1. Sicherung mit der laufenden Version (Art „vor einem Update“),
2. App- und Updater-Image der neuen Version laden, Installationsdateien (`compose.*.yaml`, `update.sh`) aus dem
   Updater-Image übernehmen, `NESTWERK_VERSION` in `.env` setzen,
3. Neustart und Warten, bis die App gesund ist.

Kommt die neue Version nicht hoch, startet der Updater die alte wieder; hatte die neue die Datenbank schon migriert,
spielt er vorher die Sicherung aus Schritt 1 ein. Ergebnis und Protokoll stehen auf der Seite „Updates“.

- **Kanal:** *Releases* (Tags `vX.Y.Z`, Standard) oder *main* (jeder erfolgreiche Build von main, Image-Tag
  `sha-<commit>`).
- **GitHub-Token:** Solange das Repository privat ist, braucht die Versionsprüfung einen fein granularen Token nur für
  `chrisbad/nestwerk` mit Lesezugriff auf *Contents* und *Actions*. Er wird verschlüsselt gespeichert.
- **Sicherheit:** Die App hat keinen Zugriff auf Docker. Sie legt nur einen Auftrag mit der Zielversion ab; der
  Updater prüft ihn und nimmt alles andere aus seinem eigenen Image.

**Per SSH** (erste Einrichtung des Updaters, oder wenn die App nicht mehr startet):

```bash
sudo GITHUB_TOKEN=… /opt/nestwerk/update.sh          # eingestellte Version (Standard: latest)
sudo GITHUB_TOKEN=… /opt/nestwerk/update.sh 1.4.0    # bestimmte Version
```

`update.sh` holt die Installationsdateien der Zielversion (bei privatem Repository mit `GITHUB_TOKEN`), ergänzt
`NESTWERK_DIR` in `.env`, sichert mit der laufenden Version, lädt die neuen Images und startet neu.

**Bestehende Installation auf den Updater umstellen (einmalig):** die neue `update.sh` aus dem Repository nach
`/opt/nestwerk/` kopieren und wie oben ausführen. Danach läuft der Dienst `updater`, und Updates gehen in Nestwerk.
Hinter nginx-proxy zusätzlich `proxy/nestwerk-vhost.conf` erneut nach `/opt/proxy/vhost.d/<domain>` kopieren und
`docker exec nginx-proxy nginx -s reload` ausführen: Dann sehen alle während des Neustarts eine Wartungsseite.

**Regeln für neue Versionen**, damit Updates nie Daten verlieren:
- Migrationen nur hinzufügend oder mit Datenübernahme; `MigrationUpgradeTest` migriert Bestandsdaten von V18 auf den
  neuesten Stand und vergleicht die Zeilen. Bei jedem Release einen weiteren Snapshot unter
  `nestwerk-app/src/test/resources/db/upgrade/` ergänzen.
- Neue Variablen in den Compose-Dateien immer mit Standardwert (`${VAR:-…}`): Ein Update ändert `.env` nur bei
  `NESTWERK_VERSION`.

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
