# Nestwerk Teilprojekt 2: ToDo-Backlog, ACL, Benachrichtigungen

Stand: 2026-10-14 · Status: abgestimmt im Brainstorming, zur Prüfung

## Ziel

Ein Familien-Backlog für Aufgaben, die irgendwann erledigt werden müssen, aber nicht sofort. Nichts soll aus dem Blick geraten; Nestwerk erinnert aktiv (In-App, E-Mail, Browser-Push). Dazu werden Rechte und ein regelbasiertes ACL vollständig in die Datenbank verlagert und eine allgemeine Benachrichtigungsfunktion gebaut, die später auch der Kalender (Teilprojekt 1, Phase 3) nutzt.

## Lieferstufen (eine Spec, getrennt lieferbar)

0. **Rechte in der DB:** Rechtekatalog als Tabelle, ACL-Regeln, Matrix in der Rollenverwaltung
1. **ToDo-Kern:** ToDos, Kontakte, Zuständige, Liste mit Filtern, Detailseite, Start-Kachel „Meine ToDos“
2. **Struktur:** Unteraufgaben (beliebig tief) und Baum, Verknüpfungen, Wiederholung, Board
3. **Zusammenarbeit:** Kommentare, Verlauf, @Erwähnungen, Beobachter, Anhänge (ToDos und Kontakte)
4. **Benachrichtigungen In-App:** Kern, Zeitplaner, Glocke, alle Anlässe und Ereignisse
5. **Kanäle:** E-Mail, Browser-Push, Web-App-Manifest, Einstellungsebenen
6. **Kalender-Anbindung:** Fälligkeiten im Kalender, Termin aus ToDo

Jede Stufe endet mit lauffähigem, getestetem Stand.

## Entscheidungen

- **Zeitangaben** sind Datumswerte ohne Uhrzeit: `frühestens ab`, `spätestens bis`, `Wiedervorlage` (bei jedem ToDo möglich). Erinnert wird zu einer festen, vom Admin einstellbaren Tageszeit (Standard 08:00, Zeitzone `nestwerk.timezone`).
- **Status:** Offen, In Arbeit, Erledigt, Verworfen. Abgeleitet (nicht gesetzt): ruhend (`ab` in der Zukunft), blockiert (offener Blocker), überfällig (`bis` vorbei, offen), bald fällig (`bis` in ≤ 7 Tagen).
- **Felder:** Überschrift (1–200), Beschreibung (Markdown, ≤ 20 000), Priorität (niedrig/normal/hoch/dringend), Schlagworte (frei, kleingeschrieben, ≤ 40 Zeichen), Wiederholungsregel (RRULE wie Kalender), privat.
- **Zuständige:** beliebig viele, gemischt aus Familienmitgliedern, Rollen (als Gruppe) und freien Kontakten; ToDos dürfen auch ohne Zuständige existieren. Pro ToDo wählbar: **einer reicht** oder **jeder einzeln**. Bei „jeder einzeln“ wird beim Speichern festgehalten, wer abhaken muss (Mitglieder direkt; Rollen aufgelöst in die Mitglieder ihrer Benutzer; Kontakte zählen als eine Abhakstelle, die Verantwortlicher oder Zuständige mit Konto für sie abhaken). Spätere Rollenänderungen verändern laufende ToDos nicht.
- **Verantwortlicher:** genau ein Benutzer, standardmäßig der Ersteller; wird immer erinnert; kann Beobachter verwalten (sofern ACL es erlaubt).
- **Beobachter:** Benutzer, die alle Benachrichtigungen zum ToDo erhalten.
- **Verknüpfungen (Jira-Vorbild):** Unteraufgabe (Hierarchie, beliebig tief), blockiert / wird blockiert von, verwandt, dupliziert / Duplikat von. Kreise (Hierarchie und Blockaden) werden abgelehnt. Abhaken trotz offenem Blocker: Warnung mit Bestätigung, dann erlaubt. Markieren als Duplikat setzt das Duplikat auf Verworfen.
- **Wiederholung:** Beim Erledigen (bzw. Verwerfen) eines wiederkehrenden ToDos entsteht ein neues ToDo; `bis` wird per RRULE auf das nächste Vorkommen gesetzt (ohne `bis` dient `ab` als Anker, ohne beides das Erledigungsdatum), `ab` und `Wiedervorlage` verschieben sich um denselben Abstand. Unteraufgaben werden offen mitkopiert, Kommentare/Anhänge/Verlauf bleiben beim alten ToDo. Alle Exemplare tragen dieselbe `series_id`.
- **Kontakte:** Name, Kategorie, Firma, Telefon, E-Mail, Adresse (Straße, PLZ, Ort), URL, Notiz, Anhänge.
- **Kalender:** ToDos mit `bis` erscheinen als abschaltbarer Ganztags-Eintragstyp; aus einem ToDo kann ein Termin angelegt werden, beide bleiben verknüpft.
- **Technik:** SortableJS (vendored) für das Board; Spring `@Scheduled` für den Zeitplaner; `nl.martijndwars:web-push` (BouncyCastle) für Push; `spring-boot-starter-mail` für E-Mail; commonmark-java plus OWASP Java HTML Sanitizer für Markdown; Anhänge im Dateisystem (Volume), Metadaten in der DB.

## Rechte und ACL

### Rechtekatalog in der Datenbank (alle Module)

- Tabelle `permissions` (key PK, module, label, description, sort_order, system); per Migration befüllt mit allen bestehenden Rechten (Verwaltung, Kalender) und den neuen.
- `role_permissions.permission` wird Fremdschlüssel auf `permissions.key`.
- Der Code kennt nur Schlüssel-Konstanten. `PermissionCatalog`/Enums werden durch ein Repository plus Konstanten ersetzt. Beim Start wird geprüft, dass jeder im Code verwendete Schlüssel existiert, sonst Abbruch mit klarer Meldung.
- Bezeichnung und Beschreibung sind in der UI änderbar; Systemrechte nicht löschbar.
- Neues Verwaltungsrecht `NOTIFICATION_SETTINGS_MANAGE` (globale Kanal-Standards, E-Mail-/Push-Konfiguration). Die ACL-Matrix wird über das bestehende Recht `ACCESS_ROLE_MANAGE` gepflegt, ebenso Bezeichnungen im Rechtekatalog.

### Regelbasiertes ACL (ToDos, Kontakte)

- Regel (`acl_rules`): **Rolle** darf **Aktion** auf **Objekttyp**, wenn **Beziehung** besteht. Kataloge `acl_actions` (objekttyp, key, label, sort_order) und `acl_relations` (key, label) liegen in der DB; ihre Bedeutung implementiert der Code.
- Objekttypen: `TODO`, `CONTACT`.
- Aktionen `TODO`: `VIEW`, `CREATE`, `CREATE_PRIVATE`, `EDIT`, `DELETE`, `CHANGE_STATUS`, `ASSIGN`, `CHANGE_RESPONSIBLE`, `WATCH`, `MANAGE_WATCHERS`, `COMMENT`, `ATTACH`, `LINK`, `NOTIFY_OVERRIDE`. Aktionen `CONTACT`: `VIEW`, `CREATE`, `EDIT`, `DELETE`, `ATTACH`.
- Beziehungen: `ANY` (jedes nicht private Objekt), `RESPONSIBLE`, `ASSIGNED` (über eigenes Mitglied oder eine eigene Rolle), `WATCHING`, `CREATOR`. Für `CONTACT` nur `ANY` und `CREATOR`. Für `CREATE`/`CREATE_PRIVATE` ist nur `ANY` sinnvoll.
- Auswertung: erlaubt, wenn mindestens eine Regel einer eigenen Rolle passt (nur Erlaubnisse, keine Verbote). Die Administrator-Rolle darf alles.
- Private ToDos sind über `ANY` nie erreichbar, nur über `RESPONSIBLE`, `ASSIGNED`, `WATCHING` (und für Administratoren).
- Zentrale Prüfung im `AccessPolicy`-Service: Einzelprüfung `can(user, action, object)` und für Listen eine Übersetzung der Sichtbarkeit in eine DB-Bedingung (kein Filtern im Speicher).
- Eskalationsschutz: Nicht-Administratoren dürfen nur Regeln vergeben, die sie selbst haben.
- UI: Matrix je Rolle und Objekttyp (Zeilen Aktionen, Spalten Beziehungen) in der Rollenverwaltung, mit Vorlagen „Kind“, „Elternteil“, „Gast (nur lesen)“.
- Startbelegung Rolle „Benutzer“: `VIEW` ANY; `CREATE` ANY; `CREATE_PRIVATE` ANY; `EDIT`, `DELETE`, `LINK`, `ASSIGN` bei RESPONSIBLE; `CHANGE_STATUS` bei ASSIGNED und RESPONSIBLE; `COMMENT`, `ATTACH`, `WATCH` bei ANY; Kontakte `VIEW` ANY. Übriges nur Administrator.

### Sichtbarkeit

- Kein Recht auf ein Objekt wirkt überall wie „nicht gefunden“ (404): Liste, Board, Baum, Suche, Detail, Anhang-Download, Kalender, Benachrichtigungen.
- Unsichtbare Unteraufgaben erscheinen nur als Anzahl („1 weitere Unteraufgabe“), unsichtbare Verknüpfungen werden ausgelassen, Blockierwarnungen nennen dann nur die Anzahl.

## Datenmodell (Flyway V4 ff.)

- `permissions`, `acl_actions`, `acl_relations`, `acl_rules` (role_id, object_type, action, relation)
- `contacts` (id, name, category, company, phone, email, street, postal_code, city, url, note, created_by, created_at, updated_at, version)
- `todos` (id, title, description, status, priority, available_from, due_on, follow_up_on, remind_days_before, rrule, series_id, completion_mode, private, parent_id, responsible_user_id, created_by, created_at, updated_at, completed_at, completed_by, version)
- `todo_tags` (todo_id, tag)
- `todo_assignees` (id, todo_id, member_id, role_id, contact_id; CHECK genau eine Spalte gesetzt)
- `todo_completions` (todo_id, member_id bzw. contact_id, required, completed_at, completed_by)
- `todo_links` (id, source_id, target_id, type `BLOCKS`/`RELATES`/`DUPLICATES`; eindeutig je Paar und Typ)
- `todo_watchers` (todo_id, user_id)
- `todo_comments` (id, todo_id, author_id, body, created_at, edited_at)
- `todo_history` (id, todo_id, actor_id, at, field, old_value, new_value) – nur anfügen
- `attachments` (id, owner_type `TODO`/`COMMENT`/`CONTACT`, owner_id, filename, content_type, size, storage_key, uploaded_by, uploaded_at)
- `todo_event_links` (todo_id, event_id)
- `notifications` (id, user_id, type, source_type, source_id, due_key, title, body, link, created_at, read_at; eindeutig über user_id, type, source_type, source_id, due_key)
- `notification_deliveries` (id, notification_id, channel, status, attempts, next_attempt_at, sent_at, last_error)
- `notification_preferences` (scope `GLOBAL`/`USER`/`TODO`, scope_id, type, channel, enabled)
- `push_subscriptions` (id, user_id, endpoint, p256dh, auth, user_agent, created_at)
- `app_settings` (key, value) – u. a. VAPID-Schlüssel, Erinnerungsuhrzeit, Standard-Vorlauf (2 Tage)

**Folge für Teilprojekt 1:** Die Pläne `2026-10-13-phase-3-erinnerungen-feed.md` und `2026-10-13-phase-4-caldav.md` verwenden V4/V5 und eigene Benachrichtigungstabellen. Vor ihrer Umsetzung werden sie auf die dann freien Migrationsnummern und die hier definierten `notifications`-Tabellen umgestellt.

## Oberfläche

- **Navigation:** „ToDos“ und „Kontakte“ (je nach Recht), Glocke mit Zähler.
- **`/todos` Liste:** Umschalter Liste | Board | Baum, Filterleiste, Suche, „ToDo“-Knopf. Gruppen Überfällig, Aktuell, Bald fällig, Ruhend, Ohne Datum; Sortierung Priorität, dann Fälligkeit. Zeile mit Abhaken (htmx; bei Blocker Bestätigungsdialog; bei „jeder einzeln“ nur eigener Haken, Anzeige „2 von 3“), Titel, Merkmalen (Priorität, blockiert, Fortschritt, privat, wiederkehrend), Fälligkeit, Zuständigen.
- **Filter** (für Liste, Board, Baum; in der URL): Beteiligung (meine, verantwortet, beobachtet, alle), Zuständig (Mitglied/Rolle/Kontakt), Status, Zeitlage, Priorität, Schlagwort, Text. Erledigt/Verworfen standardmäßig ausgeblendet.
- **`/todos/board`:** Spalten Offen | In Arbeit | Erledigt, Verworfen einklappbar; Ziehen per SortableJS mit htmx-Statuswechsel, bei fehlendem Recht zurückspringen plus Meldung; mobil wischbare Spalten und Status-Menü je Karte.
- **`/todos/tree`:** aufklappbare Hierarchie (`<details>`), Unteraufgaben per htmx nachgeladen, Fortschrittsbalken je Ebene.
- **`/todos/{id}`:** Kopf (Titel, Status, Priorität, „Ich kümmere mich“, Beobachten, Menü Kopieren / Termin anlegen / Löschen); Hauptbereich (Beschreibung, Unteraufgaben, Verknüpfungen gruppiert, Hinzufügen per Suche); Seitenleiste (Zuständige mit kombinierter Suche, Abhakmodus, Verantwortlicher, Beobachter, ab/bis/Wiedervorlage/Vorlauf, Wiederholung, Schlagworte, verknüpfte Termine, Benachrichtigungs-Ausnahmen); Zeitstrahl aus Kommentaren und Verlauf mit Filter, @Erwähnung mit Vorschlägen, Anhänge per Drag & Drop oder Kamera, Bildvorschau. Ohne Recht nur Lesedarstellung.
- **`/todos/new`:** kurzes Formular (Titel, Beschreibung, Zuständige, bis, Priorität), „Mehr Optionen“ für den Rest; als Unteraufgabe mit vorbelegtem übergeordnetem ToDo.
- **`/contacts`:** Kacheln nach Kategorie, Suche; Detail mit `tel:`/`mailto:`, OpenStreetMap-Link, URL, Anhängen, offenen ToDos des Kontakts.
- **Startseite:** Kachel „Meine ToDos“ (überfällig und aktuell, max. 5, abhakbar, Link zur gefilterten Liste).
- **Kalender:** ToDo-Fälligkeiten als Ganztags-Einträge mit Häkchen-Symbol, Schalter „ToDos anzeigen“ (im Browser gemerkt); „Termin aus ToDo“ füllt Titel und Mitglieder vor, danach gegenseitige Verweise.
- **Verwaltung:** Rechtekatalog-Seite, ACL-Matrix in der Rollenverwaltung, `/admin/notifications`.

## Benachrichtigungen

- **Kern** (Paket `notification`, modulunabhängig): `notify(Empfänger, Anlass, Quelle, Stichtag, Text, Link)` legt idempotent an, löst die Kanäle über die Einstellungsebenen auf und erzeugt Versandaufträge.
- **Zeitplaner** (minütlich, wirksam einmal täglich zur eingestellten Uhrzeit, holt Versäumtes nach; Stichtag im eindeutigen Schlüssel):
  - „ab erreicht“: `available_from` = heute
  - „vor Fälligkeit“: `due_on − Vorlauf` = heute
  - „überfällig“: erster Tag nach `due_on`, danach wöchentlich, solange offen
  - „Wiedervorlage“: `follow_up_on` = heute; Aktionen Erledigt, Später (+1 Woche / +1 Monat / Datum), Verwerfen
- **Ereignisse** (direkt aus den Services): zugewiesen, neuer Kommentar, Statuswechsel, Erwähnung, nicht mehr blockiert. Der Auslöser wird nie selbst benachrichtigt.
- **Empfänger:** Verantwortlicher und Beobachter immer; Zuständige mit Konto, zuständige Rollen über ihre Benutzer; bei „jeder einzeln“ nur, wer noch nicht abgehakt hat. Jeder Empfänger muss das ToDo laut ACL sehen dürfen.
- **Kanäle:** In-App immer an (Glocke, Dropdown, `/notifications`, Zähler per htmx alle 60 s). E-Mail (HTML mit Link; Tageslimit, darüber Sammel-Mail). Push (Service Worker `/sw.js`, Abo pro Gerät mit Testnachricht, Abos mit 404/410 werden gelöscht).
- **Einstellungsebenen:** Admin-Standard je Anlass × Kanal → Benutzer-Überschreibung (`/settings/notifications`, „Standard/an/aus“) → ToDo-Überschreibung (mit Recht `NOTIFY_OVERRIDE`). Die feinste Ebene gewinnt.
- **Versand:** asynchron über den Postausgang, bis zu 5 Versuche mit wachsendem Abstand; Fehler im Log und in einer Admin-Liste.
- **Konfiguration:** `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASSWORD`, `MAIL_FROM`, `NESTWERK_BASE_URL`. VAPID-Schlüssel erzeugt die App beim ersten Start und speichert sie in `app_settings`. Nicht konfigurierte Kanäle werden ausgeblendet.
- **Manifest:** `manifest.webmanifest` mit Name und Nest-Symbol (installierbar; auf iOS Voraussetzung für Push).

## Fehlerbehandlung

- Regelverstöße (Kreis, fehlende Regel, ab nach bis, ungültige RRULE, Längen) ergeben eine Formular- bzw. htmx-Meldung, nie einen 500er.
- Gleichzeitige Bearbeitung: Versionsprüfung, Meldung „wurde inzwischen von … geändert“ mit Neu-Laden; eigene Eingabe bleibt erhalten.
- Kein Zugriff: 404.
- Anhänge: Bilder, PDF, Office, Text bis 20 MB; Prüfung von Endung und Inhalt (Magic Bytes); Auslieferung mit `Content-Disposition` und `X-Content-Type-Options: nosniff`, inline nur Bilder und PDF; Speicherschlüssel zufällig. Speicherort konfigurierbar (`NESTWERK_ATTACHMENT_DIR`, Standard `/data/attachments`), in `compose.prod.yaml` als Volume, README-Backup ergänzt.
- Markdown wird serverseitig gerendert und bereinigt.

## Tests

Testcontainers-Integrationstests, testgetrieben:

- Migration: Rechtekatalog vollständig, Rollenrechte bleiben erhalten, Startprüfung schlägt bei fehlendem Schlüssel an.
- ACL: jede Beziehung, private ToDos nie über `ANY`, Administrator, Eskalationsschutz; Listen-Abfrage liefert dasselbe Ergebnis wie die Einzelprüfung.
- ToDo-Logik: Zeitlage, einer reicht / jeder einzeln (auch über Rolle), Wiederholung inklusive Unteraufgaben, Kreiserkennung, Blockierwarnung, Duplikat verwirft.
- Sichtbarkeit in allen Ausgabewegen (Liste, Board, Baum, Suche, Detail, Anhang, Kalender, Benachrichtigungen).
- Benachrichtigungen: keine Doppelten (auch beim Nachholen), Empfänger und deren Sichtbarkeit, Auflösung der Einstellungsebenen, Wiederholversuche, Löschen abgelehnter Push-Abos; E-Mail gegen GreenMail, Push gegen Mock-Endpunkt.
- UI: MockMvc für Seiten und htmx-Teilantworten; manuelle Browserprüfung (Handy, Dark Mode, Board-Ziehen, Push-Abo).

## Pakete

`club.badernet.nestwerk.{security (Katalog, AccessPolicy), todo, contact, attachment, notification, web}`.

## Außerhalb des Umfangs

Frei definierbare Workflows, Zeiterfassung, Gantt, Import aus Jira/Trello, ACL pro Einzelobjekt, native Push-Apps, Haushalt-Modul (eigene Spec).
