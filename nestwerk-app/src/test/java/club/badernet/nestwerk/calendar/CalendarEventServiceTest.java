package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.TestcontainersConfiguration;
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
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class CalendarEventServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Autowired CalendarEventService service;
    @Autowired EventCategoryService categories;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberService memberService;

    User owner;
    User other;
    User admin;
    User viewerOnly;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Normal", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN", "CALENDAR_PRIVATE_CREATE"));
        Role readOnly = roleService.create("Nur lesen", null, Set.of("CALENDAR_VIEW"));
        owner = userService.create("besitzer", "passwort1", Set.of(normal.getId()));
        other = userService.create("anderer", "passwort1", Set.of(normal.getId()));
        viewerOnly = userService.create("leser", "passwort1", Set.of(readOnly.getId()));
        admin = userService.createAdministrator("chef", "passwort1");
    }

    private static EventInput timed(String title, boolean isPrivate) {
        return new EventInput(title, null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, isPrivate, Set.of());
    }

    private static Instant day(int month, int day) {
        return LocalDate.of(2026, month, day).atStartOfDay(BERLIN).toInstant();
    }

    private List<String> titles(User viewer) {
        return service.findOccurrences(day(10, 1), day(11, 1), viewer).stream().map(Occurrence::title).toList();
    }

    @Test
    void createsTimedEventInConfiguredZone() {
        CalendarEvent e = service.create(timed("Arzt", false), owner);

        assertThat(e.getStartsAt()).isEqualTo(Instant.parse("2026-10-12T08:00:00Z"));
        assertThat(e.getEndsAt()).isEqualTo(Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(e.getUid()).endsWith("@nestwerk");
    }

    @Test
    void allDayEventEndsAtStartOfDayAfterLastDay() {
        EventInput in = new EventInput("Urlaub", null, null, LocalDate.of(2026, 10, 12), null,
                LocalDate.of(2026, 10, 14), null, true, null, false, Set.of());

        CalendarEvent e = service.create(in, owner);

        assertThat(e.getStartsAt()).isEqualTo(day(10, 12));
        assertThat(e.getEndsAt()).isEqualTo(day(10, 15));
    }

    @Test
    void rejectsEndNotAfterStart() {
        EventInput in = new EventInput("Falsch", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(10, 0), false, null, false, Set.of());

        assertThatThrownBy(() -> service.create(in, owner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ende");
    }

    @Test
    void rejectsAllDayEndBeforeStart() {
        EventInput in = new EventInput("Falsch", null, null, LocalDate.of(2026, 10, 12), null,
                LocalDate.of(2026, 10, 11), null, true, null, false, Set.of());

        assertThatThrownBy(() -> service.create(in, owner)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankTitleAndInvalidRule() {
        assertThatThrownBy(() -> service.create(timed(" ", false), owner))
                .isInstanceOf(IllegalArgumentException.class);
        EventInput bad = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, "FREQ=IRGENDWANN", false, Set.of());
        assertThatThrownBy(() -> service.create(bad, owner)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongLocationAndDescription() {
        EventInput longLocation = new EventInput("Termin", null, "x".repeat(256), LocalDate.of(2026, 10, 12),
                LocalTime.of(10, 0), LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of());
        EventInput longDescription = new EventInput("Termin", "x".repeat(5001), null, LocalDate.of(2026, 10, 12),
                LocalTime.of(10, 0), LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of());

        assertThatThrownBy(() -> service.create(longLocation, owner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ort");
        assertThatThrownBy(() -> service.create(longDescription, owner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Notizen");
    }

    @Test
    void rejectsOverlongLocationForSingleOccurrence() {
        EventInput in = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 5), LocalTime.of(11, 0), false, "FREQ=WEEKLY", false, Set.of());
        CalendarEvent e = service.create(in, owner);
        Instant occurrence = LocalDate.of(2026, 10, 12).atTime(10, 0).atZone(BERLIN).toInstant();
        OccurrenceInput change = new OccurrenceInput("Serie", null, "x".repeat(256),
                LocalDate.of(2026, 10, 12).atTime(10, 0), LocalDate.of(2026, 10, 12).atTime(11, 0));

        assertThatThrownBy(() -> service.overrideOccurrence(e.getId(), occurrence, change, owner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ort");
    }

    @Test
    void acceptsRulePrefixedWithRRULE() {
        EventInput in = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, "RRULE:FREQ=WEEKLY", false, Set.of());

        assertThat(service.create(in, owner).getRrule()).isEqualTo("FREQ=WEEKLY");
    }

    @Test
    void rejectsUnknownMember() {
        EventInput in = new EventInput("Mit Geist", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of(999_999L));

        assertThatThrownBy(() -> service.create(in, owner)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignsMembers() {
        FamilyMember m = memberService.create("Lena", "#112233", null);
        EventInput in = new EventInput("Mit Lena", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of(m.getId()));

        assertThat(service.create(in, owner).getMemberIds()).containsExactly(m.getId());
    }

    @Test
    void privateEventsAreOnlyVisibleToCreatorAndAdministrator() {
        service.create(timed("Geheim", true), owner);
        service.create(timed("Offen", false), owner);

        assertThat(titles(owner)).contains("Geheim", "Offen");
        assertThat(titles(admin)).contains("Geheim", "Offen");
        assertThat(titles(other)).contains("Offen").doesNotContain("Geheim");
        assertThat(titles(viewerOnly)).contains("Offen").doesNotContain("Geheim");
    }

    @Test
    void directAccessToForeignPrivateEventLooksLikeNotFound() {
        CalendarEvent e = service.create(timed("Geheim", true), owner);

        assertThatThrownBy(() -> service.get(e.getId(), other)).isInstanceOf(EventNotFoundException.class);
        assertThat(service.get(e.getId(), admin)).isNotNull();
    }

    @Test
    void creatingRequiresPermission() {
        assertThatThrownBy(() -> service.create(timed("Nein", false), viewerOnly))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void privateCreationRequiresItsOwnPermission() {
        Role noPrivate = roleService.create("Ohne privat", null,
                Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE"));
        User user = userService.create("ohne-privat", "passwort1", Set.of(noPrivate.getId()));

        assertThatThrownBy(() -> service.create(timed("Privat", true), user))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ownEventsEditableForeignOnesNot() {
        CalendarEvent e = service.create(timed("Meins", false), owner);

        service.update(e.getId(), timed("Geändert", false), owner);

        assertThatThrownBy(() -> service.update(e.getId(), timed("Hack", false), other))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(e.getId(), other)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.get(e.getId(), owner).getTitle()).isEqualTo("Geändert");
    }

    @Test
    void administratorMayEditAndDeleteAnything() {
        CalendarEvent e = service.create(timed("Fremd", false), owner);

        service.update(e.getId(), timed("Vom Admin", false), admin);
        service.delete(e.getId(), admin);

        assertThatThrownBy(() -> service.get(e.getId(), admin)).isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void updateIncrementsSequence() {
        CalendarEvent e = service.create(timed("V1", false), owner);
        int before = e.getSequence();

        service.update(e.getId(), timed("V2", false), owner);

        assertThat(service.get(e.getId(), owner).getSequence()).isGreaterThan(before);
    }

    @Test
    void cancellingOccurrenceHidesOnlyThatOne() {
        EventInput in = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 5), LocalTime.of(11, 0), false, "FREQ=WEEKLY", false, Set.of());
        CalendarEvent e = service.create(in, owner);
        Instant second = LocalDate.of(2026, 10, 12).atTime(10, 0).atZone(BERLIN).toInstant();

        service.cancelOccurrence(e.getId(), second, owner);

        assertThat(service.findOccurrences(day(10, 1), day(11, 1), owner)).hasSize(3);
    }

    @Test
    void cancellingNonOccurrenceIsRejected() {
        EventInput in = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 5), LocalTime.of(11, 0), false, "FREQ=WEEKLY", false, Set.of());
        CalendarEvent e = service.create(in, owner);
        Instant notInSeries = LocalDate.of(2026, 10, 13).atTime(10, 0).atZone(BERLIN).toInstant();

        assertThatThrownBy(() -> service.cancelOccurrence(e.getId(), notInSeries, owner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changingRuleDropsStaleExceptions() {
        EventInput weekly = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 5), LocalTime.of(11, 0), false, "FREQ=WEEKLY", false, Set.of());
        CalendarEvent e = service.create(weekly, owner);
        service.cancelOccurrence(e.getId(),
                LocalDate.of(2026, 10, 12).atTime(10, 0).atZone(BERLIN).toInstant(), owner);
        EventInput daily = new EventInput("Serie", null, null, LocalDate.of(2026, 10, 5), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 5), LocalTime.of(11, 0), false, "FREQ=DAILY", false, Set.of());

        service.update(e.getId(), daily, owner);

        assertThat(service.get(e.getId(), owner).getExceptions()).isEmpty();
    }

    @Test
    void windowLargerThanFourHundredDaysIsRejected() {
        assertThatThrownBy(() -> service.findOccurrences(day(1, 1),
                LocalDate.of(2028, 1, 1).atStartOfDay(BERLIN).toInstant(), owner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void viewingRequiresPermission() {
        Role none = roleService.create("Nichts", null, Set.of());
        User nobody = userService.create("niemand", "passwort1", Set.of(none.getId()));

        assertThatThrownBy(() -> service.findOccurrences(day(10, 1), day(11, 1), nobody))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void moveToDateKeepsLocalTimesAndDuration() {
        CalendarEvent e = service.create(timed("Arzt", false), owner);

        service.moveToDate(e.getId(), LocalDate.of(2026, 10, 29), owner);

        // über die Zeitumstellung hinweg: 10 Uhr bleibt 10 Uhr Ortszeit
        assertThat(e.getStartsAt()).isEqualTo(Instant.parse("2026-10-29T09:00:00Z"));
        assertThat(e.getEndsAt()).isEqualTo(Instant.parse("2026-10-29T10:00:00Z"));
    }

    @Test
    void moveToDateShiftsAllDayEventsWithTheirLength() {
        EventInput in = new EventInput("Urlaub", null, null, LocalDate.of(2026, 10, 12), null,
                LocalDate.of(2026, 10, 14), null, true, null, false, Set.of());
        CalendarEvent e = service.create(in, owner);

        service.moveToDate(e.getId(), LocalDate.of(2026, 10, 20), owner);

        assertThat(e.getStartsAt()).isEqualTo(day(10, 20));
        assertThat(e.getEndsAt()).isEqualTo(day(10, 23));
        assertThat(e.isAllDay()).isTrue();
    }

    @Test
    void moveToDateRejectsSeriesAndForeignEvents() {
        EventInput series = new EventInput("Training", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(18, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(19, 0), false, "FREQ=WEEKLY", false, Set.of());
        CalendarEvent repeating = service.create(series, owner);
        CalendarEvent single = service.create(timed("Arzt", false), owner);

        assertThatThrownBy(() -> service.moveToDate(repeating.getId(), LocalDate.of(2026, 10, 13), owner))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.moveToDate(single.getId(), LocalDate.of(2026, 10, 13), other))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static EventInput weekly(LocalTime start, LocalTime end) {
        return new EventInput("Training", null, null, LocalDate.of(2026, 10, 5), start, LocalDate.of(2026, 10, 5), end,
                false, "FREQ=WEEKLY", false, Set.of());
    }

    private static Instant berlin(int month, int day, int hour, int minute) {
        return LocalDate.of(2026, month, day).atTime(hour, minute).atZone(BERLIN).toInstant();
    }

    @Test
    void rescheduleGivesSingleEventNewStartAndEnd() {
        CalendarEvent e = service.create(timed("Arzt", false), owner);

        service.reschedule(e.getId(), new TimeChange.Timed(LocalDate.of(2026, 10, 13).atTime(14, 5),
                LocalDate.of(2026, 10, 13).atTime(14, 50)), owner);

        assertThat(e.getStartsAt()).isEqualTo(berlin(10, 13, 14, 5));
        assertThat(e.getEndsAt()).isEqualTo(berlin(10, 13, 14, 50));
        assertThat(e.isAllDay()).isFalse();
    }

    @Test
    void rescheduleSwitchesBetweenTimedAndAllDay() {
        CalendarEvent e = service.create(timed("Ausflug", false), owner);

        service.reschedule(e.getId(), new TimeChange.AllDay(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15)),
                owner);
        assertThat(e.isAllDay()).isTrue();
        assertThat(e.getStartsAt()).isEqualTo(day(10, 14));
        assertThat(e.getEndsAt()).isEqualTo(day(10, 16));

        service.reschedule(e.getId(), new TimeChange.Timed(LocalDate.of(2026, 10, 14).atTime(9, 0),
                LocalDate.of(2026, 10, 14).atTime(10, 0)), owner);
        assertThat(e.isAllDay()).isFalse();
    }

    @Test
    void rescheduleRejectsSeriesForeignEventsAndEndBeforeStart() {
        CalendarEvent series = service.create(weekly(LocalTime.of(18, 0), LocalTime.of(19, 0)), owner);
        CalendarEvent single = service.create(timed("Arzt", false), owner);
        TimeChange later = new TimeChange.Timed(LocalDate.of(2026, 10, 12).atTime(12, 0),
                LocalDate.of(2026, 10, 12).atTime(13, 0));

        assertThatThrownBy(() -> service.reschedule(series.getId(), later, owner))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reschedule(single.getId(), later, other))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reschedule(single.getId(), new TimeChange.Timed(
                LocalDate.of(2026, 10, 12).atTime(13, 0), LocalDate.of(2026, 10, 12).atTime(12, 0)), owner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ende");
    }

    @Test
    void rescheduleOccurrenceMovesOnlyThatOccurrenceAndKeepsItsTitle() {
        CalendarEvent series = service.create(weekly(LocalTime.of(18, 0), LocalTime.of(19, 0)), owner);
        Instant second = berlin(10, 12, 18, 0);

        service.rescheduleOccurrence(series.getId(), second, new TimeChange.Timed(
                LocalDate.of(2026, 10, 13).atTime(17, 30), LocalDate.of(2026, 10, 13).atTime(19, 15)), owner);

        List<Occurrence> all = service.findOccurrences(day(10, 1), day(10, 20), owner);
        assertThat(all).extracting(o -> o.start().toInstant())
                .containsExactly(berlin(10, 5, 18, 0), berlin(10, 13, 17, 30), berlin(10, 19, 18, 0));
        assertThat(all).extracting(Occurrence::title).containsOnly("Training");
    }

    @Test
    void occurrenceCannotSwitchToAllDay() {
        CalendarEvent series = service.create(weekly(LocalTime.of(18, 0), LocalTime.of(19, 0)), owner);

        assertThatThrownBy(() -> service.rescheduleOccurrence(series.getId(), berlin(10, 12, 18, 0),
                new TimeChange.AllDay(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12)), owner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rescheduleSeriesMovesTimeOfDayAndKeepsCancelledOccurrences() {
        CalendarEvent series = service.create(weekly(LocalTime.of(18, 0), LocalTime.of(19, 0)), owner);
        service.cancelOccurrence(series.getId(), berlin(10, 12, 18, 0), owner);

        service.rescheduleSeries(series.getId(), LocalTime.of(17, 15), java.time.Duration.ofMinutes(90), owner);

        assertThat(service.findOccurrences(day(10, 1), day(10, 27), owner)).extracting(o -> o.start().toInstant())
                .containsExactly(berlin(10, 5, 17, 15), berlin(10, 19, 17, 15), berlin(10, 26, 17, 15));
        assertThat(service.get(series.getId(), owner).getEndsAt()).isEqualTo(berlin(10, 5, 18, 45));
    }

    @Test
    void rescheduleSeriesRejectsSingleEventsAndAllDaySeries() {
        CalendarEvent single = service.create(timed("Arzt", false), owner);
        CalendarEvent allDay = service.create(new EventInput("Markt", null, null, LocalDate.of(2026, 10, 5), null,
                LocalDate.of(2026, 10, 5), null, true, "FREQ=WEEKLY", false, Set.of()), owner);

        assertThatThrownBy(() -> service.rescheduleSeries(single.getId(), LocalTime.of(9, 0),
                java.time.Duration.ofHours(1), owner)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rescheduleSeries(allDay.getId(), LocalTime.of(9, 0),
                java.time.Duration.ofHours(1), owner)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeOccurrenceSetsOwnTitle() {
        CalendarEvent series = service.create(weekly(LocalTime.of(18, 0), LocalTime.of(19, 0)), owner);
        Instant second = berlin(10, 12, 18, 0);

        service.changeOccurrence(series.getId(), second, "Training mit Turnier", new TimeChange.Timed(
                LocalDate.of(2026, 10, 12).atTime(18, 0), LocalDate.of(2026, 10, 12).atTime(21, 0)), owner);

        assertThat(service.findOccurrences(day(10, 12), day(10, 13), owner)).singleElement()
                .satisfies(o -> assertThat(o.title()).isEqualTo("Training mit Turnier"));
    }

    @Test
    void storesCategoryAndRejectsUnknownOne() {
        EventCategory sport = categories.create(new CategoryInput("Mannschaft", CategoryColor.GREEN), admin);
        EventInput withCategory = new EventInput("Spiel", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of(), false, sport.getId());
        EventInput unknown = new EventInput("Spiel", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of(), false, 999_999L);

        CalendarEvent e = service.create(withCategory, owner);

        assertThat(e.getCategoryId()).contains(sport.getId());
        assertThat(service.findOccurrences(day(10, 12), day(10, 13), owner)).singleElement()
                .satisfies(o -> assertThat(o.categoryId()).isEqualTo(sport.getId()));
        assertThatThrownBy(() -> service.create(unknown, owner)).isInstanceOf(IllegalArgumentException.class);
    }
}
