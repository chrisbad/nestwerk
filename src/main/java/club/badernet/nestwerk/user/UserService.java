package club.badernet.nestwerk.user;

import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.security.PermissionCatalog;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserService implements UserDetailsService {

    public static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final PermissionCatalog catalog;

    public UserService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
                       PermissionCatalog catalog) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.catalog = catalog;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Benutzer nicht gefunden: " + username));
        Set<GrantedAuthority> authorities = catalog.effective(user).stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(authorities)
                .disabled(!user.isEnabled())
                .build();
    }

    @Transactional(readOnly = true)
    public List<User> findAll() {
        return users.findAllByOrderByUsernameAsc();
    }

    @Transactional(readOnly = true)
    public boolean hasUsers() {
        return users.count() > 0;
    }

    @Transactional
    public User create(String username, String rawPassword, Set<Long> roleIds) {
        requireValidPassword(rawPassword);
        if (users.existsByUsername(username)) {
            throw new IllegalArgumentException("Benutzername ist bereits vergeben");
        }
        User user = new User(username, passwordEncoder.encode(rawPassword));
        user.setRoles(resolveRoles(roleIds));
        return users.save(user);
    }

    @Transactional
    public User createAdministrator(String username, String rawPassword) {
        Role admin = roles.findByAllPermissionsTrue()
                .orElseThrow(() -> new IllegalStateException("Administrator-Rolle fehlt in der Datenbank"));
        return create(username, rawPassword, Set.of(admin.getId()));
    }

    @Transactional
    public void changeRoles(Long userId, Set<Long> roleIds) {
        User user = load(userId);
        List<Role> newRoles = resolveRoles(roleIds);
        boolean staysAdministrator = newRoles.stream().anyMatch(Role::isAllPermissions);
        guardLastAdministrator(user, user.isEnabled() && staysAdministrator);
        user.setRoles(newRoles);
    }

    @Transactional
    public void setEnabled(Long userId, boolean enabled) {
        User user = load(userId);
        guardLastAdministrator(user, enabled && user.isAdministrator());
        user.setEnabled(enabled);
    }

    /** Eigene E-Mail-Adresse für Benachrichtigungen; leer entfernt sie. */
    @Transactional
    public void changeEmail(Long userId, String email) {
        String clean = email == null || email.isBlank() ? null : email.trim();
        if (clean != null && (clean.length() > 200 || !clean.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) {
            throw new IllegalArgumentException("Die E-Mail-Adresse ist ungültig.");
        }
        load(userId).setEmail(clean);
    }

    @Transactional
    public void resetPassword(Long userId, String rawPassword) {
        requireValidPassword(rawPassword);
        load(userId).setPasswordHash(passwordEncoder.encode(rawPassword));
    }

    /**
     * Verhindert Rechteausweitung durch Benutzerverwalter ohne Administratorrechte: Sie dürfen nur
     * Rollen vergeben, deren Rechte sie selbst besitzen, und Administratoren nicht verändern.
     *
     * @param actorPermissions Rechte des handelnden Benutzers
     * @param targetUserId     betroffener Benutzer oder {@code null} beim Anlegen
     * @param roleIds          zu vergebende Rollen oder {@code null}, wenn keine vergeben werden
     */
    @Transactional(readOnly = true)
    public void requireMayManage(Collection<String> actorPermissions, Long targetUserId, Set<Long> roleIds) {
        boolean actorIsAdministrator = catalog.isAdministrator(actorPermissions);
        if (actorIsAdministrator) {
            return;
        }
        if (targetUserId != null) {
            User target = load(targetUserId);
            if (target.isAdministrator()) {
                throw new IllegalArgumentException("Administratoren können nur von Administratoren geändert werden.");
            }
            if (!actorPermissions.containsAll(catalog.effective(target))) {
                throw new IllegalArgumentException(
                        "„" + target.getUsername() + "“ hat Rechte, die du selbst nicht hast, und kann daher nicht geändert werden.");
            }
        }
        for (Role role : resolveRoles(roleIds)) {
            if (role.isAllPermissions() || !actorPermissions.containsAll(catalog.effective(role))) {
                throw new IllegalArgumentException(
                        "Die Rolle „" + role.getName() + "“ enthält Rechte, die du selbst nicht hast.");
            }
        }
    }

    private User load(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Benutzer nicht gefunden"));
    }

    private List<Role> resolveRoles(Set<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<Role> found = roles.findAllById(roleIds);
        if (found.size() != roleIds.size()) {
            throw new IllegalArgumentException("Unbekannte Rolle ausgewählt");
        }
        return found;
    }

    private void guardLastAdministrator(User user, boolean willBeActiveAdministrator) {
        boolean isActiveAdministrator = user.isEnabled() && user.isAdministrator();
        if (isActiveAdministrator && !willBeActiveAdministrator
                && users.countActiveAdministrators() <= 1) {
            throw new IllegalArgumentException(
                    "Der letzte aktive Administrator kann nicht deaktiviert oder herabgestuft werden.");
        }
    }

    private void requireValidPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Das Passwort muss mindestens " + MIN_PASSWORD_LENGTH + " Zeichen lang sein.");
        }
    }
}
