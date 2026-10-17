package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.security.AdminPermission;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Prüft die Verwaltungsrechte, die in der Weboberfläche per {@code @PreAuthorize} gelten, für die MCP-Tools. */
@Component
public class AdminGuard {

    private final PermissionCatalog catalog;

    public AdminGuard(PermissionCatalog catalog) {
        this.catalog = catalog;
    }

    public void require(User user, AdminPermission permission) {
        if (!catalog.effective(user).contains(permission.name())) {
            throw new AccessDeniedException("Dafür fehlt das Recht " + permission.name());
        }
    }
}
