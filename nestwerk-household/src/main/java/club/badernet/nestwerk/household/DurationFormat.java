package club.badernet.nestwerk.household;

import java.math.BigDecimal;

/** A resolved {@link DurationUnit} together with the length of a person-day, ready to format minutes. */
public record DurationFormat(DurationUnit unit, BigDecimal hoursPerPersonDay) {

    public String of(int minutes) {
        return unit.format(minutes, hoursPerPersonDay);
    }

    /** Explanation of the person-day length, or an empty string for other units. */
    public String hint() {
        return unit == DurationUnit.PERSON_DAYS ? "1 PT = " + HouseholdSettings.plain(hoursPerPersonDay) + " h" : "";
    }

    /** Header of the minutes column. */
    public String columnLabel() {
        return unit == DurationUnit.MINUTES ? "Minuten" : "Aufwand";
    }
}
