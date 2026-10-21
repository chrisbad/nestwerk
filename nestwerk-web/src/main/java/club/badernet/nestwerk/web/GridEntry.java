package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

/** Something shown in the time grid: an event occurrence, a planned chore or a due to-do. */
public sealed interface GridEntry {

    /** Template switch: {@code event}, {@code chore} or {@code todo}. */
    String kind();

    /** The family members the entry belongs to; decides its columns when the grid is split by person. */
    Set<Long> memberIds();

    /**
     * An occurrence with its times in local time of the app zone. {@code color} comes from the category, else from
     * the first member ({@code null} = default colour).
     */
    record Event(Occurrence occurrence, LocalDateTime startsAt, LocalDateTime endsAt, String color,
                 String categoryName, boolean editable, boolean deletable) implements GridEntry {

        @Override
        public String kind() {
            return "event";
        }

        @Override
        public Set<Long> memberIds() {
            return occurrence.memberIds();
        }

        /** Last day the occurrence touches (the end is exclusive). */
        public LocalDate lastDay() {
            return endsAt.minusNanos(1).toLocalDate();
        }

        /** Member ids as comma separated list for the drag logic. */
        public String memberList() {
            return occurrence.memberIds().stream().map(String::valueOf).collect(Collectors.joining(","));
        }
    }

    record Chore(CalendarChore chore) implements GridEntry {

        @Override
        public String kind() {
            return "chore";
        }

        @Override
        public Set<Long> memberIds() {
            return chore.memberId() == null ? Set.of() : Set.of(chore.memberId());
        }
    }

    /** An open chore without planned time or person: shown in the all-day row, cannot be dragged. */
    record DayChore(UntimedChore chore) implements GridEntry {

        @Override
        public String kind() {
            return "chore";
        }

        @Override
        public Set<Long> memberIds() {
            return chore.memberId() == null ? Set.of() : Set.of(chore.memberId());
        }
    }

    /** A due to-do; {@code editable}: the viewer may move it to another day. */
    record DueTodo(Todo todo, boolean editable) implements GridEntry {

        @Override
        public String kind() {
            return "todo";
        }

        @Override
        public Set<Long> memberIds() {
            return todo.assigneeRefs().stream().filter(r -> r.type() == AssigneeRef.Type.MEMBER)
                    .map(AssigneeRef::id).collect(Collectors.toUnmodifiableSet());
        }
    }
}
