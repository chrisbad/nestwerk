package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class AdminToolsTest {

    @Autowired AdminTools adminTools;
    @Autowired UserService userService;
    @Autowired RoleService roleService;

    User admin;
    User plain;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        plain = userService.create("kind", "passwort1", Set.of());
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    @Test
    void managesRolesUsersAndMembers() {
        AdminTools.RoleItem role = adminTools.createRole(as(admin), "Helfer", "Hilft im Haushalt",
                List.of("CALENDAR_VIEW"));
        assertThat(role.permissions()).containsExactly("CALENDAR_VIEW");

        AdminTools.RoleItem renamed = adminTools.updateRole(as(admin), role.roleId(), "Hausmeister", null, null);
        assertThat(renamed.name()).isEqualTo("Hausmeister");
        assertThat(renamed.permissions()).containsExactly("CALENDAR_VIEW");

        AdminTools.RoleItem withRules = adminTools.applyAccessPreset(as(admin), role.roleId(), "KIND");
        assertThat(withRules.accessRules()).isNotEmpty();
        assertThat(adminTools.accessCatalog(as(admin)).presets()).anyMatch(p -> p.startsWith("KIND"));

        AdminTools.UserItem assigned = adminTools.setUserRoles(as(admin), plain.getId(), List.of(role.roleId()));
        assertThat(assigned.roles()).hasSize(1);
        assertThat(adminTools.setUserEnabled(as(admin), plain.getId(), false).enabled()).isFalse();
        assertThat(adminTools.listUsers(as(admin))).extracting(AdminTools.UserItem::username)
                .contains("chef", "kind");

        AdminTools.MemberItem member = adminTools.createMember(as(admin), "Oma", "#ff8800", null);
        AdminTools.MemberItem linked = adminTools.updateMember(as(admin), member.memberId(), "Oma Erna", null,
                plain.getId(), null);
        assertThat(linked.name()).isEqualTo("Oma Erna");
        assertThat(linked.userId()).isEqualTo(plain.getId());
        assertThat(adminTools.updateMember(as(admin), member.memberId(), null, null, null, true).userId()).isNull();
        assertThatThrownBy(() -> adminTools.createMember(as(admin), "X", "rot", null))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void everythingNeedsTheMatchingAdminPermission() {
        assertThatThrownBy(() -> adminTools.listUsers(as(plain))).isInstanceOf(Tools.ToolException.class)
                .hasMessageContaining("USER_MANAGE");
        assertThatThrownBy(() -> adminTools.listRoles(as(plain))).isInstanceOf(Tools.ToolException.class)
                .hasMessageContaining("ACCESS_ROLE_MANAGE");
        assertThatThrownBy(() -> adminTools.createMember(as(plain), "X", "#ffffff", null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("MEMBER_MANAGE");
        assertThatThrownBy(() -> adminTools.setRoleAccessRules(as(plain), 1L, List.of("TODO:VIEW:ANY")))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void userManagersCannotEscalate() {
        Role userAdmins = roleService.create("Benutzerverwalter", null, Set.of("USER_MANAGE", "ACCESS_ROLE_MANAGE"));
        User manager = userService.create("verwalter", "passwort1", Set.of(userAdmins.getId()));
        Role all = roleService.findAll().stream().filter(Role::isAllPermissions).findFirst().orElseThrow();

        assertThatThrownBy(() -> adminTools.setUserRoles(as(manager), plain.getId(), List.of(all.getId())))
                .isInstanceOf(Tools.ToolException.class);
        assertThatThrownBy(() -> adminTools.createRole(as(manager), "Super", null, List.of("BACKUP_MANAGE")))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void deletesMembersAndRolesWithTheirGuards() {
        AdminTools.MemberItem member = adminTools.createMember(as(admin), "Oma", "#ff8800", null);
        AdminTools.RoleItem role = adminTools.createRole(as(admin), "Helfer", null, List.of("CALENDAR_VIEW"));
        adminTools.setUserRoles(as(admin), plain.getId(), List.of(role.roleId()));

        assertThatThrownBy(() -> adminTools.deleteMember(as(plain), member.memberId()))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("MEMBER_MANAGE");
        assertThatThrownBy(() -> adminTools.deleteRole(as(plain), role.roleId()))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("ACCESS_ROLE_MANAGE");

        assertThat(adminTools.deleteMember(as(admin), member.memberId())).contains("Oma");
        assertThatThrownBy(() -> adminTools.deleteMember(as(admin), member.memberId()))
                .isInstanceOf(Tools.ToolException.class).hasMessage("Mitglied nicht gefunden");

        // still assigned to a user
        assertThatThrownBy(() -> adminTools.deleteRole(as(admin), role.roleId()))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("zugewiesen");
        adminTools.setUserRoles(as(admin), plain.getId(), List.of());
        assertThat(adminTools.deleteRole(as(admin), role.roleId())).contains("Helfer");
        assertThatThrownBy(() -> adminTools.deleteRole(as(admin), role.roleId()))
                .isInstanceOf(Tools.ToolException.class).hasMessage("Rolle nicht gefunden");
    }
}
