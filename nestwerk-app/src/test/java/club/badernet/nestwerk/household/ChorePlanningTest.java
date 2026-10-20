package club.badernet.nestwerk.household;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class ChorePlanningTest {

    @Autowired ChoreService chores;
    @Autowired ChorePlanner planner;
    @Autowired ChoreTaskService taskService;
    @Autowired ChoreTaskRepository tasks;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired FamilyMemberRepository members;
    @Autowired CalendarEventService events;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    User admin;
    User mama;
    FamilyMember mamaM;
    FamilyMember lena;
    FamilyMember tim;
    LocalDate today;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        mama = userService.create("mama", "passwort1", Set.of(benutzer));
        mamaM = members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        lena = members.save(new FamilyMember("Lena", "#00aa00", null));
        tim = members.save(new FamilyMember("Tim", "#0000aa", null));
        today = LocalDate.now(clock);
    }

    private ChoreInput input(ChoreKind kind, String rrule, Integer interval, Distribution distribution, Long... ids) {
        return new ChoreInput("Müll", null, null, 10, 10, 10, kind, rrule, today, null, interval,
                distribution, List.of(ids));
    }

    private List<ChoreTask> tasksOf(Chore c) {
        entityManager.flush();
        return tasks.findAllByChoreId(c.getId()).stream()
                .sorted(Comparator.comparing(ChoreTask::getDueOn)).toList();
    }

    @Test
    void timeSetByHandSurvivesReplanningUntilAssigneeIsAbsent() {
        Chore c = chores.create(input(ChoreKind.RECURRING, "FREQ=DAILY;COUNT=2", null, Distribution.FIXED,
                lena.getId()), admin);
        ChoreTask task = tasksOf(c).getLast();

        taskService.reschedule(task.getId(), LocalTime.of(20, 15), admin);
        planner.reassignAbsent(today);

        assertThat(task.getPlannedAt()).isEqualTo(LocalTime.of(20, 15));
        assertThat(task.isPlannedManually()).isTrue();

        events.create(new EventInput("Krank", null, null, task.getDueOn(), null, task.getDueOn(), null,
                true, null, false, Set.of(lena.getId()), true), admin);
        entityManager.flush();
        planner.reassignAbsent(today);
        assertThat(task.isPlannedManually()).isFalse();
    }

    @Test
    void rescheduleNeedsATimeAndAnOpenTask() {
        Chore c = chores.create(input(ChoreKind.RECURRING, "FREQ=DAILY;COUNT=1", null, Distribution.FIXED,
                lena.getId()), admin);
        ChoreTask task = tasksOf(c).getFirst();

        assertThatThrownBy(() -> taskService.reschedule(task.getId(), null, admin))
                .isInstanceOf(IllegalArgumentException.class);
        taskService.complete(task.getId(), admin, lena.getId(), Set.of(), ChoreTaskService.Booking.now());
        assertThatThrownBy(() -> taskService.reschedule(task.getId(), LocalTime.of(9, 0), admin))
                .isInstanceOf(ChoreConflictException.class);
    }

    @Test
    void recurringPlansWholeHorizon() {
        Chore c = chores.create(new ChoreInput("Spülmaschine", "Küche", null, 15, 15, 15,
                ChoreKind.RECURRING, "FREQ=DAILY", today, LocalTime.of(7, 0), null, Distribution.FIXED,
                List.of(lena.getId())), admin);

        List<ChoreTask> planned = tasksOf(c);
        assertThat(planned).hasSize(ChorePlanner.HORIZON_DAYS + 1);
        assertThat(planned.getFirst().getDueOn()).isEqualTo(today);
        assertThat(planned.getFirst().getDueTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(planned).allMatch(t -> t.getAssigneeMemberId().equals(lena.getId()));

        planner.plan(c, today);
        assertThat(tasksOf(c)).hasSize(ChorePlanner.HORIZON_DAYS + 1);
    }

    @Test
    void rotationGoesRoundAndSkipsAbsentMembers() {
        events.create(new EventInput("Klassenfahrt", null, null, today.plusDays(1), null, today.plusDays(1), null,
                true, null, false, Set.of(tim.getId()), true), admin);

        Chore c = chores.create(input(ChoreKind.RECURRING, "FREQ=DAILY;COUNT=4", null, Distribution.ROTATION,
                lena.getId(), tim.getId(), mamaM.getId()), admin);

        assertThat(tasksOf(c)).extracting(ChoreTask::getAssigneeMemberId)
                .containsExactly(lena.getId(), mamaM.getId(), lena.getId(), tim.getId());
    }

    @Test
    void rotationWithEverybodyAbsentLeavesTaskOpen() {
        events.create(new EventInput("Urlaub", null, null, today, null, today, null,
                true, null, false, Set.of(lena.getId(), tim.getId()), true), admin);

        Chore c = chores.create(input(ChoreKind.RECURRING, "FREQ=DAILY;COUNT=1", null, Distribution.ROTATION,
                lena.getId(), tim.getId()), admin);

        assertThat(tasksOf(c)).singleElement().satisfies(t -> assertThat(t.getAssigneeMemberId()).isNull());
    }

    @Test
    void balancedPicksMemberWithLeastMinutes() {
        chores.create(new ChoreInput("Bad", null, null, 60, 60, 60, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=1", today, null, null, Distribution.FIXED, List.of(lena.getId())), admin);

        Chore c = chores.create(input(ChoreKind.RECURRING, "FREQ=DAILY;COUNT=1", null, Distribution.BALANCED,
                lena.getId(), tim.getId()), admin);

        assertThat(tasksOf(c)).singleElement()
                .satisfies(t -> assertThat(t.getAssigneeMemberId()).isEqualTo(tim.getId()));
    }

    @Test
    void openDistributionHasNoAssignee() {
        Chore c = chores.create(input(ChoreKind.RECURRING, "FREQ=DAILY;COUNT=2", null, Distribution.OPEN), admin);

        assertThat(tasksOf(c)).hasSize(2).allMatch(t -> t.getAssigneeMemberId() == null);
    }

    @Test
    void fixedDatesBecomeTasksWithinHorizonOnly() {
        Chore c = chores.create(new ChoreInput("Gelbe Tonne", null, null, 5, 5, 5, ChoreKind.DATES,
                null, null, null, null, Distribution.FIXED, List.of(tim.getId())), admin);
        chores.addDate(c.getId(), today.plusDays(3), LocalTime.of(19, 0), admin);
        chores.addDate(c.getId(), today.plusDays(60), LocalTime.of(19, 0), admin);
        chores.addDate(c.getId(), today.minusDays(2), null, admin);

        assertThat(tasksOf(c)).singleElement().satisfies(t -> {
            assertThat(t.getDueOn()).isEqualTo(today.plusDays(3));
            assertThat(t.getSlotKey()).isEqualTo(today.plusDays(3) + "T19:00");
        });
    }

    @Test
    void removingDateRemovesItsOpenTask() {
        Chore c = chores.create(new ChoreInput("Gelbe Tonne", null, null, 5, 5, 5, ChoreKind.DATES,
                null, null, null, null, Distribution.FIXED, List.of(tim.getId())), admin);
        ChoreDate d = chores.addDate(c.getId(), today.plusDays(3), LocalTime.of(19, 0), admin);

        chores.removeDate(c.getId(), d.getId(), admin);

        assertThat(tasksOf(c)).isEmpty();
    }

    @Test
    void intervalKeepsExactlyOneOpenTask() {
        Chore c = chores.create(input(ChoreKind.INTERVAL, null, 7, Distribution.FIXED, lena.getId()), admin);

        assertThat(tasksOf(c)).singleElement().satisfies(t -> assertThat(t.getDueOn()).isEqualTo(today));
        planner.plan(c, today);
        assertThat(tasksOf(c)).hasSize(1);
    }

    @Test
    void editingReplansFutureOpenTasksOnly() {
        Chore c = chores.create(input(ChoreKind.RECURRING, "FREQ=DAILY;COUNT=3", null, Distribution.FIXED,
                lena.getId()), admin);

        chores.update(c.getId(), input(ChoreKind.RECURRING, "FREQ=DAILY;COUNT=3", null, Distribution.FIXED,
                tim.getId()), admin);

        assertThat(tasksOf(c)).hasSize(3).allMatch(t -> t.getAssigneeMemberId().equals(tim.getId()));
    }

    @Test
    void absenceAddedLaterReassignsRotationTask() {
        Chore c = chores.create(input(ChoreKind.RECURRING, "FREQ=DAILY;COUNT=1", null, Distribution.ROTATION,
                lena.getId(), tim.getId()), admin);
        assertThat(tasksOf(c).getFirst().getAssigneeMemberId()).isEqualTo(lena.getId());

        events.create(new EventInput("Krank", null, null, today, null, today, null,
                true, null, false, Set.of(lena.getId()), true), admin);
        planner.planAll(today);

        assertThat(tasksOf(c).getFirst().getAssigneeMemberId()).isEqualTo(tim.getId());
    }

    @Test
    void validatesInput() {
        assertThatThrownBy(() -> chores.create(new ChoreInput(" ", null, null, 10, 10, 10,
                ChoreKind.INTERVAL, null, today, null, 7, Distribution.OPEN, List.of()), admin))
                .hasMessageContaining("Titel");
        assertThatThrownBy(() -> chores.create(new ChoreInput("Bad", null, null, 0, 0, 0,
                ChoreKind.INTERVAL, null, today, null, 7, Distribution.OPEN, List.of()), admin))
                .hasMessageContaining("Minuten");
        assertThatThrownBy(() -> chores.create(new ChoreInput("Bad", null, null, 10, 10, 10,
                ChoreKind.INTERVAL, null, today, null, null, Distribution.OPEN, List.of()), admin))
                .hasMessageContaining("Tage");
        assertThatThrownBy(() -> chores.create(new ChoreInput("Bad", null, null, 10, 10, 10,
                ChoreKind.RECURRING, "FREQ=QUARK", today, null, null, Distribution.OPEN, List.of()), admin))
                .hasMessageContaining("Wiederholungsregel");
        assertThatThrownBy(() -> chores.create(new ChoreInput("Bad", null, null, 10, 10, 10,
                ChoreKind.INTERVAL, null, today, null, 7, Distribution.FIXED, List.of(lena.getId(), tim.getId())), admin))
                .hasMessageContaining("genau ein");
        assertThatThrownBy(() -> chores.create(new ChoreInput("Bad", null, null, 10, 10, 10,
                ChoreKind.INTERVAL, null, today, null, 7, Distribution.ROTATION, List.of()), admin))
                .hasMessageContaining("mindestens ein");
        assertThatThrownBy(() -> chores.create(new ChoreInput("Bad", null, null, 10, 10, 10,
                ChoreKind.INTERVAL, null, today, null, 7, Distribution.FIXED, List.of(999_999L)), admin))
                .hasMessageContaining("Unbekanntes");
    }

    @Test
    void deleteIsRefusedOnceCreditsExistButDeactivateWorks() {
        Chore c = chores.create(input(ChoreKind.RECURRING, "FREQ=DAILY;COUNT=2", null, Distribution.FIXED,
                mamaM.getId()), admin);
        taskService.complete(tasksOf(c).getFirst().getId(), mama, null, Set.of(), null);

        assertThatThrownBy(() -> chores.delete(c.getId(), admin)).hasMessageContaining("deaktivieren");
        chores.deactivate(c.getId(), admin);

        assertThat(tasksOf(c)).singleElement().satisfies(t -> assertThat(t.isOpen()).isFalse());
        assertThat(chores.get(c.getId(), admin).isActive()).isFalse();
    }
}
