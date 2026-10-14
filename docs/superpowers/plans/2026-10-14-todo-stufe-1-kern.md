# ToDo Stufe 1: ToDo-Kern und Kontakte – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Kompakter Plan (Aufgaben, Schnittstellen, Testfälle); Ausführung inline durch den Autor in derselben Sitzung.

**Goal:** ToDos mit Status, Priorität, Schlagworten, Zeitfenster, Wiedervorlage, privat, Verantwortlichem und gemischten Zuständigen (Mitglieder, Rollen, Kontakte, „einer reicht“/„jeder einzeln“); freie Kontakte; Rechteprüfung über das ACL aus Stufe 0; Liste mit Gruppen und Filtern, Detailseite, Start-Kachel „Meine ToDos“.

**Architecture:** JPA-Entities `Todo` (mit `TodoAssignee`, `TodoCompletion`, Tags als `@ElementCollection`) und `Contact`. `TodoAccess` ermittelt die Beziehungen eines Benutzers zu einem ToDo und übersetzt die per ACL gewährten Beziehungen in eine SQL-Bedingung für Listen. `TodoService` kapselt alle Änderungen mit Rechteprüfung; `TodoQuery` liefert gefilterte, gruppierte Listen (JDBC für die ID-Auswahl, danach JPA). Vor JDBC-Abfragen wird der `EntityManager` geflusht (Lehre aus Stufe 0). Markdown wird mit commonmark-java gerendert und mit dem OWASP-Sanitizer bereinigt.

**Spec:** `docs/superpowers/specs/2026-10-14-todo-design.md`

## Global Constraints

- Wie Stufe 0 (JDK 26, Testcontainers, Commit-Trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`).
- Migration `V6__todos_contacts.sql`. Unteraufgaben, Wiederholung und Verknüpfungen kommen in Stufe 2 (eigene Migration), Kommentare/Anhänge in Stufe 3.
- `todo_watchers` wird schon in V6 angelegt, weil die Beziehung `WATCHING` in die Sichtbarkeit eingeht; die Oberfläche dafür folgt in Stufe 3.
- Zeitangaben sind `LocalDate`. „Heute“ kommt aus einer `Clock`-Bean in der Zone `nestwerk.timezone`.
- Grenzen: Titel 1–200, Beschreibung ≤ 20 000, Schlagwort ≤ 40 (kleingeschrieben, getrimmt, max. 20 je ToDo), Kontaktname 1–150, Notiz ≤ 5 000, URL nur `http(s)://`, E-Mail mit `@`.
- Kein Zugriff auf ein vorhandenes ToDo/Kontakt → `TodoNotFoundException`/`ContactNotFoundException` (404), nie 403.
- Rechte (ACL-Aktionen): Anlegen `CREATE` (privat zusätzlich `CREATE_PRIVATE`), Inhalte `EDIT`, Status/Abhaken `CHANGE_STATUS`, Zuständige nach dem Anlegen `ASSIGN`, Verantwortlicher `CHANGE_RESPONSIBLE`, Löschen `DELETE`. Kontakte: `VIEW`, `CREATE`, `EDIT`, `DELETE` mit `ANY`/`CREATOR`.
- Modul sichtbar (Navigation, `/todos`), wenn der Benutzer für `TODO:VIEW` mindestens eine Beziehung hat; Kontakte analog `CONTACT:VIEW`.

## Review Focus

- ToDo „jeder einzeln“ an eine Rolle, deren Benutzer kein Familienmitglied haben → keine abhakpflichtige Stelle entsteht für sie; ToDo bleibt per Verantwortlichem abschließbar (Test `eachModeWithoutResolvableMembersFallsBackToResponsible`).
- Privates ToDo eines anderen erscheint weder in Liste noch Start-Kachel noch per URL (Test `privateTodoIsInvisibleEverywhere`).
- Gleichzeitige Bearbeitung mit veralteter Version → Meldung, Eingaben bleiben im Formular (Test `staleVersionKeepsInput`).
- „ab“ nach „bis“ → Meldung statt DB-Fehler (Test `availableAfterDueIsRejected`).
- Kontakt, der noch einem ToDo zugewiesen ist, wird gelöscht → Zuordnung verschwindet, ToDo bleibt (Test `deletingContactRemovesAssignment`).

## Aufgaben

### Task 1: Datenmodell
- Create `V6__todos_contacts.sql`: `contacts`, `todos` (inkl. `updated_by`, CHECK für Status/Priorität/Modus und `available_from <= due_on`), `todo_tags`, `todo_assignees` (CHECK `num_nonnulls(member_id, role_id, contact_id) = 1`, FKs mit `ON DELETE CASCADE`), `todo_completions` (`member_id` oder `contact_id`, `completed_at`/`completed_by` nullable), `todo_watchers`.
- Create `todo/TodoStatus` (`OPEN`, `IN_PROGRESS`, `DONE`, `DISCARDED`, Label, `isClosed()`), `TodoPriority` (`LOW`, `NORMAL`, `HIGH`, `URGENT`, Label, Rang), `CompletionMode` (`ANY`, `EACH`), Entities `Todo`, `TodoAssignee`, `TodoCompletion`, `contact/Contact`; Repositories.
- Test `TodoRepositoryTest`: speichert ToDo mit Tags, drei Zuständigen gemischt; Zuständiger mit zwei gesetzten Spalten wird von der DB abgelehnt.

### Task 2: Kontakte
- `contact/ContactService` (`findVisible(User)`, `get(id, User)`, `create(ContactInput, User)`, `update(id, ContactInput, User)`, `delete(id, User)`), Validierung, ACL `CONTACT`.
- `web/ContactController` (`/contacts`, `/contacts/new`, `/contacts/{id}`, POST anlegen/ändern/löschen), Templates `contacts/list.html`, `contacts/form.html`.
- Tests `ContactServiceTest` (Validierung, ACL ANY/CREATOR, 404 ohne Recht, `deletingContactRemovesAssignment`) und `ContactControllerTest` (Liste, Anlegen, Fehler im Formular).

### Task 3: Zugriff auf ToDos
- `todo/TodoAccess`: `Set<String> relations(User, Todo)`, `boolean can(User, String action, Todo)`, `boolean canCreate(User)`, `boolean canCreatePrivate(User)`, `boolean mayOpen(String username)`, `VisibilitySql visibility(User)` (SQL-Fragment + Parameter, Alias `t`).
- Test `TodoAccessTest`: jede Beziehung einzeln (verantwortlich, Ersteller, zuständig über Mitglied, über Rolle, beobachtend), privat nicht über `ANY`, Administrator alles, ohne Rolle nichts; Listen-Bedingung liefert dieselbe Menge wie `can(VIEW)` für alle ToDos einer Testmenge.

### Task 4: ToDo-Service
- `todo/TodoInput` (Titel, Beschreibung, Priorität, ab, bis, Wiedervorlage, Vorlauf, privat, Modus, Schlagworte, Zuständige als `Set<AssigneeRef>`, Verantwortlicher), `AssigneeRef` (`member:ID`/`role:ID`/`contact:ID`).
- `TodoService`: `create`, `update(id, input, expectedVersion, actor)`, `changeStatus`, `check(id, actor)` / `uncheck`, `take` (In Arbeit), `delete`, `get(id, actor)`; Pflichtstellen bei „jeder einzeln“ werden bei jeder Änderung der Zuständigen neu berechnet (Mitglieder direkt, Rollen → Mitglieder ihrer Benutzer, Kontakte); sind alle erledigt → Status `DONE`.
- `TodoConflictException` bei veralteter Version.
- Tests `TodoServiceTest`: Validierung (inkl. `availableAfterDueIsRejected`), Rechte je Aktion, privat braucht `CREATE_PRIVATE`, Zuständige ändern braucht `ASSIGN`, Abhaken „einer reicht“, „jeder einzeln“ (Teil-/Vollabschluss, über Rolle), `eachModeWithoutResolvableMembersFallsBackToResponsible`, Wiederöffnen, Versionskonflikt.

### Task 5: Abfragen und Zeitlage
- `todo/TodoTiming` (`OVERDUE`, `DUE_SOON`, `CURRENT`, `DORMANT`, `NO_DATE`, `CLOSED`) mit `of(Todo, LocalDate today)`.
- `todo/TodoFilter` (Beteiligung `MINE`/`RESPONSIBLE`/`WATCHING`/`ALL`, Zuständig, Status, Zeitlage, Priorität, Schlagwort, Text, `includeClosed`), `TodoQuery.list(User, TodoFilter)` → `List<TodoGroup>` (Gruppen in fester Reihenfolge, je Gruppe sortiert nach Priorität absteigend, dann Fälligkeit), `TodoQuery.mine(User, int limit)`.
- Tests `TodoQueryTest`: Zeitlage-Grenzen (heute fällig = bald fällig, gestern = überfällig, ab morgen = ruhend), jeder Filter, Sortierung, `privateTodoIsInvisibleEverywhere` (Teil Liste/Kachel).

### Task 6: Oberfläche ToDos
- `web/MarkdownRenderer` (commonmark + Sanitizer; Links mit `rel="nofollow noopener"`), Abhängigkeiten `org.commonmark:commonmark` und `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer`.
- `web/TodoController`: `GET /todos` (Filter als Query-Parameter), `GET /todos/new`, `POST /todos`, `GET /todos/{id}`, `POST /todos/{id}` (mit `version`), `POST /todos/{id}/check|uncheck|take|status|delete`; htmx-Anfragen auf check/uncheck liefern die aktualisierte Zeile.
- Templates `todos/list.html`, `todos/detail.html`, `todos/fragments.html` (Zeile, Merkmale, Zuständigen-Auswahl); Navigation „ToDos“/„Kontakte“ über `@todoAccess.mayOpen`/`@contactService.mayOpen`; CSS.
- Tests `TodoControllerTest`: Liste mit Gruppen, Filter per URL, Anlegen, Formularfehler behält Eingaben, `staleVersionKeepsInput`, htmx-Abhaken liefert nur die Zeile, fremdes privates ToDo 404, Lesedarstellung ohne `EDIT`, Markdown wird bereinigt (`<script>` verschwindet). `NavigationTests` um ToDos/Kontakte ergänzt.

### Task 7: Start-Kachel „Meine ToDos“
- `HomeController` lädt `TodoQuery.mine(user, 5)`; Kachel mit Abhaken (htmx) und Link `/todos?involvement=MINE`.
- Test `HomeTodoTileTest`: zeigt eigene überfällige/aktuelle, nicht ruhende, nicht fremde private.

### Task 8: Gesamtprüfung
- `./mvnw verify`, Sichtprüfung (Liste, Detail, Formular, Kontakte, Start, Rollen-Matrix aus Stufe 0; Handy, Dark Mode), Implementierungsliste aktualisieren.
