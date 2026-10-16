package club.badernet.nestwerk.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Einmal sichern und beenden, z. B. vor einem Update mit dem noch alten Image:
 * {@code docker compose run --rm --no-deps app --nestwerk.backup.run-and-exit=true
 * --server.port=0}. Der Exit-Code ist 0 bei Erfolg, sonst 1. (Ein Zufallsport, damit die laufende Instanz nicht
 * stört; ohne Web-Kontext fehlt die Security-Konfiguration.) Mit {@code --nestwerk.backup.result-file=<pfad>}
 * steht danach der Dateiname der Sicherung in dieser Datei (für den Updater).
 */
@Component
@ConditionalOnProperty(name = "nestwerk.backup.run-and-exit", havingValue = "true")
public class BackupCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackupCommand.class);

    private final BackupService backups;
    private final ConfigurableApplicationContext context;
    private final String resultFile;

    public BackupCommand(BackupService backups, ConfigurableApplicationContext context,
                         @Value("${nestwerk.backup.result-file:}") String resultFile) {
        this.backups = backups;
        this.context = context;
        this.resultFile = resultFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        BackupRun run = backups.backupNow(BackupRun.Kind.PRE_UPDATE, null);
        boolean ok = run.getStatus() != BackupRun.Status.FAILED;
        log.info("Sicherung vor dem Update: {} ({})", run.getFileName(), run.getMessage());
        if (ok && !resultFile.isBlank()) {
            writeResult(Path.of(resultFile), run);
        }
        int code = SpringApplication.exit(context, () -> ok ? 0 : 1);
        System.exit(code);
    }

    static void writeResult(Path file, BackupRun run) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, run.getFileName(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Ergebnisdatei nicht schreibbar: " + file, e);
        }
    }
}
