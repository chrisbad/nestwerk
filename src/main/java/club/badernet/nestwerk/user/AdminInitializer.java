package club.badernet.nestwerk.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Legt beim ersten Start den initialen Admin an, solange noch kein Benutzer existiert.
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final UserService userService;
    private final String username;
    private final String password;

    public AdminInitializer(UserService userService,
                            @Value("${nestwerk.admin.username}") String username,
                            @Value("${nestwerk.admin.password}") String password) {
        this.userService = userService;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userService.hasUsers()) {
            return;
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Keine Benutzer vorhanden und NESTWERK_ADMIN_PASSWORD ist nicht gesetzt. "
                            + "Bitte Umgebungsvariable NESTWERK_ADMIN_PASSWORD (und optional NESTWERK_ADMIN_USERNAME) setzen.");
        }
        userService.create(username, password, Role.ADMIN);
        log.info("Initialer Admin '{}' angelegt.", username);
    }
}
