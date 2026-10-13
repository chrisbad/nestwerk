package club.badernet.nestwerk.calendar;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Set;

/** Ein konkretes Vorkommen eines Termins (bei Serien eines von vielen). */
public record Occurrence(Long eventId, String uid, Instant recurrenceId, String title,
                         String description, String location, ZonedDateTime start, ZonedDateTime end,
                         boolean allDay, boolean privateEvent, boolean recurring, Long createdBy,
                         Set<Long> memberIds) {
}
