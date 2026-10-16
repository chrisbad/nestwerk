package club.badernet.nestwerk.blocker;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/** Eingabe für Anlegen/Ändern eines Blockers; {@code validUntil} leer = unbefristet. */
public record BlockerInput(String title, BlockerKind kind, LocalTime startTime, LocalTime endTime,
                           Set<DayOfWeek> weekdays, LocalDate validFrom, LocalDate validUntil,
                           BlockerStrength strength, BlockerVisibility visibility, boolean quiet,
                           boolean holidaysOff, Set<Long> memberIds, Set<Long> plannerIds, Set<LocalDate> skips) {
}
