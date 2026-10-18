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

/**
 * Rückfall des Updaters: {@code docker compose run --rm --no-deps app
 * --nestwerk.backup.restore-and-exit=<datei> --server.port=0} mit dem alten Image spielt die Sicherung von vor dem
 * Update vollständig ein und beendet sich (Exit-Code 0 bei Erfolg, sonst 1).
 */
@Component
@ConditionalOnProperty(name = "nestwerk.backup.restore-and-exit")
public class RestoreCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RestoreCommand.class);

    private final BackupService backups;
    private final ConfigurableApplicationContext context;
    private final String fileName;

    public RestoreCommand(BackupService backups, ConfigurableApplicationContext context,
                          @Value("${nestwerk.backup.restore-and-exit}") String fileName) {
        this.backups = backups;
        this.context = context;
        this.fileName = fileName;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean ok;
        try {
            backups.replaceAll(fileName);
            log.info("Sicherung {} vollständig eingespielt", fileName);
            ok = true;
        } catch (RuntimeException e) {
            log.error("Sicherung {} konnte nicht eingespielt werden", fileName, e);
            ok = false;
        }
        boolean success = ok;
        int code = SpringApplication.exit(context, () -> success ? 0 : 1);
        System.exit(code);
    }
}
