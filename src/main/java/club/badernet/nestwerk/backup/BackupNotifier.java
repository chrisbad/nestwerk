package club.badernet.nestwerk.backup;

import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.security.AdminPermission;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Meldet Sicherungsergebnisse an alle, die Sicherungen verwalten dürfen. */
@Component
public class BackupNotifier {

    static final String SOURCE = "BACKUP";
    private static final Logger log = LoggerFactory.getLogger(BackupNotifier.class);

    private final NotificationService notifications;
    private final UserRepository users;
    private final PermissionCatalog permissions;
    private final BackupSettings settings;
    private final TransactionTemplate tx;

    public BackupNotifier(NotificationService notifications, UserRepository users, PermissionCatalog permissions,
                          BackupSettings settings, TransactionTemplate tx) {
        this.notifications = notifications;
        this.users = users;
        this.permissions = permissions;
        this.settings = settings;
        this.tx = tx;
    }

    void finished(BackupRun run) {
        boolean problem = run.getStatus() == BackupRun.Status.FAILED || run.getStatus() == BackupRun.Status.WARNING;
        if (!problem && !settings.notifySuccess()) {
            return;
        }
        NotificationType type = problem ? NotificationType.BACKUP_FAILED : NotificationType.BACKUP_DONE;
        String title = switch (run.getStatus()) {
            case FAILED -> "Sicherung fehlgeschlagen";
            case WARNING -> "Sicherung mit Problemen";
            default -> "Sicherung erstellt";
        };
        try {
            tx.executeWithoutResult(s -> {
                for (User user : users.findAll()) {
                    if (user.isEnabled() && permissions.effective(user)
                            .contains(AdminPermission.BACKUP_MANAGE.name())) {
                        notifications.notify(user.getId(), type, SOURCE, run.getId(), String.valueOf(run.getId()),
                                title, run.getMessage(), "/admin/backup");
                    }
                }
            });
        } catch (RuntimeException e) {
            log.error("Benachrichtigung über Sicherung {} fehlgeschlagen", run.getId(), e);
        }
    }
}
