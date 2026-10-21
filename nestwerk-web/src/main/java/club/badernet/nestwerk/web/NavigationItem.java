package club.badernet.nestwerk.web;

import org.springframework.security.core.Authentication;

import java.util.function.Predicate;

/**
 * One entry of the navigation. The registry is the only place that defines navigation targets; side bar, tab bar,
 * "more" sheet and account menu are all rendered from it.
 *
 * @param id      stable key, also used to mark the current page ({@code active})
 * @param label   German text shown to the user
 * @param path    target path
 * @param group   where the entry appears
 * @param icon    key of the icon in {@code fragments/nav-icons}, {@code null} for entries without an icon
 * @param tab     whether the entry is also part of the tab bar on phones
 * @param visible who gets the entry
 */
public record NavigationItem(String id, String label, String path, NavigationGroup group, String icon, boolean tab,
                             Predicate<Authentication> visible) {

    public boolean isVisibleTo(Authentication authentication) {
        return visible.test(authentication);
    }
}
