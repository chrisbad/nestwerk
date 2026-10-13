# Nestwerk Teilprojekt 1: Rollen/Rechte, Familienmitglieder, Layout, Kalender

Stand: 2026-10-13 · Status: Entwurf zur Prüfung

## Ziel

Grundlage für alle Module plus der Familienkalender als erstes Fachmodul: frei definierbare Rollen mit Einzelrechten, Familienmitglieder als Zuweisungsziel, gemeinsames Layout mit rechteabhängiger Navigation, Kalender mit Wiederholungsregeln, privaten Terminen, Erinnerungen, iCal-Feed und CalDAV-Sync für Android.

## Phasen (ein gemeinsamer Plan, getrennt lieferbar)

1. **Basis:** Rollen/Rechte, Mitglieder, Layout/Navigation
2. **Kalender-Kern:** Termine, RRULE-Wiederholungen, Zuweisung, private Termine, Monats-/Wochen-/Agenda-Ansicht
3. **Erinnerungen und iCal-Feed**
4. **CalDAV-Server** (Zwei-Wege-Sync, z. B. DAVx5): aufwändigste Phase, baut auf iCal-UIDs/RRULE aus Phase 2 auf

Phase 1 und 2 sind nutzbar, ohne dass 3 und 4 fertig sind. Jede Phase endet mit lauffähigem, getestetem Stand.

## Entscheidungen

- Rollen werden vom Admin in der UI angelegt; ein Benutzer kann mehrere Rollen haben, effektive Rechte = Vereinigung.
- Rechte sind einzelne Aktionen (z. B. `CALENDAR_EVENT_CREATE`); Enums im Code (je Modul ein `…Permission`-Enum), Zuordnung Rolle → Rechte in der DB.
- Familienmitglieder (Name, Farbe) sind getrennt vom Login, optional mit einem Benutzerkonto verknüpft.
- Termine: Einzel-/ganztägig, volle iCal-RRULE, Ausnahmen/Änderungen einzelner Vorkommen, 0..n zugewiesene Mitglieder, Markierung „privat“ (nur Ersteller und Administrator sehen den Termin).
- Ansichten: Monat, Woche, Agenda/Liste.
- Android-Abgleich: CalDAV, Zwei-Wege.
- ToDo und Haushalt bleiben eigene spätere Specs.

## Datenmodell (Flyway V2 ff.)

Phase 1:
- `roles` (id, name unique, description, system_role)
- `role_permissions` (role_id, permission)
- `user_roles` (user_id, role_id)
- `family_members` (id, name, color, user_id nullable unique)
- Bestehende `users.role` wird auf Systemrollen „Administrator“ und „Benutzer“ migriert, Spalte danach entfernt.

Phase 2:
- `calendar_events` (id, uid unique [iCal-UID], title, description, location, starts_at, ends_at, all_day, timezone, rrule nullable, private, created_by, version/etag, created_at, updated_at)
- `calendar_event_members` (event_id, member_id)
- `calendar_event_exceptions` (id, event_id, recurrence_id, cancelled, überschriebene Felder)

Phase 3:
- `calendar_event_reminders` (event_id, minutes_before), `notifications` (user_id, event_id, occurrence, due_at, read_at)
- `calendar_feed_tokens` (user_id, token_hash, created_at)

Phase 4:
- Sync-Metadaten (ctag/sync-token, etag je Ereignis, Löschmarker für Tombstones)

## Verhalten

- Systemrolle „Administrator“: implizit alle Rechte (auch künftiger Module), nicht löschbar/bearbeitbar.
- Mindestens ein aktiver Administrator muss immer existieren.
- Authorities des `UserDetails` = Rechte aller Rollen; `/admin/**` und Kalender-Endpunkte per `@PreAuthorize`.
- Private Termine werden serverseitig herausgefiltert (Ansichten, Feed, CalDAV), nicht nur ausgeblendet.
- iCal-Feed: pro Benutzer eigene Token-URL, widerrufbar, nur lesend, enthält nur für diesen Benutzer sichtbare Termine.
- CalDAV: Authentifizierung per Basic Auth mit eigenem App-Passwort je Benutzer (nicht das Login-Passwort), Rechteprüfung wie in der Web-UI.
- Wiederholungen werden mit einer iCal-Bibliothek (ical4j) expandiert, nicht selbst implementiert.

## Annahmen (bitte bestätigen oder korrigieren)

- Erinnerungen erscheinen **nur in der App** (Benachrichtigungsliste/Banner); E-Mail oder Push sind nicht Teil dieser Version.
- CalDAV wird selbst implementiert (Spring MVC, benötigte Methoden: PROPFIND, REPORT, GET, PUT, DELETE, OPTIONS); eine fertige Server-Bibliothek ist für diese Aufgabe nicht vorgesehen.
- Zeitzone je Termin, Standard `Europe/Berlin`.

## Pakete

`club.badernet.nestwerk.{user, role, member, security, web, calendar, notification}`; CalDAV in `calendar.caldav`.

## Tests

Testcontainers-Integrationstests: Migration V1→V2, Rechteauflösung bei mehreren Rollen, Letzter-Admin-Schutz, Zugriff je Recht, RRULE-Expansion inkl. Ausnahmen, Privat-Filter in allen Ausgabewegen, Feed-Token, CalDAV-Roundtrip (PUT → PROPFIND/REPORT → GET) gegen ein reales Client-Szenario (DAVx5-kompatible Requests).

## Außerhalb des Umfangs

ToDo, Haushalt (eigene Specs); Rechte pro Objekt (kein ACL); Self-Service-Registrierung; E-Mail/Push-Erinnerungen.
