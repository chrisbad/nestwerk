# Phase 1: Rollen/Rechte, Familienmitglieder, Layout – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Frei definierbare Rollen mit Einzelrechten, Familienmitglieder als Zuweisungsziel und ein gemeinsames Layout mit rechteabhängiger Navigation.

**Architecture:** Rechte sind Enums im Code (Interface `Permission`, je Modul ein Enum, gesammelt im `PermissionCatalog`). Die Zuordnung Rolle → Rechte liegt in der DB (`role_permissions`). Die Administrator-Rolle trägt das Flag `all_permissions` und hat implizit alle Rechte. Spring-Security-Authorities des Benutzers = Namen der effektiven Rechte (kein `ROLE_`-Präfix).

**Tech Stack:** Spring Boot 4.1.1, Java 25, Spring Data JPA, Flyway, Thymeleaf + thymeleaf-extras-springsecurity6, PostgreSQL, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-13-basis-rollen-mitglieder-design.md`
(Folgephasen: `…phase-2-kalender-kern.md`, `…phase-3-erinnerungen-feed.md`, `…phase-4-caldav.md` im selben Ordner.)

## Global Constraints

- Basis-Paket `club.badernet.nestwerk`; Pakete feature-basiert: `user`, `role`, `member`, `security`, `web`.
- Alle UI-Texte, Fehlermeldungen und Flash-Meldungen auf Deutsch, mit korrekten Umlauten.
- Rechte-Namen sind die Enum-Namen (`USER_MANAGE`, `ACCESS_ROLE_MANAGE`, `MEMBER_MANAGE`). Hinweis: `ROLES_MANAGE`/`ROLE_*` wird bewusst vermieden, weil Spring Security `ROLE_` als Rollen-Präfix behandelt; die Spec nannte `ROLE_MANAGE`, hier heißt das Recht `ACCESS_ROLE_MANAGE`.
- Benutzer werden nicht gelöscht, sondern deaktiviert. Der Letzter-Admin-Schutz der Spec gilt daher für Deaktivieren und Rollenentzug (eine Löschfunktion existiert nicht).
- Flyway-Migrationen sind unveränderlich; neue Änderungen nur als neue Version (`V2__…`).
- `spring.jpa.hibernate.ddl-auto=validate` bleibt: Entities müssen exakt zum Schema passen.
- Tests: `@Import(TestcontainersConfiguration.class)`, `@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")`, DB-ändernde Tests mit `@Transactional` (Rollback).
- Testlauf: `./mvnw -q test -Dtest=<Klasse>` bzw. `./mvnw verify` (Docker muss laufen).
- Commit-Nachrichten enden mit der Zeile `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Review Focus

- Benutzer ohne jede Rolle: Login funktioniert, sieht nur die Startseite, `/admin/*` liefert 403 (kein Absturz, keine Weiterleitungsschleife).
- Rollenname in anderer Groß-/Kleinschreibung („kind“ vs. „Kind“) gilt als Duplikat.
- In der DB gespeicherter Rechte-Name, den es im Code nicht mehr gibt, wird ignoriert (kein Login-Fehler, nicht in Authorities).
- Entzug einer von zwei Rollen lässt die Rechte der anderen Rolle bestehen; Entzug der Admin-Rolle beim letzten aktiven Administrator wird abgelehnt.
- Mitglied mit einem Benutzerkonto verknüpfen, das schon einem anderen Mitglied gehört: verständliche Fehlermeldung statt 500.
- Passwort-Reset und Deaktivieren für den eigenen Account/den letzten Admin.

**Vorbedingung:** Das Repository hat noch keinen Commit. Vor Task 1: `git status` prüfen und den vorhandenen Stand (inklusive `docs/`) als Baseline committen (`git add -A && git commit -m "chore: baseline"` + Trailer), damit alle folgenden Diffs sauber sind.

---

## Dateiübersicht

| Datei | Verantwortung |
|---|---|
| `security/Permission.java`, `AdminPermission.java`, `PermissionCatalog.java` | Rechte-Modell und Katalog |
| `role/Role.java`, `RoleRepository.java`, `RoleService.java` | Rollen-Entity und Verwaltung |
| `user/User.java`, `UserRepository.java`, `UserService.java`, `AdminInitializer.java` | Benutzer mit Rollen, UserDetails, Letzter-Admin-Schutz |
| `member/FamilyMember.java`, `FamilyMemberRepository.java`, `FamilyMemberService.java` | Familienmitglieder |
| `web/AdminUserController`, `AdminRoleController`, `AdminMemberController` | Admin-Oberflächen |
| `templates/fragments/layout.html`, `static/css/nestwerk.css` | Layout, Navigation |
| `db/migration/V2__roles_and_members.sql` | Schema und Datenmigration |

---

### Task 1: Rechte-Modell und Katalog

**Files:**
- Create: `src/main/java/club/badernet/nestwerk/security/Permission.java`
- Create: `src/main/java/club/badernet/nestwerk/security/AdminPermission.java`
- Create: `src/main/java/club/badernet/nestwerk/security/PermissionCatalog.java`
- Test: `src/test/java/club/badernet/nestwerk/security/PermissionCatalogTest.java`

**Interfaces:**
- Produces: `interface Permission { String name(); String label(); String module(); }`; `PermissionCatalog.all(): List<Permission>`, `names(): Set<String>`, `find(String): Optional<Permission>`, `byModule(): Map<String, List<Permission>>` (Reihenfolge stabil). Spätere Module hängen ihre Enums in `PermissionCatalog.collect()` an.

- [ ] **Step 1: Failing test schreiben**

```java
package club.badernet.nestwerk.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionCatalogTest {

    @Test
    void namesAreUnique() {
        assertThat(PermissionCatalog.all()).extracting(Permission::name).doesNotHaveDuplicates();
    }

    @Test
    void findsPermissionByName() {
        assertThat(PermissionCatalog.find("USER_MANAGE")).contains(AdminPermission.USER_MANAGE);
    }

    @Test
    void unknownNameIsEmpty() {
        assertThat(PermissionCatalog.find("GIBT_ES_NICHT")).isEmpty();
    }

    @Test
    void groupsByModule() {
        assertThat(PermissionCatalog.byModule()).containsKey("Verwaltung");
        assertThat(PermissionCatalog.byModule().get("Verwaltung"))
                .contains(AdminPermission.USER_MANAGE, AdminPermission.ACCESS_ROLE_MANAGE,
                        AdminPermission.MEMBER_MANAGE);
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=PermissionCatalogTest`
Expected: Kompilierfehler (`Permission`/`PermissionCatalog` fehlen).

- [ ] **Step 3: Implementieren**

`Permission.java`:
```java
package club.badernet.nestwerk.security;

/** Ein einzelnes Recht. Jedes Modul liefert ein Enum, das dieses Interface implementiert. */
public interface Permission {

    /** Eindeutiger Name; wird in der DB und als Spring-Security-Authority verwendet. */
    String name();

    /** Anzeigename für die Rollenverwaltung. */
    String label();

    /** Gruppenname für die Rollenverwaltung. */
    String module();
}
```

`AdminPermission.java`:
```java
package club.badernet.nestwerk.security;

public enum AdminPermission implements Permission {
    USER_MANAGE("Benutzer verwalten"),
    ACCESS_ROLE_MANAGE("Rollen und Rechte verwalten"),
    MEMBER_MANAGE("Familienmitglieder verwalten");

    private final String label;

    AdminPermission(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String module() {
        return "Verwaltung";
    }
}
```

`PermissionCatalog.java`:
```java
package club.badernet.nestwerk.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class PermissionCatalog {

    private static final List<Permission> ALL = collect();

    private PermissionCatalog() {
    }

    private static List<Permission> collect() {
        List<Permission> all = new ArrayList<>();
        all.addAll(Arrays.asList(AdminPermission.values()));
        return List.copyOf(all);
    }

    public static List<Permission> all() {
        return ALL;
    }

    public static Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        ALL.forEach(p -> names.add(p.name()));
        return names;
    }

    public static Optional<Permission> find(String name) {
        return ALL.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public static Map<String, List<Permission>> byModule() {
        Map<String, List<Permission>> grouped = new LinkedHashMap<>();
        for (Permission p : ALL) {
            grouped.computeIfAbsent(p.module(), k -> new ArrayList<>()).add(p);
        }
        return grouped;
    }
}
```

- [ ] **Step 4: Test ausführen, Erfolg prüfen**

Run: `./mvnw -q test -Dtest=PermissionCatalogTest`
Expected: PASS (4 Tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/club/badernet/nestwerk/security src/test/java/club/badernet/nestwerk/security
git commit -m "feat: Rechte-Katalog mit Admin-Rechten"
```

---

### Task 2: Datenmodell, Benutzerdienst und Authorities

Diese Task ersetzt das Enum `user.Role` durch die Entity `role.Role`, migriert die Daten und stellt Benutzerdienst, Initializer, Controller und Security auf Rechte um, damit die App durchgängig lauffähig bleibt.

**Files:**
- Create: `src/main/resources/db/migration/V2__roles_and_members.sql`
- Delete: `src/main/java/club/badernet/nestwerk/user/Role.java`
- Create: `src/main/java/club/badernet/nestwerk/role/Role.java`, `role/RoleRepository.java`
- Modify: `user/User.java`, `user/UserRepository.java`, `user/UserService.java`, `user/AdminInitializer.java`
- Modify: `config/SecurityConfig.java`, `web/AdminUserController.java`, `templates/admin/users.html`, `templates/index.html`
- Modify: `src/test/java/club/badernet/nestwerk/user/AdminInitializerTest.java`, `src/test/java/club/badernet/nestwerk/web/SecurityAccessTests.java`
- Test: `src/test/java/club/badernet/nestwerk/user/UserServiceTest.java`, `src/test/java/club/badernet/nestwerk/user/RoleMigrationTest.java`

**Interfaces:**
- Produces:
  - `Role`: `getId()`, `getName()`, `getDescription()`, `isSystemRole()`, `isAllPermissions()`, `getPermissions(): Set<String>` (nur Namen aus dem Katalog), `effectivePermissionNames(): Set<String>`; Konstruktor `Role(String name, String description, Set<String> permissions)`; Mutatoren `rename(String)`, `setDescription(String)`, `setPermissions(Set<String>)`.
  - `RoleRepository`: `findByAllPermissionsTrue(): Optional<Role>`, `findAllByOrderByNameAsc(): List<Role>`, `existsByNameIgnoreCase(String)`, `findByNameIgnoreCase(String)`.
  - `User`: `getRoles(): Set<Role>`, `setRoles(Collection<Role>)`, `setEnabled(boolean)`, `setPasswordHash(String)`, `isAdministrator()`, `permissionNames(): Set<String>`, `hasRoleId(Long)`.
  - `UserRepository`: `countActiveAdministrators(): long`.
  - `UserService`: `create(String, String, Set<Long>)`, `createAdministrator(String, String)`, `changeRoles(Long, Set<Long>)`, `setEnabled(Long, boolean)`, `resetPassword(Long, String)`, `findAll()`, `hasUsers()`, `MIN_PASSWORD_LENGTH = 8`.

- [ ] **Step 1: Migration schreiben**

`V2__roles_and_members.sql`:
```sql
CREATE TABLE roles (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100)             NOT NULL,
    description     VARCHAR(255),
    system_role     BOOLEAN                  NOT NULL DEFAULT FALSE,
    all_permissions BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX roles_name_lower_idx ON roles (lower(name));

CREATE TABLE role_permissions (
    role_id    BIGINT       NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission VARCHAR(100) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE family_members (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100)             NOT NULL,
    color      VARCHAR(7)               NOT NULL,
    user_id    BIGINT UNIQUE REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

INSERT INTO roles (name, description, system_role, all_permissions)
VALUES ('Administrator', 'Vollzugriff auf alle Funktionen', TRUE, TRUE),
       ('Benutzer', 'Standardrolle für Familienmitglieder', TRUE, FALSE);

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
         JOIN roles r ON (u.role = 'ADMIN' AND r.name = 'Administrator')
    OR (u.role = 'USER' AND r.name = 'Benutzer');

ALTER TABLE users DROP COLUMN role;
```

- [ ] **Step 2: Failing Tests schreiben**

`RoleMigrationTest.java`:
```java
package club.badernet.nestwerk.user;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
class RoleMigrationTest {

    @Autowired
    DataSource dataSource;

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("migtest")
                .defaultSchema("migtest")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void migratesLegacyRoleColumnToUserRoles() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS migtest CASCADE");
        flyway("1").migrate();
        jdbc.update("INSERT INTO migtest.users (username, password_hash, role) "
                + "VALUES ('alt-admin', 'x', 'ADMIN'), ('alt-user', 'x', 'USER')");

        flyway("2").migrate();

        List<String> pairs = jdbc.queryForList(
                "SELECT u.username || ':' || r.name FROM migtest.user_roles ur "
                        + "JOIN migtest.users u ON u.id = ur.user_id "
                        + "JOIN migtest.roles r ON r.id = ur.role_id ORDER BY 1", String.class);
        assertThat(pairs).containsExactly("alt-admin:Administrator", "alt-user:Benutzer");
        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'migtest' AND table_name = 'users'", String.class))
                .doesNotContain("role");
        jdbc.execute("DROP SCHEMA migtest CASCADE");
    }
}
```

`UserServiceTest.java`:
```java
package club.badernet.nestwerk.user;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.security.AdminPermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class UserServiceTest {

    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JdbcTemplate jdbc;

    private Role customRole(String name, String... permissions) {
        return roles.save(new Role(name, null, Set.of(permissions)));
    }

    @Test
    void authoritiesAreUnionOfAllRoles() {
        Role a = customRole("Nur Benutzer", AdminPermission.USER_MANAGE.name());
        Role b = customRole("Nur Mitglieder", AdminPermission.MEMBER_MANAGE.name());
        userService.create("zwei-rollen", "passwort1", Set.of(a.getId(), b.getId()));

        UserDetails details = userService.loadUserByUsername("zwei-rollen");

        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("USER_MANAGE", "MEMBER_MANAGE");
    }

    @Test
    void removingOneRoleKeepsPermissionsOfTheOther() {
        Role a = customRole("Rolle A", AdminPermission.USER_MANAGE.name());
        Role b = customRole("Rolle B", AdminPermission.MEMBER_MANAGE.name());
        User user = userService.create("behalten", "passwort1", Set.of(a.getId(), b.getId()));

        userService.changeRoles(user.getId(), Set.of(b.getId()));

        assertThat(userService.loadUserByUsername("behalten").getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("MEMBER_MANAGE");
    }

    @Test
    void userWithoutRolesHasNoAuthorities() {
        userService.create("ohne-rolle", "passwort1", Set.of());

        assertThat(userService.loadUserByUsername("ohne-rolle").getAuthorities()).isEmpty();
    }

    @Test
    void administratorHasAllCatalogPermissions() {
        userService.createAdministrator("chef", "passwort1");

        assertThat(userService.loadUserByUsername("chef").getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("USER_MANAGE", "ACCESS_ROLE_MANAGE", "MEMBER_MANAGE");
    }

    @Test
    void unknownPermissionNamesInDatabaseAreIgnored() {
        Role role = customRole("Alt", AdminPermission.USER_MANAGE.name());
        jdbc.update("INSERT INTO role_permissions (role_id, permission) VALUES (?, 'ENTFERNTES_RECHT')",
                role.getId());
        userService.create("altlast", "passwort1", Set.of(role.getId()));
        users.flush();

        assertThat(userService.loadUserByUsername("altlast").getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("USER_MANAGE");
    }

    @Test
    void lastActiveAdministratorCannotBeDisabledOrDemoted() {
        users.deleteAll();
        users.flush();
        User solo = userService.createAdministrator("solo", "passwort1");
        Role plain = customRole("Plain");

        assertThatThrownBy(() -> userService.setEnabled(solo.getId(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("letzte aktive Administrator");
        assertThatThrownBy(() -> userService.changeRoles(solo.getId(), Set.of(plain.getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("letzte aktive Administrator");
    }

    @Test
    void administratorCanBeDisabledWhenAnotherOneExists() {
        users.deleteAll();
        users.flush();
        User first = userService.createAdministrator("erster", "passwort1");
        userService.createAdministrator("zweiter", "passwort1");

        userService.setEnabled(first.getId(), false);

        assertThat(users.findById(first.getId())).get().extracting(User::isEnabled).isEqualTo(false);
    }

    @Test
    void duplicateUsernameIsRejected() {
        userService.create("doppelt", "passwort1", Set.of());

        assertThatThrownBy(() -> userService.create("doppelt", "passwort1", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shortPasswordIsRejected() {
        assertThatThrownBy(() -> userService.create("kurz", "1234567", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 Zeichen");
    }

    @Test
    void passwordResetChangesHash() {
        User user = userService.create("reset", "passwort1", Set.of());
        String before = user.getPasswordHash();

        userService.resetPassword(user.getId(), "neues-passwort");

        assertThat(users.findById(user.getId())).get()
                .extracting(User::getPasswordHash).isNotEqualTo(before);
    }
}
```

`AdminInitializerTest.java` (ersetzen der Aufrufe):
```java
verify(userService).createAdministrator("admin", "secret-pw");
...
verify(userService, never()).createAdministrator("admin", "");
```
(Imports und übrige Tests bleiben; `Role`-Referenzen entfallen.)

`SecurityAccessTests.java` – die beiden Rollen-Tests ersetzen:
```java
    @Test
    @WithMockUser(authorities = "MEMBER_MANAGE")
    void userWithoutUserManageCannotAccessUserAdmin() throws Exception {
        mockMvc.perform(get("/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "USER_MANAGE")
    void userWithUserManageCanAccessUserAdmin() throws Exception {
        mockMvc.perform(get("/admin/users")).andExpect(status().isOk());
    }
```

- [ ] **Step 3: Tests ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=UserServiceTest+RoleMigrationTest+AdminInitializerTest`
Expected: Kompilierfehler (`role.Role`, neue Service-Methoden fehlen).

- [ ] **Step 4: Implementieren**

Datei `user/Role.java` löschen.

`role/Role.java`:
```java
package club.badernet.nestwerk.role;

import club.badernet.nestwerk.security.PermissionCatalog;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "system_role", nullable = false)
    private boolean systemRole;

    @Column(name = "all_permissions", nullable = false)
    private boolean allPermissions;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission", nullable = false, length = 100)
    private Set<String> permissions = new HashSet<>();

    protected Role() {
    }

    public Role(String name, String description, Set<String> permissions) {
        this.name = name;
        this.description = description;
        this.permissions = new HashSet<>(permissions);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSystemRole() {
        return systemRole;
    }

    public boolean isAllPermissions() {
        return allPermissions;
    }

    /** Gespeicherte Rechte, die im Katalog existieren (unbekannte Namen werden ignoriert). */
    public Set<String> getPermissions() {
        Set<String> known = new LinkedHashSet<>();
        for (String p : permissions) {
            if (PermissionCatalog.find(p).isPresent()) {
                known.add(p);
            }
        }
        return known;
    }

    public Set<String> effectivePermissionNames() {
        return allPermissions ? PermissionCatalog.names() : getPermissions();
    }

    public void rename(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions.clear();
        this.permissions.addAll(permissions);
    }
}
```

`role/RoleRepository.java`:
```java
package club.badernet.nestwerk.role;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByAllPermissionsTrue();

    Optional<Role> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Role> findAllByOrderByNameAsc();
}
```

`user/User.java` ersetzen:
```java
package club.badernet.nestwerk.user;

import club.badernet.nestwerk.role.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected User() {
    }

    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Set<Role> getRoles() {
        return Set.copyOf(roles);
    }

    public void setRoles(Collection<Role> newRoles) {
        roles.clear();
        roles.addAll(newRoles);
    }

    public boolean hasRoleId(Long roleId) {
        return roles.stream().anyMatch(r -> r.getId().equals(roleId));
    }

    public boolean isAdministrator() {
        return roles.stream().anyMatch(Role::isAllPermissions);
    }

    public Set<String> permissionNames() {
        Set<String> names = new LinkedHashSet<>();
        roles.forEach(r -> names.addAll(r.effectivePermissionNames()));
        return names;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
```

`UserRepository.java` ergänzen:
```java
    @Query("select count(distinct u) from User u join u.roles r "
            + "where u.enabled = true and r.allPermissions = true")
    long countActiveAdministrators();
```
(Import `org.springframework.data.jpa.repository.Query`.)

`UserService.java` ersetzen:
```java
package club.badernet.nestwerk.user;

import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserService implements UserDetailsService {

    public static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Benutzer nicht gefunden: " + username));
        Set<GrantedAuthority> authorities = user.permissionNames().stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(authorities)
                .disabled(!user.isEnabled())
                .build();
    }

    @Transactional(readOnly = true)
    public List<User> findAll() {
        return users.findAllByOrderByUsernameAsc();
    }

    @Transactional(readOnly = true)
    public boolean hasUsers() {
        return users.count() > 0;
    }

    @Transactional
    public User create(String username, String rawPassword, Set<Long> roleIds) {
        requireValidPassword(rawPassword);
        if (users.existsByUsername(username)) {
            throw new IllegalArgumentException("Benutzername ist bereits vergeben");
        }
        User user = new User(username, passwordEncoder.encode(rawPassword));
        user.setRoles(resolveRoles(roleIds));
        return users.save(user);
    }

    @Transactional
    public User createAdministrator(String username, String rawPassword) {
        Role admin = roles.findByAllPermissionsTrue()
                .orElseThrow(() -> new IllegalStateException("Administrator-Rolle fehlt in der Datenbank"));
        return create(username, rawPassword, Set.of(admin.getId()));
    }

    @Transactional
    public void changeRoles(Long userId, Set<Long> roleIds) {
        User user = load(userId);
        List<Role> newRoles = resolveRoles(roleIds);
        boolean staysAdministrator = newRoles.stream().anyMatch(Role::isAllPermissions);
        guardLastAdministrator(user, user.isEnabled() && staysAdministrator);
        user.setRoles(newRoles);
    }

    @Transactional
    public void setEnabled(Long userId, boolean enabled) {
        User user = load(userId);
        guardLastAdministrator(user, enabled && user.isAdministrator());
        user.setEnabled(enabled);
    }

    @Transactional
    public void resetPassword(Long userId, String rawPassword) {
        requireValidPassword(rawPassword);
        load(userId).setPasswordHash(passwordEncoder.encode(rawPassword));
    }

    private User load(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Benutzer nicht gefunden"));
    }

    private List<Role> resolveRoles(Set<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<Role> found = roles.findAllById(roleIds);
        if (found.size() != roleIds.size()) {
            throw new IllegalArgumentException("Unbekannte Rolle ausgewählt");
        }
        return found;
    }

    private void guardLastAdministrator(User user, boolean willBeActiveAdministrator) {
        boolean isActiveAdministrator = user.isEnabled() && user.isAdministrator();
        if (isActiveAdministrator && !willBeActiveAdministrator
                && users.countActiveAdministrators() <= 1) {
            throw new IllegalArgumentException(
                    "Der letzte aktive Administrator kann nicht deaktiviert oder herabgestuft werden.");
        }
    }

    private void requireValidPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Das Passwort muss mindestens " + MIN_PASSWORD_LENGTH + " Zeichen lang sein.");
        }
    }
}
```

`AdminInitializer.java` – Zeile 40 ersetzen: `userService.createAdministrator(username, password);`

`config/SecurityConfig.java`:
```java
package club.badernet.nestwerk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/error", "/css/**", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").permitAll())
                .logout(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

`web/AdminUserController.java` (Zwischenstand, wird in Task 4 erweitert):
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.user.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashSet;
import java.util.Set;

@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasAuthority('USER_MANAGE')")
public class AdminUserController {

    private final UserService userService;
    private final RoleRepository roleRepository;

    public AdminUserController(UserService userService, RoleRepository roleRepository) {
        this.userService = userService;
        this.roleRepository = roleRepository;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("users", userService.findAll());
        model.addAttribute("roles", roleRepository.findAllByOrderByNameAsc());
        return "admin/users";
    }

    @PostMapping
    String create(@RequestParam String username,
                  @RequestParam String password,
                  @RequestParam(required = false) Set<Long> roleIds,
                  RedirectAttributes redirect) {
        String name = username.trim();
        if (name.isEmpty() || name.length() > 100) {
            redirect.addFlashAttribute("error", "Der Benutzername muss 1–100 Zeichen lang sein.");
            return "redirect:/admin/users";
        }
        try {
            userService.create(name, password, roleIds == null ? new HashSet<>() : roleIds);
            redirect.addFlashAttribute("success", "Benutzer '" + name + "' wurde angelegt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }
}
```

`templates/admin/users.html`: Spalte „Rolle“ → Rollenliste, Rollenauswahl als Checkboxen:
```html
<tr><th>Benutzername</th><th>Rollen</th><th>Aktiv</th></tr>
...
<td><span th:each="r, s : ${user.roles}" th:text="${r.name} + (${s.last} ? '' : ', ')"></span></td>
...
<p>Rollen:
    <label th:each="r : ${roles}"><input type="checkbox" name="roleIds" th:value="${r.id}"> <span th:text="${r.name}"></span></label>
</p>
```
(Die `<select name="role">`-Variante entfällt.)

`templates/index.html`: Zeile mit `hasRole('ADMIN')` ersetzen durch `sec:authorize="hasAuthority('USER_MANAGE')"`.

- [ ] **Step 5: Tests ausführen, Erfolg prüfen**

Run: `./mvnw -q test`
Expected: PASS (alle Tests, inkl. `NestwerkApplicationTests`, `SecurityAccessTests`).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: Rollen mit Einzelrechten, mehrere Rollen je Benutzer, Letzter-Admin-Schutz"
```

---

### Task 3: Layout und Navigation

> **Stand 2026-10-14:** `fragments/layout.html`, `static/css/nestwerk.css`, `static/js/nestwerk.js` (inkl. htmx) existieren bereits mit dem neuen Design. Fragmente: `head(title)`, `nav` (Aufruf mit `nav(active='…')`, Klasse `topnav`), `toasts`, `passwordToggle`, `nestmark`. Für diese Task bleiben nur: `sec:authorize` in `nav` von `hasRole('ADMIN')` auf die Rechte umstellen, Menüpunkte Rollen/Mitglieder ergänzen und `NavigationTests` anlegen. Die CSS-/HTML-Beispiele unten sind überholt.

**Files:**
- Create: `src/main/resources/templates/fragments/layout.html`
- Create: `src/main/resources/static/css/nestwerk.css`
- Modify: `src/main/resources/templates/index.html`, `src/main/resources/templates/admin/users.html`
- Test: `src/test/java/club/badernet/nestwerk/web/NavigationTests.java`

**Interfaces:**
- Produces: Fragmente `~{fragments/layout :: head('Titel')}` und `~{fragments/layout :: nav}`. Alle späteren Seiten binden beide ein; weitere Module fügen ihre Menüpunkte in `nav` hinzu.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
class NavigationTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    @WithMockUser(authorities = "USER_MANAGE")
    void showsOnlyPermittedMenuEntries() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/users")))
                .andExpect(content().string(not(containsString("/admin/roles"))))
                .andExpect(content().string(not(containsString("/admin/members"))));
    }

    @Test
    @WithMockUser
    void userWithoutPermissionsSeesNoAdminEntries() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/admin/"))));
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=NavigationTests`
Expected: FAIL in `showsOnlyPermittedMenuEntries`, weil `index.html` noch kein Layout-Menü enthält. Damit der Fehlschlag eindeutig ist, im ersten Test zusätzlich `.andExpect(content().string(containsString("class=\"topnav\"")))` ergänzen.

- [ ] **Step 3: Implementieren**

`fragments/layout.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head th:fragment="head(title)">
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title th:text="${title} + ' – Nestwerk'">Nestwerk</title>
    <link rel="stylesheet" th:href="@{/css/nestwerk.css}">
</head>
<body>
<nav th:fragment="nav" class="topnav">
    <a th:href="@{/}" class="brand">Nestwerk</a>
    <a th:href="@{/admin/users}" sec:authorize="hasAuthority('USER_MANAGE')">Benutzer</a>
    <a th:href="@{/admin/roles}" sec:authorize="hasAuthority('ACCESS_ROLE_MANAGE')">Rollen</a>
    <a th:href="@{/admin/members}" sec:authorize="hasAuthority('MEMBER_MANAGE')">Mitglieder</a>
    <span class="spacer"></span>
    <span sec:authentication="name">benutzer</span>
    <form th:action="@{/logout}" method="post"><button type="submit">Abmelden</button></form>
</nav>
</body>
</html>
```

`static/css/nestwerk.css`:
```css
body { font-family: system-ui, sans-serif; margin: 0; color: #222; }
main { max-width: 60rem; margin: 1rem auto; padding: 0 1rem; }
.topnav { display: flex; gap: 1rem; align-items: center; padding: .5rem 1rem; background: #2b4a6f; color: #fff; flex-wrap: wrap; }
.topnav a { color: #fff; text-decoration: none; }
.topnav .brand { font-weight: bold; }
.topnav .spacer { flex: 1; }
.topnav form { margin: 0; }
table { border-collapse: collapse; width: 100%; }
th, td { border-bottom: 1px solid #ddd; padding: .4rem; text-align: left; vertical-align: top; }
.flash-success { color: #1a6b2f; }
.flash-error { color: #b00020; }
fieldset { margin: .5rem 0; }
```

`index.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head th:replace="~{fragments/layout :: head('Start')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav}"></nav>
<main>
    <h1>Willkommen bei Nestwerk</h1>
    <p>Angemeldet als <strong sec:authentication="name">benutzer</strong>.</p>
</main>
</body>
</html>
```

`admin/users.html`: `<head>`-Block durch `<head th:replace="~{fragments/layout :: head('Benutzerverwaltung')}"></head>` ersetzen, `<p><a th:href="@{/}">Zurück</a></p>` durch `<nav th:replace="~{fragments/layout :: nav}"></nav>` ersetzen, Rest in `<main>…</main>` fassen, Flash-Meldungen mit `class="flash-success"` / `class="flash-error"`.

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=NavigationTests+SecurityAccessTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: gemeinsames Layout mit rechteabhängiger Navigation"
```

---

### Task 4: Rollenverwaltung

**Files:**
- Create: `src/main/java/club/badernet/nestwerk/role/RoleService.java`
- Create: `src/main/java/club/badernet/nestwerk/web/AdminRoleController.java`
- Create: `src/main/resources/templates/admin/roles.html`
- Modify: `user/UserRepository.java` (Methode `existsByRoles_Id`)
- Test: `src/test/java/club/badernet/nestwerk/role/RoleServiceTest.java`, `src/test/java/club/badernet/nestwerk/web/AdminRoleControllerTest.java`

**Interfaces:**
- Consumes: `RoleRepository`, `UserRepository`, `PermissionCatalog`.
- Produces: `RoleService`: `findAll(): List<Role>`, `create(String name, String description, Set<String> permissions): Role`, `update(Long id, String name, String description, Set<String> permissions)`, `delete(Long id)`. Alle Regelverstöße als `IllegalArgumentException` mit deutscher Meldung.

- [ ] **Step 1: Failing Tests schreiben**

`RoleServiceTest.java`:
```java
package club.badernet.nestwerk.role;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class RoleServiceTest {

    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired UserService userService;

    @Test
    void createsRoleWithPermissions() {
        Role role = roleService.create("Kind", "Eingeschränkt", Set.of("MEMBER_MANAGE"));

        assertThat(role.getPermissions()).containsExactly("MEMBER_MANAGE");
    }

    @Test
    void rejectsUnknownPermission() {
        assertThatThrownBy(() -> roleService.create("Kaputt", null, Set.of("GIBT_ES_NICHT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GIBT_ES_NICHT");
    }

    @Test
    void rejectsDuplicateNameIgnoringCase() {
        roleService.create("Kind", null, Set.of());

        assertThatThrownBy(() -> roleService.create("kind", null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existiert bereits");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> roleService.create("  ", null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void administratorRoleCannotBeUpdatedOrDeleted() {
        Role admin = roles.findByAllPermissionsTrue().orElseThrow();

        assertThatThrownBy(() -> roleService.update(admin.getId(), "Admin", null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> roleService.delete(admin.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemRoleKeepsNameButPermissionsAreEditable() {
        Role benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow();

        roleService.update(benutzer.getId(), "Umbenannt", "neu", Set.of("MEMBER_MANAGE"));

        Role reloaded = roles.findById(benutzer.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Benutzer");
        assertThat(reloaded.getPermissions()).containsExactly("MEMBER_MANAGE");
    }

    @Test
    void assignedRoleCannotBeDeleted() {
        Role role = roleService.create("Belegt", null, Set.of());
        userService.create("hat-rolle", "passwort1", Set.of(role.getId()));

        assertThatThrownBy(() -> roleService.delete(role.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zugewiesen");
    }

    @Test
    void unassignedCustomRoleCanBeDeleted() {
        Role role = roleService.create("Weg", null, Set.of());

        roleService.delete(role.getId());

        assertThat(roles.findById(role.getId())).isEmpty();
    }
}
```

`AdminRoleControllerTest.java`:
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class AdminRoleControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired RoleRepository roles;

    @Test
    @WithMockUser(authorities = "USER_MANAGE")
    void forbiddenWithoutRolePermission() throws Exception {
        mockMvc.perform(get("/admin/roles")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void createsRoleFromForm() throws Exception {
        mockMvc.perform(post("/admin/roles").with(csrf())
                        .param("name", "Teenager")
                        .param("description", "Mit Einschränkungen")
                        .param("permissions", "MEMBER_MANAGE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));

        assertThat(roles.findByNameIgnoreCase("Teenager")).isPresent();
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void showsErrorForDuplicateName() throws Exception {
        mockMvc.perform(post("/admin/roles").with(csrf()).param("name", "Benutzer"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));
    }
}
```

- [ ] **Step 2: Tests ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=RoleServiceTest+AdminRoleControllerTest`
Expected: Kompilierfehler (`RoleService` fehlt).

- [ ] **Step 3: Implementieren**

`UserRepository` ergänzen: `boolean existsByRoles_Id(Long roleId);`

`role/RoleService.java`:
```java
package club.badernet.nestwerk.role;

import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final UserRepository users;

    public RoleService(RoleRepository roles, UserRepository users) {
        this.roles = roles;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<Role> findAll() {
        return roles.findAllByOrderByNameAsc();
    }

    @Transactional
    public Role create(String name, String description, Set<String> permissions) {
        String cleaned = requireName(name);
        if (roles.existsByNameIgnoreCase(cleaned)) {
            throw new IllegalArgumentException("Eine Rolle mit diesem Namen existiert bereits.");
        }
        return roles.save(new Role(cleaned, clean(description), validPermissions(permissions)));
    }

    @Transactional
    public void update(Long id, String name, String description, Set<String> permissions) {
        Role role = get(id);
        if (role.isAllPermissions()) {
            throw new IllegalArgumentException("Die Administrator-Rolle kann nicht geändert werden.");
        }
        if (!role.isSystemRole()) {
            String cleaned = requireName(name);
            Optional<Role> clash = roles.findByNameIgnoreCase(cleaned);
            if (clash.isPresent() && !clash.get().getId().equals(id)) {
                throw new IllegalArgumentException("Eine Rolle mit diesem Namen existiert bereits.");
            }
            role.rename(cleaned);
        }
        role.setDescription(clean(description));
        role.setPermissions(validPermissions(permissions));
    }

    @Transactional
    public void delete(Long id) {
        Role role = get(id);
        if (role.isSystemRole()) {
            throw new IllegalArgumentException("Systemrollen können nicht gelöscht werden.");
        }
        if (users.existsByRoles_Id(id)) {
            throw new IllegalArgumentException("Die Rolle ist noch Benutzern zugewiesen.");
        }
        roles.delete(role);
    }

    private Role get(Long id) {
        return roles.findById(id).orElseThrow(() -> new IllegalArgumentException("Rolle nicht gefunden"));
    }

    private String requireName(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty() || cleaned.length() > 100) {
            throw new IllegalArgumentException("Der Rollenname muss 1–100 Zeichen lang sein.");
        }
        return cleaned;
    }

    private String clean(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    private Set<String> validPermissions(Set<String> permissions) {
        Set<String> valid = new HashSet<>();
        if (permissions == null) {
            return valid;
        }
        for (String p : permissions) {
            if (PermissionCatalog.find(p).isEmpty()) {
                throw new IllegalArgumentException("Unbekanntes Recht: " + p);
            }
            valid.add(p);
        }
        return valid;
    }
}
```

`web/AdminRoleController.java`:
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.PermissionCatalog;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;

@Controller
@RequestMapping("/admin/roles")
@PreAuthorize("hasAuthority('ACCESS_ROLE_MANAGE')")
public class AdminRoleController {

    private final RoleService roleService;

    public AdminRoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("roles", roleService.findAll());
        model.addAttribute("permissionsByModule", PermissionCatalog.byModule());
        return "admin/roles";
    }

    @PostMapping
    String create(@RequestParam String name,
                  @RequestParam(required = false) String description,
                  @RequestParam(required = false) Set<String> permissions,
                  RedirectAttributes redirect) {
        return run(redirect, "Rolle '" + name.trim() + "' wurde angelegt.",
                () -> roleService.create(name, description, permissions));
    }

    @PostMapping("/{id}")
    String update(@PathVariable Long id,
                  @RequestParam(required = false) String name,
                  @RequestParam(required = false) String description,
                  @RequestParam(required = false) Set<String> permissions,
                  RedirectAttributes redirect) {
        return run(redirect, "Rolle wurde gespeichert.",
                () -> roleService.update(id, name, description, permissions));
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        return run(redirect, "Rolle wurde gelöscht.", () -> roleService.delete(id));
    }

    private String run(RedirectAttributes redirect, String successMessage, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", successMessage);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/roles";
    }
}
```

`templates/admin/roles.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head th:replace="~{fragments/layout :: head('Rollen')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav}"></nav>
<main>
<h1>Rollen und Rechte</h1>
<p th:if="${success}" class="flash-success" th:text="${success}"></p>
<p th:if="${error}" class="flash-error" th:text="${error}"></p>

<section th:each="role : ${roles}">
    <h2 th:text="${role.name}"></h2>
    <p th:if="${role.allPermissions}">Hat automatisch alle Rechte (nicht änderbar).</p>
    <form th:unless="${role.allPermissions}" th:action="@{/admin/roles/{id}(id=${role.id})}" method="post">
        <p th:unless="${role.systemRole}">
            <label>Name <input type="text" name="name" maxlength="100" th:value="${role.name}" required></label>
        </p>
        <p><label>Beschreibung <input type="text" name="description" maxlength="255" th:value="${role.description}"></label></p>
        <fieldset th:each="entry : ${permissionsByModule}">
            <legend th:text="${entry.key}"></legend>
            <label th:each="p : ${entry.value}">
                <input type="checkbox" name="permissions" th:value="${p.name()}"
                       th:checked="${role.permissions.contains(p.name())}">
                <span th:text="${p.label()}"></span>
            </label>
        </fieldset>
        <button type="submit">Speichern</button>
    </form>
    <form th:unless="${role.systemRole}" th:action="@{/admin/roles/{id}/delete(id=${role.id})}" method="post">
        <button type="submit">Rolle löschen</button>
    </form>
</section>

<h2>Neue Rolle anlegen</h2>
<form th:action="@{/admin/roles}" method="post">
    <p><label>Name <input type="text" name="name" maxlength="100" required></label></p>
    <p><label>Beschreibung <input type="text" name="description" maxlength="255"></label></p>
    <fieldset th:each="entry : ${permissionsByModule}">
        <legend th:text="${entry.key}"></legend>
        <label th:each="p : ${entry.value}">
            <input type="checkbox" name="permissions" th:value="${p.name()}">
            <span th:text="${p.label()}"></span>
        </label>
    </fieldset>
    <button type="submit">Anlegen</button>
</form>
</main>
</body>
</html>
```

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=RoleServiceTest+AdminRoleControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Rollenverwaltung mit Rechte-Checkboxen"
```

---

### Task 5: Benutzerverwaltung erweitern

**Files:**
- Modify: `web/AdminUserController.java`, `templates/admin/users.html`
- Test: `src/test/java/club/badernet/nestwerk/web/AdminUserControllerTest.java`

**Interfaces:**
- Consumes: `UserService.changeRoles/setEnabled/resetPassword`, `RoleRepository.findAllByOrderByNameAsc`, `User.hasRoleId`.
- Produces: Endpunkte `POST /admin/users/{id}/roles` (`roleIds`), `POST /admin/users/{id}/enabled` (`enabled=true|false`), `POST /admin/users/{id}/password` (`password`).

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
@WithMockUser(authorities = "USER_MANAGE")
class AdminUserControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired UserRepository users;

    @Test
    void lastAdministratorCannotBeDisabled() throws Exception {
        users.deleteAll();
        users.flush();
        User solo = userService.createAdministrator("solo", "passwort1");

        mockMvc.perform(post("/admin/users/{id}/enabled", solo.getId()).with(csrf()).param("enabled", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        assertThat(users.findById(solo.getId())).get().extracting(User::isEnabled).isEqualTo(true);
    }

    @Test
    void usersCanBeDisabledAndRolesChanged() throws Exception {
        User user = userService.create("normal", "passwort1", Set.of());

        mockMvc.perform(post("/admin/users/{id}/enabled", user.getId()).with(csrf()).param("enabled", "false"))
                .andExpect(flash().attributeExists("success"));

        assertThat(users.findById(user.getId())).get().extracting(User::isEnabled).isEqualTo(false);
    }

    @Test
    void passwordResetRejectsShortPassword() throws Exception {
        User user = userService.create("pw", "passwort1", Set.of());

        mockMvc.perform(post("/admin/users/{id}/password", user.getId()).with(csrf()).param("password", "kurz"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void ownAccountMayBeResetButStillValidated() throws Exception {
        User user = userService.create("selbst", "passwort1", Set.of());

        mockMvc.perform(post("/admin/users/{id}/password", user.getId()).with(csrf()).param("password", "ganz-neu-123"))
                .andExpect(flash().attributeExists("success"));
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=AdminUserControllerTest`
Expected: FAIL (404/405: Endpunkte fehlen).

- [ ] **Step 3: Implementieren**

In `AdminUserController` ergänzen (Imports `PathVariable`):
```java
    @PostMapping("/{id}/roles")
    String changeRoles(@PathVariable Long id,
                       @RequestParam(required = false) Set<Long> roleIds,
                       RedirectAttributes redirect) {
        return run(redirect, "Rollen wurden gespeichert.",
                () -> userService.changeRoles(id, roleIds == null ? new HashSet<>() : roleIds));
    }

    @PostMapping("/{id}/enabled")
    String setEnabled(@PathVariable Long id, @RequestParam boolean enabled, RedirectAttributes redirect) {
        return run(redirect, enabled ? "Benutzer wurde aktiviert." : "Benutzer wurde deaktiviert.",
                () -> userService.setEnabled(id, enabled));
    }

    @PostMapping("/{id}/password")
    String resetPassword(@PathVariable Long id, @RequestParam String password, RedirectAttributes redirect) {
        return run(redirect, "Passwort wurde zurückgesetzt.", () -> userService.resetPassword(id, password));
    }

    private String run(RedirectAttributes redirect, String successMessage, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", successMessage);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }
```
Die `create`-Methode ebenfalls auf `run(...)` umstellen (Name-Validierung davor beibehalten).

`templates/admin/users.html`: Tabelle ersetzen durch pro Benutzer eine Zeile mit drei Formularen:
```html
<tr th:each="user : ${users}">
    <td th:text="${user.username}"></td>
    <td>
        <form th:action="@{/admin/users/{id}/roles(id=${user.id})}" method="post">
            <label th:each="r : ${roles}">
                <input type="checkbox" name="roleIds" th:value="${r.id}" th:checked="${user.hasRoleId(r.id)}">
                <span th:text="${r.name}"></span>
            </label>
            <button type="submit">Rollen speichern</button>
        </form>
    </td>
    <td>
        <form th:action="@{/admin/users/{id}/enabled(id=${user.id})}" method="post">
            <input type="hidden" name="enabled" th:value="${!user.enabled}">
            <span th:text="${user.enabled} ? 'aktiv' : 'deaktiviert'"></span>
            <button type="submit" th:text="${user.enabled} ? 'Deaktivieren' : 'Aktivieren'"></button>
        </form>
    </td>
    <td>
        <form th:action="@{/admin/users/{id}/password(id=${user.id})}" method="post">
            <input type="password" name="password" minlength="8" placeholder="Neues Passwort" required>
            <button type="submit">Zurücksetzen</button>
        </form>
    </td>
</tr>
```
Tabellenkopf entsprechend: `Benutzername | Rollen | Status | Passwort`.

- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=AdminUserControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Benutzer deaktivieren, Rollen zuweisen, Passwort zurücksetzen"
```

---

### Task 6: Familienmitglieder

**Files:**
- Create: `src/main/java/club/badernet/nestwerk/member/FamilyMember.java`, `FamilyMemberRepository.java`, `FamilyMemberService.java`
- Create: `src/main/java/club/badernet/nestwerk/web/AdminMemberController.java`
- Create: `src/main/resources/templates/admin/members.html`
- Test: `src/test/java/club/badernet/nestwerk/member/FamilyMemberServiceTest.java`

**Interfaces:**
- Produces: `FamilyMember`: `getId()`, `getName()`, `getColor()`, `getUserId(): Long` (nullable). `FamilyMemberService`: `findAll(): List<FamilyMember>` (nach Name), `create(String name, String color, Long userId)`, `update(Long id, String name, String color, Long userId)`, `delete(Long id)`, `findByUserId(Long userId): Optional<FamilyMember>`. Spätere Module referenzieren Mitglieder nur über `FamilyMember.getId()`.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.member;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class FamilyMemberServiceTest {

    @Autowired FamilyMemberService members;
    @Autowired UserService userService;

    @Test
    void createsMemberWithoutAccount() {
        FamilyMember m = members.create("Lena", "#ff8800", null);

        assertThat(m.getUserId()).isNull();
        assertThat(members.findAll()).extracting(FamilyMember::getName).contains("Lena");
    }

    @Test
    void linksMemberToUser() {
        User user = userService.create("lena", "passwort1", Set.of());

        FamilyMember m = members.create("Lena", "#ff8800", user.getId());

        assertThat(members.findByUserId(user.getId())).get().extracting(FamilyMember::getId).isEqualTo(m.getId());
    }

    @Test
    void rejectsUserAlreadyLinkedToAnotherMember() {
        User user = userService.create("geteilt", "passwort1", Set.of());
        members.create("Erstes", "#112233", user.getId());

        assertThatThrownBy(() -> members.create("Zweites", "#445566", user.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bereits");
    }

    @Test
    void allowsKeepingOwnLinkOnUpdate() {
        User user = userService.create("eigen", "passwort1", Set.of());
        FamilyMember m = members.create("Eigen", "#112233", user.getId());

        members.update(m.getId(), "Eigen neu", "#445566", user.getId());

        assertThat(members.findAll()).extracting(FamilyMember::getName).contains("Eigen neu");
    }

    @Test
    void rejectsInvalidColorAndBlankName() {
        assertThatThrownBy(() -> members.create("Farbe", "rot", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> members.create(" ", "#112233", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownUser() {
        assertThatThrownBy(() -> members.create("Geist", "#112233", 999_999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesMember() {
        FamilyMember m = members.create("Weg", "#112233", null);

        members.delete(m.getId());

        assertThat(members.findAll()).extracting(FamilyMember::getId).doesNotContain(m.getId());
    }
}
```

- [ ] **Step 2: Test ausführen, Fehlschlag prüfen**

Run: `./mvnw -q test -Dtest=FamilyMemberServiceTest`
Expected: Kompilierfehler.

- [ ] **Step 3: Implementieren**

`FamilyMember.java`:
```java
package club.badernet.nestwerk.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "family_members")
public class FamilyMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 7)
    private String color;

    @Column(name = "user_id", unique = true)
    private Long userId;

    protected FamilyMember() {
    }

    public FamilyMember(String name, String color, Long userId) {
        this.name = name;
        this.color = color;
        this.userId = userId;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getColor() {
        return color;
    }

    public Long getUserId() {
        return userId;
    }

    public void change(String name, String color, Long userId) {
        this.name = name;
        this.color = color;
        this.userId = userId;
    }
}
```

`FamilyMemberRepository.java`:
```java
package club.badernet.nestwerk.member;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FamilyMemberRepository extends JpaRepository<FamilyMember, Long> {

    List<FamilyMember> findAllByOrderByNameAsc();

    Optional<FamilyMember> findByUserId(Long userId);
}
```

`FamilyMemberService.java`:
```java
package club.badernet.nestwerk.member;

import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class FamilyMemberService {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final FamilyMemberRepository members;
    private final UserRepository users;

    public FamilyMemberService(FamilyMemberRepository members, UserRepository users) {
        this.members = members;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<FamilyMember> findAll() {
        return members.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<FamilyMember> findByUserId(Long userId) {
        return members.findByUserId(userId);
    }

    @Transactional
    public FamilyMember create(String name, String color, Long userId) {
        String cleanName = requireName(name);
        requireColor(color);
        requireLinkable(userId, null);
        return members.save(new FamilyMember(cleanName, color, userId));
    }

    @Transactional
    public void update(Long id, String name, String color, Long userId) {
        FamilyMember member = members.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mitglied nicht gefunden"));
        String cleanName = requireName(name);
        requireColor(color);
        requireLinkable(userId, id);
        member.change(cleanName, color, userId);
    }

    @Transactional
    public void delete(Long id) {
        if (!members.existsById(id)) {
            throw new IllegalArgumentException("Mitglied nicht gefunden");
        }
        members.deleteById(id);
    }

    private String requireName(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty() || cleaned.length() > 100) {
            throw new IllegalArgumentException("Der Name muss 1–100 Zeichen lang sein.");
        }
        return cleaned;
    }

    private void requireColor(String color) {
        if (color == null || !COLOR.matcher(color).matches()) {
            throw new IllegalArgumentException("Die Farbe muss im Format #RRGGBB angegeben werden.");
        }
    }

    private void requireLinkable(Long userId, Long memberId) {
        if (userId == null) {
            return;
        }
        if (!users.existsById(userId)) {
            throw new IllegalArgumentException("Das ausgewählte Benutzerkonto existiert nicht.");
        }
        Optional<FamilyMember> existing = members.findByUserId(userId);
        if (existing.isPresent() && !existing.get().getId().equals(memberId)) {
            throw new IllegalArgumentException(
                    "Dieses Benutzerkonto ist bereits mit einem anderen Mitglied verknüpft.");
        }
    }
}
```

`web/AdminMemberController.java`:
```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.user.UserService;
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
@RequestMapping("/admin/members")
@PreAuthorize("hasAuthority('MEMBER_MANAGE')")
public class AdminMemberController {

    private final FamilyMemberService members;
    private final UserService userService;

    public AdminMemberController(FamilyMemberService members, UserService userService) {
        this.members = members;
        this.userService = userService;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("members", members.findAll());
        model.addAttribute("users", userService.findAll());
        return "admin/members";
    }

    @PostMapping
    String create(@RequestParam String name, @RequestParam String color,
                  @RequestParam(required = false) Long userId, RedirectAttributes redirect) {
        return run(redirect, "Mitglied wurde angelegt.", () -> members.create(name, color, userId));
    }

    @PostMapping("/{id}")
    String update(@PathVariable Long id, @RequestParam String name, @RequestParam String color,
                  @RequestParam(required = false) Long userId, RedirectAttributes redirect) {
        return run(redirect, "Mitglied wurde gespeichert.", () -> members.update(id, name, color, userId));
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        return run(redirect, "Mitglied wurde gelöscht.", () -> members.delete(id));
    }

    private String run(RedirectAttributes redirect, String successMessage, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", successMessage);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/members";
    }
}
```

`templates/admin/members.html`:
```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('Familienmitglieder')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav}"></nav>
<main>
<h1>Familienmitglieder</h1>
<p th:if="${success}" class="flash-success" th:text="${success}"></p>
<p th:if="${error}" class="flash-error" th:text="${error}"></p>

<section th:each="m : ${members}">
    <form th:action="@{/admin/members/{id}(id=${m.id})}" method="post">
        <input type="text" name="name" maxlength="100" th:value="${m.name}" required>
        <input type="color" name="color" th:value="${m.color}">
        <select name="userId">
            <option value="">– kein Konto –</option>
            <option th:each="u : ${users}" th:value="${u.id}" th:text="${u.username}"
                    th:selected="${m.userId != null and m.userId == u.id}"></option>
        </select>
        <button type="submit">Speichern</button>
    </form>
    <form th:action="@{/admin/members/{id}/delete(id=${m.id})}" method="post">
        <button type="submit">Löschen</button>
    </form>
</section>

<h2>Neues Mitglied</h2>
<form th:action="@{/admin/members}" method="post">
    <p><label>Name <input type="text" name="name" maxlength="100" required></label></p>
    <p><label>Farbe <input type="color" name="color" value="#4a90d9"></label></p>
    <p><label>Benutzerkonto
        <select name="userId">
            <option value="">– kein Konto –</option>
            <option th:each="u : ${users}" th:value="${u.id}" th:text="${u.username}"></option>
        </select></label></p>
    <button type="submit">Anlegen</button>
</form>
</main>
</body>
</html>
```
- [ ] **Step 4: Tests ausführen**

Run: `./mvnw -q test -Dtest=FamilyMemberServiceTest`
Expected: PASS.

- [ ] **Step 5: Gesamtlauf und manuelle Prüfung**

Run: `./mvnw verify`
Expected: BUILD SUCCESS.

Manuell (`NESTWERK_ADMIN_PASSWORD=changeme ./mvnw spring-boot:run`): als `admin` anmelden → Rolle „Teenager“ mit `MEMBER_MANAGE` anlegen → Benutzer mit dieser Rolle anlegen → mit ihm anmelden: Menü zeigt nur „Mitglieder“, `/admin/users` liefert 403 → Mitglied anlegen und mit Konto verknüpfen.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: Familienmitglieder mit Farbe und optionalem Benutzerkonto"
```
