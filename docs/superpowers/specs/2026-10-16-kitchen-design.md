# Küche: Rezepte, Essensplan, Einkauf und Vorrat – Design

## Warum
Die Familie plant Essen bisher im Kopf und kauft mit Bring! ein. Rezepte, Essensplan, Vorrat und Einkaufsliste sollen
in Nestwerk zusammenkommen: Aus dem Plan entsteht die Liste, die Liste geht an Bring!, Gekauftes landet im Vorrat,
Gekochtes wird abgebucht. Zu jedem Rezept will der Nutzer die Kalorien sehen und am Plan die Tagesbilanz je Person.

## Ziele
- **Lebensmittel** mit Nährwerten pro 100 g, Stückgewicht und Haltbarkeit; Grundstock gängiger Zutaten; Barcode-Scan
  mit Open Food Facts; manuelle Pflege.
- **Rezepte** mit Zutaten (Menge, Einheit, Lebensmittel), Schritten, Portionen, Dauer, Schlagworten, Foto;
  Kalorien und Makros je Rezept und Portion; Skalierung. Import per Link (schema.org/Recipe, auch Cookidoo-Seiten),
  Anlegen über MCP (Claude liest Foto oder Text und legt das Rezept an).
- **Essensplan** je Woche mit frei wählbaren Mahlzeiten, Mitessern und Portionen; Drag & Drop; „Woche auffüllen“.
- **Kalorienziele** je Person mit Tagesbilanz und Portionsvorschlag.
- **Vorrat** mit Menge, Ort und MHD; „Gekocht“ bucht ab; Warnungen vor Ablauf und Rezepte zum Verbrauchen.
- **Einkaufsliste** aus dem Plan minus Vorrat plus freie Artikel; an Bring! senden; Gekauftes von Bring! zurücklesen;
  „Einräumen“ zum Erfassen im Vorrat.
- **Rechte** über Rollen.

## Nicht-Ziele
- Keine Nährwert-Datenbank mit Anspruch auf Vollständigkeit (Grundstock + Open Food Facts + eigene Pflege).
- Kein Login bei Cookidoo und keine Synchronisation ganzer Cookidoo-Sammlungen.
- Keine Einkaufs-App-Funktionen jenseits von Bring! (Preise, Märkte, Angebote).
- Keine Mikronährstoffe, nur kcal, Eiweiß, Fett, Kohlenhydrate.

## Anti-Ziele
- Ein Ausfall von Bring!, Open Food Facts oder einer Rezeptseite darf nur die jeweilige Funktion betreffen und muss mit
  einer verständlichen Meldung enden – nie mit einer Fehlerseite oder hängenden Seite.
- Nichts wird stillschweigend abgebucht oder gelöscht: „Gekocht“ und „Einräumen“ zeigen vorher, was passiert.
- Fehlende Nährwerte werden sichtbar markiert statt als 0 kcal gezählt.

## Modell (Migration `V21__kitchen.sql`)
- `foods`: Name (eindeutig ohne Groß/Klein), Kategorie (für die Gruppierung der Liste: Obst & Gemüse, Kühlregal,
  Brot & Backwaren, Fleisch & Fisch, Vorrat, Tiefkühl, Getränke, Gewürze, Sonstiges), kcal/Eiweiß/Fett/KH pro 100 g
  (alle optional), Stückgewicht g (optional), Packungsgröße g (optional), Haltbarkeit in Tagen (optional), Barcode
  (optional, eindeutig), Herkunft (`SEED`, `OFF`, `MANUAL`).
- `recipes`: Titel, Beschreibung, Portionen, Dauer in Minuten, Schlagworte (Text, kommagetrennt), Quelle (URL),
  Schritte (Markdown), angelegt von, Zeitstempel. Foto als Anhang (`AttachmentOwner.Type.RECIPE`).
- `recipe_ingredients`: Rezept, Position, Menge (optional), Einheit, Lebensmittel (optional), Text (Originalzeile bzw.
  Name, wenn kein Lebensmittel zugeordnet), Notiz.
- `meal_slots`: Name, Reihenfolge, Anteil am Tagesbedarf in %, „beim Auffüllen planen“. Grundstock: Frühstück 25 %,
  Mittagessen 35 %, Abendessen 30 % (auffüllen), Snack 10 %.
- `meals`: Tag, Mahlzeit (Slot), Rezept (optional) oder freier Titel, gekocht am. `meal_eaters`: Mahlzeit, Person,
  Portionen (Dezimal, Standard 1).
- `family_members.kcal_goal` (optional, kcal pro Tag).
- `pantry_items`: Lebensmittel (optional) oder Name, Menge, Einheit, Ort (Text mit Vorschlägen Kühlschrank, Vorrat,
  Tiefkühler), MHD (optional), hinzugefügt am.
- `shopping_items`: Name, Lebensmittel (optional), Menge, Einheit, Notiz, Herkunft (`PLAN`, `MANUAL`), Status
  (`OPEN`, `SENT`, `BOUGHT`, `STORED`), an Bring! gesendet am, gekauft am.
- Einstellungen in `app_settings` (`kitchen.*`): Bring!-E-Mail, Passwort (verschlüsselt mit `SecretBox`),
  Bring!-Liste (UUID + Name).

## Mengen und Nährwerte
- Einheiten: `g`, `kg`, `ml`, `l`, `Stück`, `EL` (15 ml), `TL` (5 ml), `Prise` (0,5 g), `Packung`, `Bund`, `Dose`,
  `Scheibe`. Umrechnung in Gramm: Masse direkt, Volumen 1 ml = 1 g, `Stück`/`Bund`/`Scheibe` über Stückgewicht,
  `Packung`/`Dose` über Packungsgröße. Nicht umrechenbar → Zutat zählt nicht, Rezept zeigt „Nährwerte unvollständig“
  mit den betroffenen Zutaten.
- Nährwerte je Rezept = Summe der Zutaten; je Portion = Summe / Portionen. Skalierung im Rezept per Portionenwahl.
- Zeilen-Parser für Import und MCP: „200 g Mehl“, „2 EL Olivenöl“, „1 ½ Zwiebeln“, „Salz“, „1-2 Knoblauchzehen“
  (nimmt den ersten Wert), Brüche und Komma-Dezimal. Lebensmittel-Zuordnung: exakter Name, dann einfache Plural-/
  Singularform, dann Lebensmittel, dessen Name im Text vorkommt (längster Treffer). Kein Treffer → ohne Lebensmittel.

## Integrationen
- **Abruf von außen** über einen gemeinsamen sicheren Abrufer (heute `IcalFetcher`: nur http/https, keine internen
  Adressen, keine Weiterleitungen, Zeitlimit) mit wählbarer Größengrenze (Rezeptseiten bis 3 MB).
- **Rezept-Import per Link**: JSON-LD-Blöcke der Seite lesen, `Recipe` auch in `@graph` oder Listen finden; Titel,
  Portionen (`recipeYield`), Zutaten (`recipeIngredient`), Schritte (Text, `HowToStep`, `HowToSection`), Dauer
  (`totalTime` bzw. Summe aus `prepTime` + `cookTime`), Quelle. Das Ergebnis öffnet das Rezeptformular vorausgefüllt –
  gespeichert wird erst nach Prüfung. Cookidoo-Rezeptseiten haben dieselben Daten; dafür ist kein Login nötig.
- **Open Food Facts**: `GET https://world.openfoodfacts.org/api/v2/product/{code}.json` mit eigenem User-Agent;
  übernimmt Name (deutsch bevorzugt), Marke, Nährwerte pro 100 g, Packungsgröße. Ergebnis legt ein Lebensmittel an
  (Herkunft `OFF`) oder findet das vorhandene mit dem Barcode.
- **Bring!** (inoffizielle Web-API, wie die Web-App): Anmeldung mit E-Mail/Passwort, Listen abrufen, Artikel mit
  Spezifikation („500 g“) hinzufügen, Liste lesen. Rücklesen: Artikel, die Nestwerk gesendet hat und die bei Bring!
  nicht mehr unter „zu kaufen“ stehen, gelten als gekauft → Status `BOUGHT` → erscheinen unter „Einräumen“.
  Zugangsdaten werden verschlüsselt gespeichert; das Token nur im Speicher.
- Alle drei hinter eigenen Client-Schnittstellen; Tests nutzen `MockRestServiceServer` bzw. Fakes, kein echter Zugriff.

## Essensplan
- Wochenansicht (Mo–So) × Mahlzeiten; jede Zelle hat Einträge mit Rezept/Titel, Mitessern und Portionen.
- Rezeptleiste mit Suche; Rezepte per Drag & Drop in Zellen, Einträge zwischen Zellen verschieben; mobil: Antippen →
  Blatt mit Auswahl.
- Neue Mahlzeit: Mitesser = alle Personen; Portionen je Person = Vorschlag (siehe Kalorienziele), sonst 1.
- **Woche auffüllen**: Für jeden leeren Slot mit „beim Auffüllen planen“ ab heute bis Sonntag ein Rezept wählen.
  Bewertung: nicht in den letzten 14 Tagen und nicht schon in dieser Woche geplant; Pluspunkte für Rezepte, die
  Vorrat mit MHD in den nächsten 5 Tagen verbrauchen; Portion nahe am Kalorienziel; an vollen Tagen (Termine +
  Blocker der Mitesser im Zeitraum 16–20 Uhr) nur Rezepte mit Dauer ≤ 30 Minuten. Zufall entscheidet bei Gleichstand.
- „Gekocht“ an einer Mahlzeit: Blatt mit den Zutaten (skaliert auf die Summe der Portionen) und den Vorratsposten,
  von denen abgebucht wird; Mengen korrigierbar; Bestätigen bucht ab (ältestes MHD zuerst) und setzt „gekocht am“.

## Kalorienziele
- Ziel je Person (kcal/Tag) auf der Personenverwaltung bzw. der Küchen-Einstellungsseite.
- Tagesbilanz im Plan je Person: Summe aus kcal/Portion × Portionen aller Mahlzeiten mit ihr als Mitesser; Anzeige
  „1 850 / 2 000 kcal“, farbig bei > 110 %.
- Portionsvorschlag = Ziel × Anteil des Slots / kcal je Portion, auf 0,25 gerundet, zwischen 0,5 und 3.

## Vorrat
- Liste gruppiert nach Ort, sortiert nach MHD; Ablaufendes (≤ 2 Tage) markiert; Abgelaufenes rot.
- Hinzufügen per Formular oder Barcode (Lebensmittel per Open Food Facts), Menge ändern, entnehmen, löschen.
- Täglich um 8 Uhr eine Benachrichtigung an Nutzer mit Einkauf-/Vorratsrecht, wenn Posten in den nächsten 2 Tagen
  ablaufen (eine Sammelnachricht, nur an Tagen mit Treffern).
- „Zum Verbrauchen“: Rezepte, die ablaufende Lebensmittel enthalten.

## Einkaufsliste
- „Aus Plan erzeugen“ für einen Zeitraum (Standard: heute bis Sonntag): Bedarf aller ungekochten Mahlzeiten je
  Lebensmittel in Gramm (bzw. Stück, wenn nur in Stück), minus Vorrat (umgerechnet), positive Reste werden Artikel
  mit Herkunft `PLAN`. Zutaten ohne Lebensmittel werden je Text zusammengefasst ohne Abzug. Erneutes Erzeugen ersetzt
  nur offene `PLAN`-Artikel. Mengen werden auf sinnvolle Schritte gerundet (Packungsgröße, sonst 50 g / 1 Stück).
- Freie Artikel per Eingabezeile; abhaken in der App = gekauft.
- „An Bring! senden“: alle `OPEN`-Artikel → Bring!, Status `SENT`. „Von Bring! abgleichen“ (Knopf und alle 15 Minuten,
  wenn Bring! eingerichtet ist und gesendete Artikel offen sind).
- „Einräumen“: gekaufte Artikel mit Menge, Ort, MHD (Vorschlag: heute + Haltbarkeit) bestätigen → Vorrat; Barcode-Scan
  ordnet einen Artikel einem Produkt zu.

## Oberfläche
- Navigation „Küche“ mit Unterseiten Essensplan, Rezepte, Einkauf, Vorrat (Tabs oben), Lebensmittel und Einstellungen
  (Bring!, Mahlzeiten, Ziele) über das Menü der Küche. Eclipse-Stil, mobil benutzbar, Blätter statt Dialoge auf dem
  Handy.
- Barcode: Kamera über `BarcodeDetector` (wo vorhanden), sonst Eingabefeld für die Nummer.

## Rechte (Modul „Küche“)
- `KITCHEN_VIEW` – Küche ansehen; `KITCHEN_RECIPES_EDIT` – Rezepte und Lebensmittel pflegen; `KITCHEN_PLAN` –
  Essensplan bearbeiten und „Gekocht“; `KITCHEN_SHOPPING` – Einkauf und Vorrat; `KITCHEN_SETTINGS` – Bring!-Konto,
  Mahlzeiten und Kalorienziele. Rolle „Benutzer“ bekommt alle außer `KITCHEN_SETTINGS`.

## MCP
- `search_foods`, `create_food`, `search_recipes`, `get_recipe`, `create_recipe` (Zutaten als Textzeilen, werden
  geparst und zugeordnet; Antwort nennt nicht zugeordnete Zutaten), `plan_meal`, `get_meal_plan`, `shopping_list`,
  `add_shopping_item`, `list_pantry`. Rechte wie in der Oberfläche.

## Entscheidungen, die ich getroffen habe
- Cookidoo über den Link-Import der öffentlichen Rezeptseite statt über die inoffizielle Login-API: kein gespeichertes
  Cookidoo-Passwort, weniger bruchanfällig; ganze Sammlungen bleiben außen vor.
- Bring! über die inoffizielle API der Web-App; Rücklesen über „nicht mehr zu kaufen“.
- 1 ml = 1 g für alle Lebensmittel (keine Dichten).
- Grundstock mit Durchschnittswerten (etwa 150 Lebensmittel) als Teil der Migration.
- Barcode ohne zusätzliche JS-Bibliothek: `BarcodeDetector`, sonst Nummer eintippen.
- Rechte als Rollenrechte (nicht ACL wie Haushalt), weil der Nutzer „Rechte über Rollen“ wollte.
- Mahlzeiten als konfigurierbare Slots mit Anteil am Tagesbedarf; Auffüllen nur für markierte Slots.
