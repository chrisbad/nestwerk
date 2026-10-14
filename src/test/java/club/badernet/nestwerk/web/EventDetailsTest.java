package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.Occurrence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventDetailsTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static Occurrence occurrence(LocalDateTime start, LocalDateTime end, boolean allDay) {
        ZonedDateTime s = start.atZone(BERLIN);
        return new Occurrence(1L, "uid", s.toInstant(), "Termin", null, null, s, end.atZone(BERLIN),
                allDay, false, false, 1L, Set.of());
    }

    @Test
    void singleAllDayEventShowsOneDay() {
        var o = occurrence(LocalDateTime.of(2026, 10, 12, 0, 0), LocalDateTime.of(2026, 10, 13, 0, 0), true);

        assertThat(EventDetails.when(o)).isEqualTo("Montag, 12. Oktober 2026, ganztägig");
    }

    @Test
    void multiDayAllDayEventShowsInclusiveLastDay() {
        var o = occurrence(LocalDateTime.of(2026, 10, 12, 0, 0), LocalDateTime.of(2026, 10, 15, 0, 0), true);

        assertThat(EventDetails.when(o))
                .isEqualTo("Montag, 12. Oktober 2026 bis Mittwoch, 14. Oktober 2026, ganztägig");
    }

    @Test
    void timedEventAcrossMidnightShowsBothDays() {
        var o = occurrence(LocalDateTime.of(2026, 10, 12, 22, 0), LocalDateTime.of(2026, 10, 13, 2, 0), false);

        assertThat(EventDetails.when(o))
                .isEqualTo("Montag, 12. Oktober 2026, 22:00 Uhr bis Dienstag, 13. Oktober 2026, 02:00 Uhr");
    }

    @Test
    void namesKnownRulesAndShowsCustomOnes() {
        assertThat(EventDetails.repeat("FREQ=WEEKLY;INTERVAL=2")).isEqualTo("Alle 2 Wochen");
        assertThat(EventDetails.repeat("FREQ=MONTHLY;BYDAY=-1FR")).isEqualTo("Eigene Regel: FREQ=MONTHLY;BYDAY=-1FR");
        assertThat(EventDetails.repeat(null)).isNull();
    }
}
