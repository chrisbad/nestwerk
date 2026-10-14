# Haushalt: Putzplan mit fairer Verteilung, Zeitkonto und Belohnungen

Stand: 2026-10-14 · Status: freigegeben im Brainstorming

## Ziel

Hausarbeiten werden fair verteilt, man wird erinnert, und es wird sichtbar, wer wie viel im Haushalt leistet –
Erwachsene genauso wie Kinder. Erledigte Minuten werden einem Konto gutgeschrieben, von dem Belohnungen
eingelöst werden können.

## Entscheidungen aus dem Brainstorming

| Thema | Entscheidung |
|---|---|
| Zweck | Hausarbeiten / Putzplan (kein Haushaltsbuch, keine Einkaufsliste, keine Essensplanung) |
| Arten | **Feste Termine** mit Uhrzeit (Liste, von Hand oder per iCal-Import), **periodisch** (RRULE + optionale Uhrzeit), **Intervall** (X Tage nach letzter Erledigung) |
| Aufwand | Jede Hausarbeit hat geplante **Minuten** und einen **Aufwandsgrad** (leicht/mittel/schwer) |
| Auswertung | Minuten und Anzahl je Grad **getrennt** nebeneinander, keine Verrechnung |
| Gutschrift | Planwert wird gutgeschrieben; Berechtigte können nachträglich **korrigieren** (mit Grund) |
| Gemeinsam | Mithelfer bekommen **jeweils die vollen Minuten** |
| Verteilung | pro Hausarbeit: **fest**, **reihum**, **ausgleichend** (wenigste Minuten), **offen** (selbst nehmen) |
| Abwesenheit | aus dem **Kalender**: neues Häkchen „abwesend“ an Terminen, gilt für die teilnehmenden Mitglieder |
| Versäumt | bleibt beim Zuständigen offen und überfällig; andere können übernehmen und bekommen die Minuten |
| Belohnungen | Konto aus Minuten; Belohnungen mit Minutenpreis; Mitglied beantragt, Berechtigte bestätigen/lehnen ab |
| Ansichten | Wochenplan (Personen × Tage), „Meine Hausarbeiten heute“ + Start-Kachel, Auswertung + Konto, Wand-Tablet |
| Tablet | Gerätekopplung durch Berechtigte (widerrufbar) + **PIN pro Mitglied** beim Abhaken |
| Später | Abgleich mit dem Familien-Google-Kalender (zusammen mit Phase 4 CalDAV) |

**Annahmen (bestätigt):** Gezählt wird pro **Familienmitglied** (auch ohne Benutzerkonto, z. B. kleine Kinder
über das Tablet). Einlösen zieht vom Kontostand ab, die Statistik bleibt unberührt. Rechte über das ACL in der
Datenbank. Erinnerungen über den vorhandenen Benachrichtigungskern.

## Architektur

Eigenes Paket `household` auf den gemeinsamen Kernen:

- **ACL** (`AclService`): neue Objekttypen `CHORE` und `REWARD`, eigene Beziehungen.
- **Benachrichtigungen** (`NotificationService`): neue Typen, Quelle `CHORE_TASK` bzw. `REDEMPTION`.
- **Zeitplanung**: eigener `HouseholdScheduler` (minütlich; Planung täglich, zeitgenaue Erinnerungen).
- **Kalender**: Spalte `absence` an `calendar_events`, Auswertung über `RecurrenceExpander`.
- **Wiederholung**: ical4j `Recur<LocalDate>` wie bei ToDos (`TodoRecurrence` als Vorbild).

### Begriffe

- **Hausarbeit** (`Chore`): die Vorlage – Titel, Bereich, Minuten, Grad, Art, Rhythmus, Verteilung, Kandidaten.
- **Aufgabe** (`ChoreTask`): ein konkretes Vorkommen mit Fälligkeit (Datum, optional Uhrzeit), Zuständigem
  und Status `OPEN` / `DONE`.
- **Gutschrift** (`ChoreCredit`): Minuten für ein Mitglied aus einer erledigten Aufgabe.
- **Belohnung** (`Reward`) und **Einlösung** (`Redemption`).

## Datenmodell (Migration V12)

```
chores(id, title ≤150, area ≤100?, description ≤2000?, minutes 1..600, effort EASY|MEDIUM|HARD,
       kind DATES|RECURRING|INTERVAL, rrule ≤500?, starts_on DATE, due_time TIME?, interval_days 1..365?,
       distribution FIXED|ROTATION|BALANCED|OPEN, active, ical_url ≤500?, ical_filter ≤100?,
       ical_days_before 0..7, ical_time TIME?, ical_synced_at?, created_by → users, created_at, version)
chore_members(chore_id, member_id, position)            -- Kandidaten / Reihenfolge
chore_dates(id, chore_id, due_on, due_time?, source MANUAL|ICAL, external_uid?)   -- feste Termine
chore_tasks(id, chore_id, slot_key ≤40, due_on, due_time?, assignee_member_id?, status OPEN|DONE,
            completed_at?, completed_by_user?, notified_due BOOLEAN, version, UNIQUE(chore_id, slot_key))
chore_credits(id, task_id, member_id, minutes, effort, corrected_minutes?, correction_reason ≤200?,
              corrected_by?, corrected_at?, created_at, UNIQUE(task_id, member_id))
rewards(id, title ≤100, description ≤500?, cost_minutes 1..100000, active, created_at)
reward_redemptions(id, reward_id, member_id, cost_minutes, status REQUESTED|APPROVED|REJECTED,
                   requested_by → users, requested_at, decided_by?, decided_at?)
household_devices(id, name ≤100, token_hash ≤64 UNIQUE?, pairing_hash ≤64?, pairing_expires_at?,
                  created_by, created_at, last_seen_at?, revoked_at?)
family_members.pin_hash ≤100?
calendar_events.absence BOOLEAN DEFAULT FALSE
```

`slot_key` ist `yyyy-MM-dd` bzw. `yyyy-MM-ddTHH:mm` und macht die Planung idempotent.

## Planung der Aufgaben

- **Horizont** 21 Tage ab heute. Geplant wird beim Speichern einer Hausarbeit und täglich durch den Zeitplaner.
- **DATES**: je Eintrag in `chore_dates` im Horizont eine Aufgabe (Termine in der Vergangenheit werden nicht
  nachgeplant).
- **RECURRING**: alle Termine der RRULE ab `starts_on` im Horizont, Uhrzeit `due_time`.
- **INTERVAL**: immer genau **eine** offene Aufgabe. Erste Fälligkeit `starts_on`; nach Erledigung
  neue Aufgabe mit Fälligkeit = Erledigungsdatum + `interval_days`. Rückgängig machen der Erledigung entfernt
  die Nachfolgerin wieder, sofern sie noch offen ist.
- **Ändern einer Hausarbeit** löscht die künftigen (ab heute) offenen Aufgaben und plant neu; erledigte und
  überfällige bleiben. Deaktivieren/Löschen entfernt die künftigen offenen Aufgaben (Löschen nur, wenn keine
  Gutschriften existieren; sonst nur Deaktivieren).

### Verteilung

- **FIXED**: genau ein Kandidat; bekommt jede Aufgabe (auch bei Abwesenheit).
- **ROTATION**: Kandidaten in Reihenfolge. Nächster nach dem Zuständigen der zuletzt geplanten Aufgabe;
  wer am Fälligkeitstag abwesend ist, wird übersprungen. Sind alle abwesend, bleibt die Aufgabe offen (ohne
  Zuständigen).
- **BALANCED**: unter den anwesenden Kandidaten der mit den wenigsten Minuten (Gutschriften der letzten
  28 Tage + geplante Minuten seiner offenen Aufgaben ab heute); Gleichstand → Reihenfolge.
- **OPEN**: kein Zuständiger; jeder mit Recht zum Abhaken kann nehmen bzw. direkt erledigen.
- Der Zeitplaner prüft täglich künftige offene Aufgaben von ROTATION/BALANCED, deren Zuständiger inzwischen
  abwesend ist, und verteilt sie neu.

### Abwesenheit

Ein Mitglied ist an Tag D abwesend, wenn es an einem Kalendertermin mit `absence = true` teilnimmt, dessen
Vorkommen D überdeckt (Wiederholungen über `RecurrenceExpander`, Ausnahmen berücksichtigt). Das Häkchen
steht im Terminformular („Abwesend – im Putzplan überspringen“) und als Kennzeichen in der Lese-Ansicht.

## Erledigen, Übernehmen, Korrigieren

- **Abhaken**: Aufgabe → `DONE`; Gutschrift (Planminuten, Grad) für das erledigende Mitglied und für jeden
  ausgewählten **Mithelfer**. Erledigender ist standardmäßig das Mitglied des Benutzers; mit Recht
  `COMPLETE` bei `ANY` darf man auch für andere Mitglieder abhaken.
- **Rückgängig**: durch den Abhakenden selbst am selben Tag oder mit `CORRECT`; löscht die Gutschriften.
- **Übernehmen/Umverteilen** (`ASSIGN`): Zuständigen ändern (Kandidat oder beliebiges Mitglied bei `ANY`),
  benachrichtigt den neuen Zuständigen.
- **Korrigieren** (`CORRECT`): Minuten einer Gutschrift ändern (0..600) mit Pflicht-Grund; sichtbar in der
  Auswertung.
- Gleichzeitiges Abhaken: optimistische Sperre (`version`); zweiter Versuch bekommt „bereits erledigt“.

## Belohnungen

- Kontostand eines Mitglieds = Σ wirksame Gutschriften (korrigiert, sonst Planwert) − Σ Einlösungen in
  `REQUESTED` oder `APPROVED`. Beantragen nur bei ausreichendem Stand.
- Beantragen (`REWARD/REDEEM`): für das eigene Mitglied; mit `REDEEM` bei `ANY` auch für andere.
- Entscheiden (`REWARD/APPROVE`): bestätigen oder ablehnen (Ablehnen gibt die Minuten frei). Benachrichtigt
  den Antragsteller; neue Anträge benachrichtigen alle mit `APPROVE`.
- Verwalten (`REWARD/MANAGE`): Belohnungen anlegen, ändern, (de)aktivieren.

## Rechte (ACL)

Objekttyp **CHORE** (Beziehungen: `ANY`, `ASSIGNED` = eigenes Mitglied ist Kandidat bzw. Zuständiger,
`CREATOR`):

| Aktion | Bedeutung | Zellen |
|---|---|---|
| VIEW | Putzplan und Hausarbeiten sehen | ANY, ASSIGNED |
| CREATE | Hausarbeiten anlegen | ANY |
| EDIT | bearbeiten, iCal importieren | ANY, CREATOR |
| DELETE | löschen/deaktivieren | ANY, CREATOR |
| COMPLETE | abhaken / rückgängig (eigene Erledigung) | ANY, ASSIGNED |
| ASSIGN | umverteilen / übernehmen | ANY, ASSIGNED |
| CORRECT | Gutschriften korrigieren | ANY |
| STATS | Auswertung aller Mitglieder (eigene sieht jeder) | ANY |

Objekttyp **REWARD** (nur `ANY`): VIEW, REDEEM (für andere; für sich selbst genügt VIEW + eigenes Mitglied), MANAGE,
APPROVE.

Neue Berechtigung im Katalog: `HOUSEHOLD_DEVICE_MANAGE` (Modul Verwaltung) – Wand-Tablets koppeln/widerrufen.

Vorlagen: **Elternteil** alles; **Kind** CHORE VIEW ANY, COMPLETE ASSIGNED, ASSIGN ASSIGNED, REWARD VIEW;
**Gast** CHORE VIEW ANY. Rolle **Benutzer** startet mit VIEW ANY, COMPLETE ANY/ASSIGNED, ASSIGN ASSIGNED,
REWARD VIEW.

## Benachrichtigungen

| Typ | Empfänger | Zeitpunkt |
|---|---|---|
| CHORE_DUE „Hausarbeit fällig“ | Zuständiger (bzw. alle Kandidaten bei OPEN) | Aufgaben mit Uhrzeit: 60 Min vorher; ohne Uhrzeit: zur Erinnerungsstunde am Fälligkeitstag |
| CHORE_OVERDUE „Hausarbeit überfällig“ | Zuständiger | am Tag nach der Fälligkeit (einmal) |
| CHORE_ASSIGNED „Hausarbeit übernommen“ | neuer Zuständiger | beim Umverteilen durch andere |
| REWARD_REQUESTED | alle mit REWARD/APPROVE | beim Beantragen |
| REWARD_DECIDED | Antragsteller | beim Entscheiden |

Nur Mitglieder mit Benutzerkonto werden benachrichtigt. Kanäle und Einstellungen wie bei ToDos (die Typen
erscheinen automatisch in den Einstellungsseiten).

## Ansichten

- **Wochenplan** `/household?week=`: Zeilen = Mitglieder (+ Zeile „offen“), Spalten Mo–So; Aufgaben als
  Chips mit Häkchen (htmx), Uhrzeit, Minuten, Grad-Symbol; überfällige links in einer eigenen Spalte.
  Mobil: Tage untereinander.
- **Heute** `/household/today`: eigene heute fälligen und überfälligen, offene zum Nehmen; Start-Kachel
  „Meine Hausarbeiten“ (max. 5).
- **Hausarbeiten** `/household/chores`: Liste, Formular, Detail mit nächsten Terminen, festen Terminen und
  iCal-Import (Datei oder Abo-URL, Filtertext, Tage vorher, Uhrzeit).
- **Auswertung** `/household/stats?period=day|week|month|year&date=`: je Mitglied Minuten, Anzahl leicht/
  mittel/schwer, überfällige; darunter die Gutschriften des Zeitraums mit Korrektur.
- **Belohnungen** `/household/rewards`: Kontostände, Belohnungen, Antrag, offene Anträge, Verlauf.
- **Wand-Tablet** `/tablet`: heute und morgen für alle, große Chips; Abhaken: Mitglied antippen → PIN →
  erledigt. Kopplung `/tablet/pair` mit 8-stelligem Code (15 Min gültig) aus `/admin/devices`.

## Wand-Tablet: Sicherheit

- Kopplung: Code wird nur als SHA-256-Hash gespeichert, einmal verwendbar, 15 Minuten gültig, 5 Fehlversuche
  je Minute global.
- Nach Kopplung: Cookie `NESTWERK_DEVICE` (32 Byte Zufall, HttpOnly, SameSite=Strict, Secure bei HTTPS,
  400 Tage), in der DB nur als Hash. Widerruf wirkt sofort.
- Das Tablet sieht ausschließlich `/tablet/**`: Aufgaben von heute/morgen/überfällig, Mitgliedsnamen und
  Farben. Keine Konten, keine Auswertung, kein Zugriff auf andere Module.
- Abhaken nur mit gültiger PIN (4–8 Ziffern, BCrypt) des gewählten Mitglieds; Mitglieder ohne PIN können am
  Tablet nicht abhaken. 5 Fehlversuche je Mitglied und Gerät → 5 Minuten Sperre.
- PIN setzen: das Mitglied selbst unter „Meine PIN fürs Tablet“, für Mitglieder ohne Konto die
  Mitgliederverwaltung (`MEMBER_MANAGE`).

## iCal-Import (feste Termine)

- Quelle: hochgeladene `.ics` (≤ 1 MB) oder Abo-URL (http/https, Ports 80/443, keine privaten/lokalen
  Adressen, keine Weiterleitungen, 10 s Timeout, ≤ 1 MB). Abo-URLs werden täglich neu geladen.
- Filter: Termine, deren Titel den Filtertext enthält (ohne Groß-/Kleinschreibung); leer = alle.
- Aus jedem Termin: Fälligkeit = Termindatum − `ical_days_before` Tage, Uhrzeit `ical_time` (optional).
- Abgleich über die UID: neue Termine hinzufügen, verschwundene künftige iCal-Termine entfernen;
  manuelle Termine bleiben unberührt. Höchstens 1000 Termine je Import.

## Fehlerfälle und Grenzen

- Validierung mit deutschen Meldungen: Titel 1–150, Minuten 1–600, Intervall 1–365, RRULE gültig,
  mindestens ein Kandidat außer bei OPEN, FIXED genau einer.
- Fremde/unsichtbare Hausarbeiten → 404; fehlende Rechte → 403.
- Gelöschtes Mitglied: Kandidaten/Zuständigkeit entfallen (Gutschriften bleiben über `ON DELETE CASCADE`
  nicht erhalten – Mitglieder werden in der Praxis nicht gelöscht; dokumentiert).

## Tests

Pro Stufe Service- und Controller-Tests mit Testcontainers: Planung je Art, Rotation mit Abwesenheit,
Ausgleich, Intervall-Nachfolger, Abhaken mit Mithelfern, Rückgängig, Korrektur, Kontostand und Einlösen,
ACL-Grenzen (fremde Aufgaben, Korrektur ohne Recht), Benachrichtigungen idempotent, Tablet ohne/mit
Kopplung, falsche PIN und Sperre, Widerruf, iCal-Import (Filter, UID-Abgleich, SSRF-Abwehr).

## Nicht enthalten

Google-Kalender-Abgleich (später mit CalDAV), Taschengeld in Euro, Fotos als Nachweis, Bereiche als eigene
Verwaltung (nur Freitext), Push-Erinnerungen ans Tablet.
