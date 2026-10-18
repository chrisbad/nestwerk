package club.badernet.nestwerk.calendar;

import java.time.LocalDate;
import java.util.List;

public record DayCell(LocalDate date, boolean inMonth, boolean today, List<Occurrence> occurrences) {
}
