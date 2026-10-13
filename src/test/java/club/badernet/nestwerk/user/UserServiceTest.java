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
