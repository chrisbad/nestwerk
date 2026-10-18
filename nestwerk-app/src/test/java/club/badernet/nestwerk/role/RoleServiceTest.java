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
