package club.badernet.nestwerk.household;

import club.badernet.nestwerk.settings.AppSettings;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
/** Household-wide settings stored in {@code app_settings}. */
@Component
public class HouseholdSettings {

    static final String HOURS_PER_PERSON_DAY = "household.hoursPerPersonDay";
    public static final BigDecimal DEFAULT_HOURS_PER_PERSON_DAY = BigDecimal.valueOf(8);
    public static final BigDecimal MAX_HOURS_PER_PERSON_DAY = BigDecimal.valueOf(24);

    private final AppSettings settings;
    private final HouseholdAccess access;

    public HouseholdSettings(AppSettings settings, HouseholdAccess access) {
        this.settings = settings;
        this.access = access;
    }

    /** Length of one person-day in hours, used to display efforts as person-days. */
    public BigDecimal hoursPerPersonDay() {
        return settings.get(HOURS_PER_PERSON_DAY).map(BigDecimal::new).orElse(DEFAULT_HOURS_PER_PERSON_DAY);
    }

    public boolean canManage(User user) {
        return access.canAny(user, "CREATE");
    }

    @Transactional
    public void setHoursPerPersonDay(BigDecimal hours, User actor) {
        if (!canManage(actor)) {
            throw new AccessDeniedException("Keine Berechtigung, die Haushalts-Einstellungen zu ändern");
        }
        if (hours == null || hours.signum() <= 0 || hours.compareTo(MAX_HOURS_PER_PERSON_DAY) > 0) {
            throw new IllegalArgumentException("Stunden pro Personentag müssen größer als 0 und höchstens "
                    + MAX_HOURS_PER_PERSON_DAY + " sein.");
        }
        settings.set(HOURS_PER_PERSON_DAY, hours.stripTrailingZeros().toPlainString());
    }

    /** Hours without trailing zeros and with a German decimal comma, e.g. {@code 7,5}. */
    public static String plain(BigDecimal hours) {
        return hours.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** Builds the formatter for a unit on a period. */
    public DurationFormat format(DurationUnit unit, String periodKey) {
        return new DurationFormat(unit.resolve(periodKey), hoursPerPersonDay());
    }
}
