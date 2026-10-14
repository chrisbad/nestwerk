# ToDo Stufe 0: Rechtekatalog und ACL in der Datenbank – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der Rechtekatalog liegt in der Datenbank (Tabelle `permissions`, Bezeichnungen in der UI änderbar), und es gibt ein regelbasiertes ACL („Rolle darf Aktion auf Objekttyp bei Beziehung“) mit Katalogtabellen, Vorlagen, Eskalationsschutz und Matrix in der Rollenverwaltung.

**Architecture:** Die Rechte-Enums bleiben nur noch als Schlüsselkonstanten im Code; Bezeichnung, Modul und Reihenfolge kommen aus `permissions`. `PermissionCatalog` wird von einer statischen Klasse zur Spring-Komponente (JDBC) und berechnet effektive Rechte von Rollen und Benutzern (Administrator = ganzer Katalog). Das ACL (`security.acl`) speichert Regeln in `acl_rules`; welche Kombinationen gültig sind, steht in `acl_cells`. `AclService` liefert die Matrix, prüft Regeln (`permits`, `grantedRelations`) und ändert sie mit Eskalationsschutz. Die Auswertung für konkrete ToDos/Kontakte (Beziehungen ermitteln, Sichtbarkeit in SQL) folgt in Stufe 1.

**Tech Stack:** Spring Boot 4.1.1, Java 25, Spring JDBC (`JdbcTemplate`, `NamedParameterJdbcTemplate`), JPA, Flyway, Thymeleaf, htmx, Testcontainers (PostgreSQL 17).

**Spec:** `docs/superpowers/specs/2026-10-14-todo-design.md` (Abschnitte „Rechte und ACL“, „Lieferstufen“ Stufe 0)

## Global Constraints

- Java-Builds immer mit JDK 26: jedem Maven-Befehl `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26"` voranstellen (Bash). Das System-JDK 17 kann `--release 25` nicht.
- Tests brauchen laufendes Docker (Testcontainers). Einzeltests: `./mvnw -q test -Dtest=Klasse1,Klasse2`; Ergebnis in `target/surefire-reports/*.txt` prüfen.
- Flyway: neue Versionen `V4__permission_catalog.sql` und `V5__acl.sql`; V1–V3 bleiben unverändert. (Die Kalender-Pläne Phase 3/4 werden später umnummeriert.)
- `spring.jpa.hibernate.ddl-auto: validate` – neue Tabellen ohne Entity sind erlaubt, bestehende Entities dürfen nicht von der DB abweichen.
- Rechte-Schlüssel sind die Enum-Namen (`USER_MANAGE`, `CALENDAR_*`) und gleichzeitig Spring-Security-Authorities (ohne `ROLE_`).
- Die Administrator-Rolle (`roles.all_permissions = TRUE`) hat alle Rechte und alle ACL-Regeln, ohne Einträge in `role_permissions`/`acl_rules`, und ist nicht änderbar.
- ACL: nur Erlaubnisse, keine Verbote. Private Objekte sind über `ANY` nie erreichbar (Auswertung in Stufe 1).
- Eskalationsschutz: Nicht-Administratoren dürfen nur Rechte und ACL-Regeln vergeben, die sie selbst haben, und keine Rollen/Benutzer ändern, die mehr haben.
- Regelverstöße werfen `IllegalArgumentException` mit deutscher Meldung; Controller zeigen sie als Flash-Meldung, nie als 500.
- UI-Texte Deutsch mit Umlauten. Thymeleaf: `th:each` und `th:replace` nie auf demselben Element; Records im Template über Methodenaufruf (`p.key()`), Spring-Beans über `${@beanName.methode()}`.
- Commit-Nachrichten enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- Altdaten: `role_permissions` enthält Schlüssel, die es im Katalog nicht gibt → Migration V4 entfernt sie still statt zu scheitern (Task 1, `PermissionMigrationTest`).
- Rolle mit ACL-Regeln wird gelöscht → Regeln verschwinden mit (Kaskade), kein Fremdschlüsselfehler (Task 4, `deletingRoleRemovesItsRules`).
- Manipulierter POST an die Matrix (`rules=TODO:EDIT`, `rules=X:Y:Z`, `rules=TODO:CREATE:RESPONSIBLE`) → Flash-Fehler, keine Änderung, kein 500 (Task 6, `malformedRuleShowsError`).
- Benutzerverwalter ohne Administratorrechte weist eine Rolle zu, die ACL-Regeln enthält, die er selbst nicht hat → abgelehnt (Task 5, `nonAdministratorCannotAssignRoleWithForeignAccessRules`).
- Neu angelegte Rolle ohne jede Regel → Rollenseite rendert die Matrix ohne Fehler (Task 6, `newRoleWithoutRulesRendersMatrix`).

## Dateiübersicht

| Datei | Verantwortung |
|---|---|
| `db/migration/V4__permission_catalog.sql` | Tabelle `permissions`, Seed, Bereinigung, FK von `role_permissions` |
| `security/Permission.java`, `AdminPermission.java`, `calendar/CalendarPermission.java` | nur noch Schlüsselkonstanten |
| `security/PermissionDefinition.java` | Record eines Katalogeintrags |
| `security/PermissionCatalog.java` | Spring-Komponente: Katalog lesen, Texte ändern, effektive Rechte |
| `security/PermissionCatalogVerifier.java` | Startprüfung: alle Code-Schlüssel existieren |
| `role/Role.java`, `user/User.java` | Katalog-Abhängigkeit entfernt |
| `user/UserService.java`, `role/RoleService.java`, `calendar/CalendarEventService.java` | effektive Rechte über `PermissionCatalog` |
| `web/AdminPermissionController.java`, `templates/admin/permissions.html` | Bezeichnungen der Rechte pflegen |
| `db/migration/V5__acl.sql` | ACL-Kataloge, Zellen, Regeln, Vorlagen, Startbelegung |
| `security/acl/AclRule.java`, `AclAction.java`, `AclRelation.java`, `AclObjectType.java`, `AclPreset.java` | Records |
| `security/acl/AclService.java` | Matrix, Regeln lesen/prüfen/ändern, Vorlagen, Eskalationsschutz |
| `security/CurrentUser.java` | zusätzlich `find()` |
| `web/AdminRoleController.java`, `templates/admin/roles.html`, `static/css/nestwerk.css` | ACL-Matrix in der Rollenverwaltung |
| `web/AdminUserController.java` | ACL-Eskalationsschutz bei Rollenzuweisung |

Alle Java-Pfade relativ zu `src/main/java/club/badernet/nestwerk/`, Ressourcen relativ zu `src/main/resources/`, Tests relativ zu `src/test/java/club/badernet/nestwerk/`.

---

### Task 1: Rechtekatalog in der Datenbank

**Files:**
- Create: `src/main/resources/db/migration/V4__permission_catalog.sql`
- Create: `src/main/java/club/badernet/nestwerk/security/PermissionDefinition.java`
- Modify (ersetzen): `src/main/java/club/badernet/nestwerk/security/PermissionCatalog.java`
- Modify: `security/Permission.java`, `security/AdminPermission.java`, `calendar/CalendarPermission.java`
- Modify: `role/Role.java`, `user/User.java`, `user/UserService.java`, `role/RoleService.java`, `calendar/CalendarEventService.java`, `web/AdminRoleController.java`, `templates/admin/roles.html`
- Test (ersetzen): `src/test/java/club/badernet/nestwerk/security/PermissionCatalogTest.java`
- Test (neu): `src/test/java/club/badernet/nestwerk/security/PermissionMigrationTest.java`

**Interfaces:**
- Produces:
  - `record PermissionDefinition(String key, String module, String label, String description, int sortOrder, boolean system)`
  - `@Component PermissionCatalog` (Bean-Name `permissionCatalog`):
    - `List<PermissionDefinition> all()` – sortiert nach `sort_order`
    - `Set<String> keys()` – alle Schlüssel (gecacht, Reihenfolge wie `all()`)
    - `boolean exists(String key)`
    - `Map<String, List<PermissionDefinition>> byModule()` – Module in Reihenfolge ihres ersten Eintrags
    - `Set<String> effective(Role role)` – Administrator-Rolle: `keys()`, sonst gespeicherte Rechte
    - `Set<String> effective(User user)` – Administrator: `keys()`, sonst Vereinigung der Rollen
    - `boolean isAdministrator(Collection<String> permissions)` – enthält alle `keys()`
    - `static Set<String> codeKeys()` – alle im Code verwendeten Schlüssel (Enums)
  - `Role.getPermissions()` liefert die gespeicherten Schlüssel ungefiltert; `Role.effectivePermissionNames()` und `User.permissionNames()` entfallen.

- [ ] **Step 1: Failing Tests schreiben**

`src/test/java/club/badernet/nestwerk/security/PermissionCatalogTest.java` (komplett ersetzen):

```java
package club.badernet.nestwerk.security;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class PermissionCatalogTest {

    @Autowired PermissionCatalog catalog;
    @Autowired RoleRepository roles;

    @Test
    void containsEveryKeyTheCodeUses() {
        assertThat(catalog.keys()).containsAll(PermissionCatalog.codeKeys());
    }

    @Test
    void groupsByModuleInSortOrder() {
        Map<String, List<PermissionDefinition>> byModule = catalog.byModule();

        assertThat(byModule.keySet()).containsExactly("Verwaltung", "Kalender");
        assertThat(byModule.get("Verwaltung")).extracting(PermissionDefinition::key)
                .containsExactly("USER_MANAGE", "ACCESS_ROLE_MANAGE", "MEMBER_MANAGE");
        assertThat(byModule.get("Kalender").getFirst().label()).isEqualTo("Kalender ansehen");
    }

    @Test
    void knowsWhichKeysExist() {
        assertThat(catalog.exists("USER_MANAGE")).isTrue();
        assertThat(catalog.exists("GIBT_ES_NICHT")).isFalse();
    }

    @Test
    void administratorRoleHasWholeCatalog() {
        Role admin = roles.findByAllPermissionsTrue().orElseThrow();

        assertThat(catalog.effective(admin)).isEqualTo(catalog.keys());
    }

    @Test
    void normalRoleHasItsStoredPermissions() {
        Role benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow();

        assertThat(catalog.effective(benutzer)).contains("CALENDAR_VIEW").doesNotContain("USER_MANAGE");
    }

    @Test
    void administratorIsWhoeverHoldsTheWholeCatalog() {
        assertThat(catalog.isAdministrator(catalog.keys())).isTrue();
        assertThat(catalog.isAdministrator(List.of("USER_MANAGE"))).isFalse();
    }
}
```

`src/test/java/club/badernet/nestwerk/security/PermissionMigrationTest.java`:

```java
package club.badernet.nestwerk.security;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
class PermissionMigrationTest {

    @Autowired DataSource dataSource;

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("migtest4")
                .defaultSchema("migtest4")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void dropsUnknownPermissionsAndEnforcesCatalog() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS migtest4 CASCADE");
        flyway("3").migrate();
        Long benutzer = jdbc.queryForObject("SELECT id FROM migtest4.roles WHERE name = 'Benutzer'", Long.class);
        jdbc.update("INSERT INTO migtest4.role_permissions (role_id, permission) VALUES (?, 'VERALTET'), (?, 'MEMBER_MANAGE')",
                benutzer, benutzer);

        flyway("4").migrate();

        assertThat(jdbc.queryForList("SELECT permission FROM migtest4.role_permissions WHERE role_id = ?",
                String.class, benutzer))
                .contains("MEMBER_MANAGE", "CALENDAR_VIEW")
                .doesNotContain("VERALTET");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO migtest4.role_permissions (role_id, permission) VALUES (?, 'NEU')", benutzer))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.execute("DROP SCHEMA migtest4 CASCADE");
    }
}
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag prüfen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=PermissionCatalogTest,PermissionMigrationTest`
Expected: FAIL – Kompilierfehler (`PermissionCatalog` ist keine Bean, `PermissionDefinition` existiert nicht).

- [ ] **Step 3: Migration V4 schreiben**

`src/main/resources/db/migration/V4__permission_catalog.sql`:

```sql
-- Rechtekatalog in der Datenbank; der Code kennt nur noch die Schlüssel.
CREATE TABLE permissions (
    key         VARCHAR(100) PRIMARY KEY,
    module      VARCHAR(100) NOT NULL,
    label       VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    sort_order  INT          NOT NULL,
    system      BOOLEAN      NOT NULL DEFAULT TRUE
);

INSERT INTO permissions (key, module, label, description, sort_order)
VALUES ('USER_MANAGE', 'Verwaltung', 'Benutzer verwalten',
        'Konten anlegen, Rollen zuweisen, Passwörter zurücksetzen', 10),
       ('ACCESS_ROLE_MANAGE', 'Verwaltung', 'Rollen und Rechte verwalten',
        'Rollen, Rechte und Zugriffsregeln bearbeiten', 20),
       ('MEMBER_MANAGE', 'Verwaltung', 'Familienmitglieder verwalten',
        'Mitglieder anlegen, Farben und Konten zuordnen', 30),
       ('CALENDAR_VIEW', 'Kalender', 'Kalender ansehen', NULL, 110),
       ('CALENDAR_EVENT_CREATE', 'Kalender', 'Termine anlegen', NULL, 120),
       ('CALENDAR_EVENT_EDIT_OWN', 'Kalender', 'Eigene Termine bearbeiten', NULL, 130),
       ('CALENDAR_EVENT_EDIT_ALL', 'Kalender', 'Alle Termine bearbeiten', NULL, 140),
       ('CALENDAR_EVENT_DELETE_OWN', 'Kalender', 'Eigene Termine löschen', NULL, 150),
       ('CALENDAR_EVENT_DELETE_ALL', 'Kalender', 'Alle Termine löschen', NULL, 160),
       ('CALENDAR_PRIVATE_CREATE', 'Kalender', 'Private Termine anlegen', NULL, 170);

-- Altdaten mit unbekannten Rechten entfernen, danach Fremdschlüssel erzwingen
DELETE FROM role_permissions WHERE permission NOT IN (SELECT key FROM permissions);

ALTER TABLE role_permissions
    ADD CONSTRAINT role_permissions_permission_fk
        FOREIGN KEY (permission) REFERENCES permissions (key) ON DELETE CASCADE;
```

- [ ] **Step 4: Rechte-Enums auf Schlüssel reduzieren**

`security/Permission.java`:

```java
package club.badernet.nestwerk.security;

/**
 * Schlüssel eines Rechts, wie ihn der Code prüft. Bezeichnung, Modul und Reihenfolge stehen in der
 * Tabelle {@code permissions}; jedes Modul liefert ein Enum, das dieses Interface implementiert.
 */
public interface Permission {

    /** Eindeutiger Schlüssel; wird in der DB und als Spring-Security-Authority verwendet. */
    String name();
}
```

`security/AdminPermission.java`:

```java
package club.badernet.nestwerk.security;

public enum AdminPermission implements Permission {
    USER_MANAGE,
    ACCESS_ROLE_MANAGE,
    MEMBER_MANAGE
}
```

`calendar/CalendarPermission.java`:

```java
package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.security.Permission;

public enum CalendarPermission implements Permission {
    CALENDAR_VIEW,
    CALENDAR_EVENT_CREATE,
    CALENDAR_EVENT_EDIT_OWN,
    CALENDAR_EVENT_EDIT_ALL,
    CALENDAR_EVENT_DELETE_OWN,
    CALENDAR_EVENT_DELETE_ALL,
    CALENDAR_PRIVATE_CREATE
}
```

- [ ] **Step 5: Katalog als Komponente**

`security/PermissionDefinition.java`:

```java
package club.badernet.nestwerk.security;

/** Ein Eintrag aus der Tabelle {@code permissions}. */
public record PermissionDefinition(String key, String module, String label, String description,
                                   int sortOrder, boolean system) {
}
```

`security/PermissionCatalog.java` (komplett ersetzen):

```java
package club.badernet.nestwerk.security;

import club.badernet.nestwerk.calendar.CalendarPermission;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Rechtekatalog aus der Tabelle {@code permissions} und Berechnung effektiver Rechte. */
@Component
public class PermissionCatalog {

    private final JdbcTemplate jdbc;
    private volatile Set<String> keys;

    public PermissionCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Alle Schlüssel, die der Code prüft. Jedes Modul-Enum wird hier ergänzt. */
    public static Set<String> codeKeys() {
        Set<String> keys = new LinkedHashSet<>();
        Stream.of(AdminPermission.values(), CalendarPermission.values())
                .flatMap(Arrays::stream)
                .forEach(p -> keys.add(p.name()));
        return keys;
    }

    public List<PermissionDefinition> all() {
        return jdbc.query("SELECT key, module, label, description, sort_order, system FROM permissions "
                        + "ORDER BY sort_order, key",
                (rs, i) -> new PermissionDefinition(rs.getString("key"), rs.getString("module"),
                        rs.getString("label"), rs.getString("description"), rs.getInt("sort_order"),
                        rs.getBoolean("system")));
    }

    /** Schlüssel ändern sich nur durch Migrationen, daher einmalig geladen. */
    public Set<String> keys() {
        Set<String> cached = keys;
        if (cached == null) {
            Set<String> loaded = new LinkedHashSet<>();
            all().forEach(p -> loaded.add(p.key()));
            cached = Set.copyOf(loaded);
            keys = cached;
        }
        return cached;
    }

    public boolean exists(String key) {
        return keys().contains(key);
    }

    public Map<String, List<PermissionDefinition>> byModule() {
        Map<String, List<PermissionDefinition>> grouped = new LinkedHashMap<>();
        for (PermissionDefinition p : all()) {
            grouped.computeIfAbsent(p.module(), k -> new ArrayList<>()).add(p);
        }
        return grouped;
    }

    public Set<String> effective(Role role) {
        return role.isAllPermissions() ? keys() : role.getPermissions();
    }

    public Set<String> effective(User user) {
        if (user.isAdministrator()) {
            return keys();
        }
        Set<String> names = new LinkedHashSet<>();
        user.getRoles().forEach(r -> names.addAll(r.getPermissions()));
        return names;
    }

    public boolean isAdministrator(Collection<String> permissions) {
        return permissions.containsAll(keys());
    }
}
```

Hinweis: `Set.copyOf` verliert die Reihenfolge; `keys()` wird nur für Mengenvergleiche genutzt, die Reihenfolge für die Anzeige kommt aus `all()`/`byModule()`.

- [ ] **Step 6: Role und User entkoppeln**

`role/Role.java`: Import `club.badernet.nestwerk.security.PermissionCatalog` und `java.util.LinkedHashSet` entfernen; `getPermissions()` ersetzen und `effectivePermissionNames()` löschen:

```java
    /** Gespeicherte Rechte; der Fremdschlüssel auf {@code permissions} garantiert, dass sie existieren. */
    public Set<String> getPermissions() {
        return Set.copyOf(permissions);
    }
```

`user/User.java`: Methode `permissionNames()` und den dann ungenutzten Import `java.util.LinkedHashSet` löschen.

- [ ] **Step 7: Aufrufer umstellen**

`user/UserService.java`:
- Konstruktor um `PermissionCatalog catalog` erweitern (Feld `private final PermissionCatalog catalog;`), Signatur: `public UserService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder, PermissionCatalog catalog)`.
- In `loadUserByUsername`: `user.permissionNames().stream()` → `catalog.effective(user).stream()`.
- In `requireMayManage`:

```java
        boolean actorIsAdministrator = catalog.isAdministrator(actorPermissions);
        ...
            if (!actorPermissions.containsAll(catalog.effective(target))) {
        ...
            if (role.isAllPermissions() || !actorPermissions.containsAll(catalog.effective(role))) {
```

- Import `club.badernet.nestwerk.security.PermissionCatalog` bleibt (jetzt Bean statt statischer Klasse).

`role/RoleService.java`:
- Konstruktor `public RoleService(RoleRepository roles, UserRepository users, PermissionCatalog catalog)` mit Feld `catalog`.
- `requireMayManage`:

```java
        if (catalog.isAdministrator(actorPermissions)) {
            return;
        }
        if (roleId != null && !actorPermissions.containsAll(catalog.effective(get(roleId)))) {
```

- `validPermissions`: `if (PermissionCatalog.find(p).isEmpty())` → `if (!catalog.exists(p))`.

`calendar/CalendarEventService.java`:
- Konstruktor um `PermissionCatalog permissions` erweitern (Feld `private final PermissionCatalog permissions;`, Import `club.badernet.nestwerk.security.PermissionCatalog`).
- `has` und `require`:

```java
    private boolean has(User user, CalendarPermission permission) {
        return permissions.effective(user).contains(permission.name());
    }

    private void require(User user, CalendarPermission permission) {
        if (!has(user, permission)) {
            throw new AccessDeniedException("Keine Berechtigung: " + permission.name());
        }
    }
```

`web/AdminRoleController.java`:
- Konstruktor `public AdminRoleController(RoleService roleService, PermissionCatalog catalog)` mit Feld `catalog`.
- In `list`: `PermissionCatalog.byModule()` → `catalog.byModule()`.

`templates/admin/roles.html`: alle drei Vorkommen von `role.effectivePermissionNames()` → `@permissionCatalog.effective(role)`; in beiden Rechte-Fieldsets `p.name()` → `p.key()` (je dreimal: `th:value`, `th:disabled`, `th:checked` bzw. `th:title`). Kontrolle: `grep -n "name()\|effectivePermissionNames" src/main/resources/templates/admin/roles.html` liefert nichts mehr.

- [ ] **Step 8: Tests laufen lassen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=PermissionCatalogTest,PermissionMigrationTest,RoleServiceTest,UserServiceTest,AdminRoleControllerTest,AdminUserControllerTest,CalendarEventServiceTest`
Expected: PASS (alle).

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V4__permission_catalog.sql src/main/java src/main/resources/templates/admin/roles.html src/test/java/club/badernet/nestwerk/security
git commit -m "feat: Rechtekatalog in der Datenbank

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Startprüfung des Rechtekatalogs

**Files:**
- Create: `src/main/java/club/badernet/nestwerk/security/PermissionCatalogVerifier.java`
- Test: `src/test/java/club/badernet/nestwerk/security/PermissionCatalogVerifierTest.java`

**Interfaces:**
- Consumes: `PermissionCatalog.keys()`, `PermissionCatalog.codeKeys()` (Task 1)
- Produces: `PermissionCatalogVerifier implements ApplicationRunner` mit `void verify(Collection<String> codeKeys)` – wirft `IllegalStateException`, deren Meldung alle fehlenden Schlüssel nennt.

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.security;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
class PermissionCatalogVerifierTest {

    @Autowired PermissionCatalogVerifier verifier;

    @Test
    void acceptsKeysThatExist() {
        assertThatCode(() -> verifier.verify(PermissionCatalog.codeKeys())).doesNotThrowAnyException();
    }

    @Test
    void namesMissingKeys() {
        assertThatThrownBy(() -> verifier.verify(List.of("USER_MANAGE", "FEHLT_NOCH", "AUCH_WEG")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FEHLT_NOCH")
                .hasMessageContaining("AUCH_WEG")
                .hasMessageNotContaining("USER_MANAGE");
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag prüfen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=PermissionCatalogVerifierTest`
Expected: FAIL – Kompilierfehler, `PermissionCatalogVerifier` fehlt.

- [ ] **Step 3: Implementieren**

```java
package club.badernet.nestwerk.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** Bricht den Start ab, wenn der Code Rechte prüft, die per Migration nie angelegt wurden. */
@Component
public class PermissionCatalogVerifier implements ApplicationRunner {

    private final PermissionCatalog catalog;

    public PermissionCatalogVerifier(PermissionCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify(PermissionCatalog.codeKeys());
    }

    void verify(Collection<String> codeKeys) {
        List<String> missing = codeKeys.stream().filter(k -> !catalog.exists(k)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Diese Rechte fehlen in der Tabelle permissions "
                    + "(Flyway-Migration vergessen?): " + String.join(", ", missing));
        }
    }
}
```

- [ ] **Step 4: Test laufen lassen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=PermissionCatalogVerifierTest,NestwerkApplicationTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/club/badernet/nestwerk/security/PermissionCatalogVerifier.java src/test/java/club/badernet/nestwerk/security/PermissionCatalogVerifierTest.java
git commit -m "feat: Startprüfung für den Rechtekatalog

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Bezeichnungen der Rechte pflegen

**Files:**
- Modify: `security/PermissionCatalog.java` (Methode `updateText`)
- Create: `web/AdminPermissionController.java`, `templates/admin/permissions.html`
- Modify: `templates/admin/roles.html` (Link), `static/css/nestwerk.css`
- Test: `src/test/java/club/badernet/nestwerk/web/AdminPermissionControllerTest.java`

**Interfaces:**
- Consumes: `PermissionCatalog.byModule()` (Task 1)
- Produces: `void PermissionCatalog.updateText(String key, String label, String description)`; Seiten `GET /admin/permissions`, `POST /admin/permissions/{key}` (Recht `ACCESS_ROLE_MANAGE`).

- [ ] **Step 1: Failing Test schreiben**

```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.security.PermissionDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class AdminPermissionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired PermissionCatalog catalog;

    private String label(String key) {
        return catalog.all().stream().filter(p -> p.key().equals(key)).findFirst()
                .map(PermissionDefinition::label).orElseThrow();
    }

    @Test
    @WithMockUser(authorities = "USER_MANAGE")
    void forbiddenWithoutRolePermission() throws Exception {
        mockMvc.perform(get("/admin/permissions")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void listsPermissionsByModule() throws Exception {
        mockMvc.perform(get("/admin/permissions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Verwaltung")))
                .andExpect(content().string(containsString("Kalender ansehen")));
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void renamesPermissionAndRolePageShowsNewLabel() throws Exception {
        mockMvc.perform(post("/admin/permissions/{key}", "CALENDAR_VIEW").with(csrf())
                        .param("label", "Familienkalender lesen").param("description", "Nur ansehen"))
                .andExpect(flash().attributeExists("success"));

        assertThat(label("CALENDAR_VIEW")).isEqualTo("Familienkalender lesen");
        mockMvc.perform(get("/admin/roles"))
                .andExpect(content().string(containsString("Familienkalender lesen")));
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void blankLabelIsRejected() throws Exception {
        mockMvc.perform(post("/admin/permissions/{key}", "CALENDAR_VIEW").with(csrf()).param("label", "  "))
                .andExpect(flash().attributeExists("error"));

        assertThat(label("CALENDAR_VIEW")).isEqualTo("Kalender ansehen");
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void unknownKeyIsRejected() throws Exception {
        mockMvc.perform(post("/admin/permissions/{key}", "GIBT_ES_NICHT").with(csrf()).param("label", "x"))
                .andExpect(flash().attributeExists("error"));
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag prüfen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=AdminPermissionControllerTest`
Expected: FAIL – `GET /admin/permissions` liefert 404 statt 200/403.

- [ ] **Step 3: `updateText` ergänzen**

In `PermissionCatalog` (Import `org.springframework.transaction.annotation.Transactional`):

```java
    @Transactional
    public void updateText(String key, String label, String description) {
        String cleanLabel = label == null ? "" : label.trim();
        if (cleanLabel.isEmpty() || cleanLabel.length() > 100) {
            throw new IllegalArgumentException("Die Bezeichnung muss 1–100 Zeichen lang sein.");
        }
        String cleanDescription = description == null || description.isBlank() ? null : description.trim();
        if (cleanDescription != null && cleanDescription.length() > 255) {
            throw new IllegalArgumentException("Die Beschreibung darf höchstens 255 Zeichen lang sein.");
        }
        int updated = jdbc.update("UPDATE permissions SET label = ?, description = ? WHERE key = ?",
                cleanLabel, cleanDescription, key);
        if (updated == 0) {
            throw new IllegalArgumentException("Recht nicht gefunden");
        }
    }
```

- [ ] **Step 4: Controller**

`web/AdminPermissionController.java`:

```java
package club.badernet.nestwerk.web;

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

@Controller
@RequestMapping("/admin/permissions")
@PreAuthorize("hasAuthority('ACCESS_ROLE_MANAGE')")
public class AdminPermissionController {

    private final PermissionCatalog catalog;

    public AdminPermissionController(PermissionCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("permissionsByModule", catalog.byModule());
        return "admin/permissions";
    }

    @PostMapping("/{key}")
    String update(@PathVariable String key, @RequestParam(required = false) String label,
                  @RequestParam(required = false) String description, RedirectAttributes redirect) {
        try {
            catalog.updateText(key, label, description);
            redirect.addFlashAttribute("success", "Recht wurde gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/permissions";
    }
}
```

- [ ] **Step 5: Template und Link**

`templates/admin/permissions.html`:

```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head('Rechte')}"></head>
<body>
<header th:replace="~{fragments/layout :: nav(active='roles')}"></header>
<main>
    <a class="back-link" th:href="@{/admin/roles}">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 6-6 6 6 6"/></svg>
        Zu den Rollen
    </a>
    <div class="page-head">
        <div>
            <h1>Rechte</h1>
            <p>Bezeichnungen und Beschreibungen, wie sie in der Rollenverwaltung erscheinen.</p>
        </div>
    </div>

    <p hidden data-toast="success" th:if="${success}" th:text="${success}"></p>
    <p hidden data-toast="error" th:if="${error}" th:text="${error}"></p>

    <div class="stack">
        <section class="sheet permission-module" th:each="entry : ${permissionsByModule}">
            <h2 th:text="${entry.key}">Modul</h2>
            <form class="permission-row" th:each="p : ${entry.value}"
                  th:action="@{/admin/permissions/{key}(key=${p.key()})}" method="post">
                <span class="permission-key" th:text="${p.key()}">USER_MANAGE</span>
                <div class="field">
                    <label th:for="'label-' + ${p.key()}">Bezeichnung</label>
                    <input th:id="'label-' + ${p.key()}" type="text" name="label" maxlength="100"
                           th:value="${p.label()}" required>
                </div>
                <div class="field">
                    <label th:for="'desc-' + ${p.key()}">Beschreibung <span class="hint">optional</span></label>
                    <input th:id="'desc-' + ${p.key()}" type="text" name="description" maxlength="255"
                           th:value="${p.description()}">
                </div>
                <button type="submit" class="button button-small">Speichern</button>
            </form>
        </section>
    </div>
</main>
<div th:replace="~{fragments/layout :: toasts}"></div>
</body>
</html>
```

In `templates/admin/roles.html` im Block `<div class="page-actions">` vor dem Knopf „Rolle anlegen“ einfügen:

```html
            <a class="button button-ghost" th:href="@{/admin/permissions}">Rechte benennen</a>
```

An `static/css/nestwerk.css` anhängen (vor dem Abschnitt `/* --- Animationen`):

```css
/* --- Rechtekatalog ------------------------------------------------------- */

.permission-module { padding: clamp(1.25rem, 3vw, 2rem); display: grid; gap: 1rem; }
.permission-module h2 { font-size: var(--step-1); margin: 0; }
.permission-row {
    display: grid;
    grid-template-columns: 1fr 1.5fr auto;
    gap: 0.75rem;
    align-items: end;
    padding-top: 1rem;
    border-top: 1px solid var(--line);
}
.permission-row .field { margin: 0; }
.permission-key { grid-column: 1 / -1; font-size: var(--step--1); color: var(--ink-soft); }
@media (max-width: 40rem) {
    .permission-row { grid-template-columns: 1fr; }
}
```

- [ ] **Step 6: Tests laufen lassen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=AdminPermissionControllerTest,AdminRoleControllerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/club/badernet/nestwerk/security/PermissionCatalog.java src/main/java/club/badernet/nestwerk/web/AdminPermissionController.java src/main/resources/templates/admin src/main/resources/static/css/nestwerk.css src/test/java/club/badernet/nestwerk/web/AdminPermissionControllerTest.java
git commit -m "feat: Bezeichnungen der Rechte in der Oberfläche pflegen

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: ACL-Kataloge und Regelauswertung

**Files:**
- Create: `src/main/resources/db/migration/V5__acl.sql`
- Create: `security/acl/AclRule.java`, `AclAction.java`, `AclRelation.java`, `AclObjectType.java`, `AclPreset.java`, `AclService.java`
- Test: `src/test/java/club/badernet/nestwerk/security/acl/AclServiceTest.java`

**Interfaces:**
- Consumes: `PermissionCatalog.isAdministrator(Collection<String>)` (Task 1), `User.isAdministrator()`, `User.getId()`, `RoleRepository`
- Produces:
  - `record AclRule(String objectType, String action, String relation)` mit `static AclRule parse(String value)` (Format `TYP:AKTION:BEZIEHUNG`, sonst `IllegalArgumentException("Ungültige Zugriffsregel: " + value)`) und `String key()`
  - `record AclAction(String key, String label)`, `record AclRelation(String key, String label)`, `record AclPreset(String key, String label)`
  - `record AclObjectType(String key, String label, List<AclAction> actions, List<AclRelation> relations, Set<String> cells)` mit `boolean allows(String action, String relation)` (`cells` enthält `"AKTION:BEZIEHUNG"`)
  - `@Service AclService`:
    - `List<AclObjectType> matrix()`
    - `Set<AclRule> cells()` – alle gültigen Kombinationen
    - `Set<AclRule> rulesOf(Long roleId)`
    - `Map<Long, Set<String>> ruleKeysByRole()` – nur Rollen mit Regeln
    - `Set<AclRule> rulesOf(User user)` – Administrator: `cells()`
    - `boolean permits(User user, String objectType, String action, Set<String> relations)`
    - `Set<String> grantedRelations(User user, String objectType, String action)`
    - `List<AclPreset> presets()`
  - Konstanten der Objekttypen/Beziehungen sind Strings: `TODO`, `CONTACT`; `ANY`, `RESPONSIBLE`, `ASSIGNED`, `WATCHING`, `CREATOR`.

- [ ] **Step 1: Failing Test schreiben**

`src/test/java/club/badernet/nestwerk/security/acl/AclServiceTest.java`:

```java
package club.badernet.nestwerk.security.acl;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class AclServiceTest {

    @Autowired AclService acl;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired JdbcTemplate jdbc;

    private User benutzer(String name) {
        Role role = roles.findByNameIgnoreCase("Benutzer").orElseThrow();
        return userService.create(name, "passwort1", Set.of(role.getId()));
    }

    @Test
    void matrixListsTodoAndContactActions() {
        List<AclObjectType> matrix = acl.matrix();

        assertThat(matrix).extracting(AclObjectType::key).containsExactly("TODO", "CONTACT");
        assertThat(matrix.get(0).actions()).hasSize(14);
        assertThat(matrix.get(0).relations()).extracting(AclRelation::key)
                .containsExactly("ANY", "RESPONSIBLE", "ASSIGNED", "WATCHING", "CREATOR");
        assertThat(matrix.get(1).actions()).extracting(AclAction::key)
                .containsExactly("VIEW", "CREATE", "EDIT", "DELETE", "ATTACH");
        assertThat(matrix.get(1).relations()).extracting(AclRelation::key).containsExactly("ANY", "CREATOR");
    }

    @Test
    void creatingIsOnlyPossibleForAny() {
        AclObjectType todo = acl.matrix().getFirst();

        assertThat(todo.allows("CREATE", "ANY")).isTrue();
        assertThat(todo.allows("CREATE", "RESPONSIBLE")).isFalse();
        assertThat(todo.allows("EDIT", "RESPONSIBLE")).isTrue();
    }

    @Test
    void benutzerRoleIsSeeded() {
        User user = benutzer("normal");

        assertThat(acl.permits(user, "TODO", "VIEW", Set.of("ANY"))).isTrue();
        assertThat(acl.permits(user, "TODO", "EDIT", Set.of("ANY"))).isFalse();
        assertThat(acl.permits(user, "TODO", "EDIT", Set.of("ANY", "RESPONSIBLE"))).isTrue();
        assertThat(acl.grantedRelations(user, "TODO", "CHANGE_STATUS"))
                .containsExactlyInAnyOrder("RESPONSIBLE", "ASSIGNED");
        assertThat(acl.permits(user, "CONTACT", "EDIT", Set.of("ANY", "CREATOR"))).isFalse();
    }

    @Test
    void administratorMayDoEverything() {
        User admin = userService.createAdministrator("chefin", "passwort1");

        assertThat(acl.permits(admin, "TODO", "CHANGE_RESPONSIBLE", Set.of("ANY"))).isTrue();
        assertThat(acl.grantedRelations(admin, "TODO", "EDIT"))
                .containsExactlyInAnyOrder("ANY", "RESPONSIBLE", "ASSIGNED", "WATCHING", "CREATOR");
        assertThat(acl.rulesOf(admin)).isEqualTo(acl.cells());
    }

    @Test
    void userWithoutRolesMayDoNothing() {
        User nobody = userService.create("niemand", "passwort1", Set.of());

        assertThat(acl.permits(nobody, "TODO", "VIEW", Set.of("ANY"))).isFalse();
        assertThat(acl.grantedRelations(nobody, "TODO", "VIEW")).isEmpty();
    }

    @Test
    void presetsAreSeeded() {
        assertThat(acl.presets()).extracting(AclPreset::key).containsExactly("KIND", "ELTERNTEIL", "GAST");
    }

    @Test
    void parsesAndFormatsRules() {
        AclRule rule = AclRule.parse("TODO:EDIT:RESPONSIBLE");

        assertThat(rule).isEqualTo(new AclRule("TODO", "EDIT", "RESPONSIBLE"));
        assertThat(rule.key()).isEqualTo("TODO:EDIT:RESPONSIBLE");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AclRule.parse("TODO:EDIT"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingRoleRemovesItsRules() {
        Role role = roleService.create("Vergänglich", null, Set.of());
        jdbc.update("INSERT INTO acl_rules (role_id, object_type, action, relation) VALUES (?, 'TODO', 'VIEW', 'ANY')",
                role.getId());

        roleService.delete(role.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM acl_rules WHERE role_id = ?", Integer.class,
                role.getId())).isZero();
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag prüfen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=AclServiceTest`
Expected: FAIL – Kompilierfehler, Paket `security.acl` fehlt.

- [ ] **Step 3: Migration V5 schreiben**

`src/main/resources/db/migration/V5__acl.sql`:

```sql
-- Regelbasiertes ACL: Rolle darf Aktion auf Objekttyp bei Beziehung.
CREATE TABLE acl_object_types (
    key        VARCHAR(50) PRIMARY KEY,
    label      VARCHAR(100) NOT NULL,
    sort_order INT          NOT NULL
);

CREATE TABLE acl_actions (
    object_type VARCHAR(50)  NOT NULL REFERENCES acl_object_types (key),
    key         VARCHAR(50)  NOT NULL,
    label       VARCHAR(100) NOT NULL,
    sort_order  INT          NOT NULL,
    PRIMARY KEY (object_type, key)
);

CREATE TABLE acl_relations (
    key        VARCHAR(50) PRIMARY KEY,
    label      VARCHAR(100) NOT NULL,
    sort_order INT          NOT NULL
);

-- Gültige Kombinationen (Zellen der Matrix)
CREATE TABLE acl_cells (
    object_type VARCHAR(50) NOT NULL,
    action      VARCHAR(50) NOT NULL,
    relation    VARCHAR(50) NOT NULL REFERENCES acl_relations (key),
    PRIMARY KEY (object_type, action, relation),
    FOREIGN KEY (object_type, action) REFERENCES acl_actions (object_type, key)
);

CREATE TABLE acl_rules (
    role_id     BIGINT      NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    object_type VARCHAR(50) NOT NULL,
    action      VARCHAR(50) NOT NULL,
    relation    VARCHAR(50) NOT NULL,
    PRIMARY KEY (role_id, object_type, action, relation),
    FOREIGN KEY (object_type, action, relation) REFERENCES acl_cells (object_type, action, relation)
);

CREATE TABLE acl_presets (
    key        VARCHAR(50) PRIMARY KEY,
    label      VARCHAR(100) NOT NULL,
    sort_order INT          NOT NULL
);

CREATE TABLE acl_preset_rules (
    preset_key  VARCHAR(50) NOT NULL REFERENCES acl_presets (key) ON DELETE CASCADE,
    object_type VARCHAR(50) NOT NULL,
    action      VARCHAR(50) NOT NULL,
    relation    VARCHAR(50) NOT NULL,
    PRIMARY KEY (preset_key, object_type, action, relation),
    FOREIGN KEY (object_type, action, relation) REFERENCES acl_cells (object_type, action, relation)
);

INSERT INTO acl_object_types (key, label, sort_order)
VALUES ('TODO', 'ToDos', 10),
       ('CONTACT', 'Kontakte', 20);

INSERT INTO acl_relations (key, label, sort_order)
VALUES ('ANY', 'Alle', 10),
       ('RESPONSIBLE', 'Verantwortlich', 20),
       ('ASSIGNED', 'Zuständig', 30),
       ('WATCHING', 'Beobachtend', 40),
       ('CREATOR', 'Ersteller', 50);

INSERT INTO acl_actions (object_type, key, label, sort_order)
VALUES ('TODO', 'VIEW', 'Ansehen', 10),
       ('TODO', 'CREATE', 'Anlegen', 20),
       ('TODO', 'CREATE_PRIVATE', 'Privat anlegen', 30),
       ('TODO', 'EDIT', 'Bearbeiten', 40),
       ('TODO', 'DELETE', 'Löschen', 50),
       ('TODO', 'CHANGE_STATUS', 'Status ändern und abhaken', 60),
       ('TODO', 'ASSIGN', 'Zuständige ändern', 70),
       ('TODO', 'CHANGE_RESPONSIBLE', 'Verantwortlichen ändern', 80),
       ('TODO', 'WATCH', 'Selbst beobachten', 90),
       ('TODO', 'MANAGE_WATCHERS', 'Beobachter verwalten', 100),
       ('TODO', 'COMMENT', 'Kommentieren', 110),
       ('TODO', 'ATTACH', 'Anhänge hochladen', 120),
       ('TODO', 'LINK', 'Verknüpfen', 130),
       ('TODO', 'NOTIFY_OVERRIDE', 'Benachrichtigungen überschreiben', 140),
       ('CONTACT', 'VIEW', 'Ansehen', 10),
       ('CONTACT', 'CREATE', 'Anlegen', 20),
       ('CONTACT', 'EDIT', 'Bearbeiten', 30),
       ('CONTACT', 'DELETE', 'Löschen', 40),
       ('CONTACT', 'ATTACH', 'Anhänge hochladen', 50);

-- Anlegen hat noch keine Beziehung, daher nur "Alle"; Kontakte kennen nur "Alle" und "Ersteller".
INSERT INTO acl_cells (object_type, action, relation)
SELECT a.object_type, a.key, r.key
FROM acl_actions a
         CROSS JOIN acl_relations r
WHERE (a.object_type = 'TODO' AND (a.key NOT IN ('CREATE', 'CREATE_PRIVATE') OR r.key = 'ANY'))
   OR (a.object_type = 'CONTACT' AND r.key IN ('ANY', 'CREATOR') AND (a.key <> 'CREATE' OR r.key = 'ANY'));

INSERT INTO acl_presets (key, label, sort_order)
VALUES ('KIND', 'Kind', 10),
       ('ELTERNTEIL', 'Elternteil', 20),
       ('GAST', 'Gast (nur lesen)', 30);

INSERT INTO acl_preset_rules (preset_key, object_type, action, relation)
VALUES ('KIND', 'TODO', 'VIEW', 'RESPONSIBLE'),
       ('KIND', 'TODO', 'VIEW', 'ASSIGNED'),
       ('KIND', 'TODO', 'VIEW', 'WATCHING'),
       ('KIND', 'TODO', 'CREATE', 'ANY'),
       ('KIND', 'TODO', 'EDIT', 'RESPONSIBLE'),
       ('KIND', 'TODO', 'CHANGE_STATUS', 'RESPONSIBLE'),
       ('KIND', 'TODO', 'CHANGE_STATUS', 'ASSIGNED'),
       ('KIND', 'TODO', 'WATCH', 'RESPONSIBLE'),
       ('KIND', 'TODO', 'WATCH', 'ASSIGNED'),
       ('KIND', 'TODO', 'COMMENT', 'RESPONSIBLE'),
       ('KIND', 'TODO', 'COMMENT', 'ASSIGNED'),
       ('KIND', 'TODO', 'COMMENT', 'WATCHING'),

       ('ELTERNTEIL', 'TODO', 'VIEW', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'VIEW', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'VIEW', 'ASSIGNED'),
       ('ELTERNTEIL', 'TODO', 'VIEW', 'WATCHING'),
       ('ELTERNTEIL', 'TODO', 'CREATE', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'CREATE_PRIVATE', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'EDIT', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'EDIT', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'DELETE', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'CHANGE_STATUS', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'CHANGE_STATUS', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'CHANGE_STATUS', 'ASSIGNED'),
       ('ELTERNTEIL', 'TODO', 'ASSIGN', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'ASSIGN', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'WATCH', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'WATCH', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'WATCH', 'ASSIGNED'),
       ('ELTERNTEIL', 'TODO', 'MANAGE_WATCHERS', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'COMMENT', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'COMMENT', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'COMMENT', 'ASSIGNED'),
       ('ELTERNTEIL', 'TODO', 'COMMENT', 'WATCHING'),
       ('ELTERNTEIL', 'TODO', 'ATTACH', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'ATTACH', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'TODO', 'ATTACH', 'ASSIGNED'),
       ('ELTERNTEIL', 'TODO', 'ATTACH', 'WATCHING'),
       ('ELTERNTEIL', 'TODO', 'LINK', 'ANY'),
       ('ELTERNTEIL', 'TODO', 'LINK', 'RESPONSIBLE'),
       ('ELTERNTEIL', 'CONTACT', 'VIEW', 'ANY'),
       ('ELTERNTEIL', 'CONTACT', 'CREATE', 'ANY'),
       ('ELTERNTEIL', 'CONTACT', 'EDIT', 'ANY'),
       ('ELTERNTEIL', 'CONTACT', 'ATTACH', 'ANY'),

       ('GAST', 'TODO', 'VIEW', 'ANY'),
       ('GAST', 'CONTACT', 'VIEW', 'ANY');

-- Startbelegung der Rolle "Benutzer". Private ToDos sind nur über die beteiligten Beziehungen
-- erreichbar, daher stehen VIEW/COMMENT/ATTACH/WATCH auch für diese Beziehungen.
INSERT INTO acl_rules (role_id, object_type, action, relation)
SELECT r.id, v.object_type, v.action, v.relation
FROM roles r
         CROSS JOIN (VALUES ('TODO', 'VIEW', 'ANY'),
                            ('TODO', 'VIEW', 'RESPONSIBLE'),
                            ('TODO', 'VIEW', 'ASSIGNED'),
                            ('TODO', 'VIEW', 'WATCHING'),
                            ('TODO', 'CREATE', 'ANY'),
                            ('TODO', 'CREATE_PRIVATE', 'ANY'),
                            ('TODO', 'EDIT', 'RESPONSIBLE'),
                            ('TODO', 'DELETE', 'RESPONSIBLE'),
                            ('TODO', 'LINK', 'RESPONSIBLE'),
                            ('TODO', 'ASSIGN', 'RESPONSIBLE'),
                            ('TODO', 'CHANGE_STATUS', 'RESPONSIBLE'),
                            ('TODO', 'CHANGE_STATUS', 'ASSIGNED'),
                            ('TODO', 'COMMENT', 'ANY'),
                            ('TODO', 'COMMENT', 'RESPONSIBLE'),
                            ('TODO', 'COMMENT', 'ASSIGNED'),
                            ('TODO', 'COMMENT', 'WATCHING'),
                            ('TODO', 'ATTACH', 'ANY'),
                            ('TODO', 'ATTACH', 'RESPONSIBLE'),
                            ('TODO', 'ATTACH', 'ASSIGNED'),
                            ('TODO', 'ATTACH', 'WATCHING'),
                            ('TODO', 'WATCH', 'ANY'),
                            ('TODO', 'WATCH', 'RESPONSIBLE'),
                            ('TODO', 'WATCH', 'ASSIGNED'),
                            ('TODO', 'WATCH', 'WATCHING'),
                            ('CONTACT', 'VIEW', 'ANY')) AS v (object_type, action, relation)
WHERE r.name = 'Benutzer';
```

- [ ] **Step 4: Records anlegen**

`security/acl/AclRule.java`:

```java
package club.badernet.nestwerk.security.acl;

/** Eine Zugriffsregel bzw. Zelle der Matrix: Aktion auf Objekttyp bei Beziehung. */
public record AclRule(String objectType, String action, String relation) {

    /** Liest das Format {@code TYP:AKTION:BEZIEHUNG}, wie es die Matrix sendet. */
    public static AclRule parse(String value) {
        String[] parts = value == null ? new String[0] : value.split(":", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("Ungültige Zugriffsregel: " + value);
        }
        return new AclRule(parts[0], parts[1], parts[2]);
    }

    public String key() {
        return objectType + ":" + action + ":" + relation;
    }
}
```

`security/acl/AclAction.java`:

```java
package club.badernet.nestwerk.security.acl;

public record AclAction(String key, String label) {
}
```

`security/acl/AclRelation.java`:

```java
package club.badernet.nestwerk.security.acl;

public record AclRelation(String key, String label) {
}
```

`security/acl/AclPreset.java`:

```java
package club.badernet.nestwerk.security.acl;

public record AclPreset(String key, String label) {
}
```

`security/acl/AclObjectType.java`:

```java
package club.badernet.nestwerk.security.acl;

import java.util.List;
import java.util.Set;

/** Ein Objekttyp mit seinen Aktionen, den dafür sinnvollen Beziehungen und gültigen Zellen. */
public record AclObjectType(String key, String label, List<AclAction> actions,
                            List<AclRelation> relations, Set<String> cells) {

    public boolean allows(String action, String relation) {
        return cells.contains(action + ":" + relation);
    }
}
```

- [ ] **Step 5: `AclService` (lesender Teil)**

`security/acl/AclService.java`:

```java
package club.badernet.nestwerk.security.acl;

import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Zugriffsregeln: Matrix lesen, Regeln prüfen und (mit Eskalationsschutz) ändern. */
@Service
public class AclService {

    private static final RowMapper<AclRule> RULE = (rs, i) ->
            new AclRule(rs.getString("object_type"), rs.getString("action"), rs.getString("relation"));

    private final NamedParameterJdbcTemplate jdbc;
    private final RoleRepository roles;
    private final PermissionCatalog permissions;

    public AclService(NamedParameterJdbcTemplate jdbc, RoleRepository roles, PermissionCatalog permissions) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public List<AclObjectType> matrix() {
        List<AclRelation> relations = jdbc.query("SELECT key, label FROM acl_relations ORDER BY sort_order",
                (rs, i) -> new AclRelation(rs.getString("key"), rs.getString("label")));
        List<String[]> types = jdbc.query("SELECT key, label FROM acl_object_types ORDER BY sort_order",
                (rs, i) -> new String[]{rs.getString("key"), rs.getString("label")});
        Set<AclRule> cells = cells();
        List<AclObjectType> result = new ArrayList<>();
        for (String[] type : types) {
            List<AclAction> actions = jdbc.query(
                    "SELECT key, label FROM acl_actions WHERE object_type = :type ORDER BY sort_order",
                    Map.of("type", type[0]), (rs, i) -> new AclAction(rs.getString("key"), rs.getString("label")));
            Set<String> typeCells = new HashSet<>();
            Set<String> usedRelations = new HashSet<>();
            for (AclRule cell : cells) {
                if (cell.objectType().equals(type[0])) {
                    typeCells.add(cell.action() + ":" + cell.relation());
                    usedRelations.add(cell.relation());
                }
            }
            List<AclRelation> typeRelations = relations.stream().filter(r -> usedRelations.contains(r.key())).toList();
            result.add(new AclObjectType(type[0], type[1], actions, typeRelations, Set.copyOf(typeCells)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Set<AclRule> cells() {
        return new HashSet<>(jdbc.query("SELECT object_type, action, relation FROM acl_cells", RULE));
    }

    @Transactional(readOnly = true)
    public Set<AclRule> rulesOf(Long roleId) {
        return new HashSet<>(jdbc.query(
                "SELECT object_type, action, relation FROM acl_rules WHERE role_id = :id", Map.of("id", roleId), RULE));
    }

    /** Regelschlüssel je Rolle; Rollen ohne Regeln fehlen in der Map. */
    @Transactional(readOnly = true)
    public Map<Long, Set<String>> ruleKeysByRole() {
        Map<Long, Set<String>> result = new HashMap<>();
        jdbc.query("SELECT role_id, object_type, action, relation FROM acl_rules", rs -> {
            AclRule rule = RULE.mapRow(rs, 0);
            result.computeIfAbsent(rs.getLong("role_id"), k -> new LinkedHashSet<>()).add(rule.key());
        });
        return result;
    }

    @Transactional(readOnly = true)
    public Set<AclRule> rulesOf(User user) {
        if (user.isAdministrator()) {
            return cells();
        }
        return new HashSet<>(jdbc.query("SELECT DISTINCT a.object_type, a.action, a.relation FROM acl_rules a "
                + "JOIN user_roles ur ON ur.role_id = a.role_id WHERE ur.user_id = :id", Map.of("id", user.getId()), RULE));
    }

    /** Erlaubt, wenn eine Regel der Rollen des Benutzers für eine der vorliegenden Beziehungen passt. */
    public boolean permits(User user, String objectType, String action, Set<String> relations) {
        return grantedRelations(user, objectType, action).stream().anyMatch(relations::contains);
    }

    /** Beziehungen, bei denen der Benutzer die Aktion ausführen darf (Grundlage für Listenabfragen). */
    @Transactional(readOnly = true)
    public Set<String> grantedRelations(User user, String objectType, String action) {
        Set<String> result = new LinkedHashSet<>();
        for (AclRule rule : rulesOf(user)) {
            if (rule.objectType().equals(objectType) && rule.action().equals(action)) {
                result.add(rule.relation());
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<AclPreset> presets() {
        return jdbc.query("SELECT key, label FROM acl_presets ORDER BY sort_order",
                (rs, i) -> new AclPreset(rs.getString("key"), rs.getString("label")));
    }
}
```

Hinweis: `roles` und `permissions` werden erst in Task 5 benutzt; sie stehen schon im Konstruktor, damit Task 5 nur Methoden ergänzt.

- [ ] **Step 6: Test laufen lassen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=AclServiceTest,PermissionMigrationTest,RoleMigrationTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V5__acl.sql src/main/java/club/badernet/nestwerk/security/acl src/test/java/club/badernet/nestwerk/security/acl
git commit -m "feat: ACL-Kataloge, Vorlagen und Regelauswertung

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Regeln ändern, Vorlagen anwenden, Eskalationsschutz

**Files:**
- Modify: `security/acl/AclService.java`
- Modify: `web/AdminUserController.java`
- Test: `src/test/java/club/badernet/nestwerk/security/acl/AclServiceChangeTest.java`
- Test: `src/test/java/club/badernet/nestwerk/web/AdminUserControllerTest.java` (ein Test ergänzt)

**Interfaces:**
- Consumes: `AclService.cells()`, `rulesOf(Long)`, `rulesOf(User)`, `presets()` (Task 4); `PermissionCatalog.isAdministrator` (Task 1)
- Produces:
  - `void AclService.replaceRules(User actor, Long roleId, Set<AclRule> rules)`
  - `void AclService.applyPreset(User actor, Long roleId, String presetKey)`
  - `void AclService.requireMayAssign(String actorUsername, Collection<String> actorPermissions, Long targetUserId, Set<Long> roleIds)`

- [ ] **Step 1: Failing Tests schreiben**

`src/test/java/club/badernet/nestwerk/security/acl/AclServiceChangeTest.java`:

```java
package club.badernet.nestwerk.security.acl;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class AclServiceChangeTest {

    private static final AclRule VIEW_ANY = new AclRule("TODO", "VIEW", "ANY");
    private static final AclRule EDIT_ANY = new AclRule("TODO", "EDIT", "ANY");

    @Autowired AclService acl;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired PermissionCatalog catalog;

    User admin;
    Role kind;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        kind = roleService.create("Kind", null, Set.of());
    }

    /** Rollenverwalter ohne Administratorrechte, der selbst nur TODO:VIEW:ANY hat. */
    private User manager() {
        Role verwalter = roleService.create("Verwalter", null, Set.of("ACCESS_ROLE_MANAGE"));
        acl.replaceRules(admin, verwalter.getId(), Set.of(VIEW_ANY));
        return userService.create("verwalter", "passwort1", Set.of(verwalter.getId()));
    }

    @Test
    void replacesRulesExactly() {
        acl.replaceRules(admin, kind.getId(), Set.of(VIEW_ANY, EDIT_ANY));
        acl.replaceRules(admin, kind.getId(), Set.of(VIEW_ANY));

        assertThat(acl.rulesOf(kind.getId())).containsExactly(VIEW_ANY);
    }

    @Test
    void rejectsCombinationsThatAreNoCell() {
        assertThatThrownBy(() -> acl.replaceRules(admin, kind.getId(), Set.of(new AclRule("TODO", "CREATE", "RESPONSIBLE"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TODO:CREATE:RESPONSIBLE");
        assertThatThrownBy(() -> acl.replaceRules(admin, kind.getId(), Set.of(new AclRule("X", "Y", "Z"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void administratorRoleCannotGetRules() {
        Role adminRole = roles.findByAllPermissionsTrue().orElseThrow();

        assertThatThrownBy(() -> acl.replaceRules(admin, adminRole.getId(), Set.of(VIEW_ANY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonAdministratorMayGrantOwnRules() {
        acl.replaceRules(manager(), kind.getId(), Set.of(VIEW_ANY));

        assertThat(acl.rulesOf(kind.getId())).containsExactly(VIEW_ANY);
    }

    @Test
    void nonAdministratorCannotGrantRulesBeyondOwn() {
        User manager = manager();

        assertThatThrownBy(() -> acl.replaceRules(manager, kind.getId(), Set.of(VIEW_ANY, EDIT_ANY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selbst hast");
        assertThat(acl.rulesOf(kind.getId())).isEmpty();
    }

    @Test
    void nonAdministratorCannotChangeRoleWithMoreRules() {
        acl.replaceRules(admin, kind.getId(), Set.of(VIEW_ANY, EDIT_ANY));
        User manager = manager();

        assertThatThrownBy(() -> acl.replaceRules(manager, kind.getId(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(acl.rulesOf(kind.getId())).hasSize(2);
    }

    @Test
    void applyingPresetReplacesRules() {
        acl.replaceRules(admin, kind.getId(), Set.of(EDIT_ANY));

        acl.applyPreset(admin, kind.getId(), "GAST");

        assertThat(acl.rulesOf(kind.getId()))
                .containsExactlyInAnyOrder(VIEW_ANY, new AclRule("CONTACT", "VIEW", "ANY"));
    }

    @Test
    void unknownPresetIsRejected() {
        assertThatThrownBy(() -> acl.applyPreset(admin, kind.getId(), "GIBT_ES_NICHT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vorlage");
    }

    @Test
    void assigningRoleWithForeignRulesIsRejectedForNonAdministrators() {
        acl.replaceRules(admin, kind.getId(), Set.of(EDIT_ANY));
        User manager = manager();

        assertThatThrownBy(() -> acl.requireMayAssign(manager.getUsername(), Set.of("USER_MANAGE"), null,
                Set.of(kind.getId())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void administratorsMayAssignAnyRole() {
        acl.replaceRules(admin, kind.getId(), Set.of(EDIT_ANY));

        assertThatCode(() -> acl.requireMayAssign("chef", catalog.keys(), null, Set.of(kind.getId())))
                .doesNotThrowAnyException();
    }
}
```

In `AdminUserControllerTest` ergänzen (Feld `@Autowired AclService acl;`, Imports `club.badernet.nestwerk.role.Role`, `club.badernet.nestwerk.security.acl.AclRule`, `club.badernet.nestwerk.security.acl.AclService`):

```java
    @Test
    void nonAdministratorCannotAssignRoleWithForeignAccessRules() throws Exception {
        User chef = userService.createAdministrator("regelchef", "passwort1");
        Role regeln = roles.save(new Role("Nur Regeln", null, Set.of()));
        acl.replaceRules(chef, regeln.getId(), Set.of(new AclRule("TODO", "VIEW", "ANY")));

        mockMvc.perform(post("/admin/users").with(csrf())
                        .param("username", "regelkind").param("password", "passwort1")
                        .param("roleIds", regeln.getId().toString()))
                .andExpect(flash().attributeExists("error"));

        assertThat(users.findByUsername("regelkind")).isEmpty();
    }
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag prüfen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=AclServiceChangeTest,AdminUserControllerTest`
Expected: FAIL – Kompilierfehler (`replaceRules`, `applyPreset`, `requireMayAssign` fehlen).

- [ ] **Step 3: Ändernde Methoden in `AclService`**

Imports ergänzen: `club.badernet.nestwerk.role.Role`, `java.util.Collection`. Methoden:

```java
    @Transactional
    public void replaceRules(User actor, Long roleId, Set<AclRule> rules) {
        Role role = roles.findById(roleId).orElseThrow(() -> new IllegalArgumentException("Rolle nicht gefunden"));
        if (role.isAllPermissions()) {
            throw new IllegalArgumentException("Die Administrator-Rolle hat immer alle Zugriffsrechte.");
        }
        Set<AclRule> valid = cells();
        for (AclRule rule : rules) {
            if (!valid.contains(rule)) {
                throw new IllegalArgumentException("Unbekannte Zugriffsregel: " + rule.key());
            }
        }
        if (!actor.isAdministrator()) {
            Set<AclRule> own = rulesOf(actor);
            if (!own.containsAll(rulesOf(roleId))) {
                throw new IllegalArgumentException("Diese Rolle hat Zugriffsregeln, die du selbst nicht hast.");
            }
            if (!own.containsAll(rules)) {
                throw new IllegalArgumentException("Du kannst nur Zugriffsregeln vergeben, die du selbst hast.");
            }
        }
        jdbc.update("DELETE FROM acl_rules WHERE role_id = :id", Map.of("id", roleId));
        for (AclRule rule : rules) {
            jdbc.update("INSERT INTO acl_rules (role_id, object_type, action, relation) VALUES (:id, :type, :action, :relation)",
                    Map.of("id", roleId, "type", rule.objectType(), "action", rule.action(), "relation", rule.relation()));
        }
    }

    @Transactional
    public void applyPreset(User actor, Long roleId, String presetKey) {
        boolean known = presets().stream().anyMatch(p -> p.key().equals(presetKey));
        if (!known) {
            throw new IllegalArgumentException("Unbekannte Vorlage: " + presetKey);
        }
        Set<AclRule> rules = new HashSet<>(jdbc.query(
                "SELECT object_type, action, relation FROM acl_preset_rules WHERE preset_key = :key",
                Map.of("key", presetKey), RULE));
        replaceRules(actor, roleId, rules);
    }

    /**
     * Ergänzt den Eskalationsschutz beim Zuweisen von Rollen: Nicht-Administratoren dürfen keine Rollen
     * vergeben und keine Benutzer ändern, deren Zugriffsregeln über die eigenen hinausgehen.
     */
    @Transactional(readOnly = true)
    public void requireMayAssign(String actorUsername, Collection<String> actorPermissions,
                                 Long targetUserId, Set<Long> roleIds) {
        if (permissions.isAdministrator(actorPermissions)) {
            return;
        }
        Set<AclRule> own = new HashSet<>(jdbc.query("SELECT DISTINCT a.object_type, a.action, a.relation "
                + "FROM acl_rules a JOIN user_roles ur ON ur.role_id = a.role_id JOIN users u ON u.id = ur.user_id "
                + "WHERE u.username = :name", Map.of("name", actorUsername), RULE));
        if (targetUserId != null) {
            Set<AclRule> target = new HashSet<>(jdbc.query("SELECT DISTINCT a.object_type, a.action, a.relation "
                    + "FROM acl_rules a JOIN user_roles ur ON ur.role_id = a.role_id WHERE ur.user_id = :id",
                    Map.of("id", targetUserId), RULE));
            if (!own.containsAll(target)) {
                throw new IllegalArgumentException(
                        "Dieser Benutzer hat Zugriffsregeln, die du selbst nicht hast, und kann daher nicht geändert werden.");
            }
        }
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                if (!own.containsAll(rulesOf(roleId))) {
                    throw new IllegalArgumentException("Eine der Rollen enthält Zugriffsregeln, die du selbst nicht hast.");
                }
            }
        }
    }
```

- [ ] **Step 4: `AdminUserController` absichern**

Konstruktor um `AclService acl` erweitern (Feld `private final AclService acl;`, Import `club.badernet.nestwerk.security.acl.AclService`). Direkt nach **jedem** der vier Aufrufe `userService.requireMayManage(permissions(actor), X, Y);` die passende Zeile mit denselben Argumenten einfügen:

```java
            acl.requireMayAssign(actor.getName(), permissions(actor), X, Y);
```

Konkret: in `create` (`null, roles`), `changeRoles` (`id, roles`), `setEnabled` (`id, null`), `resetPassword` (`id, null`).

- [ ] **Step 5: Tests laufen lassen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=AclServiceChangeTest,AclServiceTest,AdminUserControllerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/club/badernet/nestwerk/security/acl/AclService.java src/main/java/club/badernet/nestwerk/web/AdminUserController.java src/test/java/club/badernet/nestwerk/security/acl/AclServiceChangeTest.java src/test/java/club/badernet/nestwerk/web/AdminUserControllerTest.java
git commit -m "feat: Zugriffsregeln ändern, Vorlagen anwenden, Eskalationsschutz

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: ACL-Matrix in der Rollenverwaltung

**Files:**
- Modify: `security/CurrentUser.java` (Methode `find()`)
- Modify: `web/AdminRoleController.java`, `templates/admin/roles.html`, `static/css/nestwerk.css`
- Test: `src/test/java/club/badernet/nestwerk/web/AdminRoleAclTest.java`

**Interfaces:**
- Consumes: `AclService.matrix()`, `presets()`, `ruleKeysByRole()`, `rulesOf(User)`, `replaceRules(...)`, `applyPreset(...)` (Tasks 4–5); `PermissionCatalog` (Task 1)
- Produces:
  - `Optional<User> CurrentUser.find()`
  - `POST /admin/roles/{id}/acl` (Parameter `rules`, mehrfach, Format `TYP:AKTION:BEZIEHUNG`)
  - `POST /admin/roles/{id}/acl/preset` (Parameter `preset`)
  - Model-Attribute der Rollenseite: `aclMatrix` (`List<AclObjectType>`), `aclPresets` (`List<AclPreset>`), `roleRules` (`Map<Long, Set<String>>`, für **jede** Rolle ein Eintrag), `actorRules` (`Set<String>`), `actorIsAdmin` (`boolean`)

- [ ] **Step 1: Failing Test schreiben**

`src/test/java/club/badernet/nestwerk/web/AdminRoleAclTest.java`:

```java
package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclRule;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class AdminRoleAclTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired AclService acl;

    User admin;
    Role kind;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chefin", "passwort1");
        kind = roleService.create("Kind", null, Set.of());
    }

    private static RequestPostProcessor as(String username) {
        return user(username).authorities(new SimpleGrantedAuthority("ACCESS_ROLE_MANAGE"));
    }

    @Test
    void showsMatrixWithSeededRules() throws Exception {
        mockMvc.perform(get("/admin/roles").with(as("chefin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zugriffsregeln")))
                .andExpect(content().string(containsString("value=\"TODO:VIEW:ANY\" checked")))
                .andExpect(content().string(containsString("Gast (nur lesen)")));
    }

    @Test
    void newRoleWithoutRulesRendersMatrix() throws Exception {
        mockMvc.perform(get("/admin/roles").with(as("chefin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/roles/" + kind.getId() + "/acl")));
    }

    @Test
    void savesRulesFromMatrix() throws Exception {
        mockMvc.perform(post("/admin/roles/{id}/acl", kind.getId()).with(csrf()).with(as("chefin"))
                        .param("rules", "TODO:VIEW:ASSIGNED", "TODO:CHANGE_STATUS:ASSIGNED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));

        assertThat(acl.rulesOf(kind.getId())).containsExactlyInAnyOrder(
                new AclRule("TODO", "VIEW", "ASSIGNED"), new AclRule("TODO", "CHANGE_STATUS", "ASSIGNED"));
    }

    @Test
    void appliesPreset() throws Exception {
        mockMvc.perform(post("/admin/roles/{id}/acl/preset", kind.getId()).with(csrf()).with(as("chefin"))
                        .param("preset", "GAST"))
                .andExpect(flash().attributeExists("success"));

        assertThat(acl.rulesOf(kind.getId())).hasSize(2);
    }

    @Test
    void malformedRuleShowsError() throws Exception {
        for (String bad : new String[]{"TODO:EDIT", "X:Y:Z", "TODO:CREATE:RESPONSIBLE"}) {
            mockMvc.perform(post("/admin/roles/{id}/acl", kind.getId()).with(csrf()).with(as("chefin"))
                            .param("rules", bad))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attributeExists("error"));
        }
        assertThat(acl.rulesOf(kind.getId())).isEmpty();
    }

    @Test
    void nonAdministratorSeesForeignCellsDisabledAndCannotGrantThem() throws Exception {
        Role verwalter = roleService.create("Verwalter", null, Set.of("ACCESS_ROLE_MANAGE"));
        acl.replaceRules(admin, verwalter.getId(), Set.of(new AclRule("TODO", "VIEW", "ANY")));
        userService.create("verwalter", "passwort1", Set.of(verwalter.getId()));

        mockMvc.perform(get("/admin/roles").with(as("verwalter")))
                .andExpect(content().string(containsString("value=\"TODO:EDIT:ANY\" disabled")));
        mockMvc.perform(post("/admin/roles/{id}/acl", kind.getId()).with(csrf()).with(as("verwalter"))
                        .param("rules", "TODO:EDIT:ANY"))
                .andExpect(flash().attributeExists("error"));

        assertThat(acl.rulesOf(kind.getId())).isEmpty();
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag prüfen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=AdminRoleAclTest`
Expected: FAIL – Seite enthält „Zugriffsregeln“ nicht; POST `/admin/roles/{id}/acl` liefert 404/405.

- [ ] **Step 3: `CurrentUser.find()`**

In `security/CurrentUser.java` (Import `java.util.Optional`):

```java
    /** Der angemeldete Benutzer, falls er in der Datenbank existiert. */
    @Transactional(readOnly = true)
    public Optional<User> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        return users.findByUsername(auth.getName());
    }
```

- [ ] **Step 4: Controller erweitern**

`web/AdminRoleController.java`: Konstruktor `public AdminRoleController(RoleService roleService, PermissionCatalog catalog, AclService acl, CurrentUser currentUser)` mit Feldern; Imports `club.badernet.nestwerk.security.CurrentUser`, `club.badernet.nestwerk.security.acl.AclRule`, `club.badernet.nestwerk.security.acl.AclService`, `club.badernet.nestwerk.user.User`, `java.util.HashMap`, `java.util.LinkedHashSet`, `java.util.Map`, `java.util.Optional`.

`list` ersetzen:

```java
    @GetMapping
    String list(Authentication actor, Model model) {
        var allRoles = roleService.findAll();
        model.addAttribute("roles", allRoles);
        model.addAttribute("grantable", permissions(actor));
        model.addAttribute("permissionsByModule", catalog.byModule());

        Map<Long, Set<String>> stored = acl.ruleKeysByRole();
        Map<Long, Set<String>> roleRules = new HashMap<>();
        allRoles.forEach(r -> roleRules.put(r.getId(), stored.getOrDefault(r.getId(), Set.of())));
        Optional<User> me = currentUser.find();
        boolean actorIsAdmin = me.map(User::isAdministrator).orElse(false);
        Set<String> actorRules = new LinkedHashSet<>();
        me.ifPresent(u -> acl.rulesOf(u).forEach(rule -> actorRules.add(rule.key())));

        model.addAttribute("aclMatrix", acl.matrix());
        model.addAttribute("aclPresets", acl.presets());
        model.addAttribute("roleRules", roleRules);
        model.addAttribute("actorRules", actorRules);
        model.addAttribute("actorIsAdmin", actorIsAdmin);
        return "admin/roles";
    }
```

Neue Endpunkte:

```java
    @PostMapping("/{id}/acl")
    String updateAcl(@PathVariable Long id, @RequestParam(required = false) Set<String> rules,
                     RedirectAttributes redirect) {
        return run(redirect, "Zugriffsregeln wurden gespeichert.", () -> {
            Set<AclRule> parsed = (rules == null ? Set.<String>of() : rules).stream()
                    .map(AclRule::parse).collect(Collectors.toSet());
            acl.replaceRules(currentUser.require(), id, parsed);
        });
    }

    @PostMapping("/{id}/acl/preset")
    String applyPreset(@PathVariable Long id, @RequestParam String preset, RedirectAttributes redirect) {
        return run(redirect, "Vorlage wurde übernommen.",
                () -> acl.applyPreset(currentUser.require(), id, preset));
    }
```

- [ ] **Step 5: Matrix im Template**

In `templates/admin/roles.html` innerhalb von `<div class="role-body">` direkt **vor** dem Lösch-Formular (`<form class="danger-zone" …`) einfügen:

```html
                <form class="acl" th:unless="${role.allPermissions}"
                      th:with="aclLocked=${!actorIsAdmin and !actorRules.containsAll(roleRules[role.id])}"
                      th:action="@{/admin/roles/{id}/acl(id=${role.id})}" method="post">
                    <h3>Zugriffsregeln</h3>
                    <p class="muted">Was Mitglieder dieser Rolle mit ToDos und Kontakten tun dürfen, je nach ihrer Beziehung zum Eintrag. „Alle“ schließt private Einträge nie ein.</p>
                    <p class="notice" th:if="${aclLocked}">
                        Diese Rolle hat Zugriffsregeln, die du selbst nicht hast. Nur ein Administrator kann sie ändern.
                    </p>
                    <div class="acl-preset" th:unless="${aclLocked}">
                        <div class="field">
                            <label th:for="'preset-' + ${role.id}">Vorlage</label>
                            <select th:id="'preset-' + ${role.id}" name="preset">
                                <option th:each="p : ${aclPresets}" th:value="${p.key()}" th:text="${p.label()}">Kind</option>
                            </select>
                        </div>
                        <button type="submit" class="button button-ghost"
                                th:formaction="@{/admin/roles/{id}/acl/preset(id=${role.id})}">Vorlage übernehmen</button>
                    </div>
                    <div class="acl-scroll" th:each="type : ${aclMatrix}">
                        <table class="acl-matrix">
                            <caption th:text="${type.label()}">ToDos</caption>
                            <thead>
                            <tr>
                                <th scope="col">Aktion</th>
                                <th scope="col" th:each="rel : ${type.relations()}" th:text="${rel.label()}">Alle</th>
                            </tr>
                            </thead>
                            <tbody>
                            <tr th:each="action : ${type.actions()}">
                                <th scope="row" th:text="${action.label()}">Ansehen</th>
                                <td th:each="rel : ${type.relations()}"
                                    th:with="ruleKey=${type.key() + ':' + action.key() + ':' + rel.key()}">
                                    <input th:if="${type.allows(action.key(), rel.key())}" type="checkbox" name="rules"
                                           th:value="${ruleKey}"
                                           th:checked="${roleRules[role.id].contains(ruleKey)}"
                                           th:disabled="${aclLocked or (!actorIsAdmin and !actorRules.contains(ruleKey))}"
                                           th:attr="aria-label=${action.label() + ': ' + rel.label()}">
                                </td>
                            </tr>
                            </tbody>
                        </table>
                    </div>
                    <div class="form-actions" th:unless="${aclLocked}">
                        <button type="submit" class="button">Zugriffsregeln speichern</button>
                    </div>
                </form>
```

An `static/css/nestwerk.css` anhängen (vor `/* --- Animationen`):

```css
/* --- Zugriffsregeln (ACL-Matrix) ----------------------------------------- */

.acl { margin-top: 1.5rem; padding-top: 1.25rem; border-top: 1px dashed var(--line); display: grid; gap: 1rem; }
.acl h3 { font-size: var(--step-1); margin: 0; }
.acl > .muted { margin: -0.5rem 0 0; }
.acl-preset { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: end; }
.acl-preset .field { margin: 0; }
.acl-scroll { overflow-x: auto; }
.acl-matrix { width: 100%; min-width: 34rem; border-collapse: collapse; }
.acl-matrix caption { text-align: left; font-weight: 700; padding-bottom: 0.4rem; }
.acl-matrix th, .acl-matrix td { padding: 0.4rem 0.5rem; border-bottom: 1px solid var(--line); }
.acl-matrix thead th { font-size: var(--step--1); color: var(--ink-soft); font-weight: 600; text-align: center; }
.acl-matrix thead th:first-child, .acl-matrix tbody th { text-align: left; }
.acl-matrix tbody th { font-weight: 400; }
.acl-matrix td { text-align: center; }
.acl-matrix input { width: 1.05rem; height: 1.05rem; margin: 0; accent-color: var(--moss); }
.acl-matrix input:disabled { opacity: 0.35; cursor: not-allowed; }
```

- [ ] **Step 6: Tests laufen lassen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q test -Dtest=AdminRoleAclTest,AdminRoleControllerTest,NavigationTests`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/club/badernet/nestwerk/security/CurrentUser.java src/main/java/club/badernet/nestwerk/web/AdminRoleController.java src/main/resources/templates/admin/roles.html src/main/resources/static/css/nestwerk.css src/test/java/club/badernet/nestwerk/web/AdminRoleAclTest.java
git commit -m "feat: Matrix für Zugriffsregeln in der Rollenverwaltung

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Gesamtprüfung und Sichtprüfung

**Files:**
- Modify: `docs/superpowers/roadmap.md`

- [ ] **Step 1: Kompletten Build laufen lassen**

Run: `JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" ./mvnw -q verify > target/verify.log 2>&1; echo rc=$?`
Expected: `rc=0`; Summe aus `target/surefire-reports/*.txt` ohne Failures/Errors.

- [ ] **Step 2: Sichtprüfung im Browser**

App auf einem freien Port starten (die Instanz des Benutzers auf 8081 nicht beenden):
`JAVA_HOME="$USERPROFILE/.jdks/openjdk-26" SERVER_PORT=8082 NESTWERK_ADMIN_PASSWORD=changeme ./mvnw -q spring-boot:run` (im Hintergrund).

Als `admin` prüfen: `/admin/roles` (Matrix je Rolle, Vorlage übernehmen, Speichern), `/admin/permissions` (Bezeichnung ändern, erscheint in der Rollenverwaltung), Breite 390 px (Matrix scrollt innerhalb ihres Rahmens, Seite nicht), Dark Mode. Danach geänderte Bezeichnungen zurücksetzen, Instanz beenden, `.playwright-mcp` löschen.

- [ ] **Step 3: Implementierungsliste aktualisieren**

In `docs/superpowers/roadmap.md` die Zeile 5 ergänzen: „Stufe 0 erledigt“ in der Spalte Stand.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/roadmap.md
git commit -m "docs: Stufe 0 des ToDo-Moduls abgeschlossen

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
