package club.badernet.nestwerk.calendar;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/** Eingabe für Anlegen/Ändern. Bei ganztägigen Terminen ist {@code endDate} inklusiv. */
public record EventInput(String title, String description, String location,
                         LocalDate startDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
                         boolean allDay, String rrule, boolean privateEvent, Set<Long> memberIds,
                         boolean absence) {

    public EventInput(String title, String description, String location,
                      LocalDate startDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
                      boolean allDay, String rrule, boolean privateEvent, Set<Long> memberIds) {
        this(title, description, location, startDate, startTime, endDate, endTime, allDay, rrule, privateEvent,
                memberIds, false);
    }
}
