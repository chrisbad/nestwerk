package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.blocker.BusyTimes;
import club.badernet.nestwerk.blocker.TimeRange;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps chores and events apart where one of them forbids overlap: a chore with the flag must not lie over another
 * chore or an event, an event with the flag must not lie over a chore, and neither may be put over something that
 * carries the flag itself. Without any flag, planned things may overlap as before (the planner still looks for gaps
 * on its own). Differs from "once per day" of a chore, which limits bookings, not times.
 */
@Component
public class ChoreOverlaps {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BusyTimes busy;
    private final FamilyMemberRepository members;
    private final List<PlannedRangeSource> chores;

    public ChoreOverlaps(BusyTimes busy, FamilyMemberRepository members, List<PlannedRangeSource> chores) {
        this.busy = busy;
        this.members = members;
        this.chores = chores;
    }

    /**
     * Checks a chore of one member at one time against that member's events and other planned chores.
     * {@code ignoreTaskId} is the chore task itself when it is moved.
     */
    @Transactional(readOnly = true)
    public void requireFreeForChore(String title, boolean flagged, Long memberId, TimeRange range,
                                    Long ignoreTaskId) {
        if (memberId == null) {
            return;
        }
        List<PlannedRange> candidates = new ArrayList<>(
                busy.plannedEvents(Set.of(memberId), range.start(), range.end(), null));
        chores.forEach(source -> candidates.addAll(
                source.plannedRanges(Set.of(memberId), range.start(), range.end(), ignoreTaskId)));
        firstConflict(candidates, List.of(range), flagged)
                .ifPresent(other -> {
                    throw overlap("Die Hausarbeit „" + title + "“", other);
                });
    }

    /** Checks an event of the members at the times of its occurrences against their planned chores. */
    @Transactional(readOnly = true)
    public void requireFreeForEvent(String title, boolean flagged, Collection<Long> memberIds,
                                    List<TimeRange> ranges) {
        if (memberIds.isEmpty() || ranges.isEmpty()) {
            return;
        }
        Instant from = ranges.stream().map(TimeRange::start).min(Comparator.naturalOrder()).orElseThrow();
        Instant to = ranges.stream().map(TimeRange::end).max(Comparator.naturalOrder()).orElseThrow();
        List<PlannedRange> candidates = chores.stream()
                .flatMap(source -> source.plannedRanges(memberIds, from, to, null).stream()).toList();
        firstConflict(candidates, ranges, flagged)
                .ifPresent(other -> {
                    throw overlap("Der Termin „" + title + "“", other);
                });
    }

    private static Optional<PlannedRange> firstConflict(List<PlannedRange> candidates, List<TimeRange> ranges,
                                                        boolean subjectFlagged) {
        return candidates.stream()
                .filter(candidate -> subjectFlagged || candidate.noOverlap())
                .filter(candidate -> ranges.stream().anyMatch(range -> range.overlaps(candidate.range())))
                .min(Comparator.comparing(candidate -> candidate.range().start()));
    }

    private OverlapException overlap(String subject, PlannedRange other) {
        String who = members.findById(other.memberId()).map(FamilyMember::getName).orElse("jemand");
        String when = TIME.format(other.range().start().atZone(busy.zone())) + "–"
                + TIME.format(other.range().end().atZone(busy.zone()));
        return new OverlapException(subject + " überschneidet sich mit " + other.label() + " (" + who + ", "
                + when + "). Eines von beiden darf nicht überschnitten werden.");
    }
}
