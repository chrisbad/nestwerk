# Nestwerk: Implementierungsliste

Reihenfolge, in der umgesetzt wird. Erledigtes bleibt zur Nachverfolgung stehen.

| # | Vorhaben | Spec / Plan | Stand |
|---|----------|-------------|-------|
| 1 | Modernes UI (CSS, htmx, Dark Mode) | – | erledigt |
| 2 | Phase 1: Rollen/Rechte, Familienmitglieder, Layout | `plans/2026-10-13-phase-1-rollen-mitglieder-layout.md` | erledigt |
| 3 | Phase 2: Kalender-Kern | `plans/2026-10-13-phase-2-kalender-kern.md` | erledigt |
| 4 | Nacharbeiten: Rechte ausgrauen, Längenprüfung Ort/Notizen, Lese-Ansicht für fremde Termine | – | erledigt |
| 5 | ToDo-Backlog mit ACL und Benachrichtigungen (Stufen 0–6) | `specs/2026-10-14-todo-design.md`, Pläne `plans/2026-10-14-todo-stufe-*.md` | erledigt (Stufen 0–6) |
| 6 | Haushalt: Putzplan mit Verteilung, Zeitkonto, Belohnungen, Wand-Tablet und iCal-Import | `specs/2026-10-14-haushalt-design.md`, `plans/2026-10-14-haushalt.md` | erledigt (Stufen 1–3); später: Abgleich mit Google-Kalender (mit Phase 4) |
| 6a | KI-Anbindung: MCP-Server unter `/mcp` mit persönlichen Token | `specs/2026-10-15-mcp-design.md` | erledigt |
| 6b | Sicherung (Zeitplan, Ziele, Verschlüsselung), Ex-/Import (iCal, vCard/CSV, ToDo-CSV), Installation per Skript mit nginx-proxy | `specs/2026-10-16-backup-design.md` | erledigt |
| 6c | Updates aus der App: Versionsprüfung (Releases/main), Updater-Container mit Sicherung und Rückfall | `specs/2026-10-16-updates-design.md`, `plans/2026-10-16-updates.md` | erledigt |
| 6d | Blocker: Arbeits-, Schlaf-, Ruhe- und Freizeiten, Ruhe-Ziele, Haushalt mit Uhrzeiten | `specs/2026-10-16-blocker-design.md`, `plans/2026-10-16-blocker.md` | erledigt |
| 6e | Küche: Lebensmittel, Rezepte mit Kalorien, Essensplan, Vorrat, Einkauf mit Bring! | `specs/2026-10-16-kitchen-design.md`, `plans/2026-10-16-kitchen.md` | erledigt |
| 7 | Phase 3: Erinnerungen und iCal-Feed | `plans/2026-10-13-phase-3-erinnerungen-feed.md` | offen; Plan vorher auf Migrationsnummern ab V15 und den vorhandenen Benachrichtigungskern (notifications, Kanäle, Zeitplaner) umstellen |
| 8 | Phase 4: CalDAV-Sync | `plans/2026-10-13-phase-4-caldav.md` | offen; Migrationsnummern anpassen; App-Passwörter getrennt von Push/Mail |
