package club.badernet.nestwerk.household;

import club.badernet.nestwerk.blocker.BlockerSlot;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BusyTimes;
import club.badernet.nestwerk.blocker.TimeRange;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Freie Lücken einer Person für Hausarbeiten: frei ist, was nicht von Terminen (auch Abwesenheiten), Blockern mit
 * Warnung/Sperre oder anderen eingeplanten Hausarbeiten belegt ist.
 */
@Component
public class Availability {

    static final LocalTime DAY_START = LocalTime.of(7, 0);
    static final LocalTime DAY_END = LocalTime.of(21, 0);
    private static final int STEP_MINUTES = 15;

    private final BusyTimes busy;
    private final ChoreTaskRepository tasks;

    public Availability(BusyTimes busy, ChoreTaskRepository tasks) {
        this.busy = busy;
        this.tasks = tasks;
    }

    /** Dauer, mit der eine Hausarbeit eingeplant wird: der mittlere Aufwand. */
    static int minutes(Chore chore) {
        return Math.max(5, chore.getMinutesMedium());
    }

    /**
     * Zeitfenster der Hausarbeit: frühestens (sonst 07:00) bis spätestens (sonst Fälligkeit, sonst 21:00). Ist die
     * Fälligkeit so früh, dass ab 07:00 nichts mehr passt, endet das Fenster mit ihr und beginnt eine Dauer davor.
     */
    static LocalTime[] window(Chore chore) {
        int minutes = minutes(chore);
        LocalTime end = chore.getWindowEnd() != null ? chore.getWindowEnd()
                : chore.getDueTime() != null ? chore.getDueTime() : DAY_END;
        LocalTime start = chore.getWindowStart() != null ? chore.getWindowStart() : DAY_START;
        if (chore.getWindowStart() == null && start.plusMinutes(minutes).isAfter(end)) {
            start = end.toSecondOfDay() >= minutes * 60 ? end.minusMinutes(minutes) : LocalTime.MIDNIGHT;
        }
        return new LocalTime[]{start, end};
    }

    /** Früheste freie Uhrzeit im Fenster der Hausarbeit; {@code ignoreTaskId} ist die Aufgabe selbst. */
    public Optional<LocalTime> firstGap(Long memberId, LocalDate day, Chore chore, Long ignoreTaskId) {
        LocalTime[] window = window(chore);
        int minutes = minutes(chore);
        ZoneId zone = busy.zone();
        Instant dayStart = day.atStartOfDay(zone).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
        List<TimeRange> taken = new ArrayList<>(busy.events(Set.of(memberId), dayStart, dayEnd, null)
                .getOrDefault(memberId, List.of()));
        for (BlockerSlot slot : busy.blockerSlots(memberId, dayStart, dayEnd)) {
            if (slot.blocker().getStrength() != BlockerStrength.SHOW) {
                taken.add(new TimeRange(slot.start(), slot.end()));
            }
        }
        for (ChoreTask other : tasks.findAllByAssigneeMemberIdAndDueOn(memberId, day)) {
            if (other.getPlannedAt() != null && other.isOpen() && !other.getId().equals(ignoreTaskId)) {
                Instant start = day.atTime(other.getPlannedAt()).atZone(zone).toInstant();
                taken.add(new TimeRange(start, start.plusSeconds(minutes(other.getChore()) * 60L)));
            }
        }
        for (LocalTime t = window[0]; !t.plusMinutes(minutes).isAfter(window[1]) && !t.plusMinutes(minutes).isBefore(t);
             t = t.plusMinutes(STEP_MINUTES)) {
            Instant start = day.atTime(t).atZone(zone).toInstant();
            TimeRange candidate = new TimeRange(start, start.plusSeconds(minutes * 60L));
            if (taken.stream().noneMatch(candidate::overlaps)) {
                return Optional.of(t);
            }
            if (t.plusMinutes(STEP_MINUTES).isBefore(t)) {
                break;
            }
        }
        return Optional.empty();
    }
}
