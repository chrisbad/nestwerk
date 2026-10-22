package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.user.User;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Further bands for the calendar views next to the blockers, contributed by other modules (e.g. school holidays and
 * cancelled lessons). Implementations decide themselves what the viewer may see.
 */
public interface CalendarBandSource {

    /** Bands per day from {@code from} to {@code to} (inclusive). */
    Map<LocalDate, List<BlockerBands.Band>> bands(LocalDate from, LocalDate to, User viewer);

    /** The timed ranges among them with exact start and end for the grid; all-day bands have none. */
    default List<BusyBlock> blocks(LocalDate from, LocalDate to, User viewer) {
        return List.of();
    }
}
