package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.blocker.BusyTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;

/** Zeitplaner der Küche: tägliche Ablaufwarnung und Bring!-Abgleich alle 15 Minuten. */
@Component
public class KitchenScheduler {

    static final Duration SYNC_EVERY = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(KitchenScheduler.class);

    private final ExpiryNotifier expiry;
    private final BringSync bring;
    private final BusyTimes busyTimes;
    private final Clock clock;
    private Instant lastSync = Instant.EPOCH;

    public KitchenScheduler(ExpiryNotifier expiry, BringSync bring, BusyTimes busyTimes, Clock clock) {
        this.expiry = expiry;
        this.bring = bring;
        this.busyTimes = busyTimes;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        try {
            expiry.run(ZonedDateTime.now(clock.withZone(busyTimes.zone())));
        } catch (RuntimeException e) {
            log.error("Ablaufwarnung für den Vorrat fehlgeschlagen", e);
        }
        Instant now = Instant.now(clock);
        if (Duration.between(lastSync, now).compareTo(SYNC_EVERY) < 0) {
            return;
        }
        lastSync = now;
        try {
            BringSync.Result result = bring.sync();
            if (result.failed() && bring.configured()) {
                log.warn("Bring!-Abgleich nicht möglich: {}", result.error());
            }
        } catch (RuntimeException e) {
            log.error("Bring!-Abgleich fehlgeschlagen", e);
        }
    }
}
