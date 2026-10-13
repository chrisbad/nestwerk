package club.badernet.nestwerk.calendar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceExpanderTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final RecurrenceExpander expander = new RecurrenceExpander();

    private static Instant at(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, BERLIN).toInstant();
    }

    private static CalendarEvent event(String rrule, Instant start, Instant end) {
        CalendarEvent e = new CalendarEvent("uid-1", "Test", start, end, false, "Europe/Berlin", 1L);
        e.setRrule(rrule);
        return e;
    }

    private List<Instant> starts(CalendarEvent e, Instant from, Instant to) {
        return expander.expand(e, from, to).stream().map(o -> o.start().toInstant()).toList();
    }

    @Test
    void singleEventInsideWindow() {
        CalendarEvent e = event(null, at(2026, 10, 12, 10, 0), at(2026, 10, 12, 11, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0)))
                .containsExactly(at(2026, 10, 12, 10, 0));
    }

    @Test
    void singleEventOutsideWindowIsIgnored() {
        CalendarEvent e = event(null, at(2026, 12, 1, 10, 0), at(2026, 12, 1, 11, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0))).isEmpty();
    }

    @Test
    void multiDayEventStartedBeforeWindowOverlaps() {
        CalendarEvent e = event(null, at(2026, 10, 10, 8, 0), at(2026, 10, 14, 8, 0));

        assertThat(starts(e, at(2026, 10, 12, 0, 0), at(2026, 10, 13, 0, 0))).hasSize(1);
    }

    @Test
    void weeklyRecurrence() {
        CalendarEvent e = event("FREQ=WEEKLY", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0))).containsExactly(
                at(2026, 10, 5, 10, 0), at(2026, 10, 12, 10, 0),
                at(2026, 10, 19, 10, 0), at(2026, 10, 26, 10, 0));
    }

    @Test
    void weeklyWithByDay() {
        CalendarEvent e = event("FREQ=WEEKLY;BYDAY=MO,WE", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 10, 5, 0, 0), at(2026, 10, 19, 0, 0))).containsExactly(
                at(2026, 10, 5, 10, 0), at(2026, 10, 7, 10, 0),
                at(2026, 10, 12, 10, 0), at(2026, 10, 14, 10, 0));
    }

    @Test
    void monthlyOnLastFriday() {
        CalendarEvent e = event("FREQ=MONTHLY;BYDAY=-1FR", at(2026, 10, 30, 18, 0), at(2026, 10, 30, 19, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2027, 1, 1, 0, 0))).containsExactly(
                at(2026, 10, 30, 18, 0), at(2026, 11, 27, 18, 0), at(2026, 12, 25, 18, 0));
    }

    @Test
    void everySecondWeek() {
        CalendarEvent e = event("FREQ=WEEKLY;INTERVAL=2", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 8, 0, 0))).containsExactly(
                at(2026, 10, 5, 10, 0), at(2026, 10, 19, 10, 0), at(2026, 11, 2, 10, 0));
    }

    @Test
    void countLimitsOccurrences() {
        CalendarEvent e = event("FREQ=DAILY;COUNT=3", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 1, 1, 0, 0), at(2027, 1, 1, 0, 0))).hasSize(3);
    }

    @Test
    void untilLimitsOccurrences() {
        CalendarEvent e = event("FREQ=DAILY;UNTIL=20261008T235959Z",
                at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0))).hasSize(4);
    }

    @Test
    void untilInThePastYieldsNothingInLaterWindow() {
        CalendarEvent e = event("FREQ=DAILY;UNTIL=20261008T235959Z",
                at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(starts(e, at(2026, 11, 1, 0, 0), at(2026, 12, 1, 0, 0))).isEmpty();
    }

    @Test
    void cancelledOccurrenceIsSkipped() {
        CalendarEvent e = event("FREQ=WEEKLY", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));
        e.cancelOccurrence(at(2026, 10, 12, 10, 0));

        assertThat(starts(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0))).containsExactly(
                at(2026, 10, 5, 10, 0), at(2026, 10, 19, 10, 0), at(2026, 10, 26, 10, 0));
    }

    @Test
    void movedOccurrenceUsesOverrideButKeepsRecurrenceId() {
        CalendarEvent e = event("FREQ=WEEKLY", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));
        e.overrideOccurrence(at(2026, 10, 12, 10, 0), "Verschoben", null, null,
                at(2026, 10, 13, 15, 0), at(2026, 10, 13, 16, 0));

        List<Occurrence> result = expander.expand(e, at(2026, 10, 10, 0, 0), at(2026, 10, 15, 0, 0));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("Verschoben");
        assertThat(result.getFirst().start().toInstant()).isEqualTo(at(2026, 10, 13, 15, 0));
        assertThat(result.getFirst().recurrenceId()).isEqualTo(at(2026, 10, 12, 10, 0));
    }

    @Test
    void localStartTimeSurvivesDaylightSavingChange() {
        CalendarEvent e = event("FREQ=WEEKLY", at(2026, 3, 21, 10, 0), at(2026, 3, 21, 11, 0));

        List<Occurrence> result = expander.expand(e, at(2026, 3, 1, 0, 0), at(2026, 4, 30, 0, 0));

        assertThat(result).hasSize(6);
        assertThat(result).allSatisfy(o -> assertThat(o.start().withZoneSameInstant(BERLIN).getHour()).isEqualTo(10));
    }

    @Test
    void allDayFlagIsKept() {
        CalendarEvent e = new CalendarEvent("uid-2", "Ganztag", at(2026, 10, 12, 0, 0),
                at(2026, 10, 13, 0, 0), true, "Europe/Berlin", 1L);
        e.setRrule("FREQ=WEEKLY");

        List<Occurrence> result = expander.expand(e, at(2026, 10, 1, 0, 0), at(2026, 11, 1, 0, 0));

        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(o -> {
            assertThat(o.allDay()).isTrue();
            assertThat(o.start().getHour()).isZero();
        });
    }

    @Test
    void isBaseOccurrenceDistinguishesRuleDatesFromOthers() {
        CalendarEvent e = event("FREQ=WEEKLY", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));

        assertThat(expander.isBaseOccurrence(e, at(2026, 10, 12, 10, 0))).isTrue();
        assertThat(expander.isBaseOccurrence(e, at(2026, 10, 13, 10, 0))).isFalse();
    }

    @Test
    void invalidRuleIsRejected() {
        ZonedDateTime seed = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, BERLIN);

        assertThatThrownBy(() -> expander.validate("FREQ=SOMETIMES", seed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expander.validate("das ist keine regel", seed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validRuleIsAccepted() {
        ZonedDateTime seed = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, BERLIN);

        expander.validate("FREQ=MONTHLY;BYDAY=-1FR", seed);
    }

    @Test
    void occurrenceMovedIntoOtherWeekAppearsThereAndNotInOriginalWeek() {
        CalendarEvent e = event("FREQ=WEEKLY", at(2026, 10, 5, 10, 0), at(2026, 10, 5, 11, 0));
        e.overrideOccurrence(at(2026, 10, 19, 10, 0), null, null, null,
                at(2026, 10, 16, 15, 0), at(2026, 10, 16, 16, 0));

        List<Occurrence> weekOfTwelfth = expander.expand(e, at(2026, 10, 12, 0, 0), at(2026, 10, 19, 0, 0));
        List<Occurrence> weekOfNineteenth = expander.expand(e, at(2026, 10, 19, 0, 0), at(2026, 10, 26, 0, 0));

        assertThat(weekOfTwelfth).extracting(o -> o.start().toInstant())
                .containsExactly(at(2026, 10, 12, 10, 0), at(2026, 10, 16, 15, 0));
        assertThat(weekOfTwelfth.get(1).recurrenceId()).isEqualTo(at(2026, 10, 19, 10, 0));
        assertThat(weekOfNineteenth).isEmpty();
    }
}
