package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.notification.Notification;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class TodoReminderSchedulerTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired TodoReminderScheduler scheduler;
    @Autowired TodoService service;
    @Autowired NotificationService notifications;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;

    User mama;

    @BeforeEach
    void setUp() {
        mama = userService.create("mama", "passwort1",
                Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
    }

    private Todo todo(String title, LocalDate from, LocalDate due, LocalDate followUp, Integer lead) {
        return service.create(new TodoInput(title, null, null, from, due, followUp, lead, false, null, Set.of(),
                Set.of(), null), mama);
    }

    private List<String> reminders() {
        return notifications.list(mama.getId(), false, 100).stream()
                .map(n -> n.getType().name() + "@" + n.getTitle() + "@" + n.getDueKey()).sorted().toList();
    }

    @Test
    void remindsForEachKindOnItsDay() {
        todo("Ab heute", DAY, null, null, null);
        todo("Bald", null, DAY.plusDays(2), null, null);
        todo("Eigener Vorlauf", null, DAY.plusDays(5), null, 5);
        todo("Gestern fällig", null, DAY.minusDays(1), null, null);
        todo("Wiedervorlage", null, null, DAY, null);
        todo("Nichts heute", null, DAY.plusDays(20), null, null);

        scheduler.runFor(DAY, DAY);

        assertThat(reminders()).containsExactly(
                "TODO_AVAILABLE@Ab heute@2026-11-10",
                "TODO_DUE_SOON@Bald@2026-11-10",
                "TODO_DUE_SOON@Eigener Vorlauf@2026-11-10",
                "TODO_FOLLOW_UP@Wiedervorlage@2026-11-10",
                "TODO_OVERDUE@Gestern fällig@2026-11-10");
    }

    @Test
    void overdueRepeatsWeekly() {
        todo("Lange überfällig", null, DAY.minusDays(8), null, null);

        scheduler.runFor(DAY, DAY);
        assertThat(reminders()).containsExactly("TODO_OVERDUE@Lange überfällig@2026-11-10");
        scheduler.runFor(DAY.plusDays(1), DAY.plusDays(6));
        assertThat(reminders()).hasSize(1);
        scheduler.runFor(DAY.plusDays(7), DAY.plusDays(7));
        assertThat(reminders()).hasSize(2);
    }

    @Test
    void schedulerIsIdempotent() {
        todo("Bald", null, DAY.plusDays(2), null, null);

        scheduler.runFor(DAY, DAY);
        scheduler.runFor(DAY, DAY);

        assertThat(reminders()).hasSize(1);
    }

    @Test
    void missedDaysAreCaughtUp() {
        todo("Verpasst", null, DAY.minusDays(1).plusDays(2), null, null);
        todo("Wiedervorlage verpasst", null, null, DAY.minusDays(2), null);

        scheduler.runFor(DAY.minusDays(3), DAY);

        assertThat(reminders()).contains("TODO_DUE_SOON@Verpasst@2026-11-09",
                "TODO_FOLLOW_UP@Wiedervorlage verpasst@2026-11-08");
    }

    @Test
    void closedTodosAreIgnored() {
        Todo t = todo("Erledigt", null, DAY.plusDays(2), null, null);
        service.check(t.getId(), mama);
        notifications.markAllRead(mama.getId());
        int before = notifications.list(mama.getId(), false, 100).size();

        scheduler.runFor(DAY, DAY);

        assertThat(notifications.list(mama.getId(), false, 100)).hasSize(before);
    }

    @Test
    void catchUpRangeIsLimited() {
        assertThat(TodoReminderScheduler.catchUpFrom(null, DAY)).isEqualTo(DAY);
        assertThat(TodoReminderScheduler.catchUpFrom(DAY.minusDays(1), DAY)).isEqualTo(DAY);
        assertThat(TodoReminderScheduler.catchUpFrom(DAY.minusDays(4), DAY)).isEqualTo(DAY.minusDays(3));
        assertThat(TodoReminderScheduler.catchUpFrom(DAY.minusDays(100), DAY)).isEqualTo(DAY.minusDays(30));
        assertThat(TodoReminderScheduler.catchUpFrom(DAY, DAY)).isNull();
    }

    @Test
    void notificationLinksToTodo() {
        Todo t = todo("Ab heute", DAY, null, null, null);

        scheduler.runFor(DAY, DAY);

        Notification n = notifications.list(mama.getId(), false, 10).stream()
                .filter(x -> x.getType() == NotificationType.TODO_AVAILABLE).findFirst().orElseThrow();
        assertThat(n.getLink()).isEqualTo("/todos/" + t.getId());
    }
}
