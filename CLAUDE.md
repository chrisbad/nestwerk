# CLAUDE.md

Guidelines for working on Nestwerk, a family app built as a Maven multi-module project
(Java 25, Spring Boot 4, Spring AI MCP).

## Language

- **English is the default language** for everything in the codebase: class, method and variable names,
  comments, Javadoc, log messages, exception messages, tests, documentation, commit messages, branches,
  tags, pull requests and release notes.
- Only texts shown to end users (web UI, notifications, mails) are written in German.
- When touching existing German comments or identifiers, translate them to English as part of the change.

## Project structure

- Module dependency direction: `core <- calendar <- notification <- ... <- web <- ai <- app`.
  Never introduce a dependency against this direction or a cycle between modules.
- Each domain module (`calendar`, `todo`, `household`, `kitchen`, `school`, ...) owns its entities,
  repositories and services. Other modules use its public services, never its repositories.
- MCP tools live in `nestwerk-ai/src/main/java/club/badernet/nestwerk/ai/tools`, their tests in
  `nestwerk-app/src/test/java/club/badernet/nestwerk/ai/tools`.

## MCP: every feature must be controllable via MCP (CRUD)

- **Every feature must be fully controllable via MCP.** For each domain object there must be tools
  for **Create, Read (get and list/search), Update and Delete**, plus tools for every domain-specific
  action that the web UI offers (e.g. complete, move, assign).
- A feature is not done until its MCP tools exist and are tested. New UI functionality without matching
  MCP tools is incomplete.
- Tools go through the domain services, never directly through repositories, so that permissions,
  validation and side effects (notifications, history) are identical to the web UI.
- Tools act on behalf of the authenticated user with exactly their permissions (use `Tools.read` /
  `Tools.write` helpers).
- Set `@McpTool.McpAnnotations` correctly: `readOnlyHint` for reads, `destructiveHint = true` for deletes
  and other irreversible actions.
- Tool names are `snake_case` verbs (`create_contact`, `list_events`, `delete_todo`); return small,
  purpose-built records instead of entities.
- Write clear tool and parameter descriptions: what the tool does, required formats
  (dates `YYYY-MM-DD`, times `HH:MM`), and where IDs come from.

### Exceptions for MCP

Security-critical administration stays web-only on purpose:

- creating users and setting passwords,
- restoring, deleting and uploading backups and external backup targets with credentials,
- tablet devices and their PINs,
- AI access tokens,
- push subscriptions,
- Bring! credentials.

MCP keeps backup status, schedule, retention and start, updates and the notification settings.
`McpCoverageTest` fails when a web action that deletes or cancels something has neither an MCP tool nor an
entry in its list of documented exceptions; add the tool (or the exception with its reason) in the same change.

## Clean Code

### Naming
- Use intention-revealing names; avoid abbreviations, `data`, `info`, `manager`, `util` and
  type prefixes/suffixes like `IService`/`Impl` unless there are several implementations.
- Classes are nouns, methods are verbs. Booleans read as questions (`isActive`, `hasDeadline`).
- One term per concept across the whole codebase.

### Functions and classes
- Methods are small, do one thing and stay on one level of abstraction.
- Prefer few parameters (ideally ≤ 3); group related parameters in a record.
- No flag arguments that switch behaviour; split into two methods instead.
- No hidden side effects; separate commands from queries.
- Classes follow the Single Responsibility Principle and are highly cohesive.
- Prefer composition over inheritance; depend on abstractions where it decouples modules.
- Constructor injection only, dependencies are `private final`. No field injection.

### Modern Java
- Use records for DTOs, value objects and tool results.
- Use `sealed` types, pattern matching for `switch`/`instanceof` and switch expressions where they
  clarify intent.
- Prefer immutability: `final` fields, `List.of`/`toList()`, no setters on value objects.
- Use `Optional` as a return type for "may be absent", never as a field or parameter. Never return `null`
  for collections.
- Use `java.time` exclusively for dates and times.
- Use streams when they make code clearer, plain loops when they don't.

### Error handling
- Throw meaningful, specific exceptions; never swallow exceptions or catch `Exception` without reason.
- Validate input at the boundary (controller, MCP tool) and fail fast.
- No error codes or `null` as error signals.

### Comments
- Code should explain itself; comments explain *why*, not *what*.
- Public APIs of services and every MCP tool get a short Javadoc/description.
- No commented-out code, no outdated comments, no TODOs without a reason.

### General principles
- DRY, KISS, YAGNI: no speculative abstractions, no duplicated logic.
- Boy Scout Rule: leave touched code cleaner than you found it — in small, focused steps.
- No magic numbers or strings; use constants or enums.
- Keep the scope of variables as small as possible.

## Spring

- Business logic lives in services, not in controllers, templates or MCP tools.
- Transactions belong on service methods (`@Transactional`), read-only where applicable.
- Configuration via `@ConfigurationProperties` records instead of scattered `@Value`.
- Never expose JPA entities directly to the web or MCP layer.

## Testing

- Every new or changed behaviour gets tests: unit tests for domain logic, tests for every MCP tool.
- Tests follow Arrange–Act–Assert, test one behaviour each and have descriptive names.
- Tests must be independent and deterministic (no reliance on current time without a fixed `Clock`).
- Build and run tests with `./mvnw verify` before committing.

## Git

- Commit messages, tags, releases and PRs in English, using Conventional Commits
  (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`).
- Small, focused commits; never commit secrets or local data (`data/`, backups).
