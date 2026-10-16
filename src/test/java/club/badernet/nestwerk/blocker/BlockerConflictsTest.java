package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class BlockerConflictsTest {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Montag, 12.10.2026, 07:00 Uhr. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(ZonedDateTime.of(2026, 10, 12, 7, 0, 0, 0, BERLIN).toInstant(), BERLIN);
        }
    }

    @Autowired BlockerConflicts conflicts;
    @Autowired RestGoals restGoals;
    @Autowired BlockerService blockers;
    @Autowired CalendarEventService events;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberService memberService;

    User lenaUser;
    User chrisUser;
    User admin;
    FamilyMember lena;
    FamilyMember chris;
    FamilyMember kid;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Normal", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN"));
        lenaUser = userService.create("lena", "passwort1", Set.of(normal.getId()));
        chrisUser = userService.create("chris", "passwort1", Set.of(normal.getId()));
        admin = userService.createAdministrator("chef", "passwort1");
        lena = memberService.create("Lena", "#8b6cff", lenaUser.getId());
        chris = memberService.create("Chris", "#ff9f1c", chrisUser.getId());
        kid = memberService.create("Lio", "#2ee6a8", null);
    }

    static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, BERLIN).toInstant();
    }

    static TimeRange range(int day, int fromHour, int toHour) {
        return new TimeRange(at(day, fromHour, 0), at(day, toHour, 0));
    }

    Blocker work(FamilyMember who, BlockerStrength strength, BlockerVisibility visibility, Set<Long> planners) {
        return blockers.create(new BlockerInput("Büro", BlockerKind.WORK, LocalTime.of(8, 0), LocalTime.of(16, 0),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalDate.of(2026, 10, 1), null, strength,
                visibility, false, false, Set.of(who.getId()), planners, Set.of()), admin);
    }

    @Test
    void eventDuringWorkWarnsWithReadableText() {
        work(lena, BlockerStrength.WARN, BlockerVisibility.TITLE, Set.of());

        List<Conflict> found = conflicts.check(Set.of(lena.getId()), List.of(range(13, 10, 11)), chrisUser, null);

        assertThat(found).singleElement().satisfies(c -> {
            assertThat(c.strength()).isEqualTo(BlockerStrength.WARN);
            assertThat(c.memberId()).isEqualTo(lena.getId());
            assertThat(c.message()).isEqualTo("Lena arbeitet da (Büro, 08:00–16:00).");
        });
    }

    @Test
    void blockForOthersWarnForSelfNothingForAllowedPlanners() {
        work(lena, BlockerStrength.BLOCK, BlockerVisibility.TITLE, Set.of(chris.getId()));
        work(kid, BlockerStrength.BLOCK, BlockerVisibility.TITLE, Set.of());

        assertThat(conflicts.check(Set.of(kid.getId()), List.of(range(13, 10, 11)), lenaUser, null))
                .extracting(Conflict::strength).containsExactly(BlockerStrength.BLOCK);
        assertThat(conflicts.check(Set.of(lena.getId()), List.of(range(13, 10, 11)), lenaUser, null))
                .extracting(Conflict::strength).containsExactly(BlockerStrength.WARN);
        assertThat(conflicts.check(Set.of(lena.getId()), List.of(range(13, 10, 11)), chrisUser, null)).isEmpty();
    }

    @Test
    void showOnlyAndOutsideTimesAndNoPeopleGiveNothing() {
        work(lena, BlockerStrength.SHOW, BlockerVisibility.TITLE, Set.of());
        work(chris, BlockerStrength.WARN, BlockerVisibility.TITLE, Set.of());

        assertThat(conflicts.check(Set.of(lena.getId()), List.of(range(13, 10, 11)), admin, null)).isEmpty();
        assertThat(conflicts.check(Set.of(chris.getId()), List.of(range(13, 17, 18)), admin, null)).isEmpty();
        assertThat(conflicts.check(Set.of(), List.of(range(13, 10, 11)), admin, null)).isEmpty();
    }

    @Test
    void allDayRangeCountsAsWholeDay() {
        work(lena, BlockerStrength.WARN, BlockerVisibility.TITLE, Set.of());

        assertThat(conflicts.check(Set.of(lena.getId()), List.of(new TimeRange(at(14, 0, 0), at(15, 0, 0))),
                chrisUser, null)).hasSize(1);
    }

    @Test
    void hiddenBlockerDoesNotRevealItsTitle() {
        work(lena, BlockerStrength.WARN, BlockerVisibility.HIDDEN, Set.of());

        assertThat(conflicts.check(Set.of(lena.getId()), List.of(range(13, 10, 11)), chrisUser, null))
                .singleElement().extracting(Conflict::message).isEqualTo("Lena ist da nicht verfügbar.");
        assertThat(conflicts.check(Set.of(lena.getId()), List.of(range(13, 10, 11)), lenaUser, null))
                .singleElement().extracting(Conflict::message).asString().contains("Büro");
    }

    @Test
    void alternativesAvoidBlockersAndEvents() {
        work(lena, BlockerStrength.WARN, BlockerVisibility.TITLE, Set.of());
        events.create(new EventInput("Sport", null, null, LocalDate.of(2026, 10, 13), LocalTime.of(16, 0),
                LocalDate.of(2026, 10, 13), LocalTime.of(17, 0), false, null, false, Set.of(lena.getId())), admin);

        List<TimeRange> found = conflicts.alternatives(Set.of(lena.getId()), range(13, 10, 11), null);

        assertThat(found).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        // am selben Tag, möglichst nah an 10 Uhr: vor Arbeitsbeginn
        assertThat(found.getFirst().start()).isEqualTo(at(13, 7, 0));
        assertThat(found).extracting(TimeRange::start).contains(at(13, 17, 0));
        assertThat(found).allSatisfy(t -> {
            assertThat(conflicts.check(Set.of(lena.getId()), List.of(t), chrisUser, null)).isEmpty();
            assertThat(t.end().atZone(BERLIN).toLocalTime()).isBeforeOrEqualTo(LocalTime.of(21, 0));
        });
    }

    @Test
    void freeEveningGoalWarnsWhenItWouldBeMissed() {
        blockers.saveGoal(null, lena.getId(), RestGoal.Kind.WINDOWS, LocalTime.of(18, 0), LocalTime.of(23, 59), 6,
                "Freie Abende", admin);

        RestGoals.GoalStatus status = restGoals.status(lena.getId(), LocalDate.of(2026, 10, 12)).getFirst();
        assertThat(status.reached()).isEqualTo(7);

        events.create(new EventInput("Elternabend", null, null, LocalDate.of(2026, 10, 14), LocalTime.of(19, 0),
                LocalDate.of(2026, 10, 14), LocalTime.of(21, 0), false, null, false, Set.of(lena.getId())), admin);
        assertThat(restGoals.status(lena.getId(), LocalDate.of(2026, 10, 12)).getFirst().reached()).isEqualTo(6);

        List<Conflict> found = conflicts.check(Set.of(lena.getId()), List.of(range(15, 19, 20)), chrisUser, null);
        assertThat(found).singleElement().satisfies(c -> {
            assertThat(c.strength()).isEqualTo(BlockerStrength.WARN);
            assertThat(c.message()).contains("Freie Abende", "5 von 6");
        });
    }

    @Test
    void restHoursGoalCountsRestBlockersMinusEvents() {
        blockers.create(new BlockerInput("Mittagsruhe", BlockerKind.REST, LocalTime.of(13, 0), LocalTime.of(14, 0),
                EnumSet.allOf(DayOfWeek.class), LocalDate.of(2026, 10, 1), null, BlockerStrength.SHOW,
                BlockerVisibility.TITLE, false, false, Set.of(lena.getId()), Set.of(), Set.of()), admin);
        blockers.saveGoal(null, lena.getId(), RestGoal.Kind.HOURS, null, null, 7 * 60, "Ruhe", admin);
        events.create(new EventInput("Termin", null, null, LocalDate.of(2026, 10, 13), LocalTime.of(13, 30),
                LocalDate.of(2026, 10, 13), LocalTime.of(14, 30), false, null, false, Set.of(lena.getId())), admin);

        RestGoals.GoalStatus status = restGoals.status(lena.getId(), LocalDate.of(2026, 10, 15)).getFirst();

        assertThat(status.reached()).isEqualTo(7 * 60 - 30);
        assertThat(status.target()).isEqualTo(420);
    }
}
