package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Checks the calendar permissions of a user against the effective permission catalog. */
@Component
class CalendarAccess {

    private final PermissionCatalog permissions;

    CalendarAccess(PermissionCatalog permissions) {
        this.permissions = permissions;
    }

    boolean has(User user, CalendarPermission permission) {
        return permissions.effective(user).contains(permission.name());
    }

    void require(User user, CalendarPermission permission) {
        if (!has(user, permission)) {
            throw new AccessDeniedException("Keine Berechtigung: " + permission.name());
        }
    }
}
