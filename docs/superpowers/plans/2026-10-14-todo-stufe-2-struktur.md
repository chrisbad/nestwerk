# ToDo Stufe 2: Struktur – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Kompakter Plan; Ausführung inline durch den Autor.

**Goal:** Unteraufgaben in beliebiger Tiefe mit Baumansicht und Fortschritt, Verknüpfungen nach Jira-Vorbild (blockiert, verwandt, Duplikat), wiederkehrende ToDos und ein Board mit Drag & Drop.

**Architecture:** `todos.parent_id` bildet die Hierarchie (Kreise werden per rekursiver Abfrage abgelehnt), `todo_links` die übrigen Beziehungen (Gegenrichtung wird bei der Anzeige abgeleitet). `TodoStructureService` kapselt Unteraufgaben und Verknüpfungen mit ACL-Aktion `LINK`. Wiederholung: Beim Abschließen eines ToDos mit RRULE erzeugt `TodoService` genau einen Nachfolger (`successor_id`), Datumsberechnung über ical4j `Recur<LocalDate>`. Board und Baum nutzen dieselben Filter wie die Liste; das Board verschiebt Karten mit SortableJS und htmx.

**Spec:** `docs/superpowers/specs/2026-10-14-todo-design.md`

## Global Constraints

- Wie Stufen 0/1. Migration `V7__todo_structure.sql`.
- Unteraufgabe anlegen: `CREATE` plus `LINK` am übergeordneten ToDo. Übergeordnetes ToDo ändern: `LINK` am ToDo und am neuen übergeordneten. Verknüpfung anlegen/lösen: `LINK` am Quell-ToDo, das Ziel muss sichtbar sein.
- Löschen eines ToDos löscht seine Unteraufgaben mit (Bestätigungsdialog nennt die Anzahl).
- Unsichtbare Unteraufgaben zählen im Fortschritt mit, erscheinen aber nur als Anzahl; unsichtbare Verknüpfungen werden nicht gezeigt, Blockierwarnungen nennen nur die Anzahl.
- Wiederholungs-Anker: `bis`, sonst `ab`, sonst das Erledigungsdatum. `ab` und `Wiedervorlage` verschieben sich um denselben Abstand. Unteraufgaben werden offen mitkopiert, Kommentare/Anhänge (Stufe 3) nicht.
- SortableJS lokal unter `static/js/vendor/sortable.min.js`.

## Review Focus

- Kreis über mehrere Ebenen (A → B → C → A als Unteraufgabe oder Blockade) wird abgelehnt (Tests `deepParentCycleIsRejected`, `blockingCycleIsRejected`).
- Wiederkehrendes ToDo wird erledigt, wieder geöffnet und erneut erledigt → nur ein Nachfolger (`reclosingDoesNotDuplicateSuccessor`).
- RRULE ohne weiteres Vorkommen (`COUNT=1`/`UNTIL` vorbei) → kein Nachfolger, kein Fehler (`exhaustedRuleCreatesNoSuccessor`).
- Board-Verschieben ohne Recht → Karte springt zurück, Meldung (`boardMoveWithoutRightIsRejected`).
- Duplikat markieren schließt das Duplikat, nicht das Original (`markingDuplicateDiscardsSource`).

## Aufgaben

### Task 1: Datenmodell und Unteraufgaben
- V7: `todos.parent_id` (FK, `ON DELETE CASCADE`), `todos.rrule`, `todos.series_id`, `todos.successor_id`; `todo_links` (id, source_id, target_id, type CHECK, UNIQUE(source, target, type), CHECK source ≠ target).
- `Todo`: `parentId`, `rrule`, `seriesId`, `successorId`.
- `TodoStructureService`: `createChild(parentId, input, actor)`, `moveTo(id, newParentId, actor)` (null = Wurzel), `children(id, viewer)` → `Children(visible, hiddenCount, doneCount, total)`, `ancestors(id, viewer)` (Breadcrumb, nur sichtbare), `descendantCount(id)`.
- Tests `TodoStructureServiceTest`: Kind anlegen braucht LINK am Eltern-ToDo, `deepParentCycleIsRejected`, Fortschritt zählt unsichtbare mit, Löschen löscht Kinder.

### Task 2: Verknüpfungen
- `TodoLinkType` (`BLOCKS`, `RELATES`, `DUPLICATES` mit Beschriftung je Richtung), `TodoLink`-Entity, Repository.
- `TodoStructureService`: `link(sourceId, targetId, type, actor)`, `unlink(linkId, actor)`, `links(id, viewer)` → gruppiert nach Richtung, `openBlockers(todo)` (Anzahl + sichtbare Titel).
- Tests: `blockingCycleIsRejected`, `markingDuplicateDiscardsSource`, Ziel unsichtbar → 404-Verhalten, Duplikat-Paar nicht doppelt.

### Task 3: Wiederholung
- `TodoRecurrence` (`next(rrule, anchor)` mit ical4j, `validate`), `TodoService`: RRULE prüfen und speichern; beim Wechsel in `DONE`/`DISCARDED` Nachfolger anlegen, wenn `rrule` gesetzt und noch kein `successor_id`.
- Tests `TodoRecurrenceTest`: Anker-Regeln, `reclosingDoesNotDuplicateSuccessor`, `exhaustedRuleCreatesNoSuccessor`, Unteraufgaben werden offen kopiert, Zeitfenster verschiebt sich.

### Task 4: Oberfläche Struktur
- Detailseite: Breadcrumb, Unteraufgaben-Liste mit „Unteraufgabe hinzufügen“ (`/todos/new?parent=ID`), Verknüpfungen mit Hinzufügen (Auswahl über `#ID Titel` mit Vorschlagsliste) und Lösen, Blockierhinweis; Lösch-Bestätigung nennt Unteraufgaben.
- Zeilen: Fortschritt Unteraufgaben, Merkmal „blockiert“, Abhaken mit Warnung bei offenen Blockern (`data-confirm`).
- Formular: Wiederholung (gleiche Auswahl wie Kalender, `data-repeat-select`), übergeordnetes ToDo beim Anlegen.
- Tests `TodoStructureControllerTest`.

### Task 5: Baum und Board
- `GET /todos/tree` (Wurzeln = sichtbare ToDos ohne sichtbares Eltern-ToDo), `GET /todos/{id}/children` (htmx-Fragment), Fortschrittsbalken.
- `GET /todos/board` (Spalten Offen, In Arbeit, Erledigt der letzten 14 Tage, Verworfen eingeklappt), SortableJS, `POST /todos/{id}/status` mit `HX-Request` → 204 bzw. 409 mit Meldung; mobil Status-Menü je Karte.
- Ansichtsumschalter Liste | Board | Baum mit erhaltenen Filtern.
- Tests `TodoBoardTreeTest` inkl. `boardMoveWithoutRightIsRejected`.

### Task 6: Gesamtprüfung
- `./mvnw verify`, Sichtprüfung (Baum, Board-Ziehen, Verknüpfen, Wiederholung), Implementierungsliste aktualisieren.
