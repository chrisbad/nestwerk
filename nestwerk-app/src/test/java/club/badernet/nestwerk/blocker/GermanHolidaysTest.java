package club.badernet.nestwerk.blocker;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class GermanHolidaysTest {

    static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @Test
    void nationwideHolidays2026() {
        for (String state : GermanHolidays.STATES.keySet()) {
            assertThat(GermanHolidays.holidays(2026, state)).contains(d(1, 1), d(4, 3), d(4, 6), d(5, 1), d(5, 14),
                    d(5, 25), d(10, 3), d(12, 25), d(12, 26));
        }
        assertThat(GermanHolidays.isHoliday(d(10, 16), "BY")).isFalse();
    }

    @Test
    void regionalHolidays2026() {
        assertThat(GermanHolidays.isHoliday(d(6, 4), "BY")).isTrue();
        assertThat(GermanHolidays.isHoliday(d(6, 4), "NW")).isTrue();
        assertThat(GermanHolidays.isHoliday(d(6, 4), "BE")).isFalse();
        assertThat(GermanHolidays.isHoliday(d(10, 31), "SN")).isTrue();
        assertThat(GermanHolidays.isHoliday(d(10, 31), "BY")).isFalse();
        assertThat(GermanHolidays.isHoliday(d(11, 1), "BY")).isTrue();
        assertThat(GermanHolidays.isHoliday(d(11, 18), "SN")).isTrue();
        assertThat(GermanHolidays.isHoliday(d(11, 18), "BY")).isFalse();
        assertThat(GermanHolidays.isHoliday(d(3, 8), "BE")).isTrue();
        assertThat(GermanHolidays.isHoliday(d(9, 20), "TH")).isTrue();
        assertThat(GermanHolidays.isHoliday(d(1, 6), "BW")).isTrue();
        assertThat(GermanHolidays.isHoliday(d(1, 6), "ST")).isTrue();
        assertThat(GermanHolidays.isHoliday(d(1, 6), "HE")).isFalse();
    }

    @Test
    void noStateMeansNoHolidays() {
        assertThat(GermanHolidays.isHoliday(d(12, 25), null)).isFalse();
        assertThat(GermanHolidays.isHoliday(d(12, 25), "")).isFalse();
    }
}
