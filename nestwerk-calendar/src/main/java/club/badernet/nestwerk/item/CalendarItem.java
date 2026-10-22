package club.badernet.nestwerk.item;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Everything the calendar shows is an item of one type with type-specific properties. Times are local time of the
 * app zone; the end is exclusive, an all-day item ends at midnight after its last day. {@code status} is the
 * type's own status name, {@code seriesId} points to the recurring series the item belongs to (if any).
 */
public sealed interface CalendarItem {

    ItemType type();

    Long id();

    String title();

    LocalDateTime start();

    LocalDateTime end();

    boolean allDay();

    /** The family members the item belongs to. */
    Set<Long> memberIds();

    /** Hex colour of the category or person; {@code null} means the default colour. */
    String color();

    String status();

    Long seriesId();

    Set<ItemAction> actions();

    /** One occurrence of an event; {@code occurrence} identifies it within a series. */
    record Event(Long id, String title, LocalDateTime start, LocalDateTime end, boolean allDay, Set<Long> memberIds,
                 String color, String status, Long seriesId, Set<ItemAction> actions, String location,
                 boolean privateEvent, Long categoryId, Instant occurrence) implements CalendarItem {

        @Override
        public ItemType type() {
            return ItemType.EVENT;
        }
    }

    /** A chore task of one date; {@code choreId} is the chore it belongs to. */
    record Chore(Long id, String title, LocalDateTime start, LocalDateTime end, boolean allDay, Set<Long> memberIds,
                 String color, String status, Long seriesId, Set<ItemAction> actions, int minutes, boolean planned)
            implements CalendarItem {

        @Override
        public ItemType type() {
            return ItemType.CHORE;
        }
    }

    /** A to-do shown on the day it is due. */
    record Todo(Long id, String title, LocalDateTime start, LocalDateTime end, boolean allDay, Set<Long> memberIds,
                String color, String status, Long seriesId, Set<ItemAction> actions, String priority)
            implements CalendarItem {

        @Override
        public ItemType type() {
            return ItemType.TODO;
        }
    }

    /** A school date, e.g. an exam. */
    record School(Long id, String title, LocalDateTime start, LocalDateTime end, boolean allDay, Set<Long> memberIds,
                  String color, String status, Long seriesId, Set<ItemAction> actions, String kind)
            implements CalendarItem {

        @Override
        public ItemType type() {
            return ItemType.SCHOOL;
        }
    }
}
