package club.badernet.nestwerk.web;

import org.springframework.web.util.UriComponentsBuilder;

/**
 * Validates return targets ({@code back} parameters) so a redirect can only lead to a local page of this app.
 */
public final class SafeRedirect {

    /** Longest accepted target; keeps nested query strings from growing without bound. */
    static final int MAX_LENGTH = 512;

    private static final String LOGOUT_PATH = "/logout";

    private SafeRedirect() {
    }

    /** Returns {@code back} when it is a local path, otherwise {@code fallback}. */
    public static String target(String back, String fallback) {
        return isLocalPath(back) ? back : fallback;
    }

    /** True for paths like {@code /calendar/week?date=2026-10-21}; false for absolute URLs, {@code //host} and the like. */
    public static boolean isLocalPath(String path) {
        if (path == null || path.length() > MAX_LENGTH || !path.startsWith("/") || path.startsWith("//")) {
            return false;
        }
        if (path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        String withoutQuery = path.split("[?#]", 2)[0];
        return !withoutQuery.equals(LOGOUT_PATH) && !withoutQuery.startsWith(LOGOUT_PATH + "/");
    }

    /** Appends the origin to a local path so the next page still knows where the excursion started. */
    public static String withBack(String path, String back) {
        if (!isLocalPath(back)) {
            return path;
        }
        return UriComponentsBuilder.fromUriString(path).queryParam("back", "{back}").encode().build(back).toString();
    }
}
