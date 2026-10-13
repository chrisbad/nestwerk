package club.badernet.nestwerk.security;

import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public User require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Nicht angemeldet");
        }
        return users.findByUsername(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Benutzer unbekannt"));
    }
}
