package club.badernet.nestwerk.web;

import java.util.List;

/** Answer of a calendar action: message for a toast, warnings of blockers and the affected event, if any. */
public record ActionResult(String message, List<String> warnings, Long eventId) {

    static ActionResult of(String message) {
        return new ActionResult(message, List.of(), null);
    }
}
