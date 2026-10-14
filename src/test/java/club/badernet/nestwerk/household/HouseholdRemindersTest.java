package club.badernet.nestwerk.household;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.notification.AppSettings;
import club.badernet.nestwerk.notification.Notification;
import club.badernet.nestwerk.notification.NotificationRepository;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class HouseholdRemindersTest {

    @Autowired ChoreService chores;
    @Autowired ChoreTaskService taskService;
    @Autowired ChoreTaskRepository tasks;
    @Autowired HouseholdReminders reminders;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired FamilyMemberRepository members;
    @Autowired NotificationRepository notifications;
    @Autowired AppSettings settings;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    User admin;
    User papa;
    User lena;
    FamilyMember papaM;
    FamilyMember lenaM;
    FamilyMember baby;
    LocalDate today;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
        lena = userService.create("lena", "passwort1", Set.of(benutzer));
        papaM = members.save(new FamilyMember("Papa", "#aa0000", papa.getId()));
        lenaM = members.save(new FamilyMember("Lena", "#00aa00", lena.getId()));
        baby = members.save(new FamilyMember("Baby", "#0000aa", null));
        settings.updateReminders(8, 2);
        today = LocalDate.now(clock);
    }

    private ChoreTask task(LocalTime time, Distribution distribution, Long... memberIds) {
        Chore c = chores.create(new ChoreInput("Mülltonnen rausstellen", null, null, 5, ChoreEffort.EASY,
                ChoreKind.RECURRING, "FREQ=DAILY;COUNT=1", today, time, null, distribution, List.of(memberIds)), admin);
        entityManager.flush();
        return tasks.findAllByChoreId(c.getId()).getFirst();
    }

    private List<Notification> of(NotificationType type) {
        entityManager.flush();
        return notifications.findAll().stream().filter(n -> n.getType() == type).toList();
    }

    private ZonedDateTime at(int hour, int minute) {
        return today.atTime(hour, minute).atZone(clock.getZone());
    }

    @Test
    void timedTaskIsRemindedOneHourBeforeAndOnlyOnce() {
        ChoreTask t = task(LocalTime.of(19, 0), Distribution.FIXED, papaM.getId());

        reminders.run(at(17, 59));
        assertThat(of(NotificationType.CHORE_DUE)).isEmpty();

        reminders.run(at(18, 0));
        reminders.run(at(18, 5));
        assertThat(of(NotificationType.CHORE_DUE)).singleElement().satisfies(n -> {
            assertThat(n.getUserId()).isEqualTo(papa.getId());
            assertThat(n.getLink()).isEqualTo("/household/tasks/" + t.getId());
            assertThat(n.getBody()).contains("19:00");
        });
    }

    @Test
    void untimedTaskIsRemindedAtReminderHour() {
        task(null, Distribution.FIXED, lenaM.getId());

        reminders.run(at(7, 59));
        assertThat(of(NotificationType.CHORE_DUE)).isEmpty();
        reminders.run(at(8, 0));
        assertThat(of(NotificationType.CHORE_DUE)).extracting(Notification::getUserId).containsExactly(lena.getId());
    }

    @Test
    void openTaskWithoutAssigneeRemindsAllCandidatesWithAccount() {
        task(null, Distribution.OPEN, papaM.getId(), lenaM.getId(), baby.getId());

        reminders.run(at(9, 0));

        assertThat(of(NotificationType.CHORE_DUE)).extracting(Notification::getUserId)
                .containsExactlyInAnyOrder(papa.getId(), lena.getId());
    }

    @Test
    void doneTaskIsNotReminded() {
        ChoreTask t = task(null, Distribution.FIXED, lenaM.getId());
        taskService.complete(t.getId(), lena, null, Set.of());

        reminders.run(at(9, 0));

        assertThat(of(NotificationType.CHORE_DUE)).isEmpty();
    }

    @Test
    void overdueTaskIsRemindedOnce() {
        ChoreTask t = task(null, Distribution.FIXED, lenaM.getId());
        jdbc.update("UPDATE chore_tasks SET due_on = ?, notified_due = TRUE WHERE id = ?", today.minusDays(1), t.getId());
        entityManager.clear();

        reminders.run(at(8, 30));
        reminders.run(at(9, 30));

        assertThat(of(NotificationType.CHORE_OVERDUE)).extracting(Notification::getUserId).containsExactly(lena.getId());
    }

    @Test
    void handingOverNotifiesTheNewAssignee() {
        ChoreTask t = task(null, Distribution.ROTATION, papaM.getId(), lenaM.getId());
        assertThat(t.getAssigneeMemberId()).isEqualTo(papaM.getId());

        taskService.assign(t.getId(), lenaM.getId(), papa);

        assertThat(of(NotificationType.CHORE_ASSIGNED)).extracting(Notification::getUserId).containsExactly(lena.getId());
    }
}
