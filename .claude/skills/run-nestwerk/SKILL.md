---
name: run-nestwerk
description: Run, start, build, smoke-test or screenshot the Nestwerk family app locally (Spring Boot + Postgres) and drive its web UI (calendar, todos, household) in the browser. Use when asked to launch Nestwerk, check a UI change in the browser, or take screenshots.
---

# Run Nestwerk

Nestwerk is a Spring Boot web app (Java 25, Maven multi-module) on Postgres. The agent path is
`driver.ps1`: it starts a **throwaway** Postgres container and the built app jar on
http://localhost:8099, smoke-tests it with `curl`, and stops everything again. The browser is driven
with the Playwright MCP tools via `browser-login.js`.

All paths are relative to the repository root. Commands are Windows PowerShell 5.1.

## Prerequisites

- JDK 26 at `%USERPROFILE%\.jdks\openjdk-26` (the system `JAVA_HOME` is JDK 17 and cannot build release 25).
- Docker Desktop running (`docker ps` works). Image `postgres:17` is pulled on first start.

## Build

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\openjdk-26"; .\mvnw.cmd -q -DskipTests package
```

Produces `nestwerk-app/target/nestwerk-0.0.1-SNAPSHOT.jar` (the timestamp shows 1980 – reproducible
build, not stale). Rebuild after Java changes; template, CSS and JS changes need no rebuild (see below).

## Run (agent path)

```powershell
powershell -ExecutionPolicy Bypass -File .claude\skills\run-nestwerk\driver.ps1 start
powershell -ExecutionPolicy Bypass -File .claude\skills\run-nestwerk\driver.ps1 smoke
powershell -ExecutionPolicy Bypass -File .claude\skills\run-nestwerk\driver.ps1 smoke -Path /calendar/day
powershell -ExecutionPolicy Bypass -File .claude\skills\run-nestwerk\driver.ps1 stop
```

- `start` waits until `/actuator/health` is UP (~20 s) and prints the login: `admin` / `test-admin-pw`.
  The database is empty apart from Flyway seeds; every start begins fresh.
- `smoke` logs in with the CSRF token from `/login` and prints status and `<title>` of a page,
  e.g. `GET /calendar/week -> 200, title: Kalender – Nestwerk`.
- Logs: `%TEMP%\nestwerk-run.log` and `%TEMP%\nestwerk-run.log.err`.
- Templates and static files are read from `nestwerk-web/src/main/resources` with caching off, so UI
  edits show up on a browser reload without restarting.

### Browser

With the app started, run the Playwright MCP tool
`browser_run_code_unsafe` with `filename: ".claude/skills/run-nestwerk/browser-login.js"`.
It logs in and opens the week grid. Then continue with further `browser_run_code_unsafe` snippets,
for example creating an event by clicking a free slot:

```js
async (page) => {
  await page.mouse.click(710, 560);               // free slot in the week grid
  const d = page.locator('dialog[open]');
  await d.locator('input[name=title]').fill('Smoke-Test');
  await d.getByRole('button', { name: 'Termin anlegen' }).click();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: '<scratchpad>/created.png' });
  return page.locator('.tgrid').innerText().then(t => t.includes('Smoke-Test'));
}
```

Read the screenshot file afterwards and look at it. Check UI changes at 1440, 820 and 390 px width.

## Run (human path)

`start-dev.bat` starts the app on port 8081 via `spring-boot:run` against the Docker Compose
database `nestwerk-postgres-1` – that is the user's own data. Do not use it for agent checks.

## Test

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\openjdk-26"; .\mvnw.cmd verify
```

Uses Testcontainers (Docker needed), ~920 tests.

## Gotchas

- Pages fade in. A screenshot right after navigation is dimmed and the grid is missing; wait for
  `.tgrid` plus ~1 s (done in `browser-login.js`).
- The calendar grid is `.tgrid`, not `.time-grid`. Dialogs are native `dialog[open]`.
- Fine/coarse slot size is remembered in the browser's localStorage, so a reused Playwright profile
  may open in "Fein".
- Never touch the container `nestwerk-postgres-1` – it holds the user's real data. The driver uses
  `nestwerk-run-check` on port 55432.
- The jar is locked while the app runs; `stop` before rebuilding.

## Troubleshooting

- `release version 25 not supported` → `JAVA_HOME` still points to JDK 17; set it as in Build.
- `mvnw -pl nestwerk-app spring-boot:run` cannot resolve the sibling modules → run the packaged jar
  through `driver.ps1` instead.
- `start` throws "App exited" → read `%TEMP%\nestwerk-run.log.err`; usually port 8099 or 55432 is busy
  from an earlier run – `driver.ps1 stop` and start again.
