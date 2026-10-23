package club.badernet.nestwerk.household;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Locale;

/** Unit in which efforts are displayed; values stay stored and computed in minutes. */
public enum DurationUnit {

    MINUTES("minutes", "Minuten"),
    HOURS_MINUTES("clock", "Stunden:Minuten"),
    HOURS_DECIMAL("hours", "Stunden (dezimal)"),
    PERSON_DAYS("days", "Personentage"),
    AUTO("auto", "Automatisch");

    private static final int MINUTES_PER_HOUR = 60;
    private static final int DECIMAL_PLACES = 1;

    private final String key;
    private final String label;

    DurationUnit(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Short identifier used in URLs. */
    public String key() {
        return key;
    }

    /** German name for the unit switch. */
    public String label() {
        return label;
    }

    /** The unit for the given key; unknown or missing keys fall back to minutes. */
    public static DurationUnit fromKey(String key) {
        return Arrays.stream(values()).filter(u -> u.key.equalsIgnoreCase(key)).findFirst().orElse(MINUTES);
    }

    /** Parses a unit name such as {@code HOURS_DECIMAL}; blank means the default (minutes). */
    public static DurationUnit parse(String name) {
        if (name == null || name.isBlank()) {
            return MINUTES;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannte Einheit „" + name + "“. Erlaubt: "
                    + Arrays.toString(values()));
        }
    }

    /** Replaces {@link #AUTO} by the unit that suits the period: minutes for a day, hours for a week, person-days beyond. */
    public DurationUnit resolve(String periodKey) {
        if (this != AUTO) {
            return this;
        }
        return switch (periodKey == null ? "" : periodKey.toLowerCase(Locale.ROOT)) {
            case "day" -> MINUTES;
            case "week" -> HOURS_MINUTES;
            default -> PERSON_DAYS;
        };
    }

    /** Formats the minutes in this unit with German number formatting; {@link #AUTO} is shown as minutes. */
    public String format(int minutes, BigDecimal hoursPerPersonDay) {
        return switch (this) {
            case MINUTES, AUTO -> String.format(Locale.GERMAN, "%,d Min", minutes);
            case HOURS_MINUTES -> String.format(Locale.GERMAN, "%d:%02d h", minutes / MINUTES_PER_HOUR,
                    minutes % MINUTES_PER_HOUR);
            case HOURS_DECIMAL -> String.format(Locale.GERMAN, "%,.1f h", ratio(minutes, MINUTES_PER_HOUR));
            case PERSON_DAYS -> String.format(Locale.GERMAN, "%,.1f PT",
                    ratio(minutes, hoursPerPersonDay.multiply(BigDecimal.valueOf(MINUTES_PER_HOUR))));
        };
    }

    private static BigDecimal ratio(int minutes, long divisor) {
        return ratio(minutes, BigDecimal.valueOf(divisor));
    }

    private static BigDecimal ratio(int minutes, BigDecimal divisor) {
        return BigDecimal.valueOf(minutes).divide(divisor, DECIMAL_PLACES, RoundingMode.HALF_UP);
    }
}
