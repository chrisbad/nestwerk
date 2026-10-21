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
class SettingsRegisterToolsTest {

    @Autowired SettingsRegisterTools register;
    @Autowired UserService userService;
    @Autowired RoleService roleService;

    User manager;
    User guest;

    @BeforeEach
    void setUp() {
        Role managers = roleService.create("Verwalter", null, Set.of("CALENDAR_VIEW", "CALENDAR_CATEGORY_MANAGE",
                "USER_MANAGE", "BACKUP_MANAGE"));
        manager = userService.create("verwalter", "passwort1", Set.of(managers.getId()));
        Role viewers = roleService.create("Gäste", null, Set.of("CALENDAR_VIEW"));
        guest = userService.create("gast", "passwort1", Set.of(viewers.getId()));
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    private static List<String> ids(List<SettingsRegisterTools.SettingItem> items) {
        return items.stream().map(SettingsRegisterTools.SettingItem::settingId).toList();
    }

    @Test
    void listsOnlySettingsTheUserMaySee() {
        assertThat(ids(register.listSettings(as(guest), null, null, null, null)))
                .contains("calendar.times", "notifications.mine")
                .doesNotContain("calendar.categories", "system.users", "system.backup");

        assertThat(ids(register.listSettings(as(manager), null, null, null, null)))
                .contains("calendar.categories", "system.users", "system.backup");
    }

    @Test
    void filtersByAreaTextAndScope() {
        assertThat(ids(register.listSettings(as(manager), null, "system", null, null)))
                .containsExactlyInAnyOrder("system.users", "system.backup");
        assertThat(ids(register.listSettings(as(manager), "passwort", null, null, null)))
                .containsExactly("system.users");
        assertThat(ids(register.listSettings(as(manager), null, null, "PERSONAL", null)))
                .containsExactly("notifications.mine");
    }

    @Test
    void reportsTheCurrentValueAndStatus() {
        SettingsRegisterTools.SettingItem backup = register.listSettings(as(manager), null, "SYSTEM", null, null)
                .stream().filter(item -> item.settingId().equals("system.backup")).findFirst().orElseThrow();

        assertThat(backup.status()).isEqualTo("NOT_SET");
        assertThat(backup.currentValue()).contains("noch keine Sicherung");
        assertThat(backup.path()).isEqualTo("/admin/backup");
        assertThat(register.listSettings(as(manager), null, null, null, "NOT_SET"))
                .extracting(SettingsRegisterTools.SettingItem::settingId).contains("system.backup");
    }

    @Test
    void rejectsUnknownFilterValues() {
        assertThatThrownBy(() -> register.listSettings(as(manager), null, "KELLER", null, null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Erlaubt");
    }
}
