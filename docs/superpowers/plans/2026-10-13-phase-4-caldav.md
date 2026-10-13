# Phase 4: CalDAV-Server (Zwei-Wege-Sync für Android) – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nestwerk spricht CalDAV, sodass Android (z. B. DAVx5) den Familienkalender lesen, anlegen, ändern und löschen kann.

**Architecture:** Ein eigener Spring-Security-Filterchain (`/caldav/**`, HTTP Basic mit **App-Passwörtern**, stateless, ohne CSRF) und ein `CalDavFilter`, der die WebDAV-Methoden (`OPTIONS`, `PROPFIND`, `REPORT`, `GET`, `PUT`, `DELETE`) selbst bedient, weil Spring MVC `PROPFIND`/`REPORT` nicht als `RequestMethod` kennt. Die Fachlogik läuft über `CalendarEventService` (gleiche Rechte- und Sichtbarkeitsregeln wie die Web-UI). iCal wird mit eigenem `ICalParser`/`ICalImporter` gelesen und mit dem `ICalWriter` aus Phase 3 geschrieben. Sync-Tokens basieren auf einem Änderungsprotokoll (`calendar_changes`), das auch Löschungen enthält.

**Tech Stack:** wie Phase 3; zusätzlich JAXP (`DocumentBuilderFactory`, XXE-sicher) für XML-Requests.

**Spec:** `docs/superpowers/specs/2026-10-13-basis-rollen-mitglieder-design.md`
**Voraussetzung:** Phase 1–3 umgesetzt.

## Global Constraints

- Authentifizierung für CalDAV ausschließlich per App-Passwort (nicht das Login-Passwort); App-Passwörter werden nur als BCrypt-Hash gespeichert und einmalig im Klartext angezeigt.
- Rechteprüfung und Privat-Filter wie in der Web-UI; ein Benutzer darf nur unter `/caldav/calendars/<eigener Benutzername>/` arbeiten.
- Ressourcenname = `<UID>.ics`; eine PUT-Anfrage, deren UID nicht zum Ressourcennamen passt, wird mit 403 abgelehnt.
- Ein einziger Kalender pro Benutzer (`family`); `MKCALENDAR`/`MKCOL` werden nicht unterstützt (405).
- XML-Parsing ohne DTD/externe Entities (XXE-sicher); Request-Body-Limit 1 MiB (Überschreitung wird wie jeder ungültige Request mit 400 beantwortet).
- Familienmitglieder-Zuordnung ist in iCal nicht abbildbar: Bei PUT auf einen bestehenden Termin bleiben die zugewiesenen Mitglieder erhalten.
- Flyway: neue Version `V5__caldav.sql`.
- Commit-Nachrichten enden mit `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Review Focus

- Login-Passwort (statt App-Passwort) mit HTTP Basic: 401. Widerrufenes App-Passwort: 401. Deaktivierter Benutzer: 401.
- Zugriff auf `/caldav/calendars/<anderer Benutzer>/…`: 403, ohne Existenz fremder Termine zu verraten.
- Privater Termin eines anderen Benutzers: weder in `PROPFIND`, `REPORT` noch per `GET` (404); `PUT` auf dessen UID wird abgelehnt, nicht als Neuanlage mit Unique-Verletzung (500) behandelt.
- `If-Match` mit veraltetem ETag und `If-None-Match: *` auf vorhandenem Termin: 412, ohne Daten zu überschreiben.
- Gelöschter Termin erscheint beim inkrementellen `sync-collection` als 404-Eintrag; unbekannter/zukünftiger Sync-Token liefert `valid-sync-token`-Fehler (403), damit der Client neu synchronisiert.
- Ganztägige Serie mit `UNTIL=20261231` (nur Datum) und Serie mit `EXDATE` überleben PUT → GET unverändert.
- Termin mit Umlauten, Komma und Zeilenumbruch im Titel übersteht den Roundtrip (Escaping/Unescaping, Faltung).
- Körperlose/kaputte XML-Requests und nicht-iCal-Bodies ergeben 400, nicht 500.

---

## Dateiübersicht

| Datei | Verantwortung |
|---|---|
| `db/migration/V5__caldav.sql` | App-Passwörter, Änderungsprotokoll |
| `calendar/caldav/AppPassword*.java`, `CalDavAuthenticationProvider.java` | App-Passwörter, Basic-Auth |
| `config/CalDavSecurityConfig.java` | Filterchain, HttpFirewall (PROPFIND/REPORT) |
| `calendar/ical/ICalParser.java`, `ICalImporter.java`, `calendar/ImportedEvent.java` | iCal lesen |
| `calendar/CalendarChange*.java`, `CalendarChangeLog.java` | Änderungsprotokoll, Sync-Token |
| `calendar/caldav/DavXml.java`, `CalDavFilter.java`, `CalDavConfig.java` | Protokoll |
| `web/AppPasswordController.java`, `templates/account/app-passwords.html` | Verwaltung der App-Passwörter |

---

### Task 1: App-Passwörter und CalDAV-Authentifizierung

**Files:**
- Create: `db/migration/V5__caldav.sql`
- Create: `calendar/caldav/AppPassword.java`, `AppPasswordRepository.java`, `AppPasswordService.java`, `CalDavAuthenticationProvider.java`
- Create: `config/CalDavSecurityConfig.java`
- Modify: `config/SecurityConfig.java` (`@Order(2)` am Haupt-Chain)
- Create: `web/AppPasswordController.java`, `templates/account/app-passwords.html`
- Modify: `templates/calendar/fragments.html` (Link „Sync“)
- Test: `src/test/java/club/badernet/nestwerk/calendar/caldav/AppPasswordServiceTest.java`, `src/test/java/club/badernet/nestwerk/web/CalDavSecurityTest.java`

**Interfaces:**
- Produces: `AppPasswordService.create(User, String name): String` (Klartext, einmalig, Format `xxxx-xxxx-xxxx-xxxx-xxxx-xxxx`), `verify(String username, String raw): Optional<User>`, `list(User): List<AppPassword>`, `revoke(Long id, User)`. Der Chain für `/caldav/**` authentifiziert per Basic Auth gegen `verify`; die Authorities entsprechen `User.permissionNames()`.

- [ ] **Step 1: Failing Tests schreiben**

`AppPasswordServiceTest.java`:
```java
package club.badernet.nestwerk.calendar.caldav;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.reminders.enabled=false"})
@Transactional
class AppPasswordServiceTest {

    @Autowired AppPasswordService passwords;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void verifiesGeneratedPassword() {
        User user = userService.create("dav-ich", "login-passwort", Set.of());

        String raw = passwords.create(user, "Handy");

        assertThat(raw).matches("([a-z2-9]{4}-){5}[a-z2-9]{4}");
        assertThat(passwords.verify("dav-ich", raw)).get().extracting(User::getId).isEqualTo(user.getId());
    }

    @Test
    void loginPasswordIsNotAnAppPassword() {
        User user = userService.create("dav-login", "login-passwort", Set.of());
        passwords.create(user, "Handy");

        assertThat(passwords.verify("dav-login", "login-passwort")).isEmpty();
    }

    @Test
    void passwordOfAnotherUserDoesNotWork() {
        User a = userService.create("dav-a1", "login-passwort", Set.of());
        userService.create("dav-b1", "login-passwort", Set.of());
        String raw = passwords.create(a, "Handy");

        assertThat(passwords.verify("dav-b1", raw)).isEmpty();
    }

    @Test
    void storesOnlyHash() {
        User user = userService.create("dav-hash", "login-passwort", Set.of());

        String raw = passwords.create(user, "Handy");

        Integer plain = jdbc.queryForObject(
                "SELECT count(*) FROM caldav_app_passwords WHERE password_hash = ?", Integer.class, raw);
        assertThat(plain).isZero();
    }

    @Test
    void revokedPasswordStopsWorking() {
        User user = userService.create("dav-weg", "login-passwort", Set.of());
        String raw = passwords.create(user, "alt");

        passwords.revoke(passwords.list(user).getFirst().getId(), user);

        assertThat(passwords.verify("dav-weg", raw)).isEmpty();
    }

    @Test
    void disabledUserCannotAuthenticate() {
        User user = userService.create("dav-aus", "login-passwort", Set.of());
        String raw = passwords.create(user, "Handy");

        userService.setEnabled(user.getId(), false);

        assertThat(passwords.verify("dav-aus", raw)).isEmpty();
    }

    @Test
    void foreignPasswordCannotBeRevoked() {
        User a = userService.create("dav-r1", "login-passwort", Set.of());
        User b = userService.create("dav-r2", "login-passwort", Set.of());
        passwords.create(a, "meins");
        Long id = passwords.list(a).getFirst().getId();

        assertThatThrownBy(() -> passwords.revoke(id, b)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresName() {
        User user = userService.create("dav-name", "login-passwort", Set.of());

        assertThatThrownBy(() -> passwords.create(user, " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

`CalDavSecurityTest.java`:
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.caldav.AppPasswordService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.reminders.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class CalDavSecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired AppPasswordService passwords;

    @Test
    void unauthenticatedRequestGetsBasicChallenge() throws Exception {
        mockMvc.perform(get("/caldav/"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginPasswordIsRejected() throws Exception {
        userService.create("sec-login", "login-passwort", Set.of());

        mockMvc.perform(get("/caldav/").with(httpBasic("sec-login", "login-passwort")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void appPasswordIsAccepted() throws Exception {
        User user = userService.create("sec-app", "login-passwort", Set.of());
        String raw = passwords.create(user, "Handy");

        int status = mockMvc.perform(get("/caldav/").with(httpBasic("sec-app", raw)))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotEqualTo(401);
    }

    @Test
    void formLoginAreaIsUnaffected() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Tests ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=AppPasswordServiceTest+CalDavSecurityTest`
Expected: Kompilierfehler / Tabelle fehlt.

- [ ] **Step 3: Implementieren**

`V5__caldav.sql`:
```sql
CREATE TABLE caldav_app_passwords (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          VARCHAR(100)             NOT NULL,
    password_hash VARCHAR(255)             NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMP WITH TIME ZONE
);

CREATE TABLE calendar_changes (
    seq        BIGSERIAL PRIMARY KEY,
    event_uid  VARCHAR(255)             NOT NULL,
    deleted    BOOLEAN                  NOT NULL DEFAULT FALSE,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX calendar_changes_uid_idx ON calendar_changes (event_uid);
```

`calendar/caldav/AppPassword.java`:
```java
package club.badernet.nestwerk.calendar.caldav;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "caldav_app_passwords")
public class AppPassword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected AppPassword() {
    }

    public AppPassword(Long userId, String name, String passwordHash) {
        this.userId = userId;
        this.name = name;
        this.passwordHash = passwordHash;
    }

    public void markUsed(Instant when) {
        this.lastUsedAt = when;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
}
```

`calendar/caldav/AppPasswordRepository.java`:
```java
package club.badernet.nestwerk.calendar.caldav;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppPasswordRepository extends JpaRepository<AppPassword, Long> {

    List<AppPassword> findByUserIdOrderByCreatedAtDesc(Long userId);
}
```

`calendar/caldav/AppPasswordService.java`:
```java
package club.badernet.nestwerk.calendar.caldav;

import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class AppPasswordService {

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final SecureRandom random = new SecureRandom();
    private final AppPasswordRepository passwords;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AppPasswordService(AppPasswordRepository passwords, UserRepository users, PasswordEncoder encoder) {
        this.passwords = passwords;
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public String create(User user, String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty() || cleaned.length() > 100) {
            throw new IllegalArgumentException("Die Bezeichnung muss 1–100 Zeichen lang sein.");
        }
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            if (i > 0 && i % 4 == 0) {
                raw.append('-');
            }
            raw.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        passwords.save(new AppPassword(user.getId(), cleaned, encoder.encode(raw.toString())));
        return raw.toString();
    }

    @Transactional
    public Optional<User> verify(String username, String raw) {
        if (username == null || raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Optional<User> user = users.findByUsername(username).filter(User::isEnabled);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        for (AppPassword p : passwords.findByUserIdOrderByCreatedAtDesc(user.get().getId())) {
            if (encoder.matches(raw, p.getPasswordHash())) {
                p.markUsed(Instant.now());
                return user;
            }
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public List<AppPassword> list(User user) {
        return passwords.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Transactional
    public void revoke(Long id, User user) {
        AppPassword password = passwords.findById(id)
                .filter(p -> p.getUserId().equals(user.getId()))
                .orElseThrow(() -> new IllegalArgumentException("App-Passwort nicht gefunden"));
        passwords.delete(password);
    }
}
```

`calendar/caldav/CalDavAuthenticationProvider.java`:
```java
package club.badernet.nestwerk.calendar.caldav;

import club.badernet.nestwerk.user.User;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CalDavAuthenticationProvider implements AuthenticationProvider {

    private final AppPasswordService passwords;

    public CalDavAuthenticationProvider(AppPasswordService passwords) {
        this.passwords = passwords;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = authentication.getName();
        Object credentials = authentication.getCredentials();
        User user = passwords.verify(username, credentials == null ? null : credentials.toString())
                .orElseThrow(() -> new BadCredentialsException("Ungültige Zugangsdaten"));
        List<GrantedAuthority> authorities = user.permissionNames().stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated(user.getUsername(), null, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
```

`config/CalDavSecurityConfig.java`:
```java
package club.badernet.nestwerk.config;

import club.badernet.nestwerk.calendar.caldav.CalDavAuthenticationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import java.util.List;

@Configuration
public class CalDavSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain caldavSecurityFilterChain(HttpSecurity http,
                                                  CalDavAuthenticationProvider provider) throws Exception {
        http
                .securityMatcher("/caldav/**", "/.well-known/caldav")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/.well-known/caldav").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .authenticationProvider(provider)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /** Erlaubt die WebDAV-Methoden, die StrictHttpFirewall standardmäßig ablehnt. */
    @Bean
    StrictHttpFirewall httpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHttpMethods(List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH",
                "PROPFIND", "REPORT"));
        return firewall;
    }
}
```

`SecurityConfig`: Methode `securityFilterChain` mit `@Order(2)` annotieren (Import `org.springframework.core.annotation.Order`).

`web/AppPasswordController.java`:
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.caldav.AppPasswordService;
import club.badernet.nestwerk.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/account/app-passwords")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class AppPasswordController {

    private final AppPasswordService passwords;
    private final CurrentUser currentUser;

    public AppPasswordController(AppPasswordService passwords, CurrentUser currentUser) {
        this.passwords = passwords;
        this.currentUser = currentUser;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("passwords", passwords.list(currentUser.require()));
        return "account/app-passwords";
    }

    @PostMapping
    String create(@RequestParam String name, RedirectAttributes redirect) {
        try {
            String raw = passwords.create(currentUser.require(), name);
            redirect.addFlashAttribute("newPassword", raw);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/account/app-passwords";
    }

    @PostMapping("/{id}/revoke")
    String revoke(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            passwords.revoke(id, currentUser.require());
            redirect.addFlashAttribute("success", "App-Passwort wurde widerrufen.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/account/app-passwords";
    }
}
```

`templates/account/app-passwords.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('Android-Sync')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav}"></nav>
<main>
<h1>Android-Sync (CalDAV)</h1>
<p>Server-Adresse für DAVx5 o. Ä.: <code th:text="${#httpServletRequest.requestURL.toString().replace('/account/app-passwords', '/caldav/')}"></code>
   · Benutzername: dein Nestwerk-Benutzername · Passwort: ein App-Passwort von dieser Seite
   (nicht dein Login-Passwort).</p>
<p th:if="${success}" class="flash-success" th:text="${success}"></p>
<p th:if="${error}" class="flash-error" th:text="${error}"></p>

<section th:if="${newPassword}">
    <h2>Neues App-Passwort</h2>
    <p><strong>Wird nur jetzt angezeigt.</strong></p>
    <p><input type="text" readonly size="40" th:value="${newPassword}" onclick="this.select()"></p>
</section>

<h2>Vorhandene App-Passwörter</h2>
<p th:if="${passwords.isEmpty()}" class="muted">Noch keine App-Passwörter.</p>
<ul>
    <li th:each="p : ${passwords}">
        <span th:text="${p.name}"></span>
        – erstellt <span th:text="${#temporals.format(p.createdAt, 'dd.MM.yyyy HH:mm')}"></span>
        <span th:if="${p.lastUsedAt != null}">, zuletzt benutzt
            <span th:text="${#temporals.format(p.lastUsedAt, 'dd.MM.yyyy HH:mm')}"></span></span>
        <form th:action="@{/account/app-passwords/{id}/revoke(id=${p.id})}" method="post" style="display:inline">
            <button type="submit">Widerrufen</button>
        </form>
    </li>
</ul>

<h2>Neues App-Passwort erzeugen</h2>
<form th:action="@{/account/app-passwords}" method="post">
    <label>Bezeichnung <input type="text" name="name" maxlength="100" placeholder="z. B. Mein Handy" required></label>
    <button type="submit">Erzeugen</button>
</form>
</main>
</body>
</html>
```
`fragments.html` `viewswitch` ergänzen: `<a th:href="@{/account/app-passwords}">Sync</a>`.

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=AppPasswordServiceTest+CalDavSecurityTest+SecurityAccessTests+NavigationTests`
Expected: PASS. Insbesondere müssen die Formular-Login-Tests unverändert grün bleiben (Chain-Reihenfolge).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: App-Passwörter und eigener Security-Chain für CalDAV"
```

---

### Task 2: iCal einlesen (Parser und Importer)

**Files:**
- Create: `calendar/ical/ICalParser.java`, `calendar/ical/ICalImporter.java`, `calendar/ImportedEvent.java`
- Test: `src/test/java/club/badernet/nestwerk/calendar/ical/ICalImporterTest.java`

**Interfaces:**
- Consumes: `EventInput`, `CalendarSettings.zone()`, `ICalWriter` (nur im Roundtrip-Test).
- Produces:
  - `record ImportedEvent(String uid, EventInput input, Set<Instant> cancelled, List<ImportedOverride> overrides)` mit `record ImportedOverride(Instant recurrenceId, String title, String description, String location, Instant start, Instant end)`.
  - `ICalImporter(CalendarSettings)`, `ImportedEvent parse(String ics)` (wirft `IllegalArgumentException` mit deutscher Meldung).
  - `ICalParser.parseEvents(String ics): List<ICalParser.Component>` mit `Component.name()/properties()/children()/first(String)/all(String)` und `Property.name()/value()/param(String)`.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.calendar.ical;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarSettings;
import club.badernet.nestwerk.calendar.ImportedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ICalImporterTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final ICalImporter importer = new ICalImporter(new CalendarSettings("Europe/Berlin"));

    private static String ics(String... eventLines) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//DE\r\n"
                + String.join("\r\n", eventLines) + "\r\nEND:VCALENDAR\r\n";
    }

    private static Instant at(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, BERLIN).toInstant();
    }

    @Test
    void parsesTimedEventWithTzid() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:abc", "SUMMARY:Arzt",
                "DTSTART;TZID=Europe/Berlin:20261012T100000", "DTEND;TZID=Europe/Berlin:20261012T110000",
                "END:VEVENT"));

        assertThat(e.uid()).isEqualTo("abc");
        assertThat(e.input().title()).isEqualTo("Arzt");
        assertThat(e.input().allDay()).isFalse();
        assertThat(e.input().startDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(e.input().startTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(e.input().endTime()).isEqualTo(LocalTime.of(11, 0));
    }

    @Test
    void convertsUtcTimesToConfiguredZone() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:utc", "SUMMARY:UTC",
                "DTSTART:20261012T080000Z", "DTEND:20261012T090000Z", "END:VEVENT"));

        assertThat(e.input().startTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void floatingTimesUseConfiguredZone() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:fl", "SUMMARY:Floating",
                "DTSTART:20261012T100000", "DTEND:20261012T110000", "END:VEVENT"));

        assertThat(e.input().startTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void allDayEventUsesInclusiveEndDate() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:ad", "SUMMARY:Urlaub",
                "DTSTART;VALUE=DATE:20261012", "DTEND;VALUE=DATE:20261015", "END:VEVENT"));

        assertThat(e.input().allDay()).isTrue();
        assertThat(e.input().startDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(e.input().endDate()).isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @Test
    void allDayEventWithoutEndIsOneDay() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:ad2", "SUMMARY:Feiertag", "DTSTART;VALUE=DATE:20261012", "END:VEVENT"));

        assertThat(e.input().endDate()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    void usesDurationWhenEndIsMissing() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:du", "SUMMARY:Dauer",
                "DTSTART;TZID=Europe/Berlin:20261012T100000", "DURATION:PT90M", "END:VEVENT"));

        assertThat(e.input().endTime()).isEqualTo(LocalTime.of(11, 30));
    }

    @Test
    void defaultsToOneHourWhenNoEnd() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:no", "SUMMARY:Ohne Ende",
                "DTSTART;TZID=Europe/Berlin:20261012T100000", "END:VEVENT"));

        assertThat(e.input().endTime()).isEqualTo(LocalTime.of(11, 0));
    }

    @Test
    void unfoldsLinesAndUnescapesText() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:esc",
                "SUMMARY:Kino\\, Pizza\\; Eis",
                "DESCRIPTION:Zeile 1\\nZeile 2 mit einem sehr langen Text der ger",
                " ade umgebrochen wurde",
                "LOCATION:Küche",
                "DTSTART;TZID=Europe/Berlin:20261012T100000", "DTEND;TZID=Europe/Berlin:20261012T110000",
                "END:VEVENT"));

        assertThat(e.input().title()).isEqualTo("Kino, Pizza; Eis");
        assertThat(e.input().description())
                .isEqualTo("Zeile 1\nZeile 2 mit einem sehr langen Text der gerade umgebrochen wurde");
        assertThat(e.input().location()).isEqualTo("Küche");
    }

    @Test
    void parsesRecurrenceWithExdateAndOverride() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:serie", "SUMMARY:Training",
                "DTSTART;TZID=Europe/Berlin:20261005T180000", "DTEND;TZID=Europe/Berlin:20261005T190000",
                "RRULE:FREQ=WEEKLY", "EXDATE;TZID=Europe/Berlin:20261019T180000", "END:VEVENT",
                "BEGIN:VEVENT", "UID:serie", "SUMMARY:Training (Halle)",
                "RECURRENCE-ID;TZID=Europe/Berlin:20261026T180000",
                "DTSTART;TZID=Europe/Berlin:20261026T190000", "DTEND;TZID=Europe/Berlin:20261026T200000",
                "END:VEVENT"));

        assertThat(e.input().rrule()).isEqualTo("FREQ=WEEKLY");
        assertThat(e.cancelled()).containsExactly(at(2026, 10, 19, 18, 0));
        assertThat(e.overrides()).hasSize(1);
        ImportedEvent.ImportedOverride o = e.overrides().getFirst();
        assertThat(o.recurrenceId()).isEqualTo(at(2026, 10, 26, 18, 0));
        assertThat(o.title()).isEqualTo("Training (Halle)");
        assertThat(o.start()).isEqualTo(at(2026, 10, 26, 19, 0));
        assertThat(o.end()).isEqualTo(at(2026, 10, 26, 20, 0));
    }

    @Test
    void parsesMultipleExdatesInOneLine() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:ex", "SUMMARY:Serie",
                "DTSTART;TZID=Europe/Berlin:20261005T180000", "DTEND;TZID=Europe/Berlin:20261005T190000",
                "RRULE:FREQ=DAILY",
                "EXDATE;TZID=Europe/Berlin:20261006T180000,20261007T180000", "END:VEVENT"));

        assertThat(e.cancelled()).containsExactlyInAnyOrder(at(2026, 10, 6, 18, 0), at(2026, 10, 7, 18, 0));
    }

    @Test
    void normalizesDateOnlyUntilToUtcInstant() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:until", "SUMMARY:Serie",
                "DTSTART;VALUE=DATE:20261012", "DTEND;VALUE=DATE:20261013",
                "RRULE:FREQ=DAILY;UNTIL=20261231", "END:VEVENT"));

        assertThat(e.input().rrule()).isEqualTo("FREQ=DAILY;UNTIL=20261231T225959Z");
    }

    @Test
    void privateClassMapsToPrivateFlag() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:p", "SUMMARY:Privat", "CLASS:PRIVATE",
                "DTSTART;TZID=Europe/Berlin:20261012T100000", "DTEND;TZID=Europe/Berlin:20261012T110000",
                "END:VEVENT"));

        assertThat(e.input().privateEvent()).isTrue();
    }

    @Test
    void mapsRelativeAlarmsToAllowedOffsets() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:al", "SUMMARY:Alarm",
                "DTSTART;TZID=Europe/Berlin:20261012T100000", "DTEND;TZID=Europe/Berlin:20261012T110000",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-P1D", "END:VALARM",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT7M", "END:VALARM",
                "END:VEVENT"));

        assertThat(e.input().reminderMinutes()).containsExactlyInAnyOrder(15, 1440);
    }

    @Test
    void ignoresTimezoneComponentsAndUnknownTzid() {
        ImportedEvent e = importer.parse(
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
                        + "BEGIN:VTIMEZONE\r\nTZID:W. Europe Standard Time\r\nBEGIN:STANDARD\r\n"
                        + "DTSTART:16010101T030000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0100\r\n"
                        + "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"
                        + "BEGIN:VEVENT\r\nUID:win\r\nSUMMARY:Outlook\r\n"
                        + "DTSTART;TZID=W. Europe Standard Time:20261012T100000\r\n"
                        + "DTEND;TZID=W. Europe Standard Time:20261012T110000\r\nEND:VEVENT\r\n"
                        + "END:VCALENDAR\r\n");

        assertThat(e.input().startTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(e.input().rrule()).isNull();
    }

    @Test
    void rejectsContentWithoutEvent() {
        assertThatThrownBy(() -> importer.parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> importer.parse("das ist kein kalender"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingUidOrStart() {
        assertThatThrownBy(() -> importer.parse(ics("BEGIN:VEVENT", "SUMMARY:x",
                "DTSTART:20261012T100000", "END:VEVENT"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> importer.parse(ics("BEGIN:VEVENT", "UID:x", "SUMMARY:x", "END:VEVENT")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDifferentUidsInOneResource() {
        assertThatThrownBy(() -> importer.parse(ics(
                "BEGIN:VEVENT", "UID:a", "SUMMARY:a", "DTSTART:20261012T100000", "END:VEVENT",
                "BEGIN:VEVENT", "UID:b", "SUMMARY:b", "DTSTART:20261013T100000", "END:VEVENT")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingSummaryBecomesPlaceholderTitle() {
        ImportedEvent e = importer.parse(ics(
                "BEGIN:VEVENT", "UID:t", "DTSTART;TZID=Europe/Berlin:20261012T100000", "END:VEVENT"));

        assertThat(e.input().title()).isEqualTo("(Ohne Titel)");
    }

    @Test
    void writerOutputCanBeImportedAgain() {
        CalendarEvent original = new CalendarEvent("rt@nestwerk", "Kino, Pizza; Eis\nÜbermorgen",
                at(2026, 10, 5, 18, 0), at(2026, 10, 5, 19, 30), false, "Europe/Berlin", 1L);
        original.setLocation("Küche, 2. OG");
        original.setRrule("FREQ=WEEKLY;BYDAY=MO");
        original.setReminderMinutes(Set.of(10, 60));
        original.cancelOccurrence(at(2026, 10, 12, 18, 0));
        original.overrideOccurrence(at(2026, 10, 19, 18, 0), "Verschoben", null, null,
                at(2026, 10, 20, 18, 0), at(2026, 10, 20, 19, 30));

        String text = new ICalWriter().write("Familie", List.of(original));
        ImportedEvent back = importer.parse(text);

        assertThat(back.uid()).isEqualTo("rt@nestwerk");
        assertThat(back.input().title()).isEqualTo("Kino, Pizza; Eis\nÜbermorgen");
        assertThat(back.input().location()).isEqualTo("Küche, 2. OG");
        assertThat(back.input().rrule()).isEqualTo("FREQ=WEEKLY;BYDAY=MO");
        assertThat(back.input().reminderMinutes()).containsExactlyInAnyOrder(10, 60);
        assertThat(back.cancelled()).containsExactly(at(2026, 10, 12, 18, 0));
        assertThat(back.overrides()).hasSize(1);
        assertThat(back.overrides().getFirst().start()).isEqualTo(at(2026, 10, 20, 18, 0));
    }
}
```
(Der Test nutzt `new CalendarSettings("Europe/Berlin")` – der Konstruktor aus Phase 2 ist öffentlich.)

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=ICalImporterTest`
Expected: Kompilierfehler.

- [ ] **Step 3: Implementieren**

`calendar/ImportedEvent.java`:
```java
package club.badernet.nestwerk.calendar;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Ergebnis des Einlesens einer iCal-Ressource (ein Termin, ggf. mit Ausnahmen). */
public record ImportedEvent(String uid, EventInput input, Set<Instant> cancelled,
                            List<ImportedOverride> overrides) {

    public record ImportedOverride(Instant recurrenceId, String title, String description,
                                   String location, Instant start, Instant end) {
    }
}
```

`calendar/ical/ICalParser.java`:
```java
package club.badernet.nestwerk.calendar.ical;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Minimaler RFC-5545-Leser: entfaltet Zeilen und liefert VEVENT-Komponenten mit Unterkomponenten. */
public final class ICalParser {

    public record Property(String name, Map<String, String> params, String value) {
        public String param(String key) {
            return params.get(key.toUpperCase(Locale.ROOT));
        }
    }

    public static final class Component {
        private final String name;
        private final List<Property> properties = new ArrayList<>();
        private final List<Component> children = new ArrayList<>();

        Component(String name) {
            this.name = name;
        }

        public String name() { return name; }
        public List<Property> properties() { return properties; }
        public List<Component> children() { return children; }

        public Optional<Property> first(String propertyName) {
            return properties.stream().filter(p -> p.name().equals(propertyName)).findFirst();
        }

        public List<Property> all(String propertyName) {
            return properties.stream().filter(p -> p.name().equals(propertyName)).toList();
        }
    }

    private ICalParser() {
    }

    public static List<Component> parseEvents(String ics) {
        if (ics == null || !ics.toUpperCase(Locale.ROOT).contains("BEGIN:VCALENDAR")) {
            throw new IllegalArgumentException("Das ist kein gültiger iCalendar-Inhalt.");
        }
        Deque<Component> stack = new ArrayDeque<>();
        List<Component> events = new ArrayList<>();
        for (String line : unfold(ics)) {
            if (line.isBlank()) {
                continue;
            }
            Property p = parseLine(line);
            if (p == null) {
                continue;
            }
            switch (p.name()) {
                case "BEGIN" -> stack.push(new Component(p.value().trim().toUpperCase(Locale.ROOT)));
                case "END" -> {
                    if (stack.isEmpty()) {
                        throw new IllegalArgumentException("Ungültiger iCalendar-Inhalt: unerwartetes END.");
                    }
                    Component done = stack.pop();
                    if (!stack.isEmpty()) {
                        stack.peek().children.add(done);
                    }
                    if (done.name.equals("VEVENT")) {
                        events.add(done);
                    }
                }
                default -> {
                    if (!stack.isEmpty()) {
                        stack.peek().properties.add(p);
                    }
                }
            }
        }
        if (!stack.isEmpty()) {
            throw new IllegalArgumentException("Ungültiger iCalendar-Inhalt: fehlendes END.");
        }
        return events;
    }

    private static List<String> unfold(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String raw : normalized.split("\n", -1)) {
            if (!lines.isEmpty() && !raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
                int last = lines.size() - 1;
                lines.set(last, lines.get(last) + raw.substring(1));
            } else {
                lines.add(raw);
            }
        }
        return lines;
    }

    static Property parseLine(String line) {
        boolean quoted = false;
        int colon = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ':' && !quoted) {
                colon = i;
                break;
            }
        }
        if (colon < 0) {
            return null;
        }
        List<String> parts = splitUnquoted(line.substring(0, colon), ';');
        String name = parts.get(0).trim().toUpperCase(Locale.ROOT);
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < parts.size(); i++) {
            int eq = parts.get(i).indexOf('=');
            if (eq > 0) {
                String key = parts.get(i).substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String value = parts.get(i).substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                params.put(key, value);
            }
        }
        return new Property(name, params, line.substring(colon + 1));
    }

    private static List<String> splitUnquoted(String text, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : text.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (c == separator && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    public static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                switch (next) {
                    case 'n', 'N' -> out.append('\n');
                    case ',', ';', '\\' -> out.append(next);
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
```

`calendar/ical/ICalImporter.java`:
```java
package club.badernet.nestwerk.calendar.ical;

import club.badernet.nestwerk.calendar.CalendarSettings;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.calendar.ImportedEvent;
import club.badernet.nestwerk.calendar.ImportedEvent.ImportedOverride;
import club.badernet.nestwerk.calendar.ReminderOptions;
import club.badernet.nestwerk.calendar.ical.ICalParser.Component;
import club.badernet.nestwerk.calendar.ical.ICalParser.Property;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@org.springframework.stereotype.Component
public class ICalImporter {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final Pattern DURATION = Pattern.compile(
            "^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$");
    private static final Pattern DATE_ONLY_UNTIL = Pattern.compile("UNTIL=(\\d{8})(?![T\\d])");

    private final ZoneId zone;

    public ICalImporter(CalendarSettings settings) {
        this.zone = settings.zone();
    }

    private record Moment(Instant instant, boolean dateOnly) {
    }

    public ImportedEvent parse(String ics) {
        List<Component> events = ICalParser.parseEvents(ics);
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Die Datei enthält keinen Termin (VEVENT).");
        }
        Set<String> uids = new HashSet<>();
        for (Component e : events) {
            String uid = text(e, "UID");
            if (uid == null || uid.isBlank()) {
                throw new IllegalArgumentException("Der Termin hat keine UID.");
            }
            uids.add(uid);
        }
        if (uids.size() != 1) {
            throw new IllegalArgumentException("Alle Termine einer Ressource müssen dieselbe UID haben.");
        }
        List<Component> masters = events.stream().filter(e -> e.first("RECURRENCE-ID").isEmpty()).toList();
        if (masters.size() != 1) {
            throw new IllegalArgumentException("Erwartet wird genau ein Haupttermin ohne RECURRENCE-ID.");
        }
        Component master = masters.getFirst();
        Moment start = moment(master, "DTSTART")
                .orElseThrow(() -> new IllegalArgumentException("Der Termin hat keinen Beginn (DTSTART)."));
        Optional<Moment> end = moment(master, "DTEND");
        Duration duration = master.first("DURATION").map(p -> duration(p.value())).orElse(null);

        boolean allDay = start.dateOnly();
        LocalDate startDate;
        LocalTime startTime = null;
        LocalDate endDate;
        LocalTime endTime = null;
        if (allDay) {
            startDate = start.instant().atZone(zone).toLocalDate();
            LocalDate last = startDate;
            if (end.isPresent()) {
                last = end.get().instant().atZone(zone).toLocalDate().minusDays(1);
            } else if (duration != null) {
                last = startDate.plusDays(Math.max(0, duration.toDays() - 1));
            }
            endDate = last.isBefore(startDate) ? startDate : last;
        } else {
            Instant endInstant = end.map(Moment::instant)
                    .orElseGet(() -> start.instant().plus(duration != null ? duration : Duration.ofHours(1)));
            if (!endInstant.isAfter(start.instant())) {
                endInstant = start.instant().plus(Duration.ofHours(1));
            }
            ZonedDateTime s = start.instant().atZone(zone);
            ZonedDateTime e = endInstant.atZone(zone);
            startDate = s.toLocalDate();
            startTime = s.toLocalTime();
            endDate = e.toLocalDate();
            endTime = e.toLocalTime();
        }

        String summary = text(master, "SUMMARY");
        String title = summary == null || summary.isBlank() ? "(Ohne Titel)" : summary;
        String klass = text(master, "CLASS");
        boolean isPrivate = klass != null
                && (klass.equalsIgnoreCase("PRIVATE") || klass.equalsIgnoreCase("CONFIDENTIAL"));
        String rrule = master.first("RRULE").map(p -> normalizeRule(p.value(), allDay)).orElse(null);

        EventInput input = new EventInput(title, text(master, "DESCRIPTION"), text(master, "LOCATION"),
                startDate, startTime, endDate, endTime, allDay, rrule, isPrivate, Set.of(),
                reminders(master));

        Set<Instant> cancelled = new LinkedHashSet<>();
        for (Property exdate : master.all("EXDATE")) {
            for (String value : exdate.value().split(",")) {
                if (!value.isBlank()) {
                    cancelled.add(toMoment(new Property("EXDATE", exdate.params(), value.trim())).instant());
                }
            }
        }

        List<ImportedOverride> overrides = new ArrayList<>();
        for (Component e : events) {
            Optional<Moment> recurrence = moment(e, "RECURRENCE-ID");
            if (recurrence.isEmpty()) {
                continue;
            }
            Instant ostart = moment(e, "DTSTART").map(Moment::instant).orElse(recurrence.get().instant());
            Instant oend = moment(e, "DTEND").map(Moment::instant)
                    .orElseGet(() -> ostart.plus(Duration.between(
                            start.instant(), end.map(Moment::instant).orElse(start.instant().plus(Duration.ofHours(1))))));
            overrides.add(new ImportedOverride(recurrence.get().instant(), text(e, "SUMMARY"),
                    text(e, "DESCRIPTION"), text(e, "LOCATION"), ostart, oend));
        }
        return new ImportedEvent(text(master, "UID"), input, cancelled, overrides);
    }

    private String text(Component component, String name) {
        return component.first(name).map(p -> ICalParser.unescape(p.value())).orElse(null);
    }

    private Optional<Moment> moment(Component component, String name) {
        return component.first(name).map(this::toMoment);
    }

    private Moment toMoment(Property p) {
        String v = p.value().trim();
        try {
            if ("DATE".equalsIgnoreCase(p.param("VALUE")) || v.length() == 8) {
                LocalDate date = LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE);
                return new Moment(date.atStartOfDay(zone).toInstant(), true);
            }
            if (v.endsWith("Z") || v.endsWith("z")) {
                LocalDateTime utc = LocalDateTime.parse(v.substring(0, v.length() - 1), LOCAL);
                return new Moment(utc.toInstant(ZoneOffset.UTC), false);
            }
            ZoneId tz = zone;
            String tzid = p.param("TZID");
            if (tzid != null) {
                try {
                    tz = ZoneId.of(tzid);
                } catch (DateTimeException unknown) {
                    tz = zone;
                }
            }
            return new Moment(LocalDateTime.parse(v, LOCAL).atZone(tz).toInstant(), false);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Ungültiges Datum im Termin: " + v);
        }
    }

    private String normalizeRule(String value, boolean allDay) {
        String rule = value.trim().replaceFirst("(?i)^RRULE:", "");
        Matcher m = DATE_ONLY_UNTIL.matcher(rule);
        if (m.find()) {
            LocalDate last = LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE);
            Instant endOfDay = last.atTime(23, 59, 59).atZone(zone).toInstant();
            String until = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
                    .format(endOfDay);
            rule = m.replaceFirst("UNTIL=" + until);
        }
        return rule;
    }

    private Set<Integer> reminders(Component event) {
        Set<Integer> result = new HashSet<>();
        for (Component child : event.children()) {
            if (!child.name().equals("VALARM")) {
                continue;
            }
            Optional<Property> trigger = child.first("TRIGGER");
            if (trigger.isEmpty() || "DATE-TIME".equalsIgnoreCase(trigger.get().param("VALUE"))
                    || "END".equalsIgnoreCase(trigger.get().param("RELATED"))) {
                continue;
            }
            try {
                Duration d = duration(trigger.get().value());
                long minutes = d.isNegative() ? d.negated().toMinutes() : (d.isZero() ? 0 : -1);
                if (minutes >= 0 && ReminderOptions.isAllowed((int) minutes)) {
                    result.add((int) minutes);
                }
            } catch (IllegalArgumentException ignored) {
                // Nicht darstellbare Alarme werden übersprungen.
            }
        }
        return result;
    }

    static Duration duration(String text) {
        Matcher m = DURATION.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Ungültige Dauer: " + text);
        }
        long weeks = num(m.group(2));
        long days = num(m.group(3));
        long hours = num(m.group(4));
        long minutes = num(m.group(5));
        long seconds = num(m.group(6));
        Duration d = Duration.ofDays(weeks * 7 + days).plusHours(hours).plusMinutes(minutes).plusSeconds(seconds);
        return "-".equals(m.group(1)) ? d.negated() : d;
    }

    private static long num(String s) {
        return s == null ? 0 : Long.parseLong(s);
    }
}
```
Hinweis: `ICalParser.Component` und die Spring-Annotation heißen beide `Component`; deshalb ist die Annotation am Importer voll qualifiziert (`@org.springframework.stereotype.Component`) und nicht importiert.

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=ICalImporterTest`
Expected: PASS (19 Tests).
Hinweis zu `normalizesDateOnlyUntilToUtcInstant`: 31.12.2026 23:59:59 Europe/Berlin (CET, UTC+1) = 22:59:59Z, daher `20261231T225959Z`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: iCal-Parser und Importer für CalDAV-Schreibzugriffe"
```

---

### Task 3: Änderungsprotokoll und Import im Service

**Files:**
- Create: `calendar/CalendarChange.java`, `calendar/CalendarChangeRepository.java`, `calendar/CalendarChangeLog.java`
- Modify: `calendar/CalendarEvent.java` (`retainExceptionsFor`), `calendar/CalendarEventService.java`
- Test: `src/test/java/club/badernet/nestwerk/calendar/CalendarSyncServiceTest.java`

**Interfaces:**
- Produces:
  - `CalendarChangeLog.record(String uid, boolean deleted)`, `currentToken(): long`, `uidsChangedSince(long token): List<String>`.
  - `CalendarEvent.retainExceptionsFor(Set<Instant> recurrenceIds)`.
  - `CalendarEventService.create(String uid, EventInput, User): CalendarEvent` (neue Überladung), `findByUidVisible(String uid, User): Optional<CalendarEvent>`, `saveImported(ImportedEvent, User): SaveResult` und `record SaveResult(CalendarEvent event, boolean created)` (verschachtelt in `CalendarEventService`).
  - Alle schreibenden Service-Methoden (`create`, `update`, `delete`, `cancelOccurrence`, `overrideOccurrence`, `saveImported`) protokollieren eine Änderung.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.ImportedEvent.ImportedOverride;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.reminders.enabled=false"})
@Transactional
class CalendarSyncServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Autowired CalendarEventService service;
    @Autowired CalendarChangeLog changes;
    @Autowired UserService userService;
    @Autowired RoleService roleService;

    User owner;
    User other;

    @BeforeEach
    void setUp() {
        Role role = roleService.create("Sync", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN", "CALENDAR_PRIVATE_CREATE"));
        owner = userService.create("sync-owner", "passwort1", Set.of(role.getId()));
        other = userService.create("sync-other", "passwort1", Set.of(role.getId()));
    }

    private static Instant at(int d, int h) {
        return ZonedDateTime.of(2026, 10, d, h, 0, 0, 0, BERLIN).toInstant();
    }

    private static EventInput input(String title, boolean isPrivate, String rrule) {
        return new EventInput(title, null, null, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 5), LocalTime.of(11, 0), false, rrule, isPrivate, Set.of());
    }

    private static ImportedEvent imported(String uid, EventInput in, Set<Instant> cancelled,
                                          List<ImportedOverride> overrides) {
        return new ImportedEvent(uid, in, cancelled, overrides);
    }

    @Test
    void everyWriteAdvancesTheToken() {
        long t0 = changes.currentToken();
        CalendarEvent e = service.create(input("A", false, null), owner);
        long t1 = changes.currentToken();
        service.update(e.getId(), input("B", false, null), owner);
        long t2 = changes.currentToken();
        service.delete(e.getId(), owner);
        long t3 = changes.currentToken();

        assertThat(t1).isGreaterThan(t0);
        assertThat(t2).isGreaterThan(t1);
        assertThat(t3).isGreaterThan(t2);
        assertThat(changes.uidsChangedSince(t0)).containsExactly(e.getUid());
    }

    @Test
    void tokenSinceNowIsEmpty() {
        service.create(input("A", false, null), owner);

        assertThat(changes.uidsChangedSince(changes.currentToken())).isEmpty();
    }

    @Test
    void importCreatesNewEventWithGivenUid() {
        EventInput in = input("Vom Handy", false, null);

        CalendarEventService.SaveResult r = service.saveImported(
                imported("handy-1", in, Set.of(), List.of()), owner);

        assertThat(r.created()).isTrue();
        assertThat(r.event().getUid()).isEqualTo("handy-1");
        assertThat(service.findByUidVisible("handy-1", owner)).isPresent();
    }

    @Test
    void importUpdatesExistingEventAndKeepsMembers() {
        CalendarEvent e = service.create(input("Alt", false, null), owner);
        e.setMemberIds(Set.of());
        int seq = e.getSequence();

        CalendarEventService.SaveResult r = service.saveImported(
                imported(e.getUid(), input("Neu", false, null), Set.of(), List.of()), owner);

        assertThat(r.created()).isFalse();
        assertThat(r.event().getTitle()).isEqualTo("Neu");
        assertThat(r.event().getSequence()).isGreaterThan(seq);
    }

    @Test
    void importAppliesCancellationsAndOverridesAndDropsOthers() {
        CalendarEvent e = service.create(input("Serie", false, "FREQ=WEEKLY"), owner);
        service.cancelOccurrence(e.getId(), at(12, 10), owner);

        service.saveImported(imported(e.getUid(), input("Serie", false, "FREQ=WEEKLY"),
                Set.of(at(19, 10)),
                List.of(new ImportedOverride(at(26, 10), "Halle", null, null, at(26, 18), at(26, 19)))), owner);

        CalendarEvent reloaded = service.get(e.getId(), owner);
        assertThat(reloaded.exceptionFor(at(12, 10))).isEmpty();
        assertThat(reloaded.exceptionFor(at(19, 10))).get().extracting(CalendarEventException::isCancelled)
                .isEqualTo(true);
        assertThat(reloaded.exceptionFor(at(26, 10))).get().extracting(CalendarEventException::getTitle)
                .isEqualTo("Halle");
    }

    @Test
    void importIgnoresExceptionsThatAreNotPartOfTheSeries() {
        CalendarEvent e = service.create(input("Serie", false, "FREQ=WEEKLY"), owner);

        service.saveImported(imported(e.getUid(), input("Serie", false, "FREQ=WEEKLY"),
                Set.of(at(13, 10)), List.of()), owner);

        assertThat(service.get(e.getId(), owner).getExceptions()).isEmpty();
    }

    @Test
    void importCannotTouchForeignPrivateEvent() {
        CalendarEvent secret = service.create(input("Geheim", true, null), owner);

        assertThatThrownBy(() -> service.saveImported(
                imported(secret.getUid(), input("Übernommen", false, null), Set.of(), List.of()), other))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void importCannotEditForeignPublicEventWithoutPermission() {
        CalendarEvent e = service.create(input("Meins", false, null), owner);

        assertThatThrownBy(() -> service.saveImported(
                imported(e.getUid(), input("Fremd", false, null), Set.of(), List.of()), other))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void findByUidVisibleHidesForeignPrivateEvents() {
        CalendarEvent secret = service.create(input("Geheim", true, null), owner);

        assertThat(service.findByUidVisible(secret.getUid(), other)).isEmpty();
        assertThat(service.findByUidVisible(secret.getUid(), owner)).isPresent();
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=CalendarSyncServiceTest`
Expected: Kompilierfehler.

- [ ] **Step 3: Implementieren**

`calendar/CalendarChange.java`:
```java
package club.badernet.nestwerk.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "calendar_changes")
public class CalendarChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    @Column(name = "event_uid", nullable = false, length = 255)
    private String eventUid;

    @Column(nullable = false)
    private boolean deleted;

    protected CalendarChange() {
    }

    public CalendarChange(String eventUid, boolean deleted) {
        this.eventUid = eventUid;
        this.deleted = deleted;
    }

    public Long getSeq() { return seq; }
    public String getEventUid() { return eventUid; }
    public boolean isDeleted() { return deleted; }
}
```

`calendar/CalendarChangeRepository.java`:
```java
package club.badernet.nestwerk.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CalendarChangeRepository extends JpaRepository<CalendarChange, Long> {

    @Query("select coalesce(max(c.seq), 0) from CalendarChange c")
    long maxSeq();

    @Query("select distinct c.eventUid from CalendarChange c where c.seq > :since order by c.eventUid")
    List<String> uidsChangedSince(@Param("since") long since);
}
```

`calendar/CalendarChangeLog.java`:
```java
package club.badernet.nestwerk.calendar;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class CalendarChangeLog {

    private final CalendarChangeRepository changes;

    public CalendarChangeLog(CalendarChangeRepository changes) {
        this.changes = changes;
    }

    @Transactional
    public void record(String uid, boolean deleted) {
        changes.save(new CalendarChange(uid, deleted));
    }

    @Transactional(readOnly = true)
    public long currentToken() {
        return changes.maxSeq();
    }

    @Transactional(readOnly = true)
    public List<String> uidsChangedSince(long token) {
        return changes.uidsChangedSince(token);
    }
}
```

`CalendarEvent` ergänzen:
```java
    /** Entfernt alle Ausnahmen, deren Vorkommen nicht in der übergebenen Menge liegt. */
    public void retainExceptionsFor(Set<Instant> recurrenceIds) {
        exceptions.removeIf(e -> !recurrenceIds.contains(e.getRecurrenceId()));
    }
```

`CalendarEventService` – Änderungen:
1. Konstruktor um `CalendarChangeLog changes` erweitern (Feld `private final CalendarChangeLog changes;`).
2. `create(EventInput, User)` delegiert:
```java
    @Transactional
    public CalendarEvent create(EventInput in, User actor) {
        return create(UUID.randomUUID() + "@nestwerk", in, actor);
    }

    @Transactional
    public CalendarEvent create(String uid, EventInput in, User actor) {
        require(actor, CalendarPermission.CALENDAR_EVENT_CREATE);
        if (in.privateEvent()) {
            require(actor, CalendarPermission.CALENDAR_PRIVATE_CREATE);
        }
        Instant[] times = times(in);
        CalendarEvent event = new CalendarEvent(uid, requireTitle(in.title()),
                times[0], times[1], in.allDay(), zone.getId(), actor.getId());
        applyDetails(event, in);
        CalendarEvent saved = events.save(event);
        changes.record(saved.getUid(), false);
        return saved;
    }
```
3. `update(Long, EventInput, User)` wird zu:
```java
    @Transactional
    public void update(Long id, EventInput in, User actor) {
        updateEvent(get(id, actor), in, actor, true);
    }

    private void updateEvent(CalendarEvent event, EventInput in, User actor, boolean dropStaleExceptions) {
        if (!canEdit(event, actor)) {
            throw new AccessDeniedException("Keine Berechtigung zum Bearbeiten dieses Termins");
        }
        if (in.privateEvent() && !event.isPrivateEvent()) {
            require(actor, CalendarPermission.CALENDAR_PRIVATE_CREATE);
        }
        Instant[] times = times(in);
        boolean seriesChanged = !times[0].equals(event.getStartsAt())
                || !java.util.Objects.equals(normalizeRule(in.rrule()), event.getRrule());
        event.setTitle(requireTitle(in.title()));
        event.setTimes(times[0], times[1], in.allDay());
        applyDetails(event, in);
        if (seriesChanged && dropStaleExceptions) {
            event.clearExceptions();
        }
        event.markChanged();
        changes.record(event.getUid(), false);
    }
```
4. `delete`: vor `events.delete(event)` ergänzen: `changes.record(event.getUid(), true);`
5. `cancelOccurrence` und `overrideOccurrence`: jeweils am Ende `changes.record(event.getUid(), false);`
6. Neue Methoden:
```java
    public record SaveResult(CalendarEvent event, boolean created) {
    }

    @Transactional(readOnly = true)
    public java.util.Optional<CalendarEvent> findByUidVisible(String uid, User viewer) {
        require(viewer, CalendarPermission.CALENDAR_VIEW);
        return events.findByUid(uid).filter(e -> isVisible(e, viewer));
    }

    @Transactional
    public SaveResult saveImported(ImportedEvent data, User actor) {
        java.util.Optional<CalendarEvent> found = events.findByUid(data.uid());
        boolean created = found.isEmpty();
        CalendarEvent event;
        if (created) {
            event = create(data.uid(), data.input(), actor);
        } else {
            event = found.get();
            if (!isVisible(event, actor)) {
                throw new AccessDeniedException("Keine Berechtigung für diesen Termin");
            }
            EventInput keepMembers = new EventInput(data.input().title(), data.input().description(),
                    data.input().location(), data.input().startDate(), data.input().startTime(),
                    data.input().endDate(), data.input().endTime(), data.input().allDay(),
                    data.input().rrule(), data.input().privateEvent(), event.getMemberIds(),
                    data.input().reminderMinutes());
            updateEvent(event, keepMembers, actor, false);
        }
        if (event.getRrule() == null) {
            event.clearExceptions();
        } else {
            Set<Instant> keep = new HashSet<>();
            for (Instant cancelled : data.cancelled()) {
                if (expander.isBaseOccurrence(event, cancelled)) {
                    event.cancelOccurrence(cancelled);
                    keep.add(cancelled);
                }
            }
            for (ImportedEvent.ImportedOverride o : data.overrides()) {
                if (expander.isBaseOccurrence(event, o.recurrenceId())) {
                    event.overrideOccurrence(o.recurrenceId(), o.title(), o.description(), o.location(),
                            o.start(), o.end());
                    keep.add(o.recurrenceId());
                }
            }
            event.retainExceptionsFor(keep);
        }
        changes.record(event.getUid(), false);
        return new SaveResult(event, created);
    }
```
Hinweis: `clearExceptions()` bei einer Serie ohne RRULE und gleichzeitiges späteres Anlegen derselben Keys tritt hier nicht auf (kein Re-Insert), daher keine Unique-Verletzung beim Flush.

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=CalendarSyncServiceTest+CalendarEventServiceTest+CalendarEditTests`
Expected: PASS (bestehende Service- und UI-Tests bleiben grün).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Änderungsprotokoll und Import-Speicherpfad im Termin-Service"
```

---

### Task 4: CalDAV lesend (OPTIONS, PROPFIND, REPORT, GET)

**Files:**
- Create: `calendar/caldav/DavXml.java`, `calendar/caldav/CalDavFilter.java`, `config/CalDavConfig.java`
- Test: `src/test/java/club/badernet/nestwerk/web/CalDavReadTests.java`

**Interfaces:**
- Consumes: `CalendarEventService.findAllVisible/findByUidVisible`, `CalendarChangeLog`, `ICalWriter`, `UserRepository`.
- Produces: Routen unter `/caldav/`: Root, `principals/{user}/`, `calendars/{user}/`, `calendars/{user}/family/`, `calendars/{user}/family/{uid}.ics`; `/.well-known/caldav` → 301 auf `/caldav/`. `CalDavFilter` hat zusätzlich öffentliche Hilfsmethode `static String etag(CalendarEvent)`.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarChangeLog;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.calendar.caldav.AppPasswordService;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.reminders.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class CalDavReadTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired AppPasswordService passwords;
    @Autowired CalendarEventService events;
    @Autowired CalendarChangeLog changes;

    User a;
    User b;
    String passwordA;
    String passwordB;
    CalendarEvent open;
    CalendarEvent secret;

    @BeforeEach
    void setUp() {
        Role role = roleService.create("Dav", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN", "CALENDAR_PRIVATE_CREATE"));
        a = userService.create("dav-a", "login-passwort", Set.of(role.getId()));
        b = userService.create("dav-b", "login-passwort", Set.of(role.getId()));
        passwordA = passwords.create(a, "Handy");
        passwordB = passwords.create(b, "Handy");
        open = events.create(new EventInput("Zahnarzt", null, null, LocalDate.of(2026, 10, 12),
                LocalTime.of(10, 0), LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false,
                Set.of()), a);
        secret = events.create(new EventInput("Überraschung", null, null, LocalDate.of(2026, 10, 13),
                LocalTime.of(10, 0), LocalDate.of(2026, 10, 13), LocalTime.of(11, 0), false, null, true,
                Set.of()), a);
    }

    private ResultActions dav(String method, String path, User user, String password, String depth,
                              String body) throws Exception {
        var builder = request(HttpMethod.valueOf(method), path).with(httpBasic(user.getUsername(), password));
        if (depth != null) {
            builder.header("Depth", depth);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_XML).content(body);
        }
        return mockMvc.perform(builder);
    }

    private static String eventPath(String user, CalendarEvent e) {
        return "/caldav/calendars/" + user + "/family/" + e.getUid() + ".ics";
    }

    @Test
    void optionsAdvertisesCalendarAccess() throws Exception {
        dav("OPTIONS", "/caldav/", a, passwordA, null, null)
                .andExpect(status().isOk())
                .andExpect(header().string("DAV", containsString("calendar-access")));
    }

    @Test
    void wellKnownRedirectsToCalDavRoot() throws Exception {
        mockMvc.perform(get("/.well-known/caldav"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", containsString("/caldav/")));
    }

    @Test
    void rootPropfindPointsToPrincipal() throws Exception {
        dav("PROPFIND", "/caldav/", a, passwordA, "0", null)
                .andExpect(status().isMultiStatus())
                .andExpect(content().string(containsString("current-user-principal")))
                .andExpect(content().string(containsString("/caldav/principals/dav-a/")));
    }

    @Test
    void principalPropfindExposesCalendarHome() throws Exception {
        dav("PROPFIND", "/caldav/principals/dav-a/", a, passwordA, "0", null)
                .andExpect(status().isMultiStatus())
                .andExpect(content().string(containsString("calendar-home-set")))
                .andExpect(content().string(containsString("/caldav/calendars/dav-a/")));
    }

    @Test
    void homePropfindDepthOneListsCalendar() throws Exception {
        dav("PROPFIND", "/caldav/calendars/dav-a/", a, passwordA, "1", null)
                .andExpect(status().isMultiStatus())
                .andExpect(content().string(containsString("/caldav/calendars/dav-a/family/")))
                .andExpect(content().string(containsString("supported-calendar-component-set")))
                .andExpect(content().string(containsString("getctag")))
                .andExpect(content().string(containsString("sync-token")));
    }

    @Test
    void calendarPropfindDepthOneListsEventsWithEtag() throws Exception {
        dav("PROPFIND", "/caldav/calendars/dav-a/family/", a, passwordA, "1", null)
                .andExpect(status().isMultiStatus())
                .andExpect(content().string(containsString(open.getUid() + ".ics")))
                .andExpect(content().string(containsString("getetag")))
                .andExpect(content().string(containsString(secret.getUid() + ".ics")));
    }

    @Test
    void privateEventIsNotListedForOtherUser() throws Exception {
        dav("PROPFIND", "/caldav/calendars/dav-b/family/", b, passwordB, "1", null)
                .andExpect(status().isMultiStatus())
                .andExpect(content().string(containsString(open.getUid() + ".ics")))
                .andExpect(content().string(not(containsString(secret.getUid()))));
    }

    @Test
    void getReturnsIcsWithEtag() throws Exception {
        dav("GET", eventPath("dav-a", open), a, passwordA, null, null)
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Content-Type", containsString("text/calendar")))
                .andExpect(content().string(containsString("SUMMARY:Zahnarzt")));
    }

    @Test
    void foreignPrivateEventIsNotFound() throws Exception {
        dav("GET", eventPath("dav-b", secret), b, passwordB, null, null)
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUsersHomeIsForbidden() throws Exception {
        dav("PROPFIND", "/caldav/calendars/dav-a/family/", b, passwordB, "1", null)
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        dav("PROPFIND", "/caldav/unbekannt/", a, passwordA, "0", null).andExpect(status().isNotFound());
    }

    @Test
    void multigetReturnsCalendarData() throws Exception {
        String body = """
                <?xml version="1.0" encoding="utf-8"?>
                <C:calendar-multiget xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:prop><D:getetag/><C:calendar-data/></D:prop>
                  <D:href>%s</D:href>
                  <D:href>/caldav/calendars/dav-a/family/gibt-es-nicht.ics</D:href>
                </C:calendar-multiget>
                """.formatted(eventPath("dav-a", open));

        dav("REPORT", "/caldav/calendars/dav-a/family/", a, passwordA, "1", body)
                .andExpect(status().isMultiStatus())
                .andExpect(content().string(containsString("SUMMARY:Zahnarzt")))
                .andExpect(content().string(containsString("404 Not Found")));
    }

    @Test
    void calendarQueryReturnsAllVisibleEvents() throws Exception {
        String body = """
                <?xml version="1.0" encoding="utf-8"?>
                <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:prop><D:getetag/><C:calendar-data/></D:prop>
                  <C:filter><C:comp-filter name="VCALENDAR"><C:comp-filter name="VEVENT"/></C:comp-filter></C:filter>
                </C:calendar-query>
                """;

        dav("REPORT", "/caldav/calendars/dav-b/family/", b, passwordB, "1", body)
                .andExpect(status().isMultiStatus())
                .andExpect(content().string(containsString("SUMMARY:Zahnarzt")))
                .andExpect(content().string(not(containsString("Überraschung"))));
    }

    @Test
    void initialSyncCollectionReturnsEverythingAndToken() throws Exception {
        String body = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:sync-collection xmlns:D="DAV:">
                  <D:sync-token/><D:sync-level>1</D:sync-level><D:prop><D:getetag/></D:prop>
                </D:sync-collection>
                """;

        dav("REPORT", "/caldav/calendars/dav-a/family/", a, passwordA, null, body)
                .andExpect(status().isMultiStatus())
                .andExpect(content().string(containsString(open.getUid() + ".ics")))
                .andExpect(content().string(containsString("<D:sync-token>urn:nestwerk:"
                        + changes.currentToken() + "</D:sync-token>")));
    }

    @Test
    void incrementalSyncReportsChangesAndDeletions() throws Exception {
        long token = changes.currentToken();
        CalendarEvent added = events.create(new EventInput("Neu", null, null, LocalDate.of(2026, 10, 20),
                LocalTime.of(10, 0), LocalDate.of(2026, 10, 20), LocalTime.of(11, 0), false, null, false,
                Set.of()), a);
        events.delete(open.getId(), a);
        String body = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:sync-collection xmlns:D="DAV:">
                  <D:sync-token>urn:nestwerk:%d</D:sync-token><D:sync-level>1</D:sync-level>
                  <D:prop><D:getetag/></D:prop>
                </D:sync-collection>
                """.formatted(token);

        dav("REPORT", "/caldav/calendars/dav-a/family/", a, passwordA, null, body)
                .andExpect(status().isMultiStatus())
                .andExpect(content().string(containsString(added.getUid() + ".ics")))
                .andExpect(content().string(containsString(open.getUid() + ".ics")))
                .andExpect(content().string(containsString("404 Not Found")))
                .andExpect(content().string(not(containsString(secret.getUid()))));
    }

    @Test
    void futureSyncTokenIsRejectedSoClientResyncs() throws Exception {
        String body = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:sync-collection xmlns:D="DAV:">
                  <D:sync-token>urn:nestwerk:999999999</D:sync-token><D:sync-level>1</D:sync-level>
                  <D:prop><D:getetag/></D:prop>
                </D:sync-collection>
                """;

        dav("REPORT", "/caldav/calendars/dav-a/family/", a, passwordA, null, body)
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("valid-sync-token")));
    }

    @Test
    void brokenXmlIsBadRequest() throws Exception {
        dav("REPORT", "/caldav/calendars/dav-a/family/", a, passwordA, "1", "<kaputt")
                .andExpect(status().isBadRequest());
    }

    @Test
    void xmlWithDoctypeIsRejected() throws Exception {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<D:sync-collection xmlns:D=\"DAV:\"><D:sync-token>&e;</D:sync-token></D:sync-collection>";

        dav("REPORT", "/caldav/calendars/dav-a/family/", a, passwordA, null, evil)
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedMethodIsNotAllowed() throws Exception {
        dav("MKCALENDAR", "/caldav/calendars/dav-a/neu/", a, passwordA, null, null)
                .andExpect(status().isMethodNotAllowed());
    }
}
```
Hinweis: `MKCALENDAR` muss in der `StrictHttpFirewall`-Methodenliste stehen, sonst lehnt die Firewall vor dem Filter ab (500/400). Daher in `CalDavSecurityConfig.httpFirewall` die Liste um `"MKCALENDAR"`, `"MKCOL"` und `"PROPPATCH"` erweitern.

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=CalDavReadTests`
Expected: FAIL (404 u. a., Filter fehlt).

- [ ] **Step 3: Implementieren**

`CalDavSecurityConfig.httpFirewall`: Methodenliste ergänzen um `"MKCALENDAR", "MKCOL", "PROPPATCH"`.

`calendar/caldav/DavXml.java`:
```java
package club.badernet.nestwerk.calendar.caldav;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

final class DavXml {

    static final String DAV = "DAV:";

    private DavXml() {
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String multistatus(List<String> responses, String syncToken) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                .append("<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\"")
                .append(" xmlns:CS=\"http://calendarserver.org/ns/\">");
        responses.forEach(xml::append);
        if (syncToken != null) {
            xml.append("<D:sync-token>").append(escape(syncToken)).append("</D:sync-token>");
        }
        return xml.append("</D:multistatus>").toString();
    }

    static String response(String href, String props) {
        return "<D:response><D:href>" + escape(href) + "</D:href><D:propstat><D:prop>" + props
                + "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>";
    }

    static String notFound(String href) {
        return "<D:response><D:href>" + escape(href)
                + "</D:href><D:status>HTTP/1.1 404 Not Found</D:status></D:response>";
    }

    static String error(String innerElement) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:error xmlns:D=\"DAV:\">" + innerElement
                + "</D:error>";
    }

    /** Parst XML ohne DTD/externe Entities. Wirft IllegalArgumentException bei ungültigem Inhalt. */
    static Document parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("Der Request benötigt einen XML-Body.");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Der XML-Body ist ungültig.", e);
        }
    }

    static String rootName(Document document) {
        Element root = document.getDocumentElement();
        return root.getLocalName() != null ? root.getLocalName() : root.getNodeName();
    }

    static List<String> hrefs(Document document) {
        List<String> hrefs = new ArrayList<>();
        NodeList nodes = document.getElementsByTagNameNS(DAV, "href");
        for (int i = 0; i < nodes.getLength(); i++) {
            hrefs.add(nodes.item(i).getTextContent().trim());
        }
        return hrefs;
    }

    static String syncToken(Document document) {
        NodeList nodes = document.getElementsByTagNameNS(DAV, "sync-token");
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }
}
```

`calendar/caldav/CalDavFilter.java`:
```java
package club.badernet.nestwerk.calendar.caldav;

import club.badernet.nestwerk.calendar.CalendarChangeLog;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.CalendarPermission;
import club.badernet.nestwerk.calendar.ical.ICalWriter;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CalDavFilter extends OncePerRequestFilter {

    static final String ALLOW = "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, REPORT";
    private static final int MAX_BODY = 1_048_576;
    private static final Pattern SYNC_TOKEN = Pattern.compile("^urn:nestwerk:(\\d+)$");
    private static final String CALENDAR_NAME = "family";

    enum Kind { ROOT, PRINCIPAL, HOME, CALENDAR, EVENT }

    record Target(Kind kind, String username, String uid) {

        static Target parse(String path) {
            String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
            String[] s = p.substring(1).split("/");
            if (s.length == 1 && s[0].equals("caldav")) {
                return new Target(Kind.ROOT, null, null);
            }
            if (s.length < 2 || !s[0].equals("caldav")) {
                return null;
            }
            if (s.length == 3 && s[1].equals("principals")) {
                return new Target(Kind.PRINCIPAL, s[2], null);
            }
            if (s.length >= 3 && s[1].equals("calendars")) {
                if (s.length == 3) {
                    return new Target(Kind.HOME, s[2], null);
                }
                if (s.length == 4 && s[3].equals(CALENDAR_NAME)) {
                    return new Target(Kind.CALENDAR, s[2], null);
                }
                if (s.length == 5 && s[3].equals(CALENDAR_NAME) && s[4].endsWith(".ics")
                        && s[4].length() > 4) {
                    return new Target(Kind.EVENT, s[2], s[4].substring(0, s[4].length() - 4));
                }
            }
            return null;
        }
    }

    private final CalendarEventService events;
    private final UserRepository users;
    private final ICalWriter writer;
    private final CalendarChangeLog changeLog;

    public CalDavFilter(CalendarEventService events, UserRepository users, ICalWriter writer,
                        CalendarChangeLog changeLog) {
        this.events = events;
        this.users = users;
        this.writer = writer;
        this.changeLog = changeLog;
    }

    public static String etag(CalendarEvent event) {
        long updated = event.getUpdatedAt() == null ? 0 : event.getUpdatedAt().toEpochMilli();
        return "\"" + event.getSequence() + "-" + updated + "\"";
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !(path.equals("/.well-known/caldav") || path.equals("/caldav") || path.startsWith("/caldav/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws IOException {
        String path = path(req);
        if (path.equals("/.well-known/caldav")) {
            resp.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            resp.setHeader("Location", req.getContextPath() + "/caldav/");
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        Optional<User> user = users.findByUsername(auth.getName());
        if (user.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        Target target = Target.parse(path);
        if (target == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (target.username() != null && !target.username().equals(user.get().getUsername())) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        try {
            dispatch(req, resp, user.get(), target);
        } catch (AccessDeniedException e) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
        } catch (IllegalArgumentException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Routing

    protected void dispatch(HttpServletRequest req, HttpServletResponse resp, User user, Target target)
            throws IOException {
        switch (req.getMethod().toUpperCase(Locale.ROOT)) {
            case "OPTIONS" -> {
                resp.setHeader("DAV", "1, calendar-access");
                resp.setHeader("Allow", ALLOW);
                resp.setStatus(HttpServletResponse.SC_OK);
            }
            case "PROPFIND" -> propfind(req, resp, user, target);
            case "REPORT" -> report(req, resp, user, target);
            case "GET", "HEAD" -> get(req, resp, user, target);
            default -> {
                resp.setHeader("Allow", ALLOW);
                resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
        }
    }

    // --------------------------------------------------------------- PROPFIND

    private void propfind(HttpServletRequest req, HttpServletResponse resp, User user, Target target)
            throws IOException {
        boolean children = !"0".equals(req.getHeader("Depth"));
        List<String> responses = new ArrayList<>();
        switch (target.kind()) {
            case ROOT -> responses.add(DavXml.response(base(req) + "/", rootProps(req, user)));
            case PRINCIPAL -> responses.add(DavXml.response(principalHref(req, user), principalProps(req, user)));
            case HOME -> {
                responses.add(DavXml.response(homeHref(req, user), homeProps(req, user)));
                if (children) {
                    responses.add(DavXml.response(calendarHref(req, user), calendarProps(req, user)));
                }
            }
            case CALENDAR -> {
                responses.add(DavXml.response(calendarHref(req, user), calendarProps(req, user)));
                if (children) {
                    for (CalendarEvent e : events.findAllVisible(user)) {
                        responses.add(DavXml.response(eventHref(req, user, e.getUid()), eventProps(e)));
                    }
                }
            }
            case EVENT -> {
                Optional<CalendarEvent> event = events.findByUidVisible(target.uid(), user);
                if (event.isEmpty()) {
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                responses.add(DavXml.response(eventHref(req, user, target.uid()), eventProps(event.get())));
            }
        }
        sendXml(resp, 207, DavXml.multistatus(responses, null));
    }

    private String rootProps(HttpServletRequest req, User user) {
        return "<D:resourcetype><D:collection/></D:resourcetype><D:displayname>Nestwerk</D:displayname>"
                + currentUserPrincipal(req, user);
    }

    private String principalProps(HttpServletRequest req, User user) {
        return "<D:resourcetype><D:collection/><D:principal/></D:resourcetype>"
                + "<D:displayname>" + DavXml.escape(user.getUsername()) + "</D:displayname>"
                + currentUserPrincipal(req, user)
                + "<C:calendar-home-set><D:href>" + DavXml.escape(homeHref(req, user))
                + "</D:href></C:calendar-home-set>";
    }

    private String homeProps(HttpServletRequest req, User user) {
        return "<D:resourcetype><D:collection/></D:resourcetype><D:displayname>Kalender</D:displayname>"
                + currentUserPrincipal(req, user);
    }

    private String calendarProps(HttpServletRequest req, User user) {
        String token = "urn:nestwerk:" + changeLog.currentToken();
        boolean canWrite = user.permissionNames().contains(CalendarPermission.CALENDAR_EVENT_CREATE.name());
        return "<D:resourcetype><D:collection/><C:calendar/></D:resourcetype>"
                + "<D:displayname>Familienkalender</D:displayname>"
                + "<C:supported-calendar-component-set><C:comp name=\"VEVENT\"/></C:supported-calendar-component-set>"
                + "<CS:getctag>" + token + "</CS:getctag>"
                + "<D:sync-token>" + token + "</D:sync-token>"
                + "<D:supported-report-set>"
                + "<D:supported-report><D:report><C:calendar-multiget/></D:report></D:supported-report>"
                + "<D:supported-report><D:report><C:calendar-query/></D:report></D:supported-report>"
                + "<D:supported-report><D:report><D:sync-collection/></D:report></D:supported-report>"
                + "</D:supported-report-set>"
                + "<D:current-user-privilege-set><D:privilege><D:read/></D:privilege>"
                + (canWrite ? "<D:privilege><D:write/></D:privilege>" : "")
                + "</D:current-user-privilege-set>"
                + currentUserPrincipal(req, user);
    }

    private String eventProps(CalendarEvent event) {
        return "<D:resourcetype/><D:getetag>" + etag(event) + "</D:getetag>"
                + "<D:getcontenttype>text/calendar; charset=utf-8; component=VEVENT</D:getcontenttype>";
    }

    private String currentUserPrincipal(HttpServletRequest req, User user) {
        return "<D:current-user-principal><D:href>" + DavXml.escape(principalHref(req, user))
                + "</D:href></D:current-user-principal>";
    }

    // ----------------------------------------------------------------- REPORT

    private void report(HttpServletRequest req, HttpServletResponse resp, User user, Target target)
            throws IOException {
        if (target.kind() != Kind.CALENDAR) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Document doc = DavXml.parse(readBody(req));
        List<String> responses = new ArrayList<>();
        switch (DavXml.rootName(doc)) {
            case "calendar-multiget" -> {
                for (String href : DavXml.hrefs(doc)) {
                    Optional<String> uid = uidFromHref(href);
                    Optional<CalendarEvent> event = uid.flatMap(u -> events.findByUidVisible(u, user));
                    if (event.isPresent()) {
                        responses.add(eventWithData(req, user, event.get()));
                    } else {
                        responses.add(DavXml.notFound(href));
                    }
                }
                sendXml(resp, 207, DavXml.multistatus(responses, null));
            }
            case "calendar-query" -> {
                for (CalendarEvent e : events.findAllVisible(user)) {
                    responses.add(eventWithData(req, user, e));
                }
                sendXml(resp, 207, DavXml.multistatus(responses, null));
            }
            case "sync-collection" -> syncCollection(req, resp, user, doc);
            default -> sendXml(resp, 403, DavXml.error("<D:supported-report/>"));
        }
    }

    private void syncCollection(HttpServletRequest req, HttpServletResponse resp, User user, Document doc)
            throws IOException {
        String tokenText = DavXml.syncToken(doc);
        long current = changeLog.currentToken();
        long since = 0;
        if (tokenText != null && !tokenText.isBlank()) {
            Matcher m = SYNC_TOKEN.matcher(tokenText);
            if (!m.matches() || Long.parseLong(m.group(1)) > current) {
                sendXml(resp, 403, DavXml.error("<D:valid-sync-token/>"));
                return;
            }
            since = Long.parseLong(m.group(1));
        }
        List<String> responses = new ArrayList<>();
        if (since == 0) {
            for (CalendarEvent e : events.findAllVisible(user)) {
                responses.add(DavXml.response(eventHref(req, user, e.getUid()), eventProps(e)));
            }
        } else {
            for (String uid : changeLog.uidsChangedSince(since)) {
                Optional<CalendarEvent> event = events.findByUidVisible(uid, user);
                if (event.isPresent()) {
                    responses.add(DavXml.response(eventHref(req, user, uid), eventProps(event.get())));
                } else {
                    responses.add(DavXml.notFound(eventHref(req, user, uid)));
                }
            }
        }
        sendXml(resp, 207, DavXml.multistatus(responses, "urn:nestwerk:" + current));
    }

    private String eventWithData(HttpServletRequest req, User user, CalendarEvent event) {
        String ics = writer.write("Nestwerk Familienkalender", List.of(event));
        return DavXml.response(eventHref(req, user, event.getUid()),
                "<D:getetag>" + etag(event) + "</D:getetag>"
                        + "<C:calendar-data>" + DavXml.escape(ics) + "</C:calendar-data>");
    }

    private Optional<String> uidFromHref(String href) {
        String decoded = UriUtils.decode(href, StandardCharsets.UTF_8);
        int slash = decoded.lastIndexOf('/');
        String name = slash >= 0 ? decoded.substring(slash + 1) : decoded;
        if (!name.endsWith(".ics") || name.length() <= 4) {
            return Optional.empty();
        }
        return Optional.of(name.substring(0, name.length() - 4));
    }

    // -------------------------------------------------------------------- GET

    private void get(HttpServletRequest req, HttpServletResponse resp, User user, Target target)
            throws IOException {
        if (target.kind() != Kind.EVENT) {
            resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        Optional<CalendarEvent> event = events.findByUidVisible(target.uid(), user);
        if (event.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        byte[] body = writer.write("Nestwerk Familienkalender", List.of(event.get()))
                .getBytes(StandardCharsets.UTF_8);
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setHeader("ETag", etag(event.get()));
        resp.setContentType("text/calendar;charset=UTF-8");
        resp.setContentLength(body.length);
        if (!"HEAD".equalsIgnoreCase(req.getMethod())) {
            resp.getOutputStream().write(body);
        }
    }

    // ---------------------------------------------------------------- Helfer

    protected String readBody(HttpServletRequest req) throws IOException {
        byte[] raw = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            throw new IllegalArgumentException("Der Request ist zu groß.");
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    protected void sendXml(HttpServletResponse resp, int status, String xml) throws IOException {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setContentType("application/xml;charset=UTF-8");
        resp.setContentLength(body.length);
        resp.getOutputStream().write(body);
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length()) : uri;
        return UriUtils.decode(path, StandardCharsets.UTF_8);
    }

    protected static String base(HttpServletRequest req) {
        return req.getContextPath() + "/caldav";
    }

    protected static String enc(String segment) {
        return UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8);
    }

    protected static String principalHref(HttpServletRequest req, User user) {
        return base(req) + "/principals/" + enc(user.getUsername()) + "/";
    }

    protected static String homeHref(HttpServletRequest req, User user) {
        return base(req) + "/calendars/" + enc(user.getUsername()) + "/";
    }

    protected static String calendarHref(HttpServletRequest req, User user) {
        return homeHref(req, user) + CALENDAR_NAME + "/";
    }

    protected static String eventHref(HttpServletRequest req, User user, String uid) {
        return calendarHref(req, user) + enc(uid) + ".ics";
    }
}
```
Anmerkung: `CalDavFilter` ist bewusst nicht als `@Component` annotiert (sonst registriert Spring Boot es zusätzlich automatisch mit `/*` und ungünstiger Reihenfolge).

`config/CalDavConfig.java`:
```java
package club.badernet.nestwerk.config;

import club.badernet.nestwerk.calendar.CalendarChangeLog;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.caldav.CalDavFilter;
import club.badernet.nestwerk.calendar.ical.ICalImporter;
import club.badernet.nestwerk.calendar.ical.ICalWriter;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class CalDavConfig {

    @Bean
    FilterRegistrationBean<CalDavFilter> calDavFilter(CalendarEventService events, UserRepository users,
                                                     ICalWriter writer, ICalImporter importer,
                                                     CalendarChangeLog changeLog) {
        FilterRegistrationBean<CalDavFilter> registration =
                new FilterRegistrationBean<>(new CalDavFilter(events, users, writer, changeLog));
        registration.addUrlPatterns("/caldav", "/caldav/*", "/.well-known/caldav");
        // Muss hinter der Spring-Security-Filterkette (Order -100) laufen.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return registration;
    }
}
```
(`ICalImporter` wird erst in Task 5 an den Filter übergeben; in Task 4 den Parameter noch weglassen, in Task 5 ergänzen.)

Aus dem Test: `dav("MKCALENDAR", …)` → `dispatch` liefert 405.

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=CalDavReadTests+CalDavSecurityTest`
Expected: PASS (19 Tests). Falls `FilterRegistrationBean` in Boot 4 in einem anderen Paket liegt (`org.springframework.boot.web.servlet` → ggf. `org.springframework.boot.web.servlet.filter`), den Import per Compilerfehler korrigieren.
Falls MockMvc den Filter nicht anwendet (Antwort 404 vom DispatcherServlet): prüfen, ob die URL-Patterns des `FilterRegistrationBean` greifen; als Fallback den Filter mit `@Bean Filter` + `@Order` registrieren und `shouldNotFilter` die Pfadprüfung übernehmen lassen (ist bereits so umgesetzt).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: CalDAV lesend (PROPFIND, REPORT, GET, sync-collection)"
```

---

### Task 5: CalDAV schreibend (PUT, DELETE)

**Files:**
- Modify: `calendar/caldav/CalDavFilter.java`, `config/CalDavConfig.java`
- Test: `src/test/java/club/badernet/nestwerk/web/CalDavWriteTests.java`

**Interfaces:**
- Consumes: `ICalImporter.parse`, `CalendarEventService.saveImported/delete/findByUidVisible`.
- Produces: `PUT` (201 neu / 204 geändert, `ETag`-Header, `If-Match`/`If-None-Match`-Auswertung), `DELETE` (204, `If-Match`). Konstruktor `CalDavFilter(events, users, writer, importer, changeLog)`.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarChangeLog;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.calendar.caldav.AppPasswordService;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.reminders.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class CalDavWriteTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired AppPasswordService passwords;
    @Autowired CalendarEventService events;
    @Autowired CalendarChangeLog changes;
    @Autowired club.badernet.nestwerk.member.FamilyMemberService memberService;

    User a;
    User b;
    String passwordA;
    String passwordB;

    @BeforeEach
    void setUp() {
        Role role = roleService.create("DavW", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN", "CALENDAR_PRIVATE_CREATE"));
        a = userService.create("davw-a", "login-passwort", Set.of(role.getId()));
        b = userService.create("davw-b", "login-passwort", Set.of(role.getId()));
        passwordA = passwords.create(a, "Handy");
        passwordB = passwords.create(b, "Handy");
    }

    private static String ics(String uid, String summary, String... extra) {
        StringBuilder sb = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//DE\r\n")
                .append("BEGIN:VEVENT\r\nUID:").append(uid).append("\r\n")
                .append("DTSTAMP:20261001T000000Z\r\nSUMMARY:").append(summary).append("\r\n")
                .append("DTSTART;TZID=Europe/Berlin:20261012T100000\r\n")
                .append("DTEND;TZID=Europe/Berlin:20261012T110000\r\n");
        for (String line : extra) {
            sb.append(line).append("\r\n");
        }
        return sb.append("END:VEVENT\r\nEND:VCALENDAR\r\n").toString();
    }

    private ResultActions put(User user, String password, String uid, String body, String... headers)
            throws Exception {
        var builder = request(HttpMethod.PUT, "/caldav/calendars/" + user.getUsername() + "/family/" + uid + ".ics")
                .with(httpBasic(user.getUsername(), password))
                .contentType("text/calendar; charset=utf-8").content(body);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions send(HttpMethod method, User user, String password, String uid, String... headers)
            throws Exception {
        var builder = request(method, "/caldav/calendars/" + user.getUsername() + "/family/" + uid + ".ics")
                .with(httpBasic(user.getUsername(), password));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return mockMvc.perform(builder);
    }

    @Test
    void putCreatesEventAndReturnsEtag() throws Exception {
        put(a, passwordA, "neu-1", ics("neu-1", "Vom Handy"), "If-None-Match", "*")
                .andExpect(status().isCreated())
                .andExpect(header().exists("ETag"));

        send(HttpMethod.GET, a, passwordA, "neu-1")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SUMMARY:Vom Handy")));
    }

    @Test
    void putUpdatesExistingEventWith204() throws Exception {
        MvcResult created = put(a, passwordA, "upd-1", ics("upd-1", "Alt")).andReturn();
        String etag = created.getResponse().getHeader("ETag");

        put(a, passwordA, "upd-1", ics("upd-1", "Neu"), "If-Match", etag)
                .andExpect(status().isNoContent())
                .andExpect(header().exists("ETag"));

        send(HttpMethod.GET, a, passwordA, "upd-1")
                .andExpect(content().string(containsString("SUMMARY:Neu")));
    }

    @Test
    void ifNoneMatchStarOnExistingEventFails() throws Exception {
        put(a, passwordA, "dup-1", ics("dup-1", "Erster"));

        put(a, passwordA, "dup-1", ics("dup-1", "Zweiter"), "If-None-Match", "*")
                .andExpect(status().isPreconditionFailed());

        send(HttpMethod.GET, a, passwordA, "dup-1")
                .andExpect(content().string(containsString("SUMMARY:Erster")));
    }

    @Test
    void staleIfMatchFailsWithoutOverwriting() throws Exception {
        put(a, passwordA, "stale-1", ics("stale-1", "Version 1"));

        put(a, passwordA, "stale-1", ics("stale-1", "Version X"), "If-Match", "\"0-1\"")
                .andExpect(status().isPreconditionFailed());

        send(HttpMethod.GET, a, passwordA, "stale-1")
                .andExpect(content().string(containsString("SUMMARY:Version 1")));
    }

    @Test
    void ifMatchOnMissingEventFails() throws Exception {
        put(a, passwordA, "missing-1", ics("missing-1", "Nope"), "If-Match", "\"1-1\"")
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void uidMustMatchResourceName() throws Exception {
        put(a, passwordA, "name-1", ics("andere-uid", "Falsch")).andExpect(status().isForbidden());
    }

    @Test
    void garbageBodyIsBadRequest() throws Exception {
        put(a, passwordA, "garbage-1", "das ist kein kalender").andExpect(status().isBadRequest());
    }

    @Test
    void oversizedBodyIsRejected() throws Exception {
        put(a, passwordA, "big-1", ics("big-1", "x", "DESCRIPTION:" + "a".repeat(1_100_000)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recurrenceWithExdateSurvivesRoundTrip() throws Exception {
        put(a, passwordA, "rt-1", ics("rt-1", "Training",
                "RRULE:FREQ=WEEKLY", "EXDATE;TZID=Europe/Berlin:20261019T100000"));

        send(HttpMethod.GET, a, passwordA, "rt-1")
                .andExpect(content().string(containsString("RRULE:FREQ=WEEKLY")))
                .andExpect(content().string(containsString("EXDATE;TZID=Europe/Berlin:20261019T100000")));
    }

    @Test
    void umlautsAndEscapedCharactersSurviveRoundTrip() throws Exception {
        put(a, passwordA, "uml-1", ics("uml-1", "Kino\\, Pizza\\; Eis für Kölner", "LOCATION:Küche"));

        send(HttpMethod.GET, a, passwordA, "uml-1")
                .andExpect(content().string(containsString("SUMMARY:Kino\\, Pizza\\; Eis für Kölner")))
                .andExpect(content().string(containsString("LOCATION:Küche")));
    }

    @Test
    void deleteRemovesEventAndShowsUpAsDeletedInSync() throws Exception {
        put(a, passwordA, "del-1", ics("del-1", "Weg"));
        long token = changes.currentToken();

        send(HttpMethod.DELETE, a, passwordA, "del-1").andExpect(status().isNoContent());

        send(HttpMethod.GET, a, passwordA, "del-1").andExpect(status().isNotFound());
        assertThat(changes.uidsChangedSince(token)).containsExactly("del-1");
    }

    @Test
    void deleteWithStaleIfMatchFails() throws Exception {
        put(a, passwordA, "del-2", ics("del-2", "Bleibt"));

        send(HttpMethod.DELETE, a, passwordA, "del-2", "If-Match", "\"0-1\"")
                .andExpect(status().isPreconditionFailed());

        send(HttpMethod.GET, a, passwordA, "del-2").andExpect(status().isOk());
    }

    @Test
    void deleteOfUnknownEventIsNotFound() throws Exception {
        send(HttpMethod.DELETE, a, passwordA, "gibt-es-nicht").andExpect(status().isNotFound());
    }

    @Test
    void otherUserCannotWriteIntoForeignHome() throws Exception {
        var builder = request(HttpMethod.PUT, "/caldav/calendars/davw-a/family/hack-1.ics")
                .with(httpBasic("davw-b", passwordB)).content(ics("hack-1", "Hack"));

        mockMvc.perform(builder).andExpect(status().isForbidden());
    }

    @Test
    void putOnForeignPrivateUidIsRejectedWithoutServerError() throws Exception {
        CalendarEvent secret = events.create(new EventInput("Geheim", null, null, LocalDate.of(2026, 10, 13),
                LocalTime.of(10, 0), LocalDate.of(2026, 10, 13), LocalTime.of(11, 0), false, null, true,
                Set.of()), a);

        put(b, passwordB, secret.getUid(), ics(secret.getUid(), "Übernommen"))
                .andExpect(status().isForbidden());

        send(HttpMethod.GET, a, passwordA, secret.getUid())
                .andExpect(content().string(containsString("SUMMARY:Geheim")));
    }

    @Test
    void userWithoutCreatePermissionCannotPut() throws Exception {
        Role readOnly = roleService.create("Nur lesen DAV", null, Set.of("CALENDAR_VIEW"));
        User viewer = userService.create("davw-viewer", "login-passwort", Set.of(readOnly.getId()));
        String pw = passwords.create(viewer, "Handy");

        put(viewer, pw, "ro-1", ics("ro-1", "Nicht erlaubt")).andExpect(status().isForbidden());
    }

    @Test
    void membersAreKeptWhenPhoneUpdatesEvent() throws Exception {
        var member = memberService.create("Lena", "#112233", null);
        CalendarEvent created = events.create(new EventInput("Mit Mitglied", null, null,
                LocalDate.of(2026, 10, 12), LocalTime.of(10, 0), LocalDate.of(2026, 10, 12),
                LocalTime.of(11, 0), false, null, false, Set.of(member.getId())), a);

        put(a, passwordA, created.getUid(), ics(created.getUid(), "Geändert")).andExpect(status().isNoContent());

        CalendarEvent reloaded = events.findByUidVisible(created.getUid(), a).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Geändert");
        assertThat(reloaded.getMemberIds()).containsExactly(member.getId());
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=CalDavWriteTests`
Expected: FAIL (PUT/DELETE liefern 405).

- [ ] **Step 3: Implementieren**

`CalDavFilter` – Konstruktor um `ICalImporter importer` erweitern (Feld + Import `club.badernet.nestwerk.calendar.ImportedEvent`, `club.badernet.nestwerk.calendar.ical.ICalImporter`); `dispatch` ergänzen:
```java
            case "PUT" -> put(req, resp, user, target);
            case "DELETE" -> delete(req, resp, user, target);
```
und neue Methoden:
```java
    private void put(HttpServletRequest req, HttpServletResponse resp, User user, Target target)
            throws IOException {
        if (target.kind() != Kind.EVENT) {
            resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        ImportedEvent imported = importer.parse(readBody(req));
        if (!imported.uid().equals(target.uid())) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Die UID muss dem Ressourcennamen entsprechen.");
            return;
        }
        Optional<CalendarEvent> existing = events.findByUidVisible(target.uid(), user);
        String ifNoneMatch = req.getHeader("If-None-Match");
        String ifMatch = req.getHeader("If-Match");
        if ("*".equals(ifNoneMatch) && existing.isPresent()) {
            resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
            return;
        }
        if (ifMatch != null && !matchesEtag(ifMatch, existing)) {
            resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
            return;
        }
        CalendarEventService.SaveResult result = events.saveImported(imported, user);
        resp.setHeader("ETag", etag(result.event()));
        resp.setStatus(result.created() ? HttpServletResponse.SC_CREATED : HttpServletResponse.SC_NO_CONTENT);
    }

    private void delete(HttpServletRequest req, HttpServletResponse resp, User user, Target target)
            throws IOException {
        if (target.kind() != Kind.EVENT) {
            resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        Optional<CalendarEvent> existing = events.findByUidVisible(target.uid(), user);
        if (existing.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String ifMatch = req.getHeader("If-Match");
        if (ifMatch != null && !matchesEtag(ifMatch, existing)) {
            resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
            return;
        }
        events.delete(existing.get().getId(), user);
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private static boolean matchesEtag(String header, Optional<CalendarEvent> existing) {
        if (existing.isEmpty()) {
            return false;
        }
        if (header.trim().equals("*")) {
            return true;
        }
        String current = etag(existing.get());
        for (String candidate : header.split(",")) {
            String c = candidate.trim();
            if (c.startsWith("W/")) {
                c = c.substring(2);
            }
            if (c.equals(current)) {
                return true;
            }
        }
        return false;
    }
```
`CalDavConfig`: `new CalDavFilter(events, users, writer, importer, changeLog)`.

`readBody` wirft bei Überschreitung `IllegalArgumentException`, was `doFilterInternal` zu 400 macht (der Test `oversizedBodyIsRejected` erwartet 400).

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=CalDavWriteTests+CalDavReadTests+CalendarSyncServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: CalDAV schreibend (PUT, DELETE) mit ETag-Vorbedingungen"
```

---

### Task 6: Dokumentation und Abnahme mit echtem Client

**Files:**
- Modify: `README.md`

- [ ] **Step 1: README ergänzen**

Abschnitt am Ende von `README.md`:
```markdown
## Kalender abonnieren und mit Android synchronisieren

**Nur lesen (iCal-Feed):** In Nestwerk unter *Kalender → Abo* einen Link erzeugen und in der Kalender-App
als Abo hinzufügen (Android z. B. mit ICSx5).

**Lesen und Schreiben (CalDAV, z. B. mit DAVx5):**

1. In Nestwerk unter *Kalender → Sync* ein App-Passwort erzeugen (nur einmal sichtbar).
2. In DAVx5 ein Konto mit Basis-URL `https://<dein-server>/caldav/`, Benutzername = Nestwerk-Benutzername
   und dem App-Passwort anlegen.
3. Kalender „Familienkalender“ zur Synchronisation auswählen.

Hinweise: Der Reverse Proxy muss die HTTP-Methoden `PROPFIND`, `REPORT` und `OPTIONS` sowie die Header
`Depth`, `If-Match`, `If-None-Match` unverändert durchreichen und `/.well-known/caldav` weiterleiten.
Der Ressourcenname ist `<UID>.ics`; die Zuordnung von Familienmitgliedern ist nur in Nestwerk möglich.
```

- [ ] **Step 2: Gesamtlauf**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, alle Tests grün.

- [ ] **Step 3: Abnahme mit echtem Client (manuell)**

Mit `NESTWERK_ADMIN_PASSWORD=changeme ./mvnw spring-boot:run` oder dem Docker-Setup starten und von einem Testgerät aus (HTTPS über den Reverse Proxy) prüfen:

1. DAVx5: Konto mit App-Passwort anlegen → Kalender „Familienkalender“ erscheint, vorhandene Termine werden geladen.
2. Termin auf dem Handy anlegen → erscheint nach Sync in der Nestwerk-Weboberfläche (Monatsansicht).
3. Termin in Nestwerk ändern → nach Sync auf dem Handy geändert.
4. Wiederkehrenden Termin anlegen, ein Vorkommen auf dem Handy löschen → in Nestwerk fehlt genau dieses Vorkommen.
5. Termin auf dem Handy löschen → in Nestwerk gelöscht; Löschen in Nestwerk → nach Sync vom Handy entfernt.
6. Privaten Termin mit einem zweiten Benutzer ansehen → nicht sichtbar.
7. App-Passwort widerrufen → nächster Sync schlägt mit 401 fehl.

Abweichungen (z. B. Client verlangt weitere Properties wie `getcontentlength`, `owner` oder nutzt andere Ressourcennamen) mit den Zugriffslogs des Reverse Proxys abgleichen und gezielt im `CalDavFilter` ergänzen; jede Korrektur bekommt einen Test in `CalDavReadTests`/`CalDavWriteTests`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "docs: Kalender-Abo und CalDAV-Einrichtung beschreiben"
```
