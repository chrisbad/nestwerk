package club.badernet.nestwerk.notification;

import club.badernet.nestwerk.settings.AppSettings;
import club.badernet.nestwerk.TestcontainersConfiguration;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class NotificationServiceTest {

    @Autowired NotificationService notifications;
    @Autowired AppSettings settings;
    @Autowired UserService userService;

    User mama;
    User papa;

    @BeforeEach
    void setUp() {
        mama = userService.create("mama", "passwort1", Set.of());
        papa = userService.create("papa", "passwort1", Set.of());
    }

    private boolean send(User user, String dueKey) {
        return notifications.notify(user.getId(), NotificationType.TODO_DUE_SOON, "TODO", 7L, dueKey,
                "Steuer", "bald fällig", "/todos/7");
    }

    @Test
    void notifyIsIdempotentPerRecipientTypeSourceAndDueKey() {
        assertThat(send(mama, "2026-10-14")).isTrue();
        assertThat(send(mama, "2026-10-14")).isFalse();
        assertThat(send(mama, "2026-10-21")).isTrue();
        assertThat(send(papa, "2026-10-14")).isTrue();

        assertThat(notifications.unreadCount(mama.getId())).isEqualTo(2);
        assertThat(notifications.unreadCount("papa")).isEqualTo(1);
    }

    @Test
    void openingMarksReadAndOnlyWorksForOwnNotifications() {
        send(mama, "k");
        Notification n = notifications.latest(mama.getId(), 5).getFirst();

        assertThatThrownBy(() -> notifications.open(n.getId(), papa)).isInstanceOf(NotificationNotFoundException.class);
        Notification opened = notifications.open(n.getId(), mama);
        assertThat(opened.getLink()).isEqualTo("/todos/7");
        assertThat(notifications.unreadCount(mama.getId())).isZero();
    }

    @Test
    void markAllReadAndListFilter() {
        send(mama, "a");
        send(mama, "b");

        notifications.markAllRead(mama.getId());

        assertThat(notifications.list(mama.getId(), true, 50)).isEmpty();
        assertThat(notifications.list(mama.getId(), false, 50)).hasSize(2);
    }

    @Test
    void settingsHaveDefaultsAndCanBeChanged() {
        assertThat(settings.reminderHour()).isEqualTo(8);
        assertThat(settings.defaultLeadDays()).isEqualTo(2);

        settings.updateReminders(7, 3);

        assertThat(settings.reminderHour()).isEqualTo(7);
        assertThat(settings.defaultLeadDays()).isEqualTo(3);
        assertThatThrownBy(() -> settings.updateReminders(24, 3)).isInstanceOf(IllegalArgumentException.class);
    }
}
