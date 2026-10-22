package club.badernet.nestwerk.item;

import club.badernet.nestwerk.user.User;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Contributes the items of one {@link ItemType} to the calendar. Implemented by the module that owns the type, so
 * the calendar and its consumers (web, MCP) never need to know the domain classes. An implementation returns only
 * what the viewer may see and sets the actions the viewer may use.
 */
public interface CalendarItemSource {

    ItemType type();

    /** Items touching the days {@code from} to {@code to} (inclusive). */
    List<CalendarItem> items(LocalDate from, LocalDate to, User viewer);

    /** The item with this id, empty if it does not exist or the viewer may not see it. */
    Optional<CalendarItem> find(Long id, User viewer);
}
