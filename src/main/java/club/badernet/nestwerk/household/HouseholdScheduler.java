package club.badernet.nestwerk.household;

import club.badernet.nestwerk.calendar.AbsenceChangedEvent;
import club.badernet.nestwerk.notification.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Zeitplaner des Haushaltsplans: plant einmal am Tag die nächsten drei Wochen, erinnert minütlich an fällige
 * Hausarbeiten und verteilt nach Änderungen an Abwesenheiten neu.
 */
@Component
public class HouseholdScheduler {

    static final String LAST_PLAN = "household.lastPlan";
    private static final Logger log = LoggerFactory.getLogger(HouseholdScheduler.class);

    private final ChorePlanner planner;
    private final HouseholdReminders reminders;
    private final IcalImporter importer;
    private final AppSettings settings;
    private final Clock clock;
    private final TransactionTemplate separate;

    public HouseholdScheduler(ChorePlanner planner, HouseholdReminders reminders, IcalImporter importer,
                              AppSettings settings, Clock clock, PlatformTransactionManager transactionManager) {
        this.separate = new TransactionTemplate(transactionManager);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.importer = importer;
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
                importer.syncAll();
                planner.planAll(today);
                settings.set(LAST_PLAN, today.toString());
            }
        } catch (RuntimeException e) {
            log.error("Planung des Haushaltsplans für {} fehlgeschlagen", today, e);
        }
        try {
            reminders.run(now);
        } catch (RuntimeException e) {
            log.error("Erinnerungen zum Haushaltsplan fehlgeschlagen", e);
        }
    }

    /** Nach dem Speichern des Termins in eigener Transaktion; ein Fehler hier darf den Termin nicht verhindern. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void absenceChanged(AbsenceChangedEvent event) {
        try {
            separate.executeWithoutResult(status -> planner.reassignAbsent(planner.today()));
        } catch (RuntimeException e) {
            log.error("Neuverteilung nach Abwesenheit (Termin {}) fehlgeschlagen", event.eventId(), e);
        }
    }
}
