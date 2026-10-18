package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class NotificationSettingsToolsTest {

    @Autowired NotificationSettingsTools notificationTools;
    @Autowired UserService userService;

    User admin;
    User user;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        user = userService.create("mama", "passwort1", Set.of());
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    @Test
    void changesOwnEmailAndPreferences() {
        notificationTools.setNotificationEmail(as(user), "mama@example.org");
        notificationTools.setNotificationPreference(as(user), "todo_comment", "on", "off");

        NotificationSettingsTools.MySettings settings = notificationTools.notificationSettings(as(user));
        assertThat(settings.email()).isEqualTo("mama@example.org");
        assertThat(settings.preferences()).filteredOn(p -> p.type().equals("TODO_COMMENT"))
                .extracting(NotificationSettingsTools.Preference::email,
                        NotificationSettingsTools.Preference::push)
                .containsExactly(org.assertj.core.api.Assertions.tuple("ON", "OFF"));
        assertThatThrownBy(() -> notificationTools.setNotificationPreference(as(user), "GIBT_ES_NICHT", "on", null))
                .isInstanceOf(Tools.ToolException.class);
        assertThatThrownBy(() -> notificationTools.sendPushTest(as(user))).isInstanceOf(Tools.ToolException.class)
                .hasMessageContaining("Push");
    }

    @Test
    void adminSettingsNeedThePermission() {
        notificationTools.setReminderSchedule(as(admin), 7, 3);
        notificationTools.setGlobalNotificationDefault(as(admin), "TODO_COMMENT", "on", null);

        NotificationSettingsTools.AdminSettings settings = notificationTools.adminNotificationSettings(as(admin));
        assertThat(settings.reminderHour()).isEqualTo(7);
        assertThat(settings.defaultLeadDays()).isEqualTo(3);
        assertThat(settings.globalDefaults()).filteredOn(p -> p.type().equals("TODO_COMMENT"))
                .extracting(NotificationSettingsTools.Preference::defaultEmail).containsExactly(true);

        assertThatThrownBy(() -> notificationTools.adminNotificationSettings(as(user)))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("NOTIFICATION_SETTINGS_MANAGE");
        assertThatThrownBy(() -> notificationTools.setReminderSchedule(as(user), 7, 3))
                .isInstanceOf(Tools.ToolException.class);
        assertThatThrownBy(() -> notificationTools.setReminderSchedule(as(admin), 30, 3))
                .isInstanceOf(Tools.ToolException.class);
    }
}
