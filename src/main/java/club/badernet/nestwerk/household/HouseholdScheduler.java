package club.badernet.nestwerk.household;

import club.badernet.nestwerk.calendar.AbsenceChangedEvent;
import club.badernet.nestwerk.notification.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Zeitplaner des Putzplans: plant einmal am Tag die nächsten drei Wochen, erinnert minütlich an fällige
 * Hausarbeiten und verteilt nach Änderungen an Abwesenheiten neu.
 */
@Component
public class HouseholdScheduler {

    static final String LAST_PLAN = "household.lastPlan";
    private static final Logger log = LoggerFactory.getLogger(HouseholdScheduler.class);

    private final ChorePlanner planner;
    private final HouseholdReminders reminders;
    private final AppSettings settings;
    private final Clock clock;

    public HouseholdScheduler(ChorePlanner planner, HouseholdReminders reminders, AppSettings settings, Clock clock) {
        this.planner = planner;
        this.reminders = reminders;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        try {
            boolean plannedToday = settings.get(LAST_PLAN).map(LocalDate::parse).filter(today::equals).isPresent();
            if (!plannedToday) {
                planner.planAll(today);
                settings.set(LAST_PLAN, today.toString());
            }
        } catch (RuntimeException e) {
            log.error("Planung des Putzplans für {} fehlgeschlagen", today, e);
        }
        try {
            reminders.run(now);
        } catch (RuntimeException e) {
            log.error("Erinnerungen zum Putzplan fehlgeschlagen", e);
        }
    }

    @EventListener
    public void absenceChanged(AbsenceChangedEvent event) {
        planner.reassignAbsent(planner.today());
    }
}
