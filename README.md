# Nestwerk

Spring Boot web application (Java 25, Maven, Thymeleaf, PostgreSQL, Flyway) for running a family organiser with
Docker Compose. The user interface is in German; everything else (code, docs, commits) is English.

## Local development

Prerequisites: JDK 25, Docker.

```bash
./mvnw -pl nestwerk-app -am compile spring-boot:run
```

`spring-boot-docker-compose` starts the database from `compose.yaml` automatically.
An admin password has to be set on the first start:

```bash
NESTWERK_ADMIN_PASSWORD=changeme ./mvnw -pl nestwerk-app -am compile spring-boot:run
```

Tests (need a running Docker for Testcontainers): `./mvnw verify`

## Module structure

The build is a Maven multi-module project. Dependencies only point in one direction (no cycles):

| Module | Contents | depends on |
|---|---|---|
| `nestwerk-core` | users, roles, permissions/ACL, family members, app settings | – |
| `nestwerk-calendar` | calendar, events, blockers (absences, rest times) | core |
| `nestwerk-notification` | notifications, mail and web push | core, calendar |
| `nestwerk-contact` | contacts | core |
| `nestwerk-todo` | todos | core, notification, contact |
| `nestwerk-household` | chore plan, rewards, tablet devices | core, calendar, notification |
| `nestwerk-kitchen` | recipes, pantry, meal plan, shopping list | core, calendar, notification |
| `nestwerk-attachment` | file attachments | core, contact, todo, kitchen |
| `nestwerk-school` | school years, timetable, homework, exams, holidays, grades, parent letters | core, calendar, notification, todo, attachment |
| `nestwerk-transfer` | import/export (iCalendar, vCard, CSV) | core, calendar, contact, todo |
| `nestwerk-maintenance` | backups and updates | core, notification, attachment |
| `nestwerk-web` | controllers, forms, templates, static files | all domain modules |
| `nestwerk-ai` | MCP server, OAuth 2.1, API tokens | web and all domain modules |
| `nestwerk-app` | main class, configuration, Flyway migrations, `application.yaml`, all tests | ai |

Modules register their permissions with the catalogue in `core` through `PermissionSource`, so `core` does not need
to know the permission enums of the other modules. The tests are bundled in `nestwerk-app` because they need the
complete application including the database migrations. The executable JAR is built under `nestwerk-app/target/`.

## Installing on a Linux server

Prerequisites: a Linux server (tested on Debian/Ubuntu) with root access, a domain whose DNS points to the server,
and open ports 80/443. The script installs Docker itself if needed.

1. Download the package `nestwerk-install-vX.Y.Z.tar.gz` from
   [Releases](https://github.com/chrisbad/nestwerk/releases) and copy it to the server.
2. On the server:

   ```bash
   tar xzf nestwerk-install-*.tar.gz && cd nestwerk
   sudo ./install.sh
   ```

The script asks for the domain, an e-mail address for Let's Encrypt, the admin name and, optionally, an external
backup disk. It generates all passwords and the `NESTWERK_SECRET_KEY` itself and stores them in
`/opt/nestwerk/.env` (keep this file safe). At the end it shows the address and the admin password.

- **Reverse proxy for all services:** if the server does not run one yet, the script sets up a central
  [nginx-proxy](https://github.com/nginx-proxy/nginx-proxy) with `acme-companion` under `/opt/proxy`. It takes
  ports 80/443 and fetches the certificates automatically. If another proxy already uses 80/443, Nestwerk only
  listens on `127.0.0.1:8080` (`APP_BIND`). Your proxy forwards the domain there and sets
  `X-Forwarded-For`/`-Proto`/`-Host`.
- **Image:** the image is pulled ready-made from `ghcr.io/chrisbad/nestwerk`. As long as the package is private, the
  script asks once for a GitHub token with the `read:packages` permission and logs the server in with it.
- The database port is not published; the data lives in the `db-data` volume.
- The first admin is created from `NESTWERK_ADMIN_USERNAME`/`NESTWERK_ADMIN_PASSWORD` as long as no users exist.
  The admin creates further accounts under `/admin/users`.

### Putting another service behind the proxy

Every container in the Docker network `proxy` that sets these variables gets its own domain and certificate:

```yaml
services:
  wiki:
    image: …
    environment:
      VIRTUAL_HOST: wiki.example.org
      VIRTUAL_PORT: "3000"          # port inside the container
      LETSENCRYPT_HOST: wiki.example.org
    networks: [default, proxy]
networks:
  proxy:
    external: true
```

Custom nginx settings per domain live in `/opt/proxy/vhost.d/<domain>`. For Nestwerk the script puts larger upload
limits and longer timeouts for backups there.

### Moving to a new server

On the old server, create a backup under "Sicherungen" (backups) and download it. On the new server run
`install.sh`, log in as admin, upload the backup under "Sicherungen" and restore it. Afterwards the users and
passwords from the backup apply. The credentials of backup targets (WebDAV/SFTP) are encrypted with the old
`NESTWERK_SECRET_KEY`. Either take the value over from the old `.env` or enter the credentials again.

### Calendar, navigation and settings

The user interface is German, so menu and button names are quoted in German with a short translation where it helps.

- **Start:** whoever has the permission lands in the calendar after login, in the "Meine" ("mine") view (today's
  events, todos and chores of their own person, overdue items on top). "Tag" (day) shows the time from left to right
  with one lane per person and allows creating, moving and changing by dragging; "3 Tage" and "Woche" (week) stay
  vertical.
- **Navigation:** all menu entries come from one registry (`Navigation`) with a visibility rule; the sidebar, tab
  bar, "Mehr" ("more") sheet and user menu are generated from it. The plus menu creates events, todos, times and
  shopping items from anywhere, Ctrl + K opens the quick search.
- **Excursion and return:** pages opened from the calendar (settings, categories, quick create) get their origin as
  a `back` parameter (`SafeRedirect` only allows local paths). The arrow at the top of the page and all forms lead
  back there; without an origin, the last visited calendar view applies.
- **Settings register:** `/settings` lists all settings the user may see in a searchable, filterable and sortable
  table (component `data-table`, state kept in the address). New settings are registered in `Settings`; MCP:
  `list_settings`. Users, members and categories use the same table.
- **Layers and chores in the calendar:** "Anzeige" (display) hides events, todos and chores individually (per
  device). Open chores without a time or person appear in the "ganztägig" (all-day) bar. "Hausarbeit" in the bar
  plans a chore once (`ChoreTaskService.planOnce`, also for "Bei Bedarf", i.e. on demand) or books it unplanned;
  MCP: `plan_chore_task`, `book_chore`, `complete_chore_task`.
- **Household:** `/household` shows the day (default: own tasks, further people via filter chip, the selection is
  kept in the address `?who=` and on the device) and the week; side by side as soon as the page is wide enough
  (container query), stacked otherwise. `?date=` selects the day, `?week=` and `/household/today` redirect. In the
  list of chores, "Buchen" (book) in the side panel also books unplanned chores.
- **Home page:** the tile "Heute in der Schule" (school today) shows each child's lessons of the day as short chips
  (cancelled ones struck through), the reason for a day off and the exam of the day.
- **Side panel:** rows open their details in a shared panel (`drawer.js`) that loads a fragment via
  `GET …/{id}/edit`, instead of every row carrying its own dialog. The back button closes it.

### Notifications

Notifications always appear in the bell. In addition:

- **E-mail:** set `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASSWORD` and `MAIL_FROM` in `.env`. Every user
  stores their address under "Meine Benachrichtigungswege" (my notification channels).
- **Push to the phone:** works as soon as Nestwerk is reachable over HTTPS and `NESTWERK_BASE_URL` is set. Nestwerk
  generates the required keys itself on first need. On the iPhone, Nestwerk has to be installed via
  "Share → Add to Home Screen".
- Which channels apply by default is set by an administrator under "Benachrichtigungen einstellen" (configure
  notifications).

### Times (work, sleep, rest, leisure)

- Under "Zeiten" (times) every person enters recurring times: kind, time of day (across midnight is possible),
  weekdays, valid from/to and individual exception days (holiday). Permissions work like for events.
- **When scheduling:** *show only*, *warn* (the form asks once and suggests free times) or *block* (for others; the
  person themself gets at most a warning). Selected people may schedule anyway. This applies to the form, dragging
  in the calendar, MCP and iCal import.
- **Visibility:** with title, only "busy", or invisible; the people concerned and admins always see the title. In
  the calendar, times appear as bands; while dragging in the time grid, the grid shows blocked times of the people
  concerned hatched.
- **Quiet mode:** push and mail wait until the time ends. **Public holidays:** times marked "free on public
  holidays" are dropped on the statutory holidays of the configured federal state.
- **Rest goals:** e.g. "at least 2 free evenings" or "at least 6 hours of rest per week"; status on "Zeiten" and
  on the home page, with a warning when scheduling if a goal would tip.

### Household and wall tablet

- The chore plan plans three weeks ahead. Whoever takes part in a calendar event with the "Abwesend" (absent) tick
  is skipped during distribution.
- Every task gets a time: the earliest free gap of the responsible person within the chore's time window
  (earliest/latest, otherwise 07:00 until due date or 21:00), outside of events, times with a warning or block, and
  other chores. Whoever has no gap is skipped; if nobody has time, the task stays open ("niemand frei", nobody
  free). Planned tasks also appear in the person's calendar.
- **Pairing a wall tablet:** under "Wand-Tablets" (user menu, permission "Wand-Tablets koppeln") create a code, open
  `https://<nestwerk>/tablet/pair` on the tablet and enter the code. The tablet then only sees the chore plan for
  today and tomorrow; it is logged out there with one click.
- Tasks are ticked off at the tablet with the member's PIN. Everyone sets it under "PIN fürs Tablet"; for children
  without an account this is done in the member administration.
- Waste collection calendars can be taken over for a chore with fixed dates as an `.ics` file or subscription
  address; subscriptions are refreshed daily.

### Kitchen

- **Recipes and foods:** for every recipe Nestwerk shows calories, protein, fat and carbohydrates per serving and
  scales the ingredients to another number of servings. The nutrition values come from a base set of about 150
  foods, from Open Food Facts (barcode scan) or from your own entries. Ingredients that cannot be converted are
  marked ("Nährwerte unvollständig", nutrition incomplete) and are not counted as 0 kcal.
- **Recipe import:** a link to a recipe page (also Cookidoo pages that are publicly readable) pre-fills the recipe
  form; it is only saved after review. No Cookidoo login is needed.
- **Meal plan:** week view with freely configurable meals, eaters and servings, drag & drop and "Woche auffüllen"
  (fill week). Calorie goals (Küche → Einstellungen) provide serving suggestions and the daily balance per person.
- **Pantry:** quantity, location and best-before date; "Gekocht" (cooked) first shows what will be deducted
  (oldest best-before date first), quantities can be corrected. Every day at 8:00 a digest message arrives if
  something expires within the next two days.
- **Shopping:** "Aus Plan erzeugen" (generate from plan) = demand of all uncooked meals minus pantry, plus free
  items. Bought items are moved into the pantry via "Einräumen" (stow away).
- **Bring!:** under Küche → Einstellungen enter the e-mail and password of the Bring! account and choose a list.
  The password is stored encrypted (same key as the backups). "An Bring! senden" (send to Bring!) sends the open
  items; whatever is no longer "to buy" at Bring! counts as bought (button and every 15 minutes). Nestwerk uses
  Bring!'s unofficial web API; if it is down, the shopping list stays unchanged and the page shows a message.
- **Permissions:** `KITCHEN_VIEW`, `KITCHEN_RECIPES_EDIT`, `KITCHEN_PLAN`, `KITCHEN_SHOPPING`, `KITCHEN_SETTINGS`;
  the role "Benutzer" (user) gets all but the settings.
- **AI:** through MCP an assistant creates recipes from a photo or text (`create_recipe` with ingredients as text
  lines), plans meals and reads the shopping list and pantry (`search_foods`, `plan_meal`, `shopping_list`,
  `list_pantry` and others). Changing is possible as well (`update_recipe`, `move_meal`, `set_meal_eaters`), as are
  filling and cooking (`fill_meal_plan`, `cook_meal`), shopping (`generate_shopping_list`, `toggle_shopping_item`,
  `stow_shopping_item`, Bring!) and the pantry (`add_pantry_item`, `update_pantry_item`, `take_from_pantry`). It
  fills the chore plan with `create_chore`, `update_chore`, `add_chore_date`, `assign_chore_task` and `book_chore`.

### AI integration (MCP)

Nestwerk contains an MCP server at `https://<nestwerk>/mcp`. Through it an AI assistant such as Claude can read and
edit events, todos, the chore plan, the kitchen, contacts, times and notifications. Every feature of the web
interface has matching tools (create, read, update, delete and the domain-specific actions); the tests in
`nestwerk-app` guard this (`McpCoverageTest`). Deleting only works with tools provided explicitly for it and marked
as destructive, for example `delete_event`, `delete_todo` or `delete_chore`. Events can be moved like in the
calendar (`reschedule_event`, `reschedule_event_series`), chores to another time or day (`reschedule_chore_task`).

- **Scope:** todos with sub-structure, links, watchers, follow-up and history; events including single occurrences
  of a series; contacts; the chore plan with statistics, credit corrections, rewards and iCal subscriptions;
  kitchen including settings, recipe import by address and barcode search; times (blockers) and rest goals;
  notification settings; the settings register (`list_settings`); attachments (read text, add small files); export
  and import as text with preview.
- **Administration:** with the matching administration permissions also user roles, family members, roles and
  access rules, backup schedule, updates and global notification settings. The same rules against privilege
  escalation apply as in the web interface.
- **Deliberately web-only:** creating users and setting passwords, AI tokens, Bring! login, credentials for backup
  targets, the encryption passphrase, restoring, deleting and uploading backups, PINs and tablet pairing, setting
  up push devices.

- The assistant always acts as one user and has exactly their permissions. An administrator gives a role the
  permission "KI-Zugang nutzen" (use AI access). After that every user connects in one of two ways:
- **With the Nestwerk login (OAuth 2.1):** the client only needs the address. Nestwerk is also the authorization
  server: the client finds it via `/.well-known/oauth-protected-resource/mcp`, registers itself
  (`/oauth2/register`, RFC 7591), the user logs in in the browser and consents. Access tokens are valid for one
  hour, refresh tokens for 90 days and are renewed on every use. Under "KI-Zugänge → Verbundene Assistenten"
  (AI access → connected assistants) every connection can be revoked. For claude.ai (connectors), Nestwerk has to
  be reachable from the internet over HTTPS; Claude Code also works on the home network:

  ```bash
  claude mcp add --transport http nestwerk https://<nestwerk>/mcp
  # then in Claude Code: /mcp → nestwerk → Authenticate
  ```

- **With an access token:** create a personal token under "KI-Zugänge". It is shown only once and can be revoked at
  any time.

  ```bash
  claude mcp add --transport http nestwerk https://<nestwerk>/mcp --header "Authorization: Bearer nw_…"
  ```

  Other clients need the address and the header `Authorization: Bearer <token>`. The page "KI-Zugänge" shows
  matching snippets with the address from `NESTWERK_BASE_URL`.
- The MCP server is stateless. Nothing special is needed at the reverse proxy, except that the `Authorization`
  header is passed through and `X-Forwarded-Proto`/`X-Forwarded-Host` are correct – Nestwerk builds the OAuth
  addresses from them.

### Update

**In Nestwerk:** user menu → "Updates" (permission "Updates verwalten", manage updates). Nestwerk checks every
6 hours whether there is a new version and tells the admins. A click on "Jetzt aktualisieren" (update now) hands
the job to the `updater` service:

1. a backup with the running version (kind "before an update"),
2. pull the app and updater images of the new version, take over the installation files (`compose.*.yaml`,
   `update.sh`) from the updater image, set `NESTWERK_VERSION` in `.env`,
3. restart and wait until the app is healthy.

If the new version does not come up, the updater starts the old one again; if the new one had already migrated the
database, it restores the backup from step 1 first. Result and log are on the "Updates" page.

- **Channel:** *Releases* (tags `vX.Y.Z`, default) or *main* (every successful build of main, image tag
  `sha-<commit>`).
- **GitHub token:** as long as the repository is private, the version check needs a fine-grained token for
  `chrisbad/nestwerk` only, with read access to *Contents* and *Actions*. It is stored encrypted.
- **Security:** the app has no access to Docker. It only stores a job with the target version; the updater checks it
  and takes everything else from its own image.

**Via SSH** (first setup of the updater, or when the app no longer starts):

```bash
sudo GITHUB_TOKEN=… /opt/nestwerk/update.sh          # configured version (default: latest)
sudo GITHUB_TOKEN=… /opt/nestwerk/update.sh 1.4.0    # a specific version
```

`update.sh` fetches the installation files of the target version (with `GITHUB_TOKEN` if the repository is
private), adds `NESTWERK_DIR` to `.env`, backs up with the running version, pulls the new images and restarts.

**Switching an existing installation to the updater (once):** copy the new `update.sh` from the repository to
`/opt/nestwerk/` and run it as above. After that the `updater` service runs and updates happen in Nestwerk. Behind
nginx-proxy, also copy `proxy/nestwerk-vhost.conf` to `/opt/proxy/vhost.d/<domain>` again and run
`docker exec nginx-proxy nginx -s reload`: then everyone sees a maintenance page during the restart.

**Rules for new versions**, so that updates never lose data:
- Migrations only add, or carry the data over; `MigrationUpgradeTest` migrates existing data from V18 to the latest
  state and compares the rows. With every release add another snapshot under
  `nestwerk-app/src/test/resources/db/upgrade/`.
- New variables in the Compose files always with a default (`${VAR:-…}`): an update only changes `.env` for
  `NESTWERK_VERSION`.

### Backups

Backups are managed by an administrator (permission "Sicherungen verwalten", manage backups) under "Sicherungen" in
the user menu:

- **Back up now** or **regularly** (daily or weekly at a given time). A backup is a ZIP file with all tables and
  attachments. It lives in the `backups` volume and can be downloaded.
- **Retention:** the last N backups, plus one per week and month. Uploaded backups are kept.
- **Additional targets:** external disk (`NESTWERK_EXTERNAL_BACKUP_HOST_DIR` in `.env`, Nestwerk only writes if the
  marker file exists), WebDAV/Nextcloud and SFTP. Copies sent there can be encrypted with a passphrase
  (`.zip.enc`).
- **Restoring:** from the list or after uploading a file, including an encrypted copy. Beforehand Nestwerk creates a
  safety copy automatically. Older backups are migrated to the current state.
- A failure triggers a notification. The home page warns if the last backup is older than three days.

### Export and import

- **Calendar:** "Export" in the calendar bar delivers all visible events as `.ics`. "Import" takes `.ics` files, for
  example from Google or Outlook. Identical UIDs are updated instead of created twice.
- **Contacts:** via "Austausch" (exchange) as vCard or CSV (Excel). The import recognises CSV from Nestwerk, Google
  and Outlook and completes existing contacts.
- **Todos:** via "Austausch" the current view as CSV. The import takes the same format; with an ID an existing todo
  is updated.

Every import shows a preview first and only takes over after confirmation.

### Emergency: manual backup

If the app does not start, the database and attachments can also be backed up directly:

```bash
cd /opt/nestwerk
docker compose exec -T db pg_dump -U nestwerk nestwerk > backup.sql
docker compose run --rm --no-deps -v "$PWD":/backup --entrypoint "" app \
  tar czf /backup/attachments.tgz -C /data/attachments .
```
