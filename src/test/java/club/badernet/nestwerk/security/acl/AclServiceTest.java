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
        roles.flush();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM acl_rules WHERE role_id = ?", Integer.class,
                role.getId())).isZero();
    }
}
