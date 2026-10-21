package club.badernet.nestwerk.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.function.Predicate;

/** Reusable rules for "who sees this entry" shared by the navigation and the settings register. */
final class Visibility {

    private Visibility() {
    }

    static Predicate<Authentication> everyone() {
        return authentication -> true;
    }

    static Predicate<Authentication> hasAuthority(String authority) {
        return authentication -> authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }
}
