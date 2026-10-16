package club.badernet.nestwerk.blocker;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Wochenstand der Ruhe-Ziele einer Person (Montag bis Sonntag) und ob ein neuer Termin ein Ziel kippen würde. */
@Component
public class RestGoals {

    public record GoalStatus(RestGoal goal, int reached, int target, String text) {

        public boolean met() {
            return reached >= target;
        }

        public int percent() {
            return target <= 0 ? 100 : Math.min(100, reached * 100 / target);
        }
    }

    private final RestGoalRepository goals;
    private final BusyTimes busy;

    public RestGoals(RestGoalRepository goals, BusyTimes busy) {
        this.goals = goals;
        this.busy = busy;
    }

    /** Stand aller Ziele der Person in der Woche, in der {@code day} liegt. */
    @Transactional(readOnly = true)
    public List<GoalStatus> status(Long memberId, LocalDate day) {
        List<GoalStatus> result = new ArrayList<>();
        for (RestGoal goal : goals.findAllByMemberId(memberId)) {
            result.add(status(goal, day, List.of(), null));
        }
        return result;
    }

    /** Hinweis, falls die zusätzlichen Zeiten ein bisher erreichtes Ziel unter den Zielwert drücken. */
    @Transactional(readOnly = true)
    public List<String> wouldBreak(Long memberId, List<TimeRange> added, Long ignoreEventId) {
        List<String> warnings = new ArrayList<>();
        List<RestGoal> memberGoals = goals.findAllByMemberId(memberId);
        if (memberGoals.isEmpty() || added.isEmpty()) {
            return warnings;
        }
        Set<LocalDate> weeks = new java.util.TreeSet<>();
        for (TimeRange range : added) {
            weeks.add(monday(range.start().atZone(busy.zone()).toLocalDate()));
        }
        for (RestGoal goal : memberGoals) {
            for (LocalDate week : weeks) {
                GoalStatus before = status(goal, week, List.of(), ignoreEventId);
                GoalStatus after = status(goal, week, added, ignoreEventId);
                if (before.met() && !after.met()) {
                    warnings.add("Damit bleiben diese Woche nur " + after.text() + " „" + goal.getLabel() + "“.");
                }
            }
        }
        return warnings;
    }

    private GoalStatus status(RestGoal goal, LocalDate day, List<TimeRange> added, Long ignoreEventId) {
        LocalDate monday = monday(day);
        Instant from = monday.atStartOfDay(busy.zone()).toInstant();
        Instant to = monday.plusDays(7).atStartOfDay(busy.zone()).toInstant();
        List<TimeRange> taken = new ArrayList<>(busy.events(Set.of(goal.getMemberId()), from, to, ignoreEventId)
                .getOrDefault(goal.getMemberId(), List.of()));
        taken.addAll(added);
        if (goal.getKind() == RestGoal.Kind.WINDOWS) {
            int free = 0;
            for (int i = 0; i < 7; i++) {
                LocalDate d = monday.plusDays(i);
                TimeRange window = new TimeRange(d.atTime(goal.getWindowStart()).atZone(busy.zone()).toInstant(),
                        d.atTime(goal.getWindowEnd()).atZone(busy.zone()).toInstant());
                if (taken.stream().noneMatch(window::overlaps)) {
                    free++;
                }
            }
            return new GoalStatus(goal, free, goal.getTarget(), free + " von " + goal.getTarget());
        }
        long minutes = 0;
        for (BlockerSlot slot : busy.blockerSlots(goal.getMemberId(), from, to)) {
            if (slot.blocker().getKind() != BlockerKind.REST) {
                continue;
            }
            TimeRange rest = new TimeRange(slot.start().isBefore(from) ? from : slot.start(),
                    slot.end().isAfter(to) ? to : slot.end());
            long lost = taken.stream().mapToLong(rest::overlapMinutes).sum();
            minutes += Math.max(0, rest.duration().toMinutes() - lost);
        }
        int reached = (int) minutes;
        return new GoalStatus(goal, reached, goal.getTarget(),
                hours(reached) + " von " + hours(goal.getTarget()) + " Stunden");
    }

    static LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static String hours(int minutes) {
        return minutes % 60 == 0 ? String.valueOf(minutes / 60)
                : String.format(java.util.Locale.GERMAN, "%.1f", minutes / 60.0);
    }
}
