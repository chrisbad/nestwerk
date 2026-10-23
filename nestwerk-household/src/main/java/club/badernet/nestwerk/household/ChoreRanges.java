package club.badernet.nestwerk.household;

import club.badernet.nestwerk.blocker.TimeRange;
import club.badernet.nestwerk.calendar.PlannedRange;
import club.badernet.nestwerk.calendar.PlannedRangeSource;
import club.badernet.nestwerk.settings.CalendarSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

/** Open chore tasks with a planned time and person as time the events must keep clear when they forbid overlap. */
@Component
public class ChoreRanges implements PlannedRangeSource {

    private final ChoreTaskRepository tasks;
    private final ZoneId zone;

    public ChoreRanges(ChoreTaskRepository tasks, CalendarSettings settings) {
        this.tasks = tasks;
        this.zone = settings.zone();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlannedRange> plannedRanges(Collection<Long> memberIds, Instant from, Instant to, Long ignoreId) {
        LocalDate first = from.atZone(zone).toLocalDate();
        LocalDate last = to.atZone(zone).toLocalDate();
        return tasks.findAllByStatusAndDueOnBetween(ChoreTaskStatus.OPEN, first, last).stream()
                .filter(task -> task.getPlannedAt() != null && task.getAssigneeMemberId() != null)
                .filter(task -> memberIds.contains(task.getAssigneeMemberId()))
                .filter(task -> !task.getId().equals(ignoreId))
                .map(this::range)
                .filter(planned -> planned.range().overlaps(from, to))
                .toList();
    }

    private PlannedRange range(ChoreTask task) {
        Instant start = task.getDueOn().atTime(task.getPlannedAt()).atZone(zone).toInstant();
        TimeRange range = new TimeRange(start, start.plusSeconds(task.minutes() * 60L));
        return new PlannedRange(task.getAssigneeMemberId(), range, "der Hausarbeit „" + task.getChore().getTitle() + "“",
                task.getChore().isNoOverlap());
    }
}
