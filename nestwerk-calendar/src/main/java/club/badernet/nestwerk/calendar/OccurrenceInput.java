package club.badernet.nestwerk.calendar;

import java.time.LocalDateTime;

public record OccurrenceInput(String title, String description, String location,
                              LocalDateTime start, LocalDateTime end) {
}
