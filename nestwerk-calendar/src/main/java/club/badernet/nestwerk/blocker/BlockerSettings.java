package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.settings.AppSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Bundesland für die Feiertage (leer = keine Feiertage); gespeichert in {@code app_settings}. */
@Component
public class BlockerSettings {

    private static final String STATE = "blocker.state";

    private final AppSettings settings;

    public BlockerSettings(AppSettings settings) {
        this.settings = settings;
    }

    public String state() {
        return settings.get(STATE).filter(s -> !s.isBlank()).orElse(null);
    }

    @Transactional
    public void setState(String state) {
        String clean = state == null ? "" : state.trim().toUpperCase(java.util.Locale.ROOT);
        if (!clean.isEmpty() && !GermanHolidays.STATES.containsKey(clean)) {
            throw new IllegalArgumentException("Unbekanntes Bundesland.");
        }
        settings.set(STATE, clean);
    }
}
