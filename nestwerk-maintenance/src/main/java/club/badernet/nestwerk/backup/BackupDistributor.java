package club.badernet.nestwerk.backup;

import club.badernet.nestwerk.backup.target.BackupTarget;
import club.badernet.nestwerk.backup.target.DirectoryTarget;
import club.badernet.nestwerk.backup.target.SftpTarget;
import club.badernet.nestwerk.backup.target.WebDavTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Kopiert eine fertige Sicherung an alle eingeschalteten Ziele (auf Wunsch verschlüsselt) und räumt dort nach der
 * Aufbewahrungsregel auf. Ein fehlschlagendes Ziel hält die anderen nicht auf.
 */
@Component
public class BackupDistributor {

    /** Ergebnis: Hinweise je Ziel und die fehlgeschlagenen Ziele. */
    public record Outcome(List<String> notes, List<String> failures) {
    }

    /** Ein Ziel für die Einstellungsseite. */
    public record TargetInfo(String key, String label, BackupTarget target) {
    }

    private static final Logger log = LoggerFactory.getLogger(BackupDistributor.class);

    private final BackupSettings settings;
    private final Clock clock;
    private final Path externalDir;

    public BackupDistributor(BackupSettings settings, Clock clock,
                             @Value("${nestwerk.backup.external-dir:./data/external}") String externalDir) {
        this.settings = settings;
        this.clock = clock;
        this.externalDir = Path.of(externalDir);
    }

    public DirectoryTarget external() {
        return new DirectoryTarget(externalDir);
    }

    public Optional<WebDavTarget> webDav() {
        BackupSettings.WebDav w = settings.webDav();
        return w.url().isEmpty() ? Optional.empty()
                : Optional.of(new WebDavTarget(w.url(), w.user(), settings.webDavPassword().orElse(null)));
    }

    public Optional<SftpTarget> sftp() {
        BackupSettings.Sftp s = settings.sftp();
        return s.host().isEmpty() ? Optional.empty()
                : Optional.of(new SftpTarget(s.host(), s.port(), s.user(), s.path(),
                settings.sftpPassword().orElse(null), settings.sftpPrivateKey().orElse(null), s.hostKey()));
    }

    /** Die eingeschalteten Ziele. */
    public List<TargetInfo> enabled() {
        List<TargetInfo> targets = new ArrayList<>();
        if (settings.externalEnabled()) {
            targets.add(new TargetInfo("external", "Externe Platte", external()));
        }
        if (settings.webDav().enabled()) {
            webDav().ifPresent(t -> targets.add(new TargetInfo("webdav", "WebDAV", t)));
        }
        if (settings.sftp().enabled()) {
            sftp().ifPresent(t -> targets.add(new TargetInfo("sftp", "SFTP", t)));
        }
        return targets;
    }

    public Outcome distribute(Path file, String name) {
        List<String> notes = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<TargetInfo> targets = enabled();
        if (targets.isEmpty()) {
            return new Outcome(notes, failures);
        }
        Path upload = file;
        String uploadName = name;
        Path encrypted = null;
        try {
            if (settings.encryptionEnabled()) {
                encrypted = Files.createTempFile(file.getParent(), ".nestwerk-", ".enc.tmp");
                try (InputStream in = Files.newInputStream(file); OutputStream out = Files.newOutputStream(encrypted)) {
                    BackupCrypto.encrypt(in, out, settings.passphrase().orElseThrow());
                }
                upload = encrypted;
                uploadName = name + ".enc";
            }
            for (TargetInfo t : targets) {
                try {
                    t.target().upload(upload, uploadName);
                    int removed = prune(t.target());
                    notes.add(t.label() + ": übertragen" + (removed > 0 ? ", " + removed + " alte entfernt" : ""));
                } catch (IOException | RuntimeException e) {
                    log.warn("Sicherung an {} fehlgeschlagen", t.label(), e);
                    failures.add(t.label() + ": " + BackupService.describe(e));
                }
            }
        } catch (IOException e) {
            failures.add("Verschlüsseln: " + BackupService.describe(e));
        } finally {
            if (encrypted != null) {
                try {
                    Files.deleteIfExists(encrypted);
                } catch (IOException ignored) {
                    // temporär
                }
            }
        }
        return new Outcome(notes, failures);
    }

    private int prune(BackupTarget target) throws IOException {
        int removed = 0;
        for (String old : Retention.toDelete(target.list(), settings.retention(), clock.getZone())) {
            target.delete(old);
            removed++;
        }
        return removed;
    }
}
