package club.badernet.nestwerk.household;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Eingabe für Anlegen/Ändern einer Hausarbeit; die drei Minutenwerte sind die Zeitaufwände für leicht, mittel und
 * schwer, {@code memberIds} in Reihenfolge der Rotation.
 */
public record ChoreInput(String title, String area, String description, Integer minutesEasy, Integer minutesMedium,
                         Integer minutesHard, ChoreKind kind, String rrule, LocalDate startsOn, LocalTime dueTime,
                         Integer intervalDays, Distribution distribution, List<Long> memberIds) {
}
