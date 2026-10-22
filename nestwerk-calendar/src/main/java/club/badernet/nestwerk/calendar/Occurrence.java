package club.badernet.nestwerk.calendar;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Set;

/** A concrete occurrence of an event (one of many for a series). {@code categoryId} may be {@code null}.
 * {@code noOverlap} is the flag of the event or series the occurrence belongs to. */
public record Occurrence(Long eventId, String uid, Instant recurrenceId, String title,
                         String description, String location, ZonedDateTime start, ZonedDateTime end,
                         boolean allDay, boolean privateEvent, boolean recurring, Long createdBy,
                         Set<Long> memberIds, Long categoryId, boolean noOverlap) {

    public Occurrence(Long eventId, String uid, Instant recurrenceId, String title, String description,
                      String location, ZonedDateTime start, ZonedDateTime end, boolean allDay, boolean privateEvent,
                      boolean recurring, Long createdBy, Set<Long> memberIds, Long categoryId) {
        this(eventId, uid, recurrenceId, title, description, location, start, end, allDay, privateEvent, recurring,
                createdBy, memberIds, categoryId, false);
    }
}
