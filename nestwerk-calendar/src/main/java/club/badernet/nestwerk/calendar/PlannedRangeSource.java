package club.badernet.nestwerk.calendar;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Time planned by another module (e.g. the household plan) that events must not be placed over when one of them
 * forbids overlap. Implemented by the module that owns the planned things.
 */
public interface PlannedRangeSource {

    /** Planned ranges of the members that touch {@code [from, to)}; {@code ignoreId} is the thing being changed. */
    List<PlannedRange> plannedRanges(Collection<Long> memberIds, Instant from, Instant to, Long ignoreId);
}
