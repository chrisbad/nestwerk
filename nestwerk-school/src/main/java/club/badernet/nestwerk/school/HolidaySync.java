package club.badernet.nestwerk.school;

import club.badernet.nestwerk.settings.AppSettings;
import club.badernet.nestwerk.settings.CalendarSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ruft die Schulferien höchstens einmal täglich je Bundesland ab, für alle Bundesländer der Schulprofile.
 * Ein Ausfall der Quelle wird protokolliert; die gespeicherten Ferien bleiben gültig.
 */
@Component
@ConditionalOnProperty(name = "nestwerk.school.holidays.enabled", havingValue = "true", matchIfMissing = true)
public class HolidaySync {

    static final String LAST_RUN = "school.holidays.lastRun.";
    private static final Logger log = LoggerFactory.getLogger(HolidaySync.class);

    private final SchoolService school;
    private final HolidayService holidays;
    private final AppSettings settings;
    private final CalendarSettings calendar;

    public HolidaySync(SchoolService school, HolidayService holidays, AppSettings settings,
                       CalendarSettings calendar) {
        this.school = school;
        this.holidays = holidays;
        this.settings = settings;
        this.calendar = calendar;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void tick() {
        LocalDate today = LocalDate.now(calendar.zone());
        Set<String> states = school.profiles().stream().map(SchoolProfile::getState)
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.toSet());
        for (String state : List.copyOf(states)) {
            run(state, today);
        }
    }

    /** Ruft ein Bundesland ab, wenn es heute noch nicht geschehen ist. Liefert, ob etwas geändert wurde. */
    public boolean run(String state, LocalDate today) {
        if (settings.get(LAST_RUN + state).map(LocalDate::parse).filter(today::equals).isPresent()) {
            return false;
        }
        try {
            boolean changed = holidays.refresh(state, today);
            settings.set(LAST_RUN + state, today.toString());
            return changed;
        } catch (HolidayException e) {
            log.warn("Schulferien für {} nicht abrufbar: {}", state, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.error("Schulferien für {} fehlgeschlagen", state, e);
            return false;
        }
    }
}
