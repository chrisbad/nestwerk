package club.badernet.nestwerk.household;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class ChoreTaskServiceTest {

    @Autowired ChoreService chores;
    @Autowired ChoreTaskService service;
    @Autowired ChoreTaskRepository tasks;
    @Autowired ChorePlanner planner;
    @Autowired ChoreCreditRepository credits;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    User admin;
    User mama;
    User papa;
    User kid;
    User stranger;
    FamilyMember mamaM;
    FamilyMember papaM;
    FamilyMember kidM;
    FamilyMember baby;
    LocalDate today;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        Role eltern = roleService.create("Eltern", null, Set.of());
        acl.applyPreset(admin, eltern.getId(), "ELTERNTEIL");
        Role kinder = roleService.create("Kinder", null, Set.of());
        acl.applyPreset(admin, kinder.getId(), "KIND");
        Role leer = roleService.create("Leer", null, Set.of());
        mama = userService.create("mama", "passwort1", Set.of(eltern.getId()));
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
        kid = userService.create("kind", "passwort1", Set.of(kinder.getId()));
        stranger = userService.create("fremd", "passwort1", Set.of(leer.getId()));
        mamaM = members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        papaM = members.save(new FamilyMember("Papa", "#00aa00", papa.getId()));
        kidM = members.save(new FamilyMember("Kind", "#0000aa", kid.getId()));
        baby = members.save(new FamilyMember("Baby", "#aaaa00", null));
        today = LocalDate.now(clock);
    }

    private ChoreTask firstTask(ChoreKind kind, Integer interval, Distribution distribution, Long... ids) {
        Chore c = chores.create(new ChoreInput("Küche aufräumen", null, null, 20, 20, 20, kind,
                kind == ChoreKind.RECURRING ? "FREQ=DAILY;COUNT=2" : null, today, null, interval, distribution,
                List.of(ids)), admin);
        entityManager.flush();
        return tasks.findAllByChoreId(c.getId()).stream().min(Comparator.comparing(ChoreTask::getDueOn)).orElseThrow();
    }

    private ChoreTask kidsTask() {
        return firstTask(ChoreKind.RECURRING, null, Distribution.FIXED, kidM.getId());
    }

    @Test
    void completingCreditsDoerAndEveryHelperWithFullMinutes() {
        ChoreTask t = kidsTask();

        service.complete(t.getId(), kid, null, Set.of(papaM.getId(), kidM.getId()), null);

        entityManager.flush();
        assertThat(credits.findAllByTaskId(t.getId()))
                .extracting(ChoreCredit::getMemberId, ChoreCredit::getMinutes, ChoreCredit::getEffort)
                .containsExactlyInAnyOrder(tuple(kidM.getId(), 20, ChoreEffort.MEDIUM),
                        tuple(papaM.getId(), 20, ChoreEffort.MEDIUM));
        ChoreTask done = tasks.findById(t.getId()).orElseThrow();
        assertThat(done.isOpen()).isFalse();
        assertThat(done.getCompletedByUser()).isEqualTo(kid.getId());
    }

    @Test
    void completingTwiceIsAConflict() {
        ChoreTask t = kidsTask();
        service.complete(t.getId(), kid, null, Set.of(), null);

        assertThatThrownBy(() -> service.complete(t.getId(), papa, null, Set.of(), null))
                .isInstanceOf(ChoreConflictException.class);
    }

    @Test
    void childCannotCompleteSomebodyElsesTask() {
        ChoreTask t = firstTask(ChoreKind.RECURRING, null, Distribution.FIXED, papaM.getId());

        assertThatThrownBy(() -> service.complete(t.getId(), kid, null, Set.of(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void completingForAnotherMemberNeedsTheAnyRight() {
        ChoreTask t = kidsTask();

        assertThatThrownBy(() -> service.complete(t.getId(), kid, papaM.getId(), Set.of(), null))
                .isInstanceOf(AccessDeniedException.class);

        service.complete(t.getId(), mama, kidM.getId(), Set.of(), null);
        entityManager.flush();
        assertThat(credits.findAllByTaskId(t.getId())).extracting(ChoreCredit::getMemberId)
                .containsExactly(kidM.getId());
    }

    @Test
    void takingOverCreditsTheOneWhoDidIt() {
        ChoreTask t = kidsTask();

        service.complete(t.getId(), papa, null, Set.of(), null);

        entityManager.flush();
        assertThat(credits.findAllByTaskId(t.getId())).extracting(ChoreCredit::getMemberId)
                .containsExactly(papaM.getId());
    }

    @Test
    void userWithoutMemberMustChooseWhoDidIt() {
        ChoreTask t = kidsTask();

        assertThatThrownBy(() -> service.complete(t.getId(), admin, null, Set.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mitglied");
    }

    @Test
    void undoRemovesCreditsForCompleterOrCorrector() {
        ChoreTask t = kidsTask();
        service.complete(t.getId(), kid, null, Set.of(papaM.getId()), null);

        assertThatThrownBy(() -> service.undo(t.getId(), papa)).isInstanceOf(AccessDeniedException.class);
        service.undo(t.getId(), kid);

        entityManager.flush();
        assertThat(credits.findAllByTaskId(t.getId())).isEmpty();
        assertThat(tasks.findById(t.getId()).orElseThrow().isOpen()).isTrue();

        service.complete(t.getId(), kid, null, Set.of(), null);
        service.undo(t.getId(), mama);
        entityManager.flush();
        assertThat(tasks.findById(t.getId()).orElseThrow().isOpen()).isTrue();
    }

    @Test
    void intervalCompletionPlansSuccessorAndUndoRemovesIt() {
        ChoreTask t = firstTask(ChoreKind.INTERVAL, 7, Distribution.FIXED, kidM.getId());

        service.complete(t.getId(), kid, null, Set.of(), null);
        entityManager.flush();
        List<ChoreTask> open = tasks.findAllByChoreIdAndStatus(t.getChore().getId(), ChoreTaskStatus.OPEN);
        assertThat(open).singleElement().satisfies(n -> assertThat(n.getDueOn()).isEqualTo(today.plusDays(7)));

        service.undo(t.getId(), kid);
        entityManager.flush();
        assertThat(tasks.findAllByChoreIdAndStatus(t.getChore().getId(), ChoreTaskStatus.OPEN))
                .singleElement().satisfies(n -> assertThat(n.getId()).isEqualTo(t.getId()));
    }

    private Chore staged(ChoreKind kind, Integer interval) {
        Chore c = chores.create(new ChoreInput("Bad putzen", null, null, 15, 30, 60, kind,
                kind == ChoreKind.RECURRING ? "FREQ=DAILY;COUNT=2" : null, null, null, interval,
                Distribution.FIXED, List.of(kidM.getId())), admin);
        entityManager.flush();
        return c;
    }

    @Test
    void bookingUsesChosenEffortAndAdjustedDurationForEveryone() {
        ChoreTask t = tasks.findAllByChoreId(staged(ChoreKind.RECURRING, null).getId()).getFirst();

        service.complete(t.getId(), kid, null, Set.of(papaM.getId()),
                new ChoreTaskService.Booking(ChoreEffort.HARD, 50, null));

        entityManager.flush();
        assertThat(credits.findAllByTaskId(t.getId()))
                .extracting(ChoreCredit::getMemberId, ChoreCredit::getMinutes, ChoreCredit::getEffort)
                .containsExactlyInAnyOrder(tuple(kidM.getId(), 50, ChoreEffort.HARD),
                        tuple(papaM.getId(), 50, ChoreEffort.HARD));
    }

    @Test
    void bookingWithoutDurationTakesTheMinutesOfTheEffort() {
        ChoreTask t = tasks.findAllByChoreId(staged(ChoreKind.RECURRING, null).getId()).getFirst();

        service.complete(t.getId(), kid, null, Set.of(), new ChoreTaskService.Booking(ChoreEffort.EASY, null, null));

        entityManager.flush();
        assertThat(credits.findAllByTaskId(t.getId())).extracting(ChoreCredit::getMinutes).containsExactly(15);
    }

    @Test
    void bookingMovesTaskToPerformedTimeAndUndoRestoresThePlannedSlot() {
        ChoreTask t = kidsTask();
        String slot = t.getSlotKey();
        LocalDate planned = t.getDueOn();
        LocalDateTime performed = today.minusDays(2).atTime(17, 45);

        service.complete(t.getId(), kid, null, Set.of(), new ChoreTaskService.Booking(null, null, performed));
        entityManager.flush();
        ChoreTask done = tasks.findById(t.getId()).orElseThrow();
        assertThat(done.getDueOn()).isEqualTo(today.minusDays(2));
        assertThat(done.getDueTime()).isEqualTo(LocalTime.of(17, 45));
        assertThat(done.getSlotKey()).isEqualTo(slot);
        assertThat(credits.findAllByTaskId(t.getId())).singleElement()
                .satisfies(c -> assertThat(c.getCreatedAt()).isEqualTo(performed.atZone(clock.getZone()).toInstant()));

        // am selben Tag gebucht, also darf das Kind zurücknehmen, auch wenn die Erledigung zurückliegt
        service.undo(t.getId(), kid);
        entityManager.flush();
        ChoreTask reopened = tasks.findById(t.getId()).orElseThrow();
        assertThat(reopened.getDueOn()).isEqualTo(planned);
        assertThat(reopened.getDueTime()).isNull();
        assertThat(reopened.getEffort()).isNull();
    }

    @Test
    void movedTaskIsNotPlannedAgain() {
        Chore c = staged(ChoreKind.RECURRING, null);
        ChoreTask t = tasks.findAllByChoreId(c.getId()).stream().min(Comparator.comparing(ChoreTask::getDueOn))
                .orElseThrow();
        service.complete(t.getId(), kid, null, Set.of(),
                new ChoreTaskService.Booking(null, null, today.minusDays(1).atTime(9, 0)));
        entityManager.flush();

        planner.plan(c, today);
        entityManager.flush();
        assertThat(tasks.findAllByChoreId(c.getId())).hasSize(2);
    }

    @Test
    void bookingInTheFutureIsRejected() {
        ChoreTask t = kidsTask();

        assertThatThrownBy(() -> service.complete(t.getId(), kid, null, Set.of(),
                new ChoreTaskService.Booking(null, null, LocalDateTime.now(clock).plusHours(2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Zukunft");
        assertThatThrownBy(() -> service.complete(t.getId(), kid, null, Set.of(),
                new ChoreTaskService.Booking(ChoreEffort.EASY, 0, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onDemandPlansNothingAndIsBookedAdHoc() {
        Chore c = chores.create(new ChoreInput("Fenster", null, null, 20, 40, 90, ChoreKind.ON_DEMAND,
                "FREQ=DAILY", today, null, null, null, List.of()), admin);
        entityManager.flush();
        assertThat(tasks.findAllByChoreId(c.getId())).isEmpty();
        assertThat(c.getDistribution()).isEqualTo(Distribution.OPEN);
        assertThat(c.getStartsOn()).isNull();

        ChoreTask booked = service.bookAdHoc(c.getId(), kid, null, Set.of(),
                new ChoreTaskService.Booking(ChoreEffort.HARD, null, null));
        entityManager.flush();
        assertThat(booked.isOpen()).isFalse();
        assertThat(booked.isAdHoc()).isTrue();
        assertThat(credits.findAllByTaskId(booked.getId())).extracting(ChoreCredit::getMinutes).containsExactly(90);

        service.undo(booked.getId(), kid);
        entityManager.flush();
        assertThat(tasks.findAllByChoreId(c.getId())).isEmpty();
    }

    private Chore onDemandChore(boolean oncePerDay) {
        return chores.create(new ChoreInput("Spülmaschine", null, null, 10, 10, 10, ChoreKind.ON_DEMAND,
                "FREQ=DAILY", today, null, null, null, List.of(), null, null, oncePerDay), admin);
    }

    @Test
    void oncePerDayChoreRejectsSecondBookingOnTheSameDay() {
        Chore c = onDemandChore(true);
        service.bookAdHoc(c.getId(), kid, null, Set.of(), ChoreTaskService.Booking.now());
        entityManager.flush();

        assertThatThrownBy(() -> service.bookAdHoc(c.getId(), kid, null, Set.of(), ChoreTaskService.Booking.now()))
                .isInstanceOf(ChoreConflictException.class)
                .hasMessageContaining("einmal pro Tag");
        entityManager.flush();
        assertThat(credits.findAll().stream().filter(cr -> cr.getTask().getChore().getId().equals(c.getId())))
                .hasSize(1);
    }

    @Test
    void choreWithoutLimitCanBeBookedSeveralTimesPerDay() {
        Chore c = onDemandChore(false);

        service.bookAdHoc(c.getId(), kid, null, Set.of(), ChoreTaskService.Booking.now());
        service.bookAdHoc(c.getId(), kid, null, Set.of(), ChoreTaskService.Booking.now());
        entityManager.flush();

        assertThat(tasks.findAllByChoreIdAndStatus(c.getId(), ChoreTaskStatus.DONE)).hasSize(2);
    }

    @Test
    void oncePerDayChoreAllowsBookingAgainAfterUndo() {
        Chore c = onDemandChore(true);
        ChoreTask first = service.bookAdHoc(c.getId(), kid, null, Set.of(), ChoreTaskService.Booking.now());
        entityManager.flush();
        service.undo(first.getId(), kid);
        entityManager.flush();

        service.bookAdHoc(c.getId(), kid, null, Set.of(), ChoreTaskService.Booking.now());
        entityManager.flush();

        assertThat(tasks.findAllByChoreIdAndStatus(c.getId(), ChoreTaskStatus.DONE)).hasSize(1);
    }

    @Test
    void oncePerDayChoreRejectsCheckingOffAPlannedTaskThatWasAlreadyBooked() {
        Chore c = chores.create(new ChoreInput("Pflanzen gießen", null, null, 10, 10, 10, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=1", today, null, null, Distribution.FIXED, List.of(kidM.getId()), null, null, true),
                admin);
        entityManager.flush();
        service.bookAdHoc(c.getId(), kid, null, Set.of(), ChoreTaskService.Booking.now());
        entityManager.flush();
        ChoreTask planned = tasks.findAllByChoreIdAndStatus(c.getId(), ChoreTaskStatus.OPEN).getFirst();

        assertThatThrownBy(() -> service.complete(planned.getId(), kid, null, Set.of(), ChoreTaskService.Booking.now()))
                .isInstanceOf(ChoreConflictException.class);
    }

    @Test
    void adHocBookingOfIntervalChoreReplacesTheOpenTask() {
        Chore c = staged(ChoreKind.INTERVAL, 7);
        assertThat(tasks.findAllByChoreIdAndStatus(c.getId(), ChoreTaskStatus.OPEN)).hasSize(1);

        service.bookAdHoc(c.getId(), kid, null, Set.of(), ChoreTaskService.Booking.now());
        entityManager.flush();

        assertThat(tasks.findAllByChoreIdAndStatus(c.getId(), ChoreTaskStatus.OPEN)).singleElement()
                .satisfies(n -> assertThat(n.getDueOn()).isEqualTo(today.plusDays(7)));
    }

    @Test
    void recurringWithoutStartDateStartsToday() {
        Chore c = staged(ChoreKind.RECURRING, null);

        assertThat(c.getStartsOn()).isEqualTo(today);
    }

    @Test
    void strangerCannotBookAdHoc() {
        Chore c = staged(ChoreKind.ON_DEMAND, null);

        assertThat(service.canBook(kid, c)).isTrue();
        assertThat(service.canBook(papa, c)).isTrue();
        assertThatThrownBy(() -> service.bookAdHoc(c.getId(), stranger, null, Set.of(), null))
                .isInstanceOf(ChoreNotFoundException.class);
    }

    @Test
    void assignedMemberMayHandOverOnlyToCandidates() {
        ChoreTask t = firstTask(ChoreKind.RECURRING, null, Distribution.ROTATION, kidM.getId(), papaM.getId());
        assertThat(t.getAssigneeMemberId()).isEqualTo(kidM.getId());

        assertThatThrownBy(() -> service.assign(t.getId(), baby.getId(), kid))
                .isInstanceOf(AccessDeniedException.class);
        service.assign(t.getId(), papaM.getId(), kid);
        assertThat(tasks.findById(t.getId()).orElseThrow().getAssigneeMemberId()).isEqualTo(papaM.getId());

        service.assign(t.getId(), baby.getId(), mama);
        assertThat(tasks.findById(t.getId()).orElseThrow().getAssigneeMemberId()).isEqualTo(baby.getId());
    }

    @Test
    void invisibleForUsersWithoutViewRule() {
        ChoreTask t = kidsTask();

        assertThatThrownBy(() -> service.get(t.getId(), stranger)).isInstanceOf(ChoreNotFoundException.class);
        assertThat(service.get(t.getId(), kid).getId()).isEqualTo(t.getId());
    }

    @Test
    void mineListsOwnDueTasksAndOpenOnesToTake() {
        ChoreTask own = kidsTask();
        ChoreTask open = firstTask(ChoreKind.RECURRING, null, Distribution.OPEN);
        firstTask(ChoreKind.RECURRING, null, Distribution.FIXED, papaM.getId());

        assertThat(service.mine(kid, today)).extracting(ChoreTask::getId)
                .containsExactlyInAnyOrder(own.getId(), open.getId());
    }

    @Test
    void planOnceGivesAnOnDemandChoreADayWithoutChangingItsRhythm() {
        Chore c = staged(ChoreKind.ON_DEMAND, null);

        ChoreTask planned = service.planOnce(c.getId(), today.plusDays(2), LocalTime.of(17, 30), kidM.getId(), kid);
        entityManager.flush();

        assertThat(planned.isOpen()).isTrue();
        assertThat(planned.getDueOn()).isEqualTo(today.plusDays(2));
        assertThat(planned.getPlannedAt()).isEqualTo(LocalTime.of(17, 30));
        assertThat(planned.getAssigneeMemberId()).isEqualTo(kidM.getId());
        assertThat(tasks.findAllByChoreId(c.getId())).hasSize(1);
        planner.planAll(today);
        assertThat(tasks.findAllByChoreId(c.getId())).hasSize(1);
    }

    @Test
    void plannedOnceTaskCanBeCompletedAndUndoingRemovesIt() {
        Chore c = staged(ChoreKind.ON_DEMAND, null);
        ChoreTask planned = service.planOnce(c.getId(), today, null, kidM.getId(), kid);

        service.complete(planned.getId(), kid, null, Set.of(), null);
        entityManager.flush();
        assertThat(credits.findAllByTaskId(planned.getId())).hasSize(1);

        service.undo(planned.getId(), kid);
        entityManager.flush();
        assertThat(tasks.findAllByChoreId(c.getId())).isEmpty();
    }

    @Test
    void planOnceWithoutMemberLeavesTheTaskOpenToTake() {
        Chore c = staged(ChoreKind.ON_DEMAND, null);

        ChoreTask planned = service.planOnce(c.getId(), today.plusDays(1), null, null, papa);

        assertThat(planned.getAssigneeMemberId()).isNull();
        assertThat(planned.getPlannedAt()).isNull();
    }

    @Test
    void planOnceRejectsThePastAndUnknownMembers() {
        Chore c = staged(ChoreKind.ON_DEMAND, null);

        assertThatThrownBy(() -> service.planOnce(c.getId(), today.minusDays(1), null, null, kid))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Vergangenheit");
        assertThatThrownBy(() -> service.planOnce(c.getId(), today, null, 999_999L, mama))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Familienmitglied");
        assertThatThrownBy(() -> service.planOnce(c.getId(), null, null, null, kid))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Tag");
    }

    @Test
    void planningForSomebodyElseNeedsTheAssignRight() {
        Chore c = staged(ChoreKind.ON_DEMAND, null);

        assertThatThrownBy(() -> service.planOnce(c.getId(), today, null, papaM.getId(), kid))
                .isInstanceOf(AccessDeniedException.class);
        ChoreTask byParent = service.planOnce(c.getId(), today, null, kidM.getId(), mama);

        assertThat(byParent.getAssigneeMemberId()).isEqualTo(kidM.getId());
    }

    @Test
    void strangerCannotPlanOnce() {
        Chore c = staged(ChoreKind.ON_DEMAND, null);

        assertThatThrownBy(() -> service.planOnce(c.getId(), today, null, null, stranger))
                .isInstanceOf(ChoreNotFoundException.class);
    }

    @Test
    void skippingMarksTheTaskWithoutCreditingAnything() {
        ChoreTask t = kidsTask();

        service.skip(t.getId(), kid);

        entityManager.flush();
        ChoreTask skipped = tasks.findById(t.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(ChoreTaskStatus.SKIPPED);
        assertThat(skipped.isOpen()).isFalse();
        assertThat(credits.findAllByTaskId(t.getId())).isEmpty();
        assertThat(service.between(admin, t.getDueOn(), t.getDueOn())).extracting(ChoreTask::getStatus)
                .contains(ChoreTaskStatus.SKIPPED);
    }

    @Test
    void skippedTaskIsNotPlannedAgain() {
        ChoreTask t = kidsTask();
        service.skip(t.getId(), kid);
        entityManager.flush();

        planner.plan(t.getChore(), today);
        entityManager.flush();

        assertThat(tasks.findAllByChoreId(t.getChore().getId()).stream()
                .filter(other -> other.getDueOn().equals(t.getDueOn()))).singleElement()
                .satisfies(only -> assertThat(only.isSkipped()).isTrue());
    }

    @Test
    void intervalChoreContinuesItsRhythmAfterSkipping() {
        ChoreTask t = firstTask(ChoreKind.INTERVAL, 7, Distribution.FIXED, kidM.getId());

        service.skip(t.getId(), kid);
        entityManager.flush();

        assertThat(tasks.findAllByChoreIdAndStatus(t.getChore().getId(), ChoreTaskStatus.OPEN))
                .singleElement().satisfies(next -> assertThat(next.getDueOn()).isEqualTo(t.getDueOn().plusDays(7)));
    }

    @Test
    void undoRestoresASkippedTaskAndDropsTheSuccessor() {
        ChoreTask t = firstTask(ChoreKind.INTERVAL, 7, Distribution.FIXED, kidM.getId());
        service.skip(t.getId(), kid);
        entityManager.flush();

        service.undo(t.getId(), kid);
        entityManager.flush();

        assertThat(tasks.findAllByChoreIdAndStatus(t.getChore().getId(), ChoreTaskStatus.OPEN))
                .singleElement().satisfies(open -> assertThat(open.getId()).isEqualTo(t.getId()));
        assertThat(tasks.findById(t.getId()).orElseThrow().getCompletedAt()).isNull();
    }

    @Test
    void skippingNeedsTheRightToCompleteTheTask() {
        ChoreTask t = firstTask(ChoreKind.RECURRING, null, Distribution.FIXED, papaM.getId());

        assertThatThrownBy(() -> service.skip(t.getId(), kid)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aSkippedOrDoneTaskCannotBeSkippedOrCompletedAgain() {
        ChoreTask t = kidsTask();
        service.skip(t.getId(), kid);

        assertThatThrownBy(() -> service.skip(t.getId(), kid)).isInstanceOf(ChoreConflictException.class)
                .hasMessageContaining("nicht nötig");
        assertThatThrownBy(() -> service.complete(t.getId(), kid, null, Set.of(), null))
                .isInstanceOf(ChoreConflictException.class);
    }

    @Test
    void aTaskPlannedOnceCannotBeSkipped() {
        Chore c = staged(ChoreKind.ON_DEMAND, null);
        ChoreTask once = service.planOnce(c.getId(), today, null, kidM.getId(), mama);

        assertThatThrownBy(() -> service.skip(once.getId(), kid)).isInstanceOf(ChoreConflictException.class);
    }
}
