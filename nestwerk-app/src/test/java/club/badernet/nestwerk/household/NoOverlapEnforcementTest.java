package club.badernet.nestwerk.household;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.calendar.OverlapException;
import club.badernet.nestwerk.calendar.TimeChange;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A chore or event that forbids overlap is never put over another one; unflagged things may still overlap. */
@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class NoOverlapEnforcementTest {

    @Autowired ChoreService chores;
    @Autowired ChoreTaskService tasks;
    @Autowired ChoreTaskRepository taskRepository;
    @Autowired CalendarEventService events;
    @Autowired UserService userService;
    @Autowired FamilyMemberService memberService;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    User admin;
    FamilyMember lena;
    FamilyMember tim;
    LocalDate day;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        lena = memberService.create("Lena", "#8b6cff", null);
        tim = memberService.create("Tim", "#ff9f1c", null);
        day = LocalDate.now(clock).plusDays(1);
    }

    private CalendarEvent event(String title, int fromHour, int toHour, boolean noOverlap, Long member,
                                String rrule) {
        return events.create(new EventInput(title, null, null, day, LocalTime.of(fromHour, 0), day,
                LocalTime.of(toHour, 0), false, rrule, false, Set.of(member), false, null, noOverlap), admin);
    }

    private Chore chore(String title, boolean noOverlap) {
        return chores.create(new ChoreInput(title, null, null, 20, 30, 60, ChoreKind.ON_DEMAND, "FREQ=DAILY", day,
                null, null, null, List.of(), null, null, false, noOverlap), admin);
    }

    private ChoreTask planned(Chore chore, int hour, int minute, Long member) {
        return tasks.planOnce(chore.getId(), day, LocalTime.of(hour, minute), member, admin);
    }

    @Test
    void flaggedChoreCannotBePlannedOverAnEvent() {
        event("Zahnarzt", 10, 11, false, lena.getId(), null);

        assertThatThrownBy(() -> planned(chore("Fenster", true), 10, 30, lena.getId()))
                .isInstanceOf(OverlapException.class)
                .hasMessageContaining("Fenster").hasMessageContaining("Zahnarzt").hasMessageContaining("Lena")
                .hasMessageContaining("10:00–11:00");
    }

    @Test
    void chorePlannedOverAFlaggedEventIsRejectedEvenWithoutItsOwnFlag() {
        event("Operation", 10, 11, true, lena.getId(), null);

        assertThatThrownBy(() -> planned(chore("Fenster", false), 10, 15, lena.getId()))
                .isInstanceOf(OverlapException.class).hasMessageContaining("Operation");
    }

    @Test
    void withoutAnyFlagThingsMayOverlapAsBefore() {
        event("Zahnarzt", 10, 11, false, lena.getId(), null);

        assertThatCode(() -> planned(chore("Fenster", false), 10, 30, lena.getId())).doesNotThrowAnyException();
    }

    @Test
    void otherPeopleAndOtherTimesDoNotConflict() {
        event("Operation", 10, 11, true, lena.getId(), null);

        assertThatCode(() -> planned(chore("Fenster", true), 10, 30, tim.getId())).doesNotThrowAnyException();
        assertThatCode(() -> planned(chore("Bad", true), 11, 0, lena.getId())).doesNotThrowAnyException();
    }

    @Test
    void flaggedChoreCannotOverlapAnotherPlannedChore() {
        planned(chore("Bad putzen", false), 9, 0, lena.getId());

        assertThatThrownBy(() -> planned(chore("Fenster", true), 9, 20, lena.getId()))
                .isInstanceOf(OverlapException.class).hasMessageContaining("Bad putzen");
        assertThatCode(() -> planned(chore("Fenster", true), 9, 30, lena.getId())).doesNotThrowAnyException();
    }

    @Test
    void anUnflaggedChoreCannotBePutOverAFlaggedChore() {
        planned(chore("Fenster", true), 9, 0, lena.getId());

        assertThatThrownBy(() -> planned(chore("Bad", false), 9, 10, lena.getId()))
                .isInstanceOf(OverlapException.class).hasMessageContaining("Fenster");
    }

    @Test
    void movingAChoreOntoAFlaggedEventIsRejectedAndMovingWithinItsOwnSlotIsFine() {
        event("Operation", 10, 11, true, lena.getId(), null);
        ChoreTask task = planned(chore("Fenster", true), 8, 0, lena.getId());

        assertThatThrownBy(() -> tasks.reschedule(task.getId(), day, LocalTime.of(10, 30), admin))
                .isInstanceOf(OverlapException.class).hasMessageContaining("Operation");
        assertThatCode(() -> tasks.reschedule(task.getId(), day, LocalTime.of(8, 10), admin))
                .doesNotThrowAnyException();
    }

    @Test
    void reassigningWhileMovingChangesNothingWhenTheNewPersonIsBusy() {
        event("Operation", 10, 11, true, tim.getId(), null);
        ChoreTask task = planned(chore("Fenster", true), 8, 0, lena.getId());

        assertThatThrownBy(() -> tasks.reassignAndReschedule(task.getId(), tim.getId(), day, LocalTime.of(10, 0),
                admin)).isInstanceOf(OverlapException.class);
    }

    @Test
    void bookingAFlaggedChoreOverAnEventIsRejected() {
        LocalDate today = LocalDate.now(clock).minusDays(1);
        events.create(new EventInput("Besuch", null, null, today, LocalTime.of(10, 0), today, LocalTime.of(11, 0),
                false, null, false, Set.of(lena.getId()), false, null, false), admin);
        Chore flagged = chores.create(new ChoreInput("Fenster", null, null, 20, 30, 60, ChoreKind.ON_DEMAND,
                "FREQ=DAILY", today, null, null, null, List.of(), null, null, false, true), admin);

        assertThatThrownBy(() -> tasks.bookAdHoc(flagged.getId(), admin, lena.getId(), Set.of(),
                new ChoreTaskService.Booking(ChoreEffort.MEDIUM, 30, LocalDateTime.of(today, LocalTime.of(10, 15)))))
                .isInstanceOf(OverlapException.class).hasMessageContaining("Besuch");
        assertThatCode(() -> tasks.bookAdHoc(flagged.getId(), admin, lena.getId(), Set.of(),
                new ChoreTaskService.Booking(ChoreEffort.MEDIUM, 30, LocalDateTime.of(today, LocalTime.of(12, 0)))))
                .doesNotThrowAnyException();
    }

    @Test
    void flaggedEventCannotBeCreatedOverAPlannedChore() {
        planned(chore("Fenster", false), 10, 0, lena.getId());

        assertThatThrownBy(() -> event("Operation", 10, 12, true, lena.getId(), null))
                .isInstanceOf(OverlapException.class)
                .hasMessageContaining("Operation").hasMessageContaining("Fenster");
    }

    @Test
    void eventOverAFlaggedChoreIsRejectedEvenWithoutItsOwnFlag() {
        planned(chore("Fenster", true), 10, 0, lena.getId());

        assertThatThrownBy(() -> event("Kaffee", 10, 11, false, lena.getId(), null))
                .isInstanceOf(OverlapException.class).hasMessageContaining("Fenster");
        assertThatCode(() -> event("Kaffee", 11, 12, false, lena.getId(), null)).doesNotThrowAnyException();
    }

    @Test
    void unflaggedEventAndUnflaggedChoreMayOverlap() {
        planned(chore("Fenster", false), 10, 0, lena.getId());

        assertThatCode(() -> event("Kaffee", 10, 11, false, lena.getId(), null)).doesNotThrowAnyException();
    }

    @Test
    void aFlaggedSeriesIsCheckedForEveryOccurrence() {
        ChoreTask later = tasks.planOnce(chore("Fenster", false).getId(), day.plusDays(2), LocalTime.of(10, 0),
                lena.getId(), admin);
        assertThat(later.getDueOn()).isEqualTo(day.plusDays(2));

        assertThatThrownBy(() -> event("Chor", 10, 11, true, lena.getId(), "FREQ=DAILY;COUNT=5"))
                .isInstanceOf(OverlapException.class).hasMessageContaining("Fenster");
    }

    @Test
    void updatingAndMovingAnEventAreCheckedToo() {
        planned(chore("Fenster", false), 14, 0, lena.getId());
        CalendarEvent flagged = event("Operation", 10, 11, true, lena.getId(), null);

        assertThatThrownBy(() -> events.update(flagged.getId(), new EventInput("Operation", null, null, day,
                LocalTime.of(13, 30), day, LocalTime.of(14, 30), false, null, false, Set.of(lena.getId()), false,
                null, true), admin)).isInstanceOf(OverlapException.class);
        assertThatThrownBy(() -> events.reschedule(flagged.getId(), new TimeChange.Timed(
                LocalDateTime.of(day, LocalTime.of(14, 15)), LocalDateTime.of(day, LocalTime.of(15, 0))), admin))
                .isInstanceOf(OverlapException.class);
        assertThatCode(() -> events.reschedule(flagged.getId(), new TimeChange.Timed(
                LocalDateTime.of(day, LocalTime.of(15, 0)), LocalDateTime.of(day, LocalTime.of(16, 0))), admin))
                .doesNotThrowAnyException();
    }

    @Test
    void plannerKeepsAFlaggedChoreOutOfAnEvent() {
        event("Operation", 7, 9, true, lena.getId(), null);
        Chore daily = chores.create(new ChoreInput("Spülmaschine", null, null, 30, 30, 30, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=3", day, null, null, Distribution.FIXED, List.of(lena.getId()), null, null, false,
                true), admin);
        entityManager.flush();

        ChoreTask first = taskRepository.findAllByChoreId(daily.getId()).stream()
                .filter(t -> t.getDueOn().equals(day)).findFirst().orElseThrow();

        assertThat(first.getPlannedAt()).isNotNull().isAfterOrEqualTo(LocalTime.of(9, 0));
    }
}
