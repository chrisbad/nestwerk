package club.badernet.nestwerk.blocker;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Rechnet Blocker in konkrete Zeitfenster um (Ortszeit der Familie, Feiertage des Bundeslands). */
public final class BlockerSchedule {

    private BlockerSchedule() {
    }

    /** Alle Fenster, die sich mit {@code [from, to)} überschneiden, nach Beginn sortiert. */
    public static List<BlockerSlot> slots(Collection<Blocker> blockers, Instant from, Instant to, ZoneId zone,
                                          String state) {
        List<BlockerSlot> result = new ArrayList<>();
        // ab dem Vortag: ein Fenster über Mitternacht kann hineinragen
        LocalDate first = from.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = to.atZone(zone).toLocalDate();
        for (Blocker blocker : blockers) {
            for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                if (!blocker.startsOn(day) || blocker.isHolidaysOff() && GermanHolidays.isHoliday(day, state)) {
                    continue;
                }
                ZonedDateTime start = day.atTime(blocker.getStartTime()).atZone(zone);
                ZonedDateTime end = (blocker.isOvernight() ? day.plusDays(1) : day).atTime(blocker.getEndTime())
                        .atZone(zone);
                BlockerSlot slot = new BlockerSlot(blocker, start.toInstant(), end.toInstant(), day);
                if (slot.overlaps(from, to)) {
                    result.add(slot);
                }
            }
        }
        result.sort(Comparator.comparing(BlockerSlot::start));
        return result;
    }
}
