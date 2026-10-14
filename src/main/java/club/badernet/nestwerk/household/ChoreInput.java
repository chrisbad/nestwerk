package club.badernet.nestwerk.household;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Eingabe für Anlegen/Ändern einer Hausarbeit; {@code memberIds} in Reihenfolge der Rotation. */
public record ChoreInput(String title, String area, String description, Integer minutes, ChoreEffort effort,
                         ChoreKind kind, String rrule, LocalDate startsOn, LocalTime dueTime, Integer intervalDays,
                         Distribution distribution, List<Long> memberIds) {
}
