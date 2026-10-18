package club.badernet.nestwerk.notification;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static club.badernet.nestwerk.notification.NotificationPreferences.Choice.DEFAULT;
import static club.badernet.nestwerk.notification.NotificationPreferences.Choice.OFF;
import static club.badernet.nestwerk.notification.NotificationPreferences.Choice.ON;
import static club.badernet.nestwerk.notification.NotificationPreferences.Scope.GLOBAL;
import static club.badernet.nestwerk.notification.NotificationPreferences.Scope.TODO;
import static club.badernet.nestwerk.notification.NotificationPreferences.Scope.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class NotificationPreferencesTest {

    static final NotificationType TYPE = NotificationType.TODO_DUE_SOON;

    @Autowired NotificationPreferences prefs;

    @Test
    void defaultsArePushOnAndMailOff() {
        assertThat(prefs.enabled(Channel.PUSH, TYPE, 1L, 2L)).isTrue();
        assertThat(prefs.enabled(Channel.EMAIL, TYPE, 1L, 2L)).isFalse();
    }

    @Test
    void preferenceLevelsResolveFinestFirst() {
        prefs.set(GLOBAL, 0L, TYPE.name(), Channel.EMAIL, ON);
        assertThat(prefs.enabled(Channel.EMAIL, TYPE, 1L, 2L)).isTrue();

        prefs.set(USER, 1L, TYPE.name(), Channel.EMAIL, OFF);
        assertThat(prefs.enabled(Channel.EMAIL, TYPE, 1L, 2L)).isFalse();
        assertThat(prefs.enabled(Channel.EMAIL, TYPE, 9L, 2L)).isTrue();

        prefs.set(TODO, 2L, NotificationPreferences.ALL, Channel.EMAIL, ON);
        assertThat(prefs.enabled(Channel.EMAIL, TYPE, 1L, 2L)).isTrue();
        prefs.set(TODO, 2L, NotificationPreferences.ALL, Channel.PUSH, OFF);
        assertThat(prefs.enabled(Channel.PUSH, TYPE, 1L, 2L)).isFalse();

        prefs.set(TODO, 2L, NotificationPreferences.ALL, Channel.EMAIL, DEFAULT);
        assertThat(prefs.enabled(Channel.EMAIL, TYPE, 1L, 2L)).isFalse();
    }

    @Test
    void readsChoicesOfAScope() {
        prefs.set(USER, 5L, TYPE.name(), Channel.PUSH, OFF);

        assertThat(prefs.choices(USER, 5L)).containsEntry(TYPE.name() + ":PUSH", OFF);
        assertThat(prefs.choice(USER, 5L, NotificationType.TODO_OVERDUE.name(), Channel.PUSH)).isEqualTo(DEFAULT);
    }

    @Test
    void globalHasNoDefaultChoiceAndTypesAreChecked() {
        assertThatThrownBy(() -> prefs.set(GLOBAL, 0L, TYPE.name(), Channel.PUSH, DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prefs.set(USER, 1L, "UNSINN", Channel.PUSH, ON))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
