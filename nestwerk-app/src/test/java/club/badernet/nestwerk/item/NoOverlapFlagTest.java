package club.badernet.nestwerk.item;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import club.badernet.nestwerk.web.ChoreForm;
import club.badernet.nestwerk.web.EventForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class NoOverlapFlagTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);

    @Autowired CalendarEventService events;
    @Autowired CalendarItems calendarItems;
    @Autowired ChoreService chores;
    @Autowired UserService userService;
    @Autowired CalendarSettings calendar;

    User admin;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
    }

    private EventInput event(String rrule, boolean noOverlap) {
        return new EventInput("Operation", null, null, DAY, LocalTime.of(9, 0), DAY, LocalTime.of(11, 0), false,
                rrule, false, Set.of(), false, null, noOverlap);
    }

    private ChoreInput chore(boolean noOverlap) {
        return new ChoreInput("Fenster", null, null, 20, 40, 90, ChoreKind.ON_DEMAND, "FREQ=DAILY", DAY, null, null,
                null, List.of(), null, null, false, noOverlap);
    }

    @Test
    void eventsAreNotFlaggedByDefault() {
        CalendarEvent plain = events.create(new EventInput("Kaffee", null, null, DAY, LocalTime.of(15, 0), DAY,
                LocalTime.of(16, 0), false, null, false, Set.of()), admin);

        assertThat(plain.isNoOverlap()).isFalse();
    }

    @Test
    void eventStoresAndChangesTheFlag() {
        CalendarEvent created = events.create(event(null, true), admin);
        assertThat(events.get(created.getId(), admin).isNoOverlap()).isTrue();

        events.update(created.getId(), event(null, false), admin);

        assertThat(events.get(created.getId(), admin).isNoOverlap()).isFalse();
    }

    @Test
    void theFlagOfASeriesAppliesToEveryOccurrence() {
        events.create(event("FREQ=DAILY;COUNT=3", true), admin);

        List<Occurrence> occurrences = events.findOccurrences(DAY.atStartOfDay(calendar.zone()).toInstant(),
                DAY.plusDays(3).atStartOfDay(calendar.zone()).toInstant(), admin);

        assertThat(occurrences).hasSize(3).allMatch(Occurrence::noOverlap);
    }

    @Test
    void eventFormCarriesTheFlagBothWays() {
        CalendarEvent created = events.create(event(null, true), admin);

        EventForm form = EventForm.from(events.get(created.getId(), admin));

        assertThat(form.isNoOverlap()).isTrue();
        assertThat(form.toInput().noOverlap()).isTrue();
    }

    @Test
    void choreStoresAndChangesTheFlag() {
        Chore created = chores.create(chore(true), admin);
        assertThat(chores.get(created.getId(), admin).isNoOverlap()).isTrue();

        chores.update(created.getId(), chore(false), admin);

        assertThat(chores.get(created.getId(), admin).isNoOverlap()).isFalse();
    }

    @Test
    void choreFormCarriesTheFlagBothWays() {
        Chore created = chores.create(chore(true), admin);

        ChoreForm form = ChoreForm.from(created);

        assertThat(form.isNoOverlap()).isTrue();
        assertThat(form.toInput().noOverlap()).isTrue();
    }

    @Test
    void calendarItemsShowTheFlag() {
        events.create(event(null, true), admin);

        assertThat(items(admin)).singleElement()
                .satisfies(item -> assertThat(((CalendarItem.Event) item).noOverlap()).isTrue());
    }

    private List<CalendarItem> items(User viewer) {
        return calendarItems.between(DAY, DAY, viewer, Set.of(ItemType.EVENT), null);
    }
}
