package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.blocker.TimeRange;

/**
 * Time that one member has planned (an event or a chore). {@code label} names it in messages, {@code noOverlap}
 * tells that nothing may be planned over it.
 */
public record PlannedRange(Long memberId, TimeRange range, String label, boolean noOverlap) {
}
