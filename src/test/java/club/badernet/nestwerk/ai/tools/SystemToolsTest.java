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
class SystemToolsTest {

    @Autowired SystemTools systemTools;
    @Autowired UserService userService;

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
    void changesBackupScheduleAndRetention() {
        SystemTools.BackupStatus scheduled = systemTools.setBackupSchedule(as(admin), true, "weekly", "friday",
                "03:30");
        assertThat(scheduled.scheduleEnabled()).isTrue();
        assertThat(scheduled.frequency()).isEqualTo("WEEKLY");
        assertThat(scheduled.weekday()).isEqualTo("FRIDAY");
        assertThat(scheduled.time()).isEqualTo("03:30");
        assertThat(scheduled.nextRun()).isNotNull();

        SystemTools.BackupStatus kept = systemTools.setBackupRetention(as(admin), 5, null, 3, null);
        assertThat(kept.keepLast()).isEqualTo(5);
        assertThat(kept.keepMonthly()).isEqualTo(3);
        assertThat(systemTools.backupStatus(as(admin)).frequency()).isEqualTo("WEEKLY");
    }

    @Test
    void reportsUpdateStatusAndGuardsStart() {
        SystemTools.UpdateStatus status = systemTools.updateStatus(as(admin));
        assertThat(status.currentVersion()).isNotBlank();
        assertThat(status.channel()).isNotBlank();

        assertThatThrownBy(() -> systemTools.startUpdate(as(admin), null)).isInstanceOf(Tools.ToolException.class)
                .hasMessageContaining("confirm");
        assertThatThrownBy(() -> systemTools.startUpdate(as(admin), true)).isInstanceOf(Tools.ToolException.class)
                .hasMessageContaining("neuere Version");
    }

    @Test
    void everythingNeedsThePermission() {
        assertThatThrownBy(() -> systemTools.backupStatus(as(plain))).isInstanceOf(Tools.ToolException.class)
                .hasMessageContaining("BACKUP_MANAGE");
        assertThatThrownBy(() -> systemTools.startBackup(as(plain))).isInstanceOf(Tools.ToolException.class);
        assertThatThrownBy(() -> systemTools.updateStatus(as(plain))).isInstanceOf(Tools.ToolException.class)
                .hasMessageContaining("UPDATE_MANAGE");
        assertThatThrownBy(() -> systemTools.setUpdateChannel(as(plain), "RELEASES"))
                .isInstanceOf(Tools.ToolException.class);
    }
}
