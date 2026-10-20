package club.badernet.nestwerk.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TimeGridLayoutTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    private record Entry(String name, LocalDateTime start, LocalDateTime end) {
    }

    private static Entry entry(String name, int startHour, int startMinute, int endHour, int endMinute) {
        return new Entry(name, MONDAY.atTime(startHour, startMinute), MONDAY.atTime(endHour, endMinute));
    }

    private static Map<LocalDate, List<TimeGridLayout.Placed<Entry>>> layout(List<Entry> entries, List<LocalDate> days) {
        return TimeGridLayout.layout(entries, days, e -> e.start().atZone(BERLIN), e -> e.end().atZone(BERLIN));
    }

    private static TimeGridLayout.Placed<Entry> placed(List<TimeGridLayout.Placed<Entry>> list, String name) {
        return list.stream().filter(p -> p.entry().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void placesEntryByMinutesOfTheDay() {
        List<TimeGridLayout.Placed<Entry>> day = layout(List.of(entry("Arzt", 10, 15, 11, 0)), List.of(MONDAY)).get(MONDAY);

        assertThat(day).singleElement().satisfies(p -> {
            assertThat(p.startMinute()).isEqualTo(615);
            assertThat(p.endMinute()).isEqualTo(660);
            assertThat(p.durationMinutes()).isEqualTo(45);
            assertThat(p.column()).isZero();
            assertThat(p.columns()).isEqualTo(1);
        });
    }

    @Test
    void overlappingEntriesShareTheWidth() {
        List<TimeGridLayout.Placed<Entry>> day = layout(List.of(entry("A", 9, 0, 11, 0), entry("B", 10, 0, 12, 0),
                entry("C", 11, 0, 12, 0), entry("D", 13, 0, 14, 0)), List.of(MONDAY)).get(MONDAY);

        assertThat(placed(day, "A").column()).isZero();
        assertThat(placed(day, "B").column()).isEqualTo(1);
        assertThat(placed(day, "C").column()).isZero();
        assertThat(placed(day, "A").columns()).isEqualTo(2);
        assertThat(placed(day, "C").columns()).isEqualTo(2);
        assertThat(placed(day, "D").columns()).isEqualTo(1);
    }

    @Test
    void entriesTouchingEachOtherDoNotOverlap() {
        List<TimeGridLayout.Placed<Entry>> day = layout(List.of(entry("A", 9, 0, 10, 0), entry("B", 10, 0, 11, 0)),
                List.of(MONDAY)).get(MONDAY);

        assertThat(day).allMatch(p -> p.columns() == 1 && p.column() == 0);
    }

    @Test
    void entryOverMidnightAppearsOnBothDays() {
        LocalDate tuesday = MONDAY.plusDays(1);
        Entry night = new Entry("Nachtschicht", MONDAY.atTime(22, 0), tuesday.atTime(6, 0));

        Map<LocalDate, List<TimeGridLayout.Placed<Entry>>> days = layout(List.of(night), List.of(MONDAY, tuesday));

        assertThat(days.get(MONDAY)).singleElement().satisfies(p -> {
            assertThat(p.startMinute()).isEqualTo(1320);
            assertThat(p.endMinute()).isEqualTo(TimeGridLayout.MINUTES_PER_DAY);
            assertThat(p.continuesBefore()).isFalse();
            assertThat(p.continuesAfter()).isTrue();
        });
        assertThat(days.get(tuesday)).singleElement().satisfies(p -> {
            assertThat(p.startMinute()).isZero();
            assertThat(p.endMinute()).isEqualTo(360);
            assertThat(p.continuesBefore()).isTrue();
            assertThat(p.continuesAfter()).isFalse();
        });
    }

    @Test
    void entryEndingAtMidnightStaysOnItsDay() {
        LocalDate tuesday = MONDAY.plusDays(1);
        Entry evening = new Entry("Feier", MONDAY.atTime(20, 0), tuesday.atStartOfDay());

        Map<LocalDate, List<TimeGridLayout.Placed<Entry>>> days = layout(List.of(evening), List.of(MONDAY, tuesday));

        assertThat(days.get(MONDAY)).singleElement().satisfies(p -> assertThat(p.continuesAfter()).isFalse());
        assertThat(days.get(tuesday)).isEmpty();
    }

    @Test
    void lanesKeepAllDayEntriesOfTheSameDayApart() {
        List<LocalDate> week = MONDAY.datesUntil(MONDAY.plusDays(7)).toList();
        record Span(String name, LocalDate first, LocalDate last) {
        }
        List<Span> spans = List.of(new Span("Urlaub", MONDAY.minusDays(2), MONDAY.plusDays(2)),
                new Span("Geburtstag", MONDAY.plusDays(1), MONDAY.plusDays(1)),
                new Span("Messe", MONDAY.plusDays(3), MONDAY.plusDays(9)));

        List<TimeGridLayout.Lane<Span>> lanes = TimeGridLayout.lanes(spans, week, Span::first, Span::last);

        TimeGridLayout.Lane<Span> holiday = lanes.stream().filter(l -> l.entry().name().equals("Urlaub")).findFirst().orElseThrow();
        TimeGridLayout.Lane<Span> birthday = lanes.stream().filter(l -> l.entry().name().equals("Geburtstag")).findFirst().orElseThrow();
        TimeGridLayout.Lane<Span> fair = lanes.stream().filter(l -> l.entry().name().equals("Messe")).findFirst().orElseThrow();
        assertThat(holiday.firstColumn()).isZero();
        assertThat(holiday.span()).isEqualTo(3);
        assertThat(holiday.continuesBefore()).isTrue();
        assertThat(birthday.lane()).isNotEqualTo(holiday.lane());
        assertThat(fair.lane()).isZero();
        assertThat(fair.span()).isEqualTo(4);
        assertThat(fair.continuesAfter()).isTrue();
    }

    @Test
    void daylightSavingDayUsesLocalTime() {
        LocalDate change = LocalDate.of(2026, 10, 25);
        Entry late = new Entry("Abend", change.atTime(18, 0), change.atTime(19, 0));

        List<TimeGridLayout.Placed<Entry>> day = TimeGridLayout.layout(List.of(late), List.of(change),
                e -> ZonedDateTime.of(e.start(), BERLIN), e -> ZonedDateTime.of(e.end(), BERLIN)).get(change);

        assertThat(day).singleElement().satisfies(p -> assertThat(p.startMinute()).isEqualTo(1080));
    }
}
