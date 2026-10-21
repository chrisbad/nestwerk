# Nestwerk: Navigations- und Benutzerführungskonzept

Stand: 2026-10-21 · Grundlage: Analyse von `fragments/layout.html`, `index.html`, allen Modul-Templates
(`calendar`, `todos`, `household`, `kitchen`, `school`, `contacts`, `times`, `admin`, `settings`), der 40 Web-Controller
und des Frontend-Skripts (`nestwerk.js`, `calendar-sheets.js`, `calendar-grid.js`).
Die Aufwandswerte (Abschnitt 6) sind aus dem Code **abgeleitet, nicht in der laufenden App gemessen**.

Leitgedanke: **Der Kalender ist der Mittelpunkt.** Alles andere (Einstellungen, Verwaltung, Fachmodule) ist ein
*Ausflug*, der immer genau dort endet, wo er begonnen hat.

---

## 1. Management-Zusammenfassung

| Befund | Wirkung |
|---|---|
| Der Kalender ist funktional stark (Raster, Sheets, Drag & Drop), aber **nicht der Einstieg**: Nach dem Login landet man auf „Start", der Kalender ist eine Kachel unter vielen. | Zusätzlicher Klick bei fast jeder Aufgabe |
| **Einstellungen sind über ≥ 9 Einstiegspunkte verstreut** (Hauptnavigation, Avatar-Menü, Zahnrad in Schule, Tab in Küche, Link unter der Kalender-Legende, Button auf der Rollenseite, Kacheln auf Start …). | Man muss wissen, wo etwas liegt; Suchen statt Finden |
| **Der Rücksprung ist nur für Termine, ToDos und Haushaltsaufgaben gelöst** (`back`-Parameter). Kategorien, Zeiten, Kontakte, Küche u. a. haben fest verdrahtete Zurück-Links (z. B. `/calendar/week`). | Nach jeder Einstellung geht Ansicht, Datum und Zoom verloren; 2–3 Zusatzklicks |
| **Drei Container-Arten ohne Regel**: modaler Dialog, Bottom-Sheet, Vollseite – für gleichartige Aufgaben. | Uneinheitliches Verhalten, uneinheitliche Rückkehr |
| **Dialoge pro Tabellenzeile** (Benutzer, Mitglieder, Kategorien, Sicherungen, Hausarbeiten, Tablet) werden N-mal ins HTML gerendert. | Schwere Seiten, doppeltes Markup; der Kalender zeigt mit *einem* per htmx geladenen Sheet, dass es besser geht |
| **Navigation ist 3× kopiert** (Desktop-Leiste, Tab-Leiste/„Mehr"-Sheet, Startkacheln) plus Avatar-Menü 2× – inklusive kopierter SVG-Icons (~190 Zeilen). | Jede Änderung muss an 3–5 Stellen erfolgen; Inkonsistenzen sind programmiert |
| Tabellen haben nur einen einfachen Textfilter (`data-filter-table`, nur Benutzerseite), **keine Sortierung**, keine Spaltenfilter. | Listen mit vielen Einträgen (Hausarbeiten, Kategorien, Zeiten, Rechte) sind mühsam |

**Empfehlung in einem Satz:** Kalender als Startseite mit „Heute/Offen"-Panel und kontextsensitivem **＋-Schnellanlegen**;
eine **zentrale Einstellungs-Registry** (durchsuchbare, sortierbare Tabelle), die Einstellungen als **Drawer/Sheet über dem
Kalender** öffnet; ein **einheitlicher Rücksprung-Mechanismus** (`NavigationContext` + „Fertig"-Leiste); eine
**gemeinsame Tabellenkomponente**.

---

## 2. Analyse der Navigation (Ist)

### 2.1 Ebenen und Einstiegspunkte

```
Login → Start ("/")
          ├─ Hauptnavigation (Desktop, 12 Einträge)
          │    Start · Kalender · Zeiten · ToDos · Haushalt · Küche · Schule · Kontakte · Handbuch
          │    └ „Verwaltung": Benutzer · Rollen · Mitglieder
          ├─ Tab-Leiste (Handy, 4 + „Mehr")
          │    Start · Kalender · ToDos · Haushalt · [Mehr → Sheet mit allem + Benutzermenü]
          ├─ Werkzeuge rechts: Glocke · Farbschema · Avatar-Menü (8 Einträge)
          │    Benachrichtigungen · Meine Benachrichtigungswege · PIN fürs Tablet · KI-Zugänge ·
          │    Benachrichtigungen einstellen · Wand-Tablets · Sicherungen · Updates · Abmelden
          └─ Startkacheln (duplizieren die Hauptnavigation, per Drag sortierbar)
Modul-interne Navigation (jedes Modul anders):
   Kalender  : Segment Tag|3 Tage|Woche|Monat|Agenda, Toolbar mit 9 Steuergruppen
   ToDos     : Segment Liste|Board|Baum, „Austausch"-Menü
   Haushalt  : Segment Wochenplan|Heute|Hausarbeiten|Auswertung|Belohnungen
   Küche     : Tabs Essensplan|Rezepte|Einkauf|Vorrat|Lebensmittel + Einstellungen
   Schule    : Kind-Chips + Tabs (5) + Zahnrad-Menü (5 Einstellungsseiten)
   Kontakte  : Liste → Detail → Formular
```

### 2.2 Stärken (beibehalten)

- Kalender-Sheet für Schnellbearbeitung ohne Seitenwechsel; „Alle Details" führt mit `back` exakt zur Ansicht zurück.
- Zeitraster mit Tippen-zum-Anlegen (3 Aktionen für einen Termin), Drag & Drop, Zoom, Spalten je Person.
- Globaler Bestätigungsdialog (`confirmDialog`) mit Gefahren-Tonalität – konsistent, klein, wiederverwendet.
- Rechtegesteuerte Menüs (`sec:authorize`), `safeBack()` verhindert Open-Redirects.
- Handy-Tabbar + Bottom-Sheet ist ein bewährtes Muster.

### 2.3 Schwächen

1. **Kein klarer Mittelpunkt.** „Start" konkurriert mit „Kalender"; beide zeigen „Heute"-Information.
2. **Zeiten/Kategorien/Import/Export/Feiertage** sind Kalenderthemen, liegen aber außerhalb (Hauptnavigation bzw. Link
   unter der Legende – nur bei Recht `canManageCategories`, optisch ein Fußnoten-Link).
3. **Handy:** Küche, Schule, Kontakte, Zeiten und alle Einstellungen liegen hinter „Mehr" (+1 Klick); das „Mehr"-Sheet
   enthält Bereiche *und* das lange Benutzermenü untereinander (Scrollen).
4. **Kalender-Toolbar überladen:** ToDo-Schalter, Raster Grob/Fein, Zoom, „Nach Personen", Ansichtsumschalter, Export,
   Import, Termin + Paging – Anzeige-Optionen, Datenaustausch und Primäraktion stehen gleichrangig nebeneinander.
5. **Rücksprung uneinheitlich** (Details in 3.2).
6. **Begriffe:** „Zeiten" (Blocker/Arbeitszeiten), „Zeitkonto" (Haushalt), „Auswertung", „Benachrichtigungen" (Liste) vs.
   „Benachrichtigungen einstellen" (Admin) vs. „Meine Benachrichtigungswege" – ein Begriff pro Konzept (CLAUDE.md) ist nicht erfüllt.
7. **Quellcode-Duplikate:** Navigation/Icons 3×, Avatar-Menü 2×, `safeBack` als statische Methode in `TodoController`,
   obwohl von 7 Controllern genutzt (gehört in eine gemeinsame Hilfsklasse, ggf. `common`-Modul).

---

## 3. Analyse der Dialoge (Ist)

### 3.1 Inventar

| Typ | Vorkommen | Befund |
|---|---|---|
| **Globaler Bestätigungsdialog** | `confirmDialog` (Layout) | ✔ eine Instanz, gut |
| **Bottom-Sheet / Detail-Sheet** | `calendar-sheet` (Termin, Hausarbeit, ToDo, lazy per htmx), `nav-sheet` | ✔ Vorbild: eine Instanz, Inhalt wird geladen |
| **Schnellanlegen-Dialog** | `grid-quick` (Termin) | ✔ gut; „Mehr Details" wechselt zur Vollseite |
| **Auswahldialog** | `grid-series` (Serie ändern), `plan-pick` (Mahlzeit planen) | ✔ klein, zweckgebunden |
| **Dialog je Tabellenzeile** | `edit-user-{id}`, `edit-member-{id}`, `edit-category-{id}`, `restore-{n}` (Backup), `book-{id}` (Haushalt), Tablet-Karten | ✘ N-fach gerendert, doppeltes Markup, wächst mit den Daten |
| **Anlegen-Dialoge** | `create-user`, `create-member`, `create-role`, `create-category` | ◐ ok, aber Rollen werden z. T. inline bearbeitet, andere im Dialog |
| **Vollseiten-Formulare** | Termin, ToDo, Hausarbeit, Kontakt, Rezept, Lebensmittel, Zeit, Mahlzeit | ◐ sinnvoll bei vielen Feldern, aber ohne einheitliche Rückkehr |
| **Einstellungsseiten** | 22 Seiten (siehe 4.1) | ✘ Vollseiten mit eigenen Zurück-Links |

### 3.2 Rücksprung-Mechanik heute

- `back`-Parameter wird ausgewertet in: `CalendarController`, `TodoController`, `HouseholdController`,
  `HouseholdAccountController`, `MealPlanController`, `AttachmentController`, `UploadErrorAdvice` (+ Benachrichtigungs-Links).
- **Fest verdrahtet** (verlieren den Kontext): Kategorien → `/calendar/week`; Zeiten → `/times`; Kontakte, Küche
  (Rezepte, Lebensmittel, Einkauf), Haushalt-Hausarbeiten, Rollen/Rechte → jeweils Modul-Wurzel.
- Nach dem Speichern leiten Controller meist auf die Liste/Modul-Wurzel (`REDIRECT`) – nicht dorthin, woher man kam.
- Browser-„Zurück" funktioniert, ist aber bei POST-Redirect-Ketten unzuverlässig; Dialoge schreiben keinen History-Eintrag
  (Android-Zurück-Taste schließt kein Sheet, sondern verlässt die Seite).

### 3.3 Konsequenzen

- Drei Muster (Dialog / Sheet / Seite) für „Objekt bearbeiten" → Nutzer lernen keine Regel.
- Die Anzahl der Zeilendialoge macht Seiten schwer (Benutzer-/Mitglieder-/Kategorienliste) und verhindert
  Sortieren/Filtern per Skript, ohne dass die Dialoge mitwandern.
- Fehlende Rücksprung-Garantie ist der Hauptgrund für „Wo war ich eben?".

---

## 4. Aufgabenliste der App („Was ist hier zu erledigen?")

### 4.1 Aufgaben der Nutzer, nach Häufigkeit

**Täglich (Kern – muss in ≤ 2 Aktionen gehen)**
1. Tagesüberblick: was steht heute an (Termine, ToDos, Hausarbeiten, Schule)
2. Termin ansehen / anlegen / verschieben / absagen
3. ToDo abhaken, übernehmen, Status ändern, kommentieren
4. Hausarbeit erledigen / rückgängig / zuweisen / buchen
5. Einkaufsliste ansehen und Artikel abhaken / ergänzen
6. Hausaufgaben und Stundenplan-Änderungen (Kind) prüfen
7. Benachrichtigungen lesen und öffnen

**Wöchentlich**
8. Essensplan füllen, Mahlzeit verschieben, Einkaufsliste generieren / an Bring! senden
9. Hausarbeiten planen (Wochenplan), Belohnung einlösen / Einlösung genehmigen
10. Elternbriefe unterschreiben, Klassenarbeiten eintragen, Noten erfassen
11. Rezepte pflegen / importieren, Vorrat buchen, Wareneingang einräumen
12. Kontakte pflegen

**Gelegentlich / Konfiguration**
13. Kalender: Kategorien, Zeiten (Arbeit, Schule …), Feiertage/Bundesland, Ruhe-Ziele, Import/Export (iCal)
14. Küche: Bring!-Verbindung, Kalorienziele, Mahlzeiten-Slots
15. Schule: Kinder & Schuljahre, Schule & Stundenraster, Fächer & Lehrkräfte, Stunden & A/B-Wochen, Ferien
16. Haushalt: Hausarbeits-Katalog, Belohnungen, Wand-Tablets koppeln, PIN setzen
17. Persönlich: Benachrichtigungswege (E-Mail, Push), KI-Zugänge/Token, Farbschema

**Selten / Administration**
18. Mitglieder, Benutzer, Rollen, Rechte, Rechtebezeichnungen
19. Globale Benachrichtigungen (Erinnerungszeiten, Standard-Kanäle, fehlgeschlagene Zustellungen)
20. Sicherungen (Zeitplan, Aufbewahrung, Verschlüsselung, Ziele, Wiederherstellen, Hochladen)
21. Updates (Kanal, Prüfen, Starten)
22. Datenimport/-export (ToDos, Kontakte, Kalender)

### 4.2 „Offene Aufgaben" – was die App dem Nutzer zeigen sollte

Heute verteilt über Start-Ringe, Panels und Seiten; als **eine** Inbox gedacht („Heute & Offen"):

| Quelle | Beispiel | Aktion direkt in der Inbox |
|---|---|---|
| ToDos | fällig/überfällig, mir zugewiesen | abhaken |
| Haushalt | meine Hausarbeiten heute, offene Einlösungen (Genehmiger) | erledigen / entscheiden |
| Schule | Hausaufgaben fällig, ungelesene/ungesignte Elternbriefe, Prüfungen diese Woche, Stundenausfall | abhaken / unterschreiben |
| Küche | heute geplante Mahlzeit, Einkaufsliste offen | Einkauf öffnen |
| System (Admin) | Sicherung veraltet, Update verfügbar, fehlgeschlagene Zustellungen | zur Einstellung (Drawer) |
| Benachrichtigungen | ungelesen | öffnen |

---

## 5. Zielbild: Kalender im Zentrum

### 5.1 Informationsarchitektur („Hub & Ausflug")

```
                       ┌──────────────────────────────┐
                       │   KALENDER  (Startseite)     │
                       │  Ebenen: Termine · ToDos ·   │
                       │  Hausarbeiten · Zeiten ·     │
                       │  Schule · Mahlzeiten         │
                       └─────────────┬────────────────┘
        ┌───────────────┬────────────┼─────────────┬───────────────┐
   ＋ Schnellanlegen  Heute & Offen  Bereiche   Einstellungen   Suche (Strg+K)
   (Termin/ToDo/…)    (Inbox-Panel)  (Module)   (Drawer/Register)
                                         │            │
                          Ausflug ───────┴────────────┘  → „Fertig" bringt exakt zurück
```

Regeln:
1. **Kalender = Startseite** (pro Nutzer änderbar). Der bisherige Start-Inhalt wandert als Panel „Heute & Offen" in den
   Kalender (Desktop: rechte Spalte ab ≥ 1200 px; Handy: Tab „Heute").
2. **Drei Navigationsebenen, nicht mehr:** *Hub* (Kalender), *Bereiche* (Module), *Einstellungen*.
3. Jeder Ausflug hat einen definierten **Rückweg** (Abschnitt 5.4). Der Nutzer kehrt mit *einer* Aktion zurück.
4. **Kalender-Ebenen** (Chips in der Legende, auch als Filter nutzbar): Termine, ToDos, Hausarbeiten, Zeiten/Schule,
   Mahlzeiten (neu), Klassenarbeiten/Ferien. Kategorien und Personen in der Legende werden **klickbare Filter**.

### 5.2 Neue Hauptnavigation

Aus einer zentralen **Navigations-Registry** (`NavigationItem(id, labelKey, icon, route, group, permission, mobile)`),
aus der Desktop-Leiste, Tab-Leiste, „Mehr"-Sheet und Startkacheln erzeugt werden (löst das 3×-Kopierproblem, DRY).

| Gruppe | Einträge | Desktop | Handy |
|---|---|---|---|
| **Hub** | Kalender (inkl. Heute/Offen) | Leiste | Tab 1 |
| **Aufgaben** | ToDos · Haushalt (· Schule-Hausaufgaben als Verknüpfung) | Leiste | Tab 2 und 3 |
| **Familie** | Küche · Schule · Kontakte | Leiste | Tab 4 bzw. „Mehr" |
| **Schnellanlegen** | ＋ (Termin · ToDo · Hausarbeit buchen · Zeit · Einkaufsartikel · Hausaufgabe) | Button in der Kopfzeile | zentraler Tab-Button (Floating) |
| **Mehr / Konto** | Einstellungen · Verwaltung · Handbuch · Abmelden | Avatar-Menü (3 Einträge: Konto & Einstellungen, Handbuch, Abmelden) | „Mehr" |

- „Zeiten" wird **Teil des Kalenders** (Ebene + Schnellanlegen „Zeit" + Einstellung), kein eigener Hauptpunkt mehr.
- „Verwaltung" (Benutzer, Rollen, Mitglieder, Sicherungen, Updates …) wandert **ausschließlich in die Einstellungen**
  (Bereich „Familie & System"); die Nav-Sektion „Verwaltung" entfällt.
- Startkacheln entfallen (Navigation ist bereits sichtbar); Sortierung bleibt für das Panel „Heute & Offen".
- Tab-Leiste Handy: `Kalender · Heute · ＋ · Aufgaben · Mehr`. „Aufgaben" öffnet ToDos mit Segment ToDos | Haushalt | Schule.

### 5.3 Overlay-Regel: Dialog, Sheet oder Seite?

| Situation | Container | Beispiele |
|---|---|---|
| Schnelles Lesen/Ändern eines Objekts (≤ 5 Felder) | **Sheet** (Handy: von unten, Desktop: Seitenpanel rechts) | Termin, ToDo, Hausarbeit, Kategorie, Mitglied, Rolle zuweisen |
| Auswahl / Rückfrage / Serienwahl | **kleiner Dialog** | Serie ändern, Löschen bestätigen, Mahlzeit wählen |
| Einstellungen eines Bereichs | **Drawer** (≤ 520 px breit, Handy Vollbild-Sheet) mit Kopf „Titel · Fertig" | Kategorien, Zeiten, Benachrichtigungen, Bring!, Sicherungszeitplan |
| Viele Felder, Unterlisten, Anhänge, Mehrschritt | **Vollseite** mit Rückkehr-Leiste (5.4) | Rezept, Kontakt, Hausarbeit mit Terminen, Schuljahr einrichten, Sicherungen |
| Destruktive Aktion | **globaler Bestätigungsdialog** (besteht) | Löschen, Wiederherstellen |

Konsequenz: **Zeilendialoge entfallen.** Ein Container pro Seite, Inhalt per htmx von
`GET /…/{id}/edit?fragment=1` (wie `calendar-sheet`). Dieselbe Template-Datei liefert Vollseite *und* Fragment
(`th:fragment="content"`), keine Duplikate.

### 5.4 Konzept „Ausflug und Rückkehr"

**Ziel:** Vom Kalender (oder jeder anderen Seite) zu Einstellungen/Dialogen/Detailseiten – und danach *ohne weitere
Überlegung* zurück: gleiche Ansicht, gleiches Datum, gleicher Zoom, gleiche Filter, gleiche Scrollposition.

**Zwei Mechanismen, nach Gewicht gewählt:**

**A) Overlay (bevorzugt, Kosten 0 für den Kontext)**
- Drawer/Sheet liegt *über* dem Kalender; der Kalender wird nicht verlassen und nicht neu geladen.
- Beim Öffnen: `history.pushState({overlay: id})`, damit die Zurück-Taste (Android) das Overlay schließt.
- Beim Speichern: Fragment aktualisiert sich per htmx, Toast bestätigt; **„Fertig"/Schließen** führt zurück.
  Betrifft die Änderung den Kalender (z. B. neue Kategorie, neue Zeit), wird nur `#calendar` per `hx-select` neu geladen
  (besteht bereits für Paging) – Datum/Ansicht bleiben.
- Eingabe bleibt beim Schließen erhalten (Entwurf in `sessionStorage`, nur Hinweis „Ungespeicherte Änderungen verwerfen?").

**B) Vollseite mit Rückkehr-Leiste (für schwere Formulare)**
1. **Kontext erfassen:** Links mit `data-excursion` (oder alle Links aus dem Kalender zu fremden Bereichen) hängen
   `?back=<aktuelle URL inkl. Query>` an (Mechanismus existiert in `calendar-sheets.js: data-keep-back`).
   Zusätzlich speichert `nestwerk.js` Scrollposition + Zoom in `sessionStorage['nw.origin']`.
2. **Server:** ein request-scoped `NavigationContext` liest `back`, prüft es mit der zentralen `SafeRedirect`-Hilfe
   (heutiges `TodoController.safeBack`, nur lokale Pfade) und stellt es dem Template als `nav.back` bereit.
3. **Rückkehr-Leiste** (Layout, unter der Kopfzeile, nur wenn `back` gültig):
   `← Kalender · Woche 43 (Okt 2026)   |   [Fertig]`. „Fertig" und „Abbrechen" führen *immer* zum **Ursprung**.
4. **Formulare** tragen `back` automatisch als Hidden-Feld (Fragment `nav :: backField`), alle POST-Controller beenden mit
   `redirect:` + `NavigationContext.backOr(defaultTarget)` – statt der Modul-Wurzel.
5. **Verkettete Ausflüge** (Kalender → Kategorien → Kategorie bearbeiten): `back` bleibt unverändert der **Ursprung**
   (Wurzel), der unmittelbare Elternteil steht als normaler Link/Breadcrumb im Seitenkopf. So bringt „Fertig" nach beliebiger
   Tiefe zurück.
6. **Fallback ohne `back`** (Direktaufruf, Lesezeichen): zuletzt besuchter Kalender aus `localStorage['nw.lastCalendar']`
   (Ansicht+Datum+Filter), sonst `/calendar`. Behebt die fest verdrahteten Links (`/calendar/week`).
7. **Nach erfolgreichem Anlegen aus dem Kalender**: automatisch zurück und neuen Eintrag hervorheben (kurzes Highlight),
   Toast mit „Rückgängig".

**Zustand des Kalenders merken (Voraussetzung):** Ansicht, Zoom, „Nach Personen", Raster Grob/Fein und ToDo-Ebene
pro Nutzer in `localStorage` (Gerät) – optional als Nutzereinstellung serverseitig (neues Feld im Nutzerprofil, dann
auch per MCP steuerbar).

**Sicherheit/Qualität:** nur lokale Pfade (`/…`, kein `//`, kein `\`), Längenlimit (z. B. 512 Zeichen), keine
`/logout`/POST-Ziele; Tests für `SafeRedirect`/`NavigationContext` (Pflicht laut CLAUDE.md).

### 5.5 Schnellanlegen (＋) – kontextsensitiv

Ein Menü/Sheet an einer festen Stelle (Kopfzeile Desktop, mittiger Tab Handy). Vorbelegung aus dem Fokus des
Kalenders (fokussierter Tag, Spalte der Person, Uhrzeit der letzten Tippposition).

```
＋  ┌─ Termin            (Dialog wie grid-quick)
    ├─ ToDo              (kleines Sheet: Titel, fällig, Person → „Mehr" = Vollseite)
    ├─ Hausarbeit buchen
    ├─ Zeit (Arbeit/Schule/Ruhe)
    ├─ Einkauf: Artikel
    └─ Hausaufgabe (nur mit Schul-Recht)
```

Heute fehlen ToDo/Zeit/Einkauf im Kalender; ein ToDo aus dem Kalender anzulegen kostet 7 Aufwandspunkte (siehe 6).

### 5.6 Kalender-Toolbar entflechten

| Gruppe | Inhalt | Platzierung |
|---|---|---|
| Navigation | ‹ Heute › + Titel | links (bleibt) |
| Ansicht | Tag · 3 Tage · Woche · Monat · Agenda | Segment (bleibt) |
| **Anzeige ▾** (neu) | Raster Grob/Fein, Zoom, Nach Personen, Ebenen (ToDos, Hausarbeiten, Mahlzeiten …) | ein Popover |
| **Mehr ▾** (neu) | Import, Export, Kategorien, Zeiten, Feiertage, **Kalender-Einstellungen** | ein Menü; öffnet Drawer |
| Primäraktion | ＋ | rechts, hervorgehoben |

Spart ~4 gleichrangige Steuerelemente auf kleinen Displays und räumt die Toolbar (Textabschneiden-Risiko laut
CLAUDE.md-UI-Regel).

---

## 6. Bewertung der Klickpfade

### 6.1 Messmodell („Aufwandspunkte", AP)

| Ereignis | AP |
|---|---|
| Klick/Tipp | 1 |
| Eingabegruppe (Feld(er) ausfüllen, z. B. Formular) | 1 |
| Seitenwechsel mit **Kontextverlust** (Ansicht/Datum/Zoom/Filter neu aufbauen) | +2 |
| Handy: Weg über „Mehr" | +1 |
| Scrollen in langem Menü/Sheet | +1 |

Ziel: Häufige Aufgaben ≤ 3 AP, gelegentliche ≤ 5 AP, seltene ≤ 6 AP **ohne** Kontextverlust.

### 6.2 Ist/Soll

| # | Aufgabe (Start im Kalender) | Pfad heute | AP Ist | Pfad Soll | AP Soll |
|---|---|---|---|---|---|
| 1 | Termin anlegen | freie Zeit tippen → Titel → „Termin anlegen" | 3 | unverändert | 3 |
| 2 | Termin verschieben | Ziehen | 1 | unverändert | 1 |
| 3 | Termin: Ort/Beschreibung ändern | Eintrag → „Alle Details" → Bearbeiten → Felder → Speichern (mit `back`) | 4–5 | Eintrag → Sheet mit mehr Feldern (Ort, Beschreibung) → Speichern | 3 |
| 4 | **ToDo anlegen** | Nav „ToDos" (Handy ggf. +1) → „ToDo" → Felder+Speichern → zurück zum Kalender + Ansicht neu | 7 (8) | ＋ → ToDo → Titel/fällig → Anlegen (zurück automatisch) | 3 |
| 5 | **Kategorie anlegen** | Fußnotenlink „Kategorien verwalten" → „Kategorie anlegen" → Dialog+Speichern → „Zum Kalender" (landet in Woche, Datum weg) | 7 | Mehr ▾ → Kategorien (Drawer) → ＋ → Speichern → Fertig | 5 (ohne Verlust) |
| 6 | **Neue Zeit** (z. B. Arbeitszeit) | Nav „Zeiten" (Handy: Mehr +1) → Art wählen → Formular → Speichern → Kalender + Ansicht neu | 8 (9) | ＋ → Zeit → Art/Zeiten → Speichern | 4 |
| 7 | Einkaufsartikel ergänzen | Nav „Küche" (Handy +1) → Tab „Einkauf" → Eingabefeld → Hinzufügen → zurück | 5 (6) | ＋ → Einkauf → Artikel → Hinzufügen | 3 |
| 8 | ToDo abhaken (aus Kalender) | Eintrag → Sheet → Status | 2 | unverändert; zusätzlich Abhaken im Panel „Heute & Offen" | 1–2 |
| 9 | Hausarbeit erledigen | Eintrag → Sheet → Erledigt | 2 | unverändert | 2 |
| 10 | Hausaufgabe eintragen | Mehr (+1) → Schule → Tab Hausaufgaben → Formular → Speichern (Kind wählen +1) | 5–6 | ＋ → Hausaufgabe (Kind vorbelegt) → Speichern | 3 |
| 11 | Elternbrief unterschreiben | Mehr (+1) → Schule → Briefe → Unterschreiben | 4 | Panel „Heute & Offen": Brief → Unterschreiben | 2 |
| 12 | **Benachrichtigungswege ändern** | Avatar → „Meine Benachrichtigungswege" → Auswahl/Speichern → Kalender + Ansicht neu (Menü hat 9 Einträge) | 7 | Avatar → Einstellungen (Register, Suche „Benachr…") → Drawer → Fertig | 4 |
| 13 | Rolle zuweisen | Nav „Benutzer" (Handy: Mehr +1) → Bearbeiten (Dialog) → Rolle → Speichern → Kalender neu | 7 | Einstellungen → Familie & System → Benutzer (Tabelle) → Zeile → Sheet → Speichern → Fertig | 5 |
| 14 | Sicherung starten | Avatar → Sicherungen → „Jetzt sichern" | 3 | Panel-Hinweis „Sicherung veraltet → Jetzt sichern" | 1–2 |
| 15 | Stundenplan-Einstellung ändern | Schule → ⚙ → Stunden → bearbeiten → Speichern → zurück | 6–7 | Einstellungen → Schule (Register-Filter) → Drawer → Fertig | 5 |
| 16 | „Wo finde ich …?" (Einstellung suchen) | Handbuch/Raten über 9 Einstiegspunkte | 4–8 | Strg+K / Suchfeld im Einstellungsregister | 2 |

Auswertung: **Die teuersten Pfade sind genau die, in denen der Nutzer den Kalender verlässt** (Aufgabe 4, 5, 6, 12, 13)
– Ursache ist der Kontextverlust (+2) und der Umweg über die Module. Die Schnellanlegen-Idee und das Overlay-Muster
senken diese Pfade um 40–60 %.

### 6.3 Weitere Kennzahlen (Ist → Soll)

| Kennzahl | Ist | Soll |
|---|---|---|
| Einstiegspunkte zu Einstellungen | ≥ 9 | 1 (Register) + 1 kontextueller (⚙ / Mehr ▾) je Bereich |
| Hauptnavigationseinträge (Desktop) | 12 + 8 Menü | 5 + Avatar mit 3 |
| Navigationsdefinitionen im Code | 3–5 (Kopien) | 1 Registry |
| Dialoge im DOM je Listenseite | N (Zeilen) | 1 |
| Seiten mit garantiertem Rücksprung zum Ursprung | ca. 5 | alle Ausflüge |

---

## 7. Konzept: Einstellungen und Konfigurationen als filterbare/sortierbare Tabellen

### 7.1 Einstellungs-Register (`/settings`)

Eine Tabelle aller Einstellungen, aufrufbar über Avatar-Menü, ⚙ in jedem Bereich, „Mehr ▾" im Kalender und Strg+K.

| Spalte | Inhalt | Filter | Sortierung |
|---|---|---|---|
| **Bereich** | Kalender, Haushalt, Küche, Schule, Benachrichtigungen, Konto, Familie & System | Mehrfachauswahl (Chips) | ja |
| **Einstellung** | „Terminkategorien", „Bring!-Verbindung", „Sicherungszeitplan" … | Volltext (inkl. Schlagwörter) | ja (Standard) |
| **Geltung** | Ich · Familie · System | Auswahl | ja |
| **Aktueller Wert** | Kurzfassung: „6 Kategorien", „täglich 02:00", „nicht verbunden" | Text | optional |
| **Status** | ok · Aufmerksamkeit nötig (z. B. Sicherung veraltet) · nicht eingerichtet | Auswahl | ja |
| **Berechtigung** | benötigtes Recht; Zeilen ohne Recht werden *nicht* gezeigt | – | – |
| Aktion | **Öffnen** (Drawer) | – | – |

Verhalten:
- **Zeile = Einstieg.** Klick öffnet den Editor im Drawer (Mechanismus A), Zeilen mit Schwergewicht öffnen die Vollseite mit
  Rückkehr-Leiste (Mechanismus B).
- **Ansichten:** „Alle", „Persönlich", „Bereich X" als Voreinstellungen (Filterchips), zuletzt benutzter Filter bleibt.
- **Status „Aufmerksamkeit"** speist die Inbox (Sicherung, Update, fehlgeschlagene Zustellungen).
- Kontextuelles Öffnen: ⚙ im Kalender = Register mit vorgesetztem Filter *Bereich: Kalender* (Kategorien, Zeiten, Feiertage, Ruhe-Ziele, Import/Export).

Technische Skizze (Java, an CLAUDE.md-Stil orientiert):

```java
/** Beschreibt eine Einstellung, damit Register, Suche und MCP dieselbe Quelle nutzen. */
public record SettingDescriptor(
        String id,                 // "calendar.categories"
        SettingArea area,          // Enum: CALENDAR, HOUSEHOLD, KITCHEN, SCHOOL, NOTIFICATIONS, ACCOUNT, SYSTEM
        SettingScope scope,        // Enum: PERSONAL, FAMILY, SYSTEM
        String titleKey,
        Set<String> keywords,
        String requiredAuthority,  // null = jeder angemeldete Nutzer
        String editorPath,         // Pfad des Fragments bzw. der Vollseite
        EditorMode mode) {}        // DRAWER oder PAGE

public interface SettingSummary {  // Beans je Domänenmodul liefern Kurzwert + Status
    String settingId();
    SettingStatus status(User actor);
    String summary(User actor);
}
```

- Module registrieren ihre Einstellungen über Spring-Beans (Beans aller `SettingDescriptor`/`SettingSummary` werden vom
  `web`-Modul gesammelt) – Abhängigkeitsrichtung bleibt `core ← … ← web`.
- **MCP:** gemäß CLAUDE.md braucht jedes Feature MCP-Tools: `list_settings` (Register, readOnlyHint) mit Bereich/Text-Filter;
  Änderungen laufen weiter über die vorhandenen Fachtools (`kitchen_settings`, `notification_settings`, `set_backup_schedule`, …).
  Falls Navigations-Voreinstellungen (Startseite, Standardansicht) serverseitig gespeichert werden:
  `get_user_preferences`/`set_user_preferences`.

### 7.2 Gemeinsame Tabellenkomponente

Heute: ein Textfilter (`data-filter-table`, nur Benutzerseite), keine Sortierung, jede Tabelle handgebaut
(`users`, `roles`, `notifications`, `foods`, `stats`, `backup`, `import`). Neu: ein Fragment `table` + ein Skript `data-table`.

**Deklaration (Thymeleaf-Fragment):**

```html
<table class="table" data-table="categories" data-table-persist>
  <thead><tr>
    <th scope="col" data-col="name"   data-sort="text"   data-filter="text">Name</th>
    <th scope="col" data-col="color"  data-sort="none">Farbe</th>
    <th scope="col" data-col="usage"  data-sort="number" data-filter="range">Verwendet</th>
    <th scope="col" data-col="scope"  data-sort="text"   data-filter="select">Geltung</th>
    <th scope="col"><span class="visually-hidden">Aktionen</span></th>
  </tr></thead>
  <tbody>
    <tr data-row-id="12" data-open="/calendar/categories/12/edit?fragment=1">
      <td data-value="Arzt">Arzt</td> …
```

**Funktionen**
- **Sortieren:** Klick auf Spaltenkopf (auf-/absteigend), `aria-sort`, stabile Sortierung, Mehrspalten mit Umschalt-Klick;
  Typen `text` (locale-aware, `Intl.Collator('de')`), `number`, `date`, `enum` (definierte Reihenfolge), `boolean`.
- **Filtern:** globales Suchfeld (alle Textspalten) + Spaltenfilter (Text, Auswahl/Chips, Bereich, Datum, „nur Auffällige").
  Aktive Filter als entfernbare Chips über der Tabelle; Zähler „12 von 48".
- **Gruppieren** (optional): nach Spalte, einklappbare Gruppenköpfe (z. B. Hausarbeiten nach Raum, Rechte nach Modul).
- **Zustand:** in der URL (`?q=…&sort=name,asc&f.scope=family`) → teilbar, Browser-Zurück funktioniert;
  zusätzlich `localStorage` (`data-table-persist`) für den letzten eigenen Stand pro Tabellen-ID.
- **Zeilenaktion:** ganze Zeile klick-/tastaturbedienbar (`Enter`), öffnet Drawer/Sheet; Inline-Schalter (aktiv/inaktiv)
  per htmx ohne Dialog.
- **Mengen:** clientseitig bis ~200 Zeilen, darüber serverseitig (gleiche Parameter, Fragment-Tausch per htmx). ToDo-Filter
  (`data-auto-submit`, serverseitig) bleibt und nutzt dieselbe Chip-Darstellung.
- **Handy:** Tabelle wird zu **Karten** (`td::before` aus `data-label`), Spalten mit Priorität (`data-priority`) ein-/ausblendbar;
  nie `overflow: hidden` auf Text, Umbruch statt Abschneiden, Kürzung nur mit Ellipsis + `title` (CLAUDE.md UI-Regeln).
- **Barrierefreiheit:** echte `<table>`, `scope`, `aria-sort`, `aria-live` für Trefferzahl, Fokusführung beim Schließen des Drawers
  zurück auf die auslösende Zeile.

**Einsatzorte (Reihenfolge nach Nutzen):** Einstellungs-Register · Benutzer · Mitglieder · Rollen (Rechtematrix: Gruppierung nach Modul) ·
Kategorien · Zeiten · Hausarbeiten (Gruppierung Raum) · Belohnungen · Fächer/Stunden · Benachrichtigungsmatrix ·
Sicherungsdateien · Lebensmittel.

---

## 8. Umsetzungsplan

Jede Stufe liefert einen nutzbaren Stand, ist einzeln releasebar und schließt Tests ein (laut CLAUDE.md: Tests je Verhalten,
MCP-Tools getestet, `./mvnw verify`, Prüfung auf Handy/Tablet/Desktop ohne abgeschnittene Texte).

| Stufe | Inhalt | Aufwand* | Abhängig von | Wirkung |
|---|---|---|---|---|
| **0 – Sofortmaßnahmen** | Fest verdrahtete Zurück-Links beheben (Kategorien → letzter Kalender, Zeiten → Kalender); `localStorage['nw.lastCalendar']`; Kalender-Ansicht/Zoom/Personen merken; ToDo-Button in Kalender-Toolbar; Link „Kategorien verwalten" aus der Legende in „Mehr ▾" | 1–2 PT | – | AP 5, 6, 12: −2 je Pfad |
| **1 – Navigation-Registry** | `NavigationItem`-Registry, ein Fragment für Desktop-Leiste, Tab-Leiste, „Mehr"-Sheet; Icons als Sprite/Fragment; Avatar-Menü 1× statt 2×; Begriffe vereinheitlichen | 3–4 PT | – | DRY, Basis für alles Weitere |
| **2 – Rücksprung** | `SafeRedirect` (aus `TodoController` herauslösen, gemeinsam nutzbar), `NavigationContext`, Rückkehr-Leiste, `nav :: backField`, alle POST-Controller mit `backOr(...)`; History-Einträge für Overlays | 3–4 PT | 1 | Rückkehr in 100 % der Ausflüge |
| **3 – Overlay-Muster** | Generisches Drawer/Sheet (`data-open-sheet="url"` lädt Fragment), Umbau der Zeilendialoge (Benutzer, Mitglieder, Kategorien, Backup, Hausarbeit, Tablet) auf 1 Container, Fragment-Rendering der Vollseiten (`?fragment=1`) | 4–5 PT | 2 | Seiten leichter, Edits ohne Kontextverlust |
| **4 – Tabellenkomponente + Register** | `data-table` (Sortieren, Filtern, Chips, URL-Zustand, Karten auf dem Handy), `SettingDescriptor`/`SettingSummary`, `/settings`, MCP `list_settings`, Migration Benutzer/Mitglieder/Kategorien zuerst | 6–8 PT | 3 | Auffindbarkeit, Aufgabe 16 ≈ 2 AP |
| **5 – Kalender als Zentrum** | Kalender als Startseite, Panel „Heute & Offen" (Inbox), ＋-Schnellanlegen, Toolbar „Anzeige ▾/Mehr ▾", Ebenen Mahlzeiten/Klassenarbeiten, klickbare Legende | 6–8 PT | 1, 3 | AP 4, 6, 7, 10: 3–4 |
| **6 – Suche/Command-Palette** | Strg+K: Navigation, Einstellungen, Aktionen („Neuer Termin …"), zuletzt benutzt | 2–3 PT | 4 | Direktzugriff ≤ 2 AP |

\* PT = Personentage, grob; ohne Review/Tests-Puffer ~ +30 %.

**Reihenfolge-Empfehlung:** 0 → 1 → 2 → 3 → 4 → 5 → 6. Stufe 0 bringt sofort spürbaren Nutzen; 1 und 2 sind das
Fundament; 4 und 5 sind die sichtbaren Hauptgewinne.

### 8.1 Qualitätskriterien (Abnahme)

- Jeder Ausflug aus dem Kalender endet nach „Fertig"/Speichern in **derselben** Ansicht, auf **demselben** Datum und Zoom
  (automatisierter Test: Playwright über den `run-nestwerk`-Skill, Klickpfade aus 6.2 als Regressionstests mit AP-Obergrenze).
- Keine Navigationsdefinition außerhalb der Registry; keine Dialoge pro Tabellenzeile.
- `SafeRedirect`/`NavigationContext`: Unit-Tests (lokale Pfade ja; `//evil`, `\`, `javascript:`, zu lange Werte nein).
- MCP: `list_settings` getestet (Rechte: Nutzer ohne Recht sieht Eintrag nicht); weiterhin alle Features per MCP steuerbar.
- UI: Texte nie abgeschnitten (Drawer, Chips, Karten, Statusspalte) auf Handy/Tablet/Desktop; Fokusführung und `aria-*` geprüft.
- Alle neuen sichtbaren Texte deutsch, Code/Kommentare/Commit-Meldungen englisch (CLAUDE.md).

### 8.2 Risiken und Gegenmaßnahmen

| Risiko | Gegenmaßnahme |
|---|---|
| Gewohnheit: Start → Kalender als Startseite überrascht | Nutzereinstellung „Startseite" (Kalender/Heute-Dashboard), Hinweis im Handbuch (`manual.html`) |
| Vollseiten-Template-Umbau (Fragmente) bricht bestehende Controller | Stufenweise je Seite; Fragment-Modus nur bei `?fragment=1`, Vollseite unverändert |
| Drawer auf kleinen Handys zu eng | Auf Handy immer Vollbild-Sheet mit Kopf „Titel · Fertig" |
| `back`-Parameter wird lang/verschachtelt | Ursprung immer *unverändert*, Längenlimit, keine Verkettung |
| Registry-Beans erzeugen Kopplung | Nur Metadaten (Records), Domänenmodule kennen das `web`-Modul nicht; Sammlung im `web`-Modul |
| Berechtigungen im Register „durchsickern" | Descriptor trägt Pflichtrecht, Filterung serverseitig; Test je Eintrag |

---

## 9. Offene Entscheidungen

1. **Startseite:** Soll der Kalender für *alle* Nutzer Standard werden (Empfehlung) oder nur für Nutzer mit Kalenderrecht, andere behalten „Start"?
2. **Mahlzeiten und Klassenarbeiten als Kalender-Ebenen:** gewünscht (Empfehlung) oder bewusst im Fachmodul belassen?
3. **Desktop-Overlay:** Seitenpanel (Drawer rechts, Empfehlung) oder zentrierter Dialog für Einstellungen?
4. **Speicherort der Anzeigeeinstellungen** (Ansicht, Zoom, Personen): nur Gerät (`localStorage`, Empfehlung für Start) oder Nutzerprofil (dann zusätzlich MCP-Tool)?
5. **Handbuch:** soll die Kalender-/Einstellungs-Navigation nach Umsetzung im Handbuch (`manual.html`) und README beschrieben werden (ja, als Teil von Stufe 5)?
