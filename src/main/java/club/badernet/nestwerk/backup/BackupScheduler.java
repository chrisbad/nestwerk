package club.badernet.nestwerk.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * Prüft jede Minute, ob eine geplante Sicherung fällig ist. Verpasste Termine (App war aus) werden einmal nachgeholt,
 * nicht für jeden verpassten Termin einzeln.
 */
@Component
public class BackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

    private final BackupService backups;
    private final BackupSettings settings;
    private final Clock clock;

    public BackupScheduler(BackupService backups, BackupSettings settings, Clock clock) {
        this.backups = backups;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void tick() {
        BackupSettings.Schedule schedule = settings.schedule();
        if (!schedule.enabled()) {
            return;
        }
        Instant slot = lastSlot(schedule, ZonedDateTime.now(clock)).toInstant();
        Optional<Instant> last = settings.lastScheduled();
        if (last.isPresent() && !last.get().isBefore(slot)) {
            return;
        }
        try {
            backups.backupNow(BackupRun.Kind.SCHEDULED, null);
        } catch (IllegalStateException busy) {
            log.info("Geplante Sicherung verschoben: {}", busy.getMessage());
            return;
        }
        // auch nach einem Fehlschlag: sonst würde jede Minute erneut gesichert; gemeldet wird der Fehler per Notiz
        settings.markScheduled(slot);
    }

    /** Der nächste planmäßige Zeitpunkt nach {@code now}. */
    public static ZonedDateTime nextSlot(BackupSettings.Schedule schedule, ZonedDateTime now) {
        ZonedDateTime last = lastSlot(schedule, now);
        return schedule.frequency() == BackupSettings.Frequency.DAILY ? last.plusDays(1) : last.plusWeeks(1);
    }

    /** Der letzte planmäßige Zeitpunkt, der nicht nach {@code now} liegt. */
    static ZonedDateTime lastSlot(BackupSettings.Schedule schedule, ZonedDateTime now) {
        ZonedDateTime today = now.with(schedule.time()).withSecond(0).withNano(0);
        if (schedule.frequency() == BackupSettings.Frequency.DAILY) {
            return today.isAfter(now) ? today.minusDays(1) : today;
        }
        ZonedDateTime candidate = today.with(TemporalAdjusters.previousOrSame(schedule.weekday()));
        return candidate.isAfter(now) ? candidate.minusWeeks(1) : candidate;
    }
}
