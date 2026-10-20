package club.badernet.nestwerk.web;

/**
 * An entry of the all-day row: it starts in grid column {@code firstColumn} (0 = first day or person column), spans
 * {@code span} columns and lies in row {@code lane}. {@code memberId} is the person column it is shown in.
 */
public record AllDayItem(GridEntry entry, int firstColumn, int span, int lane, boolean continuesBefore,
                         boolean continuesAfter, Long memberId) {
}
