package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.todo.Todo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

/** Something shown in the time grid: an event occurrence, a planned chore or a due to-do. */
public sealed interface GridEntry {

    /** Template switch: {@code event}, {@code chore} or {@code todo}. */
    String kind();

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
    }

    record DueTodo(Todo todo) implements GridEntry {

        @Override
        public String kind() {
            return "todo";
        }
    }
}
