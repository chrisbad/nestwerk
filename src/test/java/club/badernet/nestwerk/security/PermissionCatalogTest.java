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
                .containsExactly("USER_MANAGE", "ACCESS_ROLE_MANAGE", "MEMBER_MANAGE", "NOTIFICATION_SETTINGS_MANAGE",
                        "HOUSEHOLD_DEVICE_MANAGE", "AI_ACCESS", "BACKUP_MANAGE");
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
