package club.badernet.nestwerk.calendar;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * Input for creating or changing an event. For all-day events {@code endDate} is inclusive. {@code categoryId} is
 * optional ({@code null} = no category).
 */
public record EventInput(String title, String description, String location,
                         LocalDate startDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
                         boolean allDay, String rrule, boolean privateEvent, Set<Long> memberIds,
                         boolean absence, Long categoryId) {

    public EventInput(String title, String description, String location,
                      LocalDate startDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
                      boolean allDay, String rrule, boolean privateEvent, Set<Long> memberIds, boolean absence) {
        this(title, description, location, startDate, startTime, endDate, endTime, allDay, rrule, privateEvent,
                memberIds, absence, null);
    }

    public EventInput(String title, String description, String location,
                      LocalDate startDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
                      boolean allDay, String rrule, boolean privateEvent, Set<Long> memberIds) {
        this(title, description, location, startDate, startTime, endDate, endTime, allDay, rrule, privateEvent,
                memberIds, false);
    }

    /** The requested times as a change of time. */
    public TimeChange timeChange() {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("Start- und Enddatum sind erforderlich.");
        }
        if (allDay) {
            return new TimeChange.AllDay(startDate, endDate);
        }
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("Start- und Endzeit sind erforderlich.");
        }
        return new TimeChange.Timed(startDate.atTime(startTime), endDate.atTime(endTime));
    }
}
