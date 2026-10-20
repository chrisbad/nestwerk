package club.badernet.nestwerk.calendar;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarGridTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static Occurrence occ(String title, ZonedDateTime start, ZonedDateTime end, boolean allDay) {
        return new Occurrence(1L, "u", start.toInstant(), title, null, null, start, end,
                allDay, false, false, 1L, Set.of(), null);
    }

    private static ZonedDateTime z(int m, int d, int h, int min) {
        return ZonedDateTime.of(2026, m, d, h, min, 0, 0, BERLIN);
    }

    @Test
    void monthGridStartsOnMondayAndEndsOnSunday() {
        List<LocalDate> days = CalendarGrid.monthDays(YearMonth.of(2026, 10));

        assertThat(days.getFirst()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(days.getFirst().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(days.getLast()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(days).hasSize(35);
    }

    @Test
    void monthStartingOnMondayHasNoLeadingDays() {
        List<LocalDate> days = CalendarGrid.monthDays(YearMonth.of(2026, 6));

        assertThat(days.getFirst()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void weekDaysAreMondayToSunday() {
        List<LocalDate> days = CalendarGrid.weekDays(LocalDate.of(2026, 10, 14));

        assertThat(days).hasSize(7);
        assertThat(days.getFirst()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(days.getLast()).isEqualTo(LocalDate.of(2026, 10, 18));
    }

    @Test
    void eventOverMidnightAppearsOnBothDays() {
        Occurrence o = occ("Nachtschicht", z(10, 31, 23, 30), z(11, 1, 0, 30), false);

        Map<LocalDate, List<Occurrence>> grouped = CalendarGrid.groupByDay(List.of(o),
                List.of(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)));

        assertThat(grouped.get(LocalDate.of(2026, 10, 31))).containsExactly(o);
        assertThat(grouped.get(LocalDate.of(2026, 11, 1))).containsExactly(o);
        assertThat(grouped.get(LocalDate.of(2026, 11, 2))).isEmpty();
    }

    @Test
    void allDayEventEndingAtMidnightDoesNotSpillIntoNextDay() {
        Occurrence o = occ("Geburtstag", z(10, 12, 0, 0), z(10, 13, 0, 0), true);

        Map<LocalDate, List<Occurrence>> grouped = CalendarGrid.groupByDay(List.of(o),
                List.of(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13)));

        assertThat(grouped.get(LocalDate.of(2026, 10, 12))).containsExactly(o);
        assertThat(grouped.get(LocalDate.of(2026, 10, 13))).isEmpty();
    }

    @Test
    void multiDayEventAppearsOnEveryDay() {
        Occurrence o = occ("Urlaub", z(10, 12, 0, 0), z(10, 15, 0, 0), true);

        Map<LocalDate, List<Occurrence>> grouped = CalendarGrid.groupByDay(List.of(o),
                CalendarGrid.weekDays(LocalDate.of(2026, 10, 12)));

        assertThat(grouped.values().stream().filter(l -> !l.isEmpty()).count()).isEqualTo(3);
    }

    @Test
    void occurrencesPerDayAreSortedByStart() {
        Occurrence late = occ("Spät", z(10, 12, 18, 0), z(10, 12, 19, 0), false);
        Occurrence early = occ("Früh", z(10, 12, 7, 0), z(10, 12, 8, 0), false);

        Map<LocalDate, List<Occurrence>> grouped = CalendarGrid.groupByDay(List.of(late, early),
                List.of(LocalDate.of(2026, 10, 12)));

        assertThat(grouped.get(LocalDate.of(2026, 10, 12))).containsExactly(early, late);
    }
}
