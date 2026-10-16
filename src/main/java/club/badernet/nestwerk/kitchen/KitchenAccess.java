package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Prüft die Küchenrechte eines Benutzers über seine Rollen. */
@Component
public class KitchenAccess {

    private final PermissionCatalog permissions;

    public KitchenAccess(PermissionCatalog permissions) {
        this.permissions = permissions;
    }

    public boolean has(User user, KitchenPermission permission) {
        return permissions.effective(user).contains(permission.name());
    }

    public void require(User user, KitchenPermission permission) {
        if (!has(user, permission)) {
            throw new AccessDeniedException("Keine Berechtigung für diese Küchenfunktion");
        }
    }
}
