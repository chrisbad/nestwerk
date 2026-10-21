package club.badernet.nestwerk.web;

import java.util.Set;

/**
 * What the user narrowed the register down to. Empty fields do not filter.
 *
 * @param text   words matched against title, description and keywords
 * @param areas  parts of the app to show
 * @param scope  whom the settings apply to, {@code null} for all
 * @param status state to show, {@code null} for all
 */
public record SettingFilter(String text, Set<SettingArea> areas, SettingScope scope, SettingStatus status) {

    public static SettingFilter none() {
        return new SettingFilter("", Set.of(), null, null);
    }

    public SettingFilter {
        text = text == null ? "" : text.trim();
        areas = areas == null ? Set.of() : Set.copyOf(areas);
    }
}
