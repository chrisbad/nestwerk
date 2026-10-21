package club.badernet.nestwerk.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Open chores of a period for the calendar: those with a planned time and those without. */
public record CalendarChores(Map<LocalDate, List<CalendarChore>> timed,
                             Map<LocalDate, List<UntimedChore>> untimed) {
}
