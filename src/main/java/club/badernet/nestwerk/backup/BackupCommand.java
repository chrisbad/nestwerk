package club.badernet.nestwerk.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Einmal sichern und beenden, z. B. vor einem Update mit dem noch alten Image:
 * {@code docker compose run --rm --no-deps app --nestwerk.backup.run-and-exit=true
 * --server.port=0}. Der Exit-Code ist 0 bei Erfolg, sonst 1. (Ein Zufallsport, damit die laufende Instanz nicht
 * stört; ohne Web-Kontext fehlt die Security-Konfiguration.)
 */
@Component
@ConditionalOnProperty(name = "nestwerk.backup.run-and-exit", havingValue = "true")
public class BackupCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackupCommand.class);

    private final BackupService backups;
    private final ConfigurableApplicationContext context;

    public BackupCommand(BackupService backups, ConfigurableApplicationContext context) {
        this.backups = backups;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        BackupRun run = backups.backupNow(BackupRun.Kind.PRE_UPDATE, null);
        boolean ok = run.getStatus() != BackupRun.Status.FAILED;
        log.info("Sicherung vor dem Update: {} ({})", run.getFileName(), run.getMessage());
        int code = SpringApplication.exit(context, () -> ok ? 0 : 1);
        System.exit(code);
    }
}
