package club.badernet.nestwerk.update;

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

/** Meldet neue Versionen und Ergebnisse von Updates an alle, die Updates verwalten dürfen. */
@Component
public class UpdateNotifier {

    static final String SOURCE = "UPDATE";
    private static final Logger log = LoggerFactory.getLogger(UpdateNotifier.class);

    private final NotificationService notifications;
    private final UserRepository users;
    private final PermissionCatalog permissions;
    private final TransactionTemplate tx;

    public UpdateNotifier(NotificationService notifications, UserRepository users, PermissionCatalog permissions,
                          TransactionTemplate tx) {
        this.notifications = notifications;
        this.users = users;
        this.permissions = permissions;
        this.tx = tx;
    }

    void available(AvailableUpdate update) {
        send(NotificationType.UPDATE_AVAILABLE, 0L, update.version(), "Neue Version " + update.version(),
                "Nestwerk " + update.version() + " ist verfügbar und kann unter „Updates“ eingespielt werden.");
    }

    private void send(NotificationType type, Long sourceId, String key, String title, String body) {
        try {
            tx.executeWithoutResult(s -> {
                for (User user : users.findAll()) {
                    if (user.isEnabled() && permissions.effective(user)
                            .contains(AdminPermission.UPDATE_MANAGE.name())) {
                        notifications.notify(user.getId(), type, SOURCE, sourceId, key, title, body,
                                "/admin/updates");
                    }
                }
            });
        } catch (RuntimeException e) {
            log.error("Benachrichtigung „{}“ fehlgeschlagen", title, e);
        }
    }
}
