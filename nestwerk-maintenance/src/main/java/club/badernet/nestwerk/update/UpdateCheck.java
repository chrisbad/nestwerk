package club.badernet.nestwerk.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Fragt alle 6 Stunden (und auf Knopfdruck) nach einer neueren Version und meldet sie einmal je Version. */
@Component
public class UpdateCheck {

    public record CheckResult(Optional<AvailableUpdate> update, String error) {
    }

    private static final Logger log = LoggerFactory.getLogger(UpdateCheck.class);

    private final ReleaseSource source;
    private final UpdateSettings settings;
    private final UpdateNotifier notifier;
    private final Clock clock;
    private final AppVersion current;
    private final AtomicReference<AvailableUpdate> cached = new AtomicReference<>();

    public UpdateCheck(ReleaseSource source, UpdateSettings settings, UpdateNotifier notifier, Clock clock,
                       ObjectProvider<BuildProperties> build,
                       @Value("${nestwerk.update.current-version:}") String overrideVersion) {
        this.source = source;
        this.settings = settings;
        this.notifier = notifier;
        this.clock = clock;
        this.current = AppVersion.of(!overrideVersion.isBlank() ? overrideVersion
                : Optional.ofNullable(build.getIfAvailable()).map(BuildProperties::getVersion).orElse("dev"));
    }

    public AppVersion current() {
        return current;
    }

    public Optional<AvailableUpdate> cached() {
        return Optional.ofNullable(cached.get());
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT6H")
    void scheduled() {
        check();
    }

    public CheckResult check() {
        if (current.isDev()) {
            return new CheckResult(Optional.empty(), null);
        }
        try {
            Optional<AvailableUpdate> update = source.latest(settings.channel(), current);
            cached.set(update.orElse(null));
            settings.recordCheck(clock.instant(), null);
            update.filter(u -> !settings.lastNotified().filter(u.version()::equals).isPresent())
                    .ifPresent(u -> {
                        notifier.available(u);
                        settings.markNotified(u.version());
                    });
            return new CheckResult(update, null);
        } catch (UpdateSourceException e) {
            log.warn("Updateprüfung fehlgeschlagen: {}", e.getMessage());
            settings.recordCheck(clock.instant(), e.getMessage());
            return new CheckResult(Optional.empty(), e.getMessage());
        }
    }
}
