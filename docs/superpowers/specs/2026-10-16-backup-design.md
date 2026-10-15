# Sicherung, Ex-/Import und Server-Installation

Stand: 2026-10-16 · Status: umgesetzt

## Ziel

Nestwerk soll sich aus der App heraus regelmäßig sichern und wiederherstellen lassen. Die Daten sollen in gängigen
Formaten aus- und einlesbar sein, und die Installation auf einem entfernten Server soll mit einem Skript gelingen,
auch wenn dort weitere Dienste laufen.

## Entscheidungen

| Thema | Entscheidung |
|---|---|
| Sicherungsformat | ZIP mit `manifest.json` (Flyway-Version, Zeilen, SHA-256 je Datei), `db/<tabelle>.copy` (Postgres `COPY` per pgjdbc `CopyManager`) und `attachments/<key>`. Kein `pg_dump`, weil das JRE-Image keinen passenden Client hat. |
| Konsistenz | alle Tabellen in einer `REPEATABLE READ READ ONLY`-Transaktion. Danach nur die Anhänge, auf die dieser Stand verweist. |
| Wiederherstellen | gleiche Schemaversion: `TRUNCATE … RESTART IDENTITY CASCADE`, `COPY FROM` in Fremdschlüssel-Reihenfolge und `setval` in einer Transaktion. Ältere Version: Flyway `clean` und `migrate` bis zur Version der Sicherung, laden, dann aktuell migrieren. Neuere Version: abgelehnt. Vorher immer eine Sicherheitskopie. Anhänge werden erst nach dem Commit ausgetauscht. |
| Gleichzeitigkeit | immer nur ein Vorgang (Sperre in der App). Die App läuft als eine Instanz. |
| Zeitplan | täglich oder wöchentlich zu einer Uhrzeit. Verpasste Termine werden einmal nachgeholt. Beim Einschalten gibt es keinen Sofortlauf. |
| Aufbewahrung | die letzten N, dazu je eine pro Woche und Monat. Die Zeit stammt aus dem Dateinamen, hochgeladene Dateien bleiben. Gilt lokal und je Ziel. |
| Ziele | externe Platte (eingebundenes Verzeichnis, Schreiben nur mit Markierungsdatei), WebDAV (JDK-`HttpClient`, Upload als `.part` + `MOVE`), SFTP (JSch, Host-Key wird beim ersten Test gelernt und danach streng geprüft). |
| Verschlüsselung | optional für die Kopien an den Zielen: AES-256-GCM in 1-MiB-Blöcken, PBKDF2 aus einer Passphrase, Kennung `NWENC1`. Die lokale Kopie bleibt unverschlüsselt (gleicher Server wie die Datenbank). |
| Zugangsdaten | AES-GCM mit `NESTWERK_SECRET_KEY` in `app_settings`. Ohne gesetzten Wert erzeugt die App eine Schlüsseldatei mit Rechten `rw-------`. |
| Meldungen | Fehlschlag oder Warnung → `BACKUP_FAILED` an alle mit `BACKUP_MANAGE`, Erfolg nur auf Wunsch. Die Startseite warnt ab 3 Tagen ohne Sicherung. |
| Fachformate | Kalender iCal (eigener Writer mit VTIMEZONE, Serien als RRULE, Absagen als EXDATE, Änderungen als RECURRENCE-ID; Lesen mit ical4j, Abgleich über UID), Kontakte vCard 4.0 (ez-vcard) und CSV (commons-csv, `;` + BOM, Spalten aus Google/Outlook erkannt), ToDos CSV mit Namen statt IDs. |
| Import-Ablauf | Upload, Vorschau, Bestätigung. Jeder Datensatz läuft in einer eigenen Transaktion: in der Vorschau zurückgerollt, beim Import bestätigt. Fehlerhafte Zeilen werden übersprungen und begründet. |
| Image | GitHub Actions baut und testet bei `v*`-Tags und auf `main` und veröffentlicht nach `ghcr.io/chrisbad/nestwerk` (amd64 und arm64; der Build-Schritt läuft plattformneutral). Die Version kommt über `-Drevision` aus dem Tag. |
| Reverse Proxy | zentraler Stack `proxy/` mit nginx-proxy und acme-companion für alle Dienste im Netz `proxy`. `vhost.d/<domain>` hebt die Upload-Grenze und die Zeitlimits an. Ohne diesen Stack läuft Nestwerk direkt auf `127.0.0.1:8080`. |
| Installation | `install.sh` aus dem Release-Paket: installiert Docker bei Bedarf, richtet den Proxy ein oder erkennt einen vorhandenen, erzeugt alle Geheimnisse und meldet sich bei ghcr.io an, falls nötig. Über Umgebungsvariablen läuft es ohne Rückfragen. `update.sh` sichert zuerst mit der alten Version (`--nestwerk.backup.run-and-exit=true`) und lädt dann das neue Image. |

## Später denkbar

- Wiederherstellen direkt von einem Ziel (ohne Herunter- und Hochladen)
- S3-kompatibles Ziel
- iCal-Abo-Feed (Phase 3) auf Basis von `CalendarIcs.write`
