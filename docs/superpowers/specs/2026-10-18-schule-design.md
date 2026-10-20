# Schulmodul – Design (Entwurf)

Stand: Entwurf, gemeinsam zu klären. Branch: `feature/schule`.

## Ziel

Familien mit Schulkindern verwalten Stundenplan, Hausaufgaben, Klassenarbeiten, Ferien, Noten und Elternbriefe an
einer Stelle. Das Modul nutzt Kalender, ToDos, Blocker und Benachrichtigungen, statt sie nachzubauen.

## Entscheidungen

| Thema | Entscheidung |
|---|---|
| Umfang | alle Funktionen in einem Durchgang (Stundenplan, Hausaufgaben, Klassenarbeiten & Ferien, Noten, Elternbriefe) |
| Schüler | bestehende Familienmitglieder (`FamilyMember`) mit Schulprofil; Kinder brauchen keinen Login |
| Schuljahr | eigener Datensatz je Kind; Schule, Klasse, Fächer, Stundenplan und Noten hängen daran, alte Jahre bleiben lesbar |
| Integration | Kalender, ToDos, Blocker, Benachrichtigungen, MCP |
| Ferien | OpenHolidays API (`openholidaysapi.org`, JSON je Bundesland), manuell korrigierbar |
| Stundenplan | Wochenraster mit A/B-Wochen, Vertretung/Ausfall je Tag |
| Rechte | eigene Schulrechte; Noten getrennt: alle Noten (Eltern) oder nur die eigenen (Kind mit verknüpftem Login) |
| Noten | Skala 1–6 mit Tendenz (+/-), optional Gewichtung mündlich/schriftlich |

## Modul und Abhängigkeiten

Neues Maven-Modul `nestwerk-school`:

- hängt ab von `core`, `calendar` (Termine, Blocker), `notification`, `todo`, `attachment` (Elternbriefe)
- wird von `nestwerk-web`, `nestwerk-ai` und `nestwerk-app` eingebunden
- Rechte über `SchoolPermissionSource` (`PermissionSource`), Migration ab V24

## Datenmodell (Skizze)

- `school_profile`: Familienmitglied, Bundesland
- `school_year`: Profil, Bezeichnung (z. B. 2026/27), Schule, Klasse, von, bis, aktiv (je Profil genau ein aktives Jahr)
- `school_subject`: Schuljahr, Name, Farbe, Lehrkraft (optional)
- `school_lesson`: Fach, Wochentag, Beginn, Ende, Raum, Wochenart (`EVERY`, `A`, `B`)
- `school_lesson_change`: Datum, Stunde, Art (`CANCELLED`, `SUBSTITUTE`), Hinweis
- `school_holiday`: Bundesland, Name, von, bis, Quelle (`ONLINE`, `MANUAL`)
- `school_homework`: ToDo-ID, Fach (ToDo trägt Fälligkeit, Zuweisung, Erledigt-Status)
- `school_exam`: Fach, Art (Klassenarbeit, Test, Elternabend, Ausflug), Datum, Kalendertermin-ID, Lernerinnerung in Tagen
- `school_grade`: Fach, Halbjahr, Note (Zehntel für Tendenz), Gewichtung, Art (mündlich/schriftlich), Datum, Notiz
- `school_letter`: Titel, Frist, Anhang, unterschrieben am/von

## Integration

- **Blocker:** Stundenplan erzeugt Blocker der neuen Art `SCHOOL`; Ferien und Vertretungs-/Ausfalltage wirken als
  Ausnahmen. Der Familienkalender zeigt die Schulzeit als Band.
- **ToDos:** Hausaufgaben sind ToDos mit Zuweisung an das Kind; Fachzuordnung über `school_homework`.
- **Kalender:** Klassenarbeiten, Elternabende und Ausflüge sind Kalendertermine; Ferien erscheinen als ganztägige Termine.
- **Benachrichtigungen:** Lernerinnerung vor Klassenarbeiten, Erinnerung an Elternbrief-Fristen, Hinweis bei Ausfall.
- **MCP:** Tools für Stundenplan heute/morgen, Hausaufgaben, Termine, Noten (nur mit Notenrecht), Elternbriefe.

## Rechte

`SCHOOL_VIEW`, `SCHOOL_EDIT` (Stundenplan, Fächer, Klassenarbeiten, Briefe), `SCHOOL_HOMEWORK` (Hausaufgaben
anlegen/erledigen), `SCHOOL_GRADES` (Noten aller Kinder sehen und pflegen), `SCHOOL_GRADES_OWN` (nur eigene Noten sehen; gilt für das
Familienmitglied, das per `userId` mit dem Benutzer verknüpft ist), `SCHOOL_SETTINGS` (Ferienquelle, Profile).

## Schuljahreswechsel

Ein neues Schuljahr entsteht aus dem alten (Fächer und Stundenplan werden auf Wunsch kopiert, Noten und
Hausaufgaben nicht). Das alte Jahr wird inaktiv, bleibt les- und auswertbar. Die Oberfläche zeigt standardmäßig nur das
aktive Jahr; eine Auswahl erlaubt den Blick in frühere Jahre. Offene Hausaufgaben (ToDos) bleiben unberührt.

## Ferienquelle

Abruf von OpenHolidays (nach Bundesland, Schuljahr im Voraus), Ergebnis in `school_holiday` zwischenspeichern, bei
Ausfall mit den gespeicherten Daten weiterarbeiten, manuell korrigierbar. Abruf höchstens einmal täglich je
Bundesland. Zu klären vor der Umsetzung: Lizenz/Nutzungsbedingungen (Seite „Data Sources“ des Projekts prüfen).

## Weitere Festlegungen

- **Noten:** gewichteter Durchschnitt je Fach (mündlich/schriftlich); die Zeugnisnote je Halbjahr wird separat erfasst
  und neben dem errechneten Schnitt angezeigt.
- **Wiederkehrende Hausaufgaben:** über die vorhandenen ToDo-Serien (`rrule`/`series`), kein eigener Mechanismus.
- **Wand-Tablet:** Stundenplan und Hausaufgaben von heute erscheinen schon in der ersten Version auf dem Tablet des
  Haushalts (Anzeige ohne Login wie dort üblich; Noten nie).
- **Startseite:** Kachel „Heute in der Schule“ (Stunden heute, fällige Hausaufgaben, nächste Klassenarbeit,
  Ausfall-Hinweise) und ein Kapitel im Handbuch.

## Offene Punkte vor der Umsetzung

1. Lizenz/Nutzungsbedingungen der OpenHolidays-Daten prüfen.
2. Umsetzungsplan in Stufen schneiden (siehe ToDo-Modul als Vorbild).
