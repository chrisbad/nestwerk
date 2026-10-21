package club.badernet.nestwerk.web;

import club.badernet.nestwerk.user.User;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Function;

/**
 * Describes one setting so that the register, the search and the MCP tool share a single source.
 *
 * @param id          stable key such as {@code calendar.categories}
 * @param area        part of the app
 * @param scope       whom it applies to
 * @param title       German name shown to the user
 * @param description one sentence about what can be set
 * @param keywords    further words the setting is found by
 * @param path        page that edits the setting
 * @param visible     who may see (and open) the setting
 * @param value       current state, evaluated only for visible entries
 */
public record SettingDescriptor(String id, SettingArea area, SettingScope scope, String title, String description,
                                Set<String> keywords, String path, Predicate<User> visible,
                                Function<User, SettingValue> value) {

    boolean matches(String text) {
        String needle = text.toLowerCase(Locale.GERMAN);
        return title.toLowerCase(Locale.GERMAN).contains(needle)
                || description.toLowerCase(Locale.GERMAN).contains(needle)
                || keywords.stream().anyMatch(k -> k.toLowerCase(Locale.GERMAN).contains(needle));
    }
}
