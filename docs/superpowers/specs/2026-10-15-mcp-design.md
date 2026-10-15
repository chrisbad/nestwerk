# KI-Anbindung: MCP-Server

Stand: 2026-10-15 · Status: umgesetzt

## Ziel

Ein KI-Assistent (Claude Code, Claude Desktop oder ein anderer MCP-Client) soll Nestwerk bedienen können:
Termine ansehen und anlegen, ToDos verwalten, Hausarbeiten abhaken, Kontakte nachschlagen. Er handelt dabei
immer als ein bestimmter Benutzer und hat genau dessen Rechte.

## Entscheidungen

| Thema | Entscheidung |
|---|---|
| Bibliothek | Spring AI 2.0.1, `spring-ai-starter-mcp-server-webmvc`, Tools per `@McpTool` |
| Protokoll | Streamable HTTP, **zustandslos** (`protocol: stateless`) unter `/mcp`: keine Sitzungen, funktioniert hinter jedem Reverse Proxy und bei Neustarts |
| Anmeldung | persönliches Bearer-Token je Benutzer (`nw_…`), nur als SHA-256-Hash gespeichert, Klartext einmalig angezeigt, widerrufbar, optional mit Ablaufdatum |
| Recht | `AI_ACCESS` („KI-Zugang nutzen“). Ohne dieses Recht kann man kein Token anlegen, und vorhandene Token werden abgewiesen. Auch gesperrte Benutzer werden abgewiesen. |
| Sicherheit | eigene Filterkette für `/mcp/**` (`@Order(1)`, zustandslos, ohne CSRF und Formular-Login). Die Web-Oberfläche bleibt unverändert. |
| Benutzer im Tool | Der Filter legt die Benutzer-ID am Request ab, der Transport gibt sie im `McpTransportContext` weiter, `McpCaller` lädt den Benutzer |
| Rechteprüfung | ausschließlich in den bestehenden Services (`…Service.x(…, actor)`). Die Tools sind dünne Adapter. |
| Umfang | Lesen und Schreiben, **kein Löschen** |
| Fehler | Service-Ausnahmen werden zu deutschen Tool-Fehlern (`isError: true`), interne Fehler nur als Hinweis aufs Server-Log |

## Tools

- **Überblick:** `whoami`, `today_overview`, `list_members`
- **Kalender:** `list_events`, `get_event`, `create_event`, `update_event`
- **ToDos:** `list_todos`, `get_todo`, `create_todo`, `update_todo` (mit `version`), `set_todo_status`, `check_todo`, `uncheck_todo`, `add_todo_comment`
- **Haushalt:** `list_chore_tasks`, `my_chore_tasks`, `complete_chore_task`, `undo_chore_task`
- **Kontakte:** `search_contacts`, `get_contact`
- **Benachrichtigungen:** `list_notifications`, `mark_notifications_read`

Datumsangaben im Format JJJJ-MM-TT, Uhrzeiten als HH:MM in der Zeitzone der App. Ganztägige Termine geben den
letzten Tag einschließlich zurück.

## Später denkbar

- Weitere Tools: Belohnungen, Hausarbeiten verwalten, Termin-Ausnahmen bei Serien
- OAuth statt statischer Token, sobald die verbreiteten Clients das bequem unterstützen
