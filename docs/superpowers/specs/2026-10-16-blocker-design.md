# Blocker: Arbeits-, Schlaf-, Ruhe- und Freizeiten – Design

## Warum
Familienmitglieder planen einander Termine und Hausarbeiten ein, ohne zu sehen, wann jemand arbeitet, schläft oder
bewusst frei hat. Nestwerk soll diese Zeiten kennen, beim Einplanen darauf achten und Ruhe aktiv schützen.

## Ziele
- **Blocker**: wiederkehrende Zeiträume einer Art (Arbeit, Schlaf, Ruhe/Pause, Freizeit, Eigene) für eine oder
  mehrere Personen, mit eigenen Regeln je Blocker.
- **Durchsetzung** überall, wo in Nestwerk etwas für jemanden eingeplant wird: Terminformular, Drag & Drop, MCP,
  iCal-Import, ToDo-Fälligkeiten, Haushaltsplaner.
- **Ruhe-Modus**: Benachrichtigungen während eines Blockers zurückhalten und danach zustellen.
- **Ruhe-Ziele** je Person („mind. 2 freie Abende“, „mind. 6 Stunden Ruhe pro Woche“) mit Wochenstand und Warnung.
- **Hausarbeiten mit Uhrzeit**: Der Planer legt jede Aufgabe in eine freie Lücke der zuständigen Person.

## Nicht-Ziele
- Kein Eingang von außen (Mail-Einladungen, Buchungslink) – späteres Projekt.
- Keine Sperre für die betroffene Person selbst.
- Keine stundengenaue Kalenderansicht (die Wochenansicht bleibt eine Liste je Tag).

## Anti-Ziele
- Ein Blocker darf nie verhindern, dass jemand seinen eigenen Kalender pflegt.
- Warnungen dürfen nicht nerven: höchstens eine Rückfrage pro Speichern, mit klarer Begründung.

## Modell
- `blockers`: Titel, Art (`WORK`, `SLEEP`, `REST`, `LEISURE`, `CUSTOM`), Startzeit, Endzeit (Ende vor Start =
  über Mitternacht, z. B. Schlaf 22:00–06:30), Wochentage, gültig ab/bis (bis optional; ein einzelner Tag = ab und bis
  gleich), Stärke (`SHOW`, `WARN`, `BLOCK`), Sichtbarkeit (`TITLE`, `BUSY`, `HIDDEN`), Ruhe-Modus (ja/nein),
  „an Feiertagen frei“ (ja/nein), angelegt von.
- `blocker_members` (für wen), `blocker_planners` (Personen, die trotzdem einplanen dürfen), `blocker_skips`
  (einzelne Tage, an denen der Blocker entfällt: Urlaub, Gleittag).
- Wochenmuster statt RRULE: Der Nutzer hat feste Wochenmuster; Tage + Uhrzeiten sind im Formular einfacher.
- Zeiten gelten in der Zeitzone des Kalenders (`CalendarSettings.zone()`).
- **Feiertage**: Bundesland als Einstellung der Installation (`app_settings`), gesetzliche Feiertage werden
  berechnet (feste Tage, Ostern-abhängige, landesspezifische). Gilt für Blocker mit „an Feiertagen frei“
  (Standard bei Arbeit: an).
- **Rechte**: wie bei Terminen – anlegen mit `CALENDAR_EVENT_CREATE`, eigene ändern mit `CALENDAR_EVENT_EDIT_OWN`,
  fremde mit `CALENDAR_EVENT_EDIT_ALL`. „Eigene“ = selbst angelegt oder für die eigene Person.

## Konfliktprüfung
- `BlockerConflicts.check(Set<Long> memberIds, List<Interval> zeiten, User actor)` liefert Konflikte mit
  Person, Blocker, wirksamer Stärke und Text („Lena arbeitet da (8–16 Uhr)“).
- Wirksame Stärke: Stärke des Blockers; ist der Handelnde die betroffene Person selbst oder steht er unter
  „dürfen trotzdem einplanen“, höchstens `WARN` bzw. gar kein Konflikt bei `SHOW`. `SHOW` erzeugt nie eine Rückfrage.
- Serien: geprüft werden die Vorkommen der nächsten 8 Wochen.
- **Sperren** (`BLOCK`) erzwingt der `CalendarEventService` (`BlockedException`), egal über welchen Weg.
- **Warnen** (`WARN`) ist Sache der Oberfläche:
  - Formular: Beim ersten Speichern erscheint die Warnung mit bis zu drei freien Alternativen (gleiche Dauer, am
    selben und an den nächsten Tagen, 7–21 Uhr, außerhalb von Blockern und Terminen der Personen); „Trotzdem
    speichern“ speichert.
  - Drag & Drop: speichert und zeigt die Warnung als Hinweis-Meldung.
  - MCP: Termin wird angelegt, die Antwort enthält die Warnung; bei Sperre Fehler mit Begründung.
  - iCal-Import: Vorschau zeigt Warnungen; gesperrte Termine werden mit Grund übersprungen.
  - ToDo mit Fälligkeit für Personen, die an dem Tag ganztägig geblockt sind: Hinweis im Formular.
- Ruhe-Ziele erzeugen Warnungen („Damit bleibt diese Woche nur 1 freier Abend, Ziel: 2“).

## Anzeige
- Kalender: Blocker als blasse Bänder je Tag (Monat: kleine Leiste mit Symbol; Woche/Agenda: Band mit Zeit und Titel
  bzw. „belegt“). Sichtbarkeit: betroffene Personen und Admins sehen immer den Titel, andere je nach Einstellung.
- Seite **„Zeiten“** (Navigation unter Kalender bzw. im „Mehr“-Blatt): Blocker je Person, Ruhe-Ziele mit
  Wochenstand, Bundesland. Formular als Dialog/Blatt, Wochentage als Chips, Farbe je Art.
- Startseite: Ring „Ruhe diese Woche“ für die eigene Person, wenn ein Ziel gesetzt ist.

## Ruhe-Modus
- `DeliveryWorker` stellt Push und Mail an Nutzer nicht zu, deren Person gerade in einem Blocker mit Ruhe-Modus ist;
  der Versuch wird auf das Ende des Blockers verschoben (`next_attempt_at`). Dadurch kommen die gesammelten
  Benachrichtigungen danach zusammen an. Die Liste in der App zeigt sie sofort.

## Ruhe-Ziele
- `rest_goals`: Person, Art (`WINDOWS` oder `HOURS`), Fenster (von–bis, z. B. 18:00–23:59, nur bei WINDOWS),
  Zielwert (Anzahl bzw. Minuten), Bezeichnung.
- WINDOWS: zählt die Tage der Woche (Mo–So), an denen im Fenster kein Termin der Person liegt.
- HOURS: zählt die Zeit in Blockern der Art `REST` der Person, abzüglich überschneidender Termine.
- Wochenstand auf „Zeiten“ und der Startseite; Warnung, wenn ein neuer Termin das Ziel unterschreiten lässt.

## Hausarbeiten mit Uhrzeit
- `chores`: optional frühestens/spätestens (`window_start`, `window_end`). Standard: 07:00 bis Fälligkeitszeit
  bzw. 21:00.
- `chore_tasks`: `planned_at` (Uhrzeit) – vom Planer gesetzt.
- Planer: Für jede neue Aufgabe in der Reihenfolge der Verteilung (reihum, ausgeglichen, fest) die erste Person, die
  im Fenster eine freie Lücke von der mittleren Aufwandsdauer hat; frei = keine Blocker mit Stärke WARN/BLOCK, keine
  Termine, nicht abwesend. Gewählt wird die früheste passende Lücke. Findet sich niemand, bleibt die Aufgabe offen
  mit Hinweis „niemand frei“.
- Ändern sich Blocker oder Termine, werden künftige offene Aufgaben neu verteilt (wie heute bei Abwesenheiten).
- Anzeige: Uhrzeit im Wochenplan; im Kalender als Eintrag der Person („🧹 Bad putzen“, 18:00–18:25), nicht
  bearbeitbar dort. Zuweisen per Drag & Drop im Wochenplan plant die Uhrzeit für die neue Person neu.

## Entscheidungen, die ich getroffen habe
- Wochenmuster statt RRULE; einzelne Tage über „gültig ab = bis“.
- Bundesland je Installation, nicht je Person.
- Rechte der Termine wiederverwendet statt neuer Rechte.
- Warnen ist Oberflächensache, Sperren erzwingt der Service.
- Ruhe-Modus verschiebt Zustellungen auf das Blocker-Ende (keine Sammel-Nachricht).
- Standardfenster für Hausarbeiten 07:00–Fälligkeit/21:00; Uhrzeit-Änderung per Drag & Drop nur über das Zuweisen
  (die Wochenansicht hat kein Stundenraster).
