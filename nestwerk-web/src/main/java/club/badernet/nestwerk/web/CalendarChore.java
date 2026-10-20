package club.badernet.nestwerk.web;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * A planned chore in the calendar of the responsible person, {@code minutes} long from {@code start}.
 * {@code movable}: the viewer may plan it at another time.
 */
public record CalendarChore(Long id, String title, LocalDate day, LocalTime start, int minutes, String color,
                            boolean movable) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    public LocalDateTime startsAt() {
        return day.atTime(start);
    }

    public LocalDateTime endsAt() {
        return startsAt().plusMinutes(minutes);
    }

    /** E.g. "18:30–18:55". */
    public String getTime() {
        return TIME.format(start) + "–" + TIME.format(endsAt());
    }
}
