package club.badernet.nestwerk.role;

import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final UserRepository users;
    private final PermissionCatalog catalog;

    public RoleService(RoleRepository roles, UserRepository users, PermissionCatalog catalog) {
        this.roles = roles;
        this.users = users;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<Role> findAll() {
        return roles.findAllByOrderByNameAsc();
    }

    @Transactional
    public Role create(String name, String description, Set<String> permissions) {
        String cleaned = requireName(name);
        if (roles.existsByNameIgnoreCase(cleaned)) {
            throw new IllegalArgumentException("Eine Rolle mit diesem Namen existiert bereits.");
        }
        return roles.save(new Role(cleaned, clean(description), validPermissions(permissions)));
    }

    @Transactional
    public void update(Long id, String name, String description, Set<String> permissions) {
        Role role = get(id);
        if (role.isAllPermissions()) {
            throw new IllegalArgumentException("Die Administrator-Rolle kann nicht geändert werden.");
        }
        if (!role.isSystemRole()) {
            String cleaned = requireName(name);
            Optional<Role> clash = roles.findByNameIgnoreCase(cleaned);
            if (clash.isPresent() && !clash.get().getId().equals(id)) {
                throw new IllegalArgumentException("Eine Rolle mit diesem Namen existiert bereits.");
            }
            role.rename(cleaned);
        }
        role.setDescription(clean(description));
        role.setPermissions(validPermissions(permissions));
    }

    @Transactional
    public void delete(Long id) {
        Role role = get(id);
        if (role.isSystemRole()) {
            throw new IllegalArgumentException("Systemrollen können nicht gelöscht werden.");
        }
        if (users.existsByRoles_Id(id)) {
            throw new IllegalArgumentException("Die Rolle ist noch Benutzern zugewiesen.");
        }
        roles.delete(role);
    }

    /**
     * Verhindert Rechteausweitung durch Rollenverwalter ohne Administratorrechte: Sie dürfen nur
     * Rechte vergeben, die sie selbst besitzen, und keine Rollen mit weitergehenden Rechten ändern.
     *
     * @param actorPermissions Rechte des handelnden Benutzers
     * @param roleId           betroffene Rolle oder {@code null} beim Anlegen
     * @param permissions      zu setzende Rechte oder {@code null}, wenn keine gesetzt werden
     */
    @Transactional(readOnly = true)
    public void requireMayManage(Collection<String> actorPermissions, Long roleId, Set<String> permissions) {
        if (catalog.isAdministrator(actorPermissions)) {
            return;
        }
        if (roleId != null && !actorPermissions.containsAll(catalog.effective(get(roleId)))) {
            throw new IllegalArgumentException("Diese Rolle enthält Rechte, die du selbst nicht hast.");
        }
        if (permissions != null && !actorPermissions.containsAll(permissions)) {
            throw new IllegalArgumentException("Du kannst nur Rechte vergeben, die du selbst hast.");
        }
    }

    private Role get(Long id) {
        return roles.findById(id).orElseThrow(() -> new IllegalArgumentException("Rolle nicht gefunden"));
    }

    private String requireName(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty() || cleaned.length() > 100) {
            throw new IllegalArgumentException("Der Rollenname muss 1–100 Zeichen lang sein.");
        }
        return cleaned;
    }

    private String clean(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    private Set<String> validPermissions(Set<String> permissions) {
        Set<String> valid = new HashSet<>();
        if (permissions == null) {
            return valid;
        }
        for (String p : permissions) {
            if (!catalog.exists(p)) {
                throw new IllegalArgumentException("Unbekanntes Recht: " + p);
            }
            valid.add(p);
        }
        return valid;
    }
}
