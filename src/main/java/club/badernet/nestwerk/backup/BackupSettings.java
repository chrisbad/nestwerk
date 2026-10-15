package club.badernet.nestwerk.backup;

import club.badernet.nestwerk.notification.AppSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;

/** Einstellungen für Zeitplan, Aufbewahrung, Verschlüsselung und Ziele; gespeichert in {@code app_settings}. */
@Component
public class BackupSettings {

    public enum Frequency { DAILY, WEEKLY }

    public record Schedule(boolean enabled, Frequency frequency, DayOfWeek weekday, LocalTime time) {
    }

    /** Die letzten {@code keepLast} Sicherungen, dazu je die neueste pro Woche bzw. Monat. */
    public record RetentionRule(int keepLast, int keepWeekly, int keepMonthly) {
    }

    public record WebDav(boolean enabled, String url, String user, boolean hasPassword) {
    }

    public record Sftp(boolean enabled, String host, int port, String user, String path, boolean hasPassword,
                       boolean hasPrivateKey, String hostKey) {
    }

    private static final String P = "backup.";

    private final AppSettings settings;
    private final SecretBox secrets;

    public BackupSettings(AppSettings settings, SecretBox secrets) {
        this.settings = settings;
        this.secrets = secrets;
    }

    // --- Zeitplan --------------------------------------------------------------------

    public Schedule schedule() {
        return new Schedule(bool("schedule.enabled", false),
                get("schedule.frequency").map(Frequency::valueOf).orElse(Frequency.DAILY),
                get("schedule.weekday").map(DayOfWeek::valueOf).orElse(DayOfWeek.SUNDAY),
                get("schedule.time").map(LocalTime::parse).orElse(LocalTime.of(2, 30)));
    }

    /**
     * Speichert den Zeitplan. Der Stichtag für „zuletzt geplant gelaufen“ wird auf jetzt gesetzt, damit nach dem
     * Einschalten nicht sofort eine nachgeholte Sicherung startet.
     */
    @Transactional
    public void updateSchedule(Schedule schedule, Instant now) {
        if (schedule.time() == null) {
            throw new IllegalArgumentException("Bitte eine Uhrzeit angeben.");
        }
        set("schedule.enabled", String.valueOf(schedule.enabled()));
        set("schedule.frequency", schedule.frequency().name());
        set("schedule.weekday", schedule.weekday().name());
        set("schedule.time", schedule.time().withSecond(0).withNano(0).toString());
        markScheduled(now);
    }

    public Optional<Instant> lastScheduled() {
        return get("schedule.last").map(Instant::parse);
    }

    @Transactional
    public void markScheduled(Instant slot) {
        set("schedule.last", slot.toString());
    }

    public boolean notifySuccess() {
        return bool("notify.success", false);
    }

    // --- Aufbewahrung ----------------------------------------------------------------

    public RetentionRule retention() {
        return new RetentionRule(integer("retention.last", 14), integer("retention.weekly", 4),
                integer("retention.monthly", 6));
    }

    @Transactional
    public void updateRetention(RetentionRule retention, boolean notifySuccess) {
        if (retention.keepLast() < 1 || retention.keepLast() > 365) {
            throw new IllegalArgumentException("Aufzubewahren sind 1 bis 365 Sicherungen.");
        }
        if (retention.keepWeekly() < 0 || retention.keepWeekly() > 104
                || retention.keepMonthly() < 0 || retention.keepMonthly() > 120) {
            throw new IllegalArgumentException("Wochen: 0 bis 104, Monate: 0 bis 120.");
        }
        set("retention.last", String.valueOf(retention.keepLast()));
        set("retention.weekly", String.valueOf(retention.keepWeekly()));
        set("retention.monthly", String.valueOf(retention.keepMonthly()));
        set("notify.success", String.valueOf(notifySuccess));
    }

    // --- Verschlüsselung ---------------------------------------------------------------

    public boolean encryptionEnabled() {
        return bool("encrypt.enabled", false) && passphrase().isPresent();
    }

    public boolean hasPassphrase() {
        return passphrase().isPresent();
    }

    Optional<String> passphrase() {
        return secret("encrypt.passphrase");
    }

    /** {@code passphrase == null} oder leer lässt eine vorhandene Passphrase unverändert. */
    @Transactional
    public void updateEncryption(boolean enabled, String passphrase) {
        if (passphrase != null && !passphrase.isEmpty()) {
            if (passphrase.length() < 8) {
                throw new IllegalArgumentException("Die Passphrase muss mindestens 8 Zeichen lang sein.");
            }
            setSecret("encrypt.passphrase", passphrase);
        }
        if (enabled && passphrase().isEmpty()) {
            throw new IllegalArgumentException("Zum Verschlüsseln bitte eine Passphrase angeben.");
        }
        set("encrypt.enabled", String.valueOf(enabled));
    }

    // --- Ziele -------------------------------------------------------------------------

    public boolean externalEnabled() {
        return bool("external.enabled", false);
    }

    @Transactional
    public void updateExternal(boolean enabled) {
        set("external.enabled", String.valueOf(enabled));
    }

    public WebDav webDav() {
        return new WebDav(bool("webdav.enabled", false), get("webdav.url").orElse(""),
                get("webdav.user").orElse(""), secret("webdav.password").isPresent());
    }

    Optional<String> webDavPassword() {
        return secret("webdav.password");
    }

    @Transactional
    public void updateWebDav(boolean enabled, String url, String user, String password) {
        String cleanUrl = url == null ? "" : url.trim();
        if (!cleanUrl.isEmpty()) {
            URI uri;
            try {
                uri = URI.create(cleanUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Die WebDAV-Adresse ist ungültig.");
            }
            if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
                throw new IllegalArgumentException("Die WebDAV-Adresse muss mit https:// beginnen.");
            }
            if (!cleanUrl.endsWith("/")) {
                cleanUrl += "/";
            }
        }
        if (enabled && cleanUrl.isEmpty()) {
            throw new IllegalArgumentException("Bitte die WebDAV-Adresse angeben.");
        }
        set("webdav.enabled", String.valueOf(enabled));
        set("webdav.url", cleanUrl);
        set("webdav.user", user == null ? "" : user.trim());
        if (password != null && !password.isEmpty()) {
            setSecret("webdav.password", password);
        }
    }

    public Sftp sftp() {
        return new Sftp(bool("sftp.enabled", false), get("sftp.host").orElse(""), integer("sftp.port", 22),
                get("sftp.user").orElse(""), get("sftp.path").orElse(""), secret("sftp.password").isPresent(),
                secret("sftp.key").isPresent(), get("sftp.hostkey").orElse(""));
    }

    Optional<String> sftpPassword() {
        return secret("sftp.password");
    }

    Optional<String> sftpPrivateKey() {
        return secret("sftp.key");
    }

    @Transactional
    public void updateSftp(boolean enabled, String host, Integer port, String user, String path, String password,
                           String privateKey) {
        String cleanHost = host == null ? "" : host.trim();
        if (!cleanHost.matches("[A-Za-z0-9.:\\-\\[\\]]*")) {
            throw new IllegalArgumentException("Der SFTP-Server ist ungültig.");
        }
        int cleanPort = port == null ? 22 : port;
        if (cleanPort < 1 || cleanPort > 65535) {
            throw new IllegalArgumentException("Der Port muss zwischen 1 und 65535 liegen.");
        }
        if (enabled && (cleanHost.isEmpty() || user == null || user.isBlank())) {
            throw new IllegalArgumentException("Bitte Server und Benutzer für SFTP angeben.");
        }
        String before = get("sftp.host").orElse("") + ":" + integer("sftp.port", 22);
        set("sftp.enabled", String.valueOf(enabled));
        set("sftp.host", cleanHost);
        set("sftp.port", String.valueOf(cleanPort));
        set("sftp.user", user == null ? "" : user.trim());
        set("sftp.path", path == null ? "" : path.trim());
        if (password != null && !password.isEmpty()) {
            setSecret("sftp.password", password);
        }
        if (privateKey != null && !privateKey.isBlank()) {
            setSecret("sftp.key", privateKey.trim() + "\n");
        }
        if (!before.equals(cleanHost + ":" + cleanPort)) {
            set("sftp.hostkey", "");
        }
    }

    @Transactional
    public void trustSftpHostKey(String hostKey) {
        set("sftp.hostkey", hostKey);
    }

    // --- Hilfen ------------------------------------------------------------------------

    private Optional<String> get(String key) {
        return settings.get(P + key).filter(v -> !v.isEmpty());
    }

    private void set(String key, String value) {
        settings.set(P + key, value);
    }

    private boolean bool(String key, boolean fallback) {
        return get(key).map(Boolean::parseBoolean).orElse(fallback);
    }

    private int integer(String key, int fallback) {
        return get(key).map(Integer::parseInt).orElse(fallback);
    }

    private Optional<String> secret(String key) {
        return get(key).map(secrets::decrypt).filter(v -> !v.isEmpty());
    }

    private void setSecret(String key, String value) {
        set(key, secrets.encrypt(value));
    }
}
