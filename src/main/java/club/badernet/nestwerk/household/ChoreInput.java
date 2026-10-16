package club.badernet.nestwerk.household;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Eingabe für Anlegen/Ändern einer Hausarbeit; die drei Minutenwerte sind die Zeitaufwände für leicht, mittel und
 * schwer, {@code memberIds} in Reihenfolge der Rotation. {@code windowStart}/{@code windowEnd} grenzen ein, wann
 * der Planer die Aufgabe einplant (leer = 07:00 bis Fälligkeit bzw. 21:00).
 */
public record ChoreInput(String title, String area, String description, Integer minutesEasy, Integer minutesMedium,
                         Integer minutesHard, ChoreKind kind, String rrule, LocalDate startsOn, LocalTime dueTime,
                         Integer intervalDays, Distribution distribution, List<Long> memberIds,
                         LocalTime windowStart, LocalTime windowEnd) {

    public ChoreInput(String title, String area, String description, Integer minutesEasy, Integer minutesMedium,
                      Integer minutesHard, ChoreKind kind, String rrule, LocalDate startsOn, LocalTime dueTime,
                      Integer intervalDays, Distribution distribution, List<Long> memberIds) {
        this(title, area, description, minutesEasy, minutesMedium, minutesHard, kind, rrule, startsOn, dueTime,
                intervalDays, distribution, memberIds, null, null);
    }
}
