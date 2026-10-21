package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.kitchen.Meal;
import club.badernet.nestwerk.todo.Todo;

/** One line of the personal day agenda: an event, a chore, a meal or a due to-do of the viewer. */
public sealed interface MyDayEntry {

    int ALL_DAY_ORDER = 0;
    int TODO_ORDER = 1;
    int MEAL_ORDER_BASE = 10;
    int TIMED_ORDER_BASE = 100;
    int UNTIMED_ORDER = 100_000;
    int MINUTES_PER_HOUR = 60;

    /** Position within the day: all-day entries first, then to-dos, then timed entries, then chores without a time. */
    int order();

    record EventEntry(Occurrence occurrence) implements MyDayEntry {
        @Override
        public int order() {
            return occurrence.allDay() ? ALL_DAY_ORDER
                    : TIMED_ORDER_BASE + occurrence.start().getHour() * MINUTES_PER_HOUR
                            + occurrence.start().getMinute();
        }
    }

    record TodoEntry(Todo todo) implements MyDayEntry {
        @Override
        public int order() {
            return TODO_ORDER;
        }
    }

    /** A planned meal the viewer eats; ordered by its slot (breakfast before dinner), between to-dos and timed entries. */
    record MealEntry(Meal meal) implements MyDayEntry {
        @Override
        public int order() {
            return MEAL_ORDER_BASE + meal.getSlot().getSortOrder();
        }
    }

    record ChoreEntry(ChoreTask task) implements MyDayEntry {
        @Override
        public int order() {
            return task.getPlannedAt() == null ? UNTIMED_ORDER
                    : TIMED_ORDER_BASE + task.getPlannedAt().getHour() * MINUTES_PER_HOUR
                            + task.getPlannedAt().getMinute();
        }
    }
}
