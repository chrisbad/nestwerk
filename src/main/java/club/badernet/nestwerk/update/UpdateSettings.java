package club.badernet.nestwerk.update;

import club.badernet.nestwerk.backup.SecretBox;
import club.badernet.nestwerk.notification.AppSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** Kanal, GitHub-Token und Stand der letzten Prüfung; gespeichert in {@code app_settings}. */
@Component
public class UpdateSettings {

    private static final String P = "update.";

    private final AppSettings settings;
    private final SecretBox secrets;

    public UpdateSettings(AppSettings settings, SecretBox secrets) {
        this.settings = settings;
        this.secrets = secrets;
    }

    public Channel channel() {
        return get("channel").map(Channel::valueOf).orElse(Channel.RELEASES);
    }

    public Optional<String> token() {
        return get("token").map(secrets::decrypt).filter(v -> !v.isEmpty());
    }

    public boolean hasToken() {
        return token().isPresent();
    }

    /** {@code token == null} oder leer lässt einen vorhandenen Token unverändert. */
    @Transactional
    public void update(Channel channel, String token) {
        set("channel", channel.name());
        if (token != null && !token.isBlank()) {
            set("token", secrets.encrypt(token.trim()));
        }
    }

    @Transactional
    public void removeToken() {
        set("token", "");
    }

    public Optional<Instant> lastChecked() {
        return get("checked").map(Instant::parse);
    }

    public Optional<String> lastError() {
        return get("error");
    }

    @Transactional
    public void recordCheck(Instant at, String error) {
        set("checked", at.toString());
        set("error", error == null ? "" : error);
    }

    public Optional<String> lastNotified() {
        return get("notified");
    }

    @Transactional
    public void markNotified(String version) {
        set("notified", version);
    }

    private Optional<String> get(String key) {
        return settings.get(P + key).filter(v -> !v.isEmpty());
    }

    private void set(String key, String value) {
        settings.set(P + key, value);
    }
}
