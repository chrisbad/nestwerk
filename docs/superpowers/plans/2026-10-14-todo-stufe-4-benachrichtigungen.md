# ToDo Stufe 4: Benachrichtigungen in der App – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Kompakter Plan; Ausführung inline durch den Autor.

**Goal:** Ein modulunabhängiger Benachrichtigungskern mit Glocke in der Navigation, Benachrichtigungsseite, täglichem Zeitplaner für Erinnerungen („ab“ erreicht, vor Fälligkeit, überfällig, Wiedervorlage) und Ereignissen (zugewiesen, Kommentar, Erwähnung, Statuswechsel, nicht mehr blockiert).

**Architecture:** Tabelle `notifications` mit eindeutigem Schlüssel (Empfänger, Anlass, Quelle, Stichtag) → `NotificationService.notify` ist idempotent (`ON CONFLICT DO NOTHING`). `TodoNotifier` (Paket `todo`) ermittelt Empfänger und wird von `TodoService`, `TodoCommentService` und dem Zeitplaner aufgerufen; jeder Empfänger muss das ToDo sehen dürfen, der Auslöser wird nie benachrichtigt. `TodoReminderScheduler` läuft minütlich, wirkt einmal täglich ab der eingestellten Uhrzeit und holt versäumte Tage nach (höchstens 31). Einstellungen (Uhrzeit, Standard-Vorlauf) liegen in `app_settings`. Kanäle E-Mail/Push folgen in Stufe 5 und hängen sich an `notify` an.

**Spec:** `docs/superpowers/specs/2026-10-14-todo-design.md` (Abschnitt Benachrichtigungen)

## Global Constraints

- Wie Stufen 0–3. Migration `V9__notifications.sql` (inkl. neues Recht `NOTIFICATION_SETTINGS_MANAGE` im Katalog und Enum `AdminPermission`).
- Empfänger eines ToDos: Verantwortlicher, Beobachter, Zuständige mit Konto (über Mitglied oder Rolle); bei „jeder einzeln“ für Erinnerungen nur, wer noch nicht abgehakt hat. Immer nur, wer das ToDo sehen darf und aktiv ist.
- Erinnerungs-Stichtage: „ab“ = `available_from`; „vor Fälligkeit“ = `due_on − Vorlauf` (Vorlauf am ToDo, sonst Standard 2); „überfällig“ = erster Tag nach `due_on`, danach alle 7 Tage; „Wiedervorlage“ = `follow_up_on`. Nur offene ToDos (Offen, In Arbeit).
- Erwähnte Benutzer erhalten „Erwähnung“ statt „neuer Kommentar“.
- Statuswechsel benachrichtigt bei Erledigt/Verworfen; „nicht mehr blockiert“ geht an die Beteiligten des blockierten ToDos, wenn dessen letzter offener Blocker abgeschlossen wird.
- Glocke: Zähler ungelesener Benachrichtigungen, aktualisiert per htmx alle 60 s; Menü mit den neuesten acht; Seite `/notifications` mit Filter ungelesen/alle, „Alle als gelesen markieren“; Öffnen markiert als gelesen.
- Wiedervorlage-Aktionen: Erledigt, +1 Woche, +1 Monat, Verwerfen (Rechte wie bei Status bzw. Bearbeiten).

## Review Focus

- Zeitplaner läuft zweimal am selben Tag oder nach Neustart erneut → keine doppelten Benachrichtigungen (`schedulerIsIdempotent`).
- Server war drei Tage aus → versäumte Erinnerungen werden nachgeholt, aber nicht vervielfacht (`missedDaysAreCaughtUp`).
- Privates ToDo: Zuständige Rolle enthält Benutzer, die es sehen dürfen, und solche ohne Recht → nur Berechtigte bekommen etwas (`recipientsMustSeeTheTodo`).
- Auslöser bekommt keine Benachrichtigung über eigene Aktionen (`actorIsNeverNotified`).
- Benachrichtigung zu einem inzwischen gelöschten ToDo → Öffnen führt zu einer verständlichen Meldung statt 404 (`openingNotificationOfDeletedTodo`).

## Aufgaben

1. **Datenmodell und Kern:** V9 (`notifications`, `app_settings`, Recht), `NotificationType`, `Notification`-Entity, `NotificationService` (`notify`, `unreadCount`, `latest`, `page`, `markRead`, `markAllRead`, `open`), `AppSettings` (Uhrzeit, Vorlauf). Tests `NotificationServiceTest`.
2. **Ereignisse:** `TodoNotifier` (`assigned`, `commented`, `statusChanged`, `unblocked`), Aufrufe in Services. Tests `TodoNotifierTest`.
3. **Zeitplaner:** `TodoReminderScheduler` (`tick()`, `runFor(from, to)`), Speicherung des letzten Laufs. Tests `TodoReminderSchedulerTest`.
4. **Oberfläche:** Glocke im Layout, `NotificationController` (`/notifications`, `/notifications/menu`, `/notifications/count`, `/notifications/{id}/open`, `/notifications/read-all`), Wiedervorlage-Aktionen (`POST /todos/{id}/follow-up`), Verwaltungsseite `/admin/notifications` (Uhrzeit, Vorlauf). Tests `NotificationControllerTest`.
5. **Gesamtprüfung:** `./mvnw verify`, Sichtprüfung, Implementierungsliste.
