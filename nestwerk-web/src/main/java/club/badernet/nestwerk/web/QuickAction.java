package club.badernet.nestwerk.web;

import org.springframework.security.core.Authentication;

import java.util.function.Predicate;

/**
 * Something the user can create from anywhere (the plus menu and the command palette).
 *
 * @param id      stable key
 * @param label   German text, e.g. "Termin"
 * @param path    page that starts the creation
 * @param visible who may use it
 */
public record QuickAction(String id, String label, String path, Predicate<Authentication> visible) {
}
