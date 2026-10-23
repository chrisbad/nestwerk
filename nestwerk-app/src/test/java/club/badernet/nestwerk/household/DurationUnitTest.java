package club.badernet.nestwerk.household;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurationUnitTest {

    private static final BigDecimal EIGHT_HOURS = BigDecimal.valueOf(8);

    @Test
    void formatsMinutesWithGermanGrouping() {
        assertThat(DurationUnit.MINUTES.format(2340, EIGHT_HOURS)).isEqualTo("2.340 Min");
    }

    @Test
    void formatsHoursAndMinutesWithPaddedMinutes() {
        assertThat(DurationUnit.HOURS_MINUTES.format(2340, EIGHT_HOURS)).isEqualTo("39:00 h");
        assertThat(DurationUnit.HOURS_MINUTES.format(65, EIGHT_HOURS)).isEqualTo("1:05 h");
    }

    @Test
    void formatsDecimalHoursWithGermanComma() {
        assertThat(DurationUnit.HOURS_DECIMAL.format(2340, EIGHT_HOURS)).isEqualTo("39,0 h");
        assertThat(DurationUnit.HOURS_DECIMAL.format(90, EIGHT_HOURS)).isEqualTo("1,5 h");
    }

    @Test
    void formatsPersonDaysUsingTheConfiguredDayLength() {
        assertThat(DurationUnit.PERSON_DAYS.format(2340, EIGHT_HOURS)).isEqualTo("4,9 PT");
        assertThat(DurationUnit.PERSON_DAYS.format(2340, BigDecimal.valueOf(6))).isEqualTo("6,5 PT");
    }

    @Test
    void roundsHalfUpAndHandlesZeroAndLargeValues() {
        assertThat(DurationUnit.HOURS_DECIMAL.format(3, EIGHT_HOURS)).isEqualTo("0,1 h");
        assertThat(DurationUnit.PERSON_DAYS.format(0, EIGHT_HOURS)).isEqualTo("0,0 PT");
        assertThat(DurationUnit.MINUTES.format(1_234_567, EIGHT_HOURS)).isEqualTo("1.234.567 Min");
        assertThat(DurationUnit.PERSON_DAYS.format(1_234_567, EIGHT_HOURS)).isEqualTo("2.572,0 PT");
    }

    @Test
    void autoPicksTheUnitByPeriod() {
        assertThat(DurationUnit.AUTO.resolve("day")).isEqualTo(DurationUnit.MINUTES);
        assertThat(DurationUnit.AUTO.resolve("week")).isEqualTo(DurationUnit.HOURS_MINUTES);
        assertThat(DurationUnit.AUTO.resolve("month")).isEqualTo(DurationUnit.PERSON_DAYS);
        assertThat(DurationUnit.AUTO.resolve("year")).isEqualTo(DurationUnit.PERSON_DAYS);
        assertThat(DurationUnit.HOURS_DECIMAL.resolve("year")).isEqualTo(DurationUnit.HOURS_DECIMAL);
    }

    @Test
    void unknownUrlKeyFallsBackToMinutes() {
        assertThat(DurationUnit.fromKey("days")).isEqualTo(DurationUnit.PERSON_DAYS);
        assertThat(DurationUnit.fromKey("nonsense")).isEqualTo(DurationUnit.MINUTES);
        assertThat(DurationUnit.fromKey(null)).isEqualTo(DurationUnit.MINUTES);
    }

    @Test
    void parseRejectsUnknownNames() {
        assertThat(DurationUnit.parse(" hours_decimal ")).isEqualTo(DurationUnit.HOURS_DECIMAL);
        assertThat(DurationUnit.parse("")).isEqualTo(DurationUnit.MINUTES);
        assertThatThrownBy(() -> DurationUnit.parse("furlongs")).isInstanceOf(IllegalArgumentException.class);
    }
}
