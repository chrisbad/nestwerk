# ToDo Stufe 3: Zusammenarbeit – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Kompakter Plan; Ausführung inline durch den Autor.

**Goal:** Kommentare mit @Erwähnungen, ein automatischer Verlauf, Beobachter und Dateianhänge an ToDos, Kommentaren und Kontakten.

**Architecture:** `todo_comments` und `todo_history` (nur anfügen) mit eigenen Entities; `TodoHistoryRecorder` wird von `TodoService`/`TodoStructureService` bei jeder Änderung aufgerufen. Anhänge: Tabelle `attachments` mit je einer optionalen Fremdschlüsselspalte für ToDo, Kommentar und Kontakt (genau eine gesetzt, `ON DELETE CASCADE`); Dateien liegen unter einem zufälligen Schlüssel im Verzeichnis `nestwerk.attachments.dir`. Verwaiste Dateien räumt ein täglicher Job ab. Erwähnungen werden aus dem Kommentartext ermittelt (`@benutzername`) und in Stufe 4 für Benachrichtigungen genutzt.

**Spec:** `docs/superpowers/specs/2026-10-14-todo-design.md`

## Global Constraints

- Wie Stufen 0–2. Migration `V8__todo_collaboration.sql`.
- Kommentar: ACL `COMMENT`; eigene Kommentare bearbeiten/löschen, Administratoren dürfen alle löschen; Text 1–10 000 Zeichen, Markdown (bereinigt).
- Beobachten: `WATCH` (sich selbst), `MANAGE_WATCHERS` (andere hinzufügen/entfernen); sich selbst entfernen geht immer.
- Anhänge: `ATTACH` am ToDo bzw. `CONTACT:ATTACH`; Kommentar-Anhänge mit dem Kommentar (`COMMENT`). Löschen: Hochlader, Administrator oder wer das Objekt bearbeiten darf.
- Erlaubt: PNG, JPEG, GIF, WebP, PDF, DOC/DOCX, XLS/XLSX, PPT/PPTX, ODT/ODS, TXT, CSV; höchstens 20 MB je Datei; Prüfung von Endung **und** Dateianfang (Magic Bytes). Auslieferung mit `X-Content-Type-Options: nosniff`, `Content-Disposition: attachment` außer für Bilder und PDF (`inline`), Content-Type aus der geprüften Liste, nie vom Client.
- Download prüft die Sichtbarkeit des Besitzers; ohne Recht 404.
- Konfiguration `nestwerk.attachments.dir` (Umgebung `NESTWERK_ATTACHMENT_DIR`, Standard `./data/attachments`, im Container `/data/attachments` als Volume).

## Review Focus

- Datei mit erlaubter Endung, aber anderem Inhalt (`bild.png` mit HTML) → abgelehnt (`disguisedFileIsRejected`).
- Dateiname mit Pfadanteilen oder Steuerzeichen (`../../etc/passwd`, `a\nb.pdf`) → nur der bereinigte Name wird gespeichert, der Speicherpfad hängt nie vom Namen ab (`filenameIsSanitized`).
- Anhang eines privaten fremden ToDos per direkter URL → 404 (`foreignAttachmentIsNotFound`).
- Löschen eines ToDos mit Kommentaren und Anhängen → Datensätze verschwinden, Dateien werden beim Aufräumen entfernt (`sweepRemovesOrphanedFiles`).
- Erwähnung eines Benutzers, der das ToDo nicht sehen darf → wird nicht als Erwähnung gewertet (`mentionsOnlyUsersWhoMaySeeTheTodo`).

## Aufgaben

### Task 1: Datenmodell
- V8: `todo_comments`, `todo_history`, `attachments` (todo_id/comment_id/contact_id, CHECK genau eine, `storage_key` eindeutig).
- Entities `TodoComment`, `TodoHistoryEntry`, `attachment/Attachment`; Repositories.

### Task 2: Verlauf
- `TodoHistoryRecorder.record(todo, actor, field, old, new)`; Aufrufe in `TodoService` (anlegen, jedes geänderte Feld mit lesbaren Werten, Status, Abhaken), `TodoStructureService` (Unteraufgabe, Verknüpfung).
- Tests `TodoHistoryTest`.

### Task 3: Kommentare und Erwähnungen
- `TodoCommentService`: `add(todoId, body, actor)`, `edit(commentId, body, actor)`, `delete(commentId, actor)`, `forTodo(todoId)`; `MentionParser.mentioned(body, todo)` → sichtbare, aktive Benutzer.
- Tests `TodoCommentServiceTest` inkl. `mentionsOnlyUsersWhoMaySeeTheTodo`.

### Task 4: Beobachter
- `TodoService`: `watch`, `unwatch`, `addWatcher(id, userId, actor)`, `removeWatcher(id, userId, actor)`; Verlaufseinträge.
- Tests in `TodoWatcherTest`.

### Task 5: Anhänge
- `attachment/AttachmentStorage` (Dateisystem), `AttachmentType` (Positivliste mit Magic Bytes), `AttachmentService` (`store(owner, file, actor)`, `load(id, viewer)`, `delete(id, actor)`, `forTodo/forComment/forContact`, `sweepOrphans()` täglich), `@EnableScheduling`.
- Tests `AttachmentServiceTest` (Typprüfung, `disguisedFileIsRejected`, `filenameIsSanitized`, Größe, Rechte, `sweepRemovesOrphanedFiles`).

### Task 6: Oberfläche
- Detailseite: Zeitstrahl (Kommentare und Verlauf, Filter „nur Kommentare“), Kommentarformular mit Dateien und @-Hinweis, Kommentare bearbeiten/löschen, Beobachter-Bereich, Anhänge mit Bildvorschau, Upload per Dateiauswahl/Drag & Drop/Kamera.
- Kontaktseite: Anhänge.
- `AttachmentController` (`GET /attachments/{id}`, `POST /attachments/{id}/delete`), Upload-Endpunkte an ToDo/Kontakt; Multipart-Grenzen in `application.yaml`.
- Tests `TodoCollaborationControllerTest`, `AttachmentControllerTest` (inkl. `foreignAttachmentIsNotFound`, Header).

### Task 7: Betrieb und Gesamtprüfung
- `compose.prod.yaml` Volume `attachments`, `.env.example`, README (Backup der Anhänge).
- `./mvnw verify`, Sichtprüfung, Implementierungsliste.
