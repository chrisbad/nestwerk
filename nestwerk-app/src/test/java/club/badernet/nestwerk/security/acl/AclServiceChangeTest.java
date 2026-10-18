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
                .containsExactlyInAnyOrder(VIEW_ANY, new AclRule("CONTACT", "VIEW", "ANY"),
                        new AclRule("CHORE", "VIEW", "ANY"));
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
