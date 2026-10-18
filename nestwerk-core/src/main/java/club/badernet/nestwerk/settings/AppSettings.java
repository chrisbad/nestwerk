package club.badernet.nestwerk.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Schlüssel/Wert-Einstellungen der App aus der Tabelle {@code app_settings}. */
@Component
public class AppSettings {

    static final String REMINDER_HOUR = "reminders.hour";
    static final String LEAD_DAYS = "reminders.defaultLeadDays";
    static final String LAST_RUN = "reminders.lastRun";

    private final JdbcTemplate jdbc;

    public AppSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        List<String> values = jdbc.queryForList("SELECT value FROM app_settings WHERE key = ?", String.class, key);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }

    @Transactional
    public void set(String key, String value) {
        jdbc.update("INSERT INTO app_settings (key, value) VALUES (?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", key, value);
    }

    public int reminderHour() {
        return get(REMINDER_HOUR).map(Integer::parseInt).orElse(8);
    }

    public int defaultLeadDays() {
        return get(LEAD_DAYS).map(Integer::parseInt).orElse(2);
    }

    @Transactional
    public void updateReminders(int hour, int leadDays) {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("Die Uhrzeit muss zwischen 0 und 23 Uhr liegen.");
        }
        if (leadDays < 0 || leadDays > 60) {
            throw new IllegalArgumentException("Der Vorlauf muss zwischen 0 und 60 Tagen liegen.");
        }
        set(REMINDER_HOUR, String.valueOf(hour));
        set(LEAD_DAYS, String.valueOf(leadDays));
    }

    public Optional<LocalDate> lastReminderRun() {
        return get(LAST_RUN).map(LocalDate::parse);
    }

    @Transactional
    public void markReminderRun(LocalDate day) {
        set(LAST_RUN, day.toString());
    }
}
