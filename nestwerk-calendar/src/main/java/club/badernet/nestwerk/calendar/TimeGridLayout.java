package club.badernet.nestwerk.calendar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Places timed entries on the days of a time grid. Entries spanning midnight appear on every day they touch.
 * Overlapping entries share the width of the day: each cluster of transitively overlapping entries gets as many
 * columns as needed, and every entry takes the first free column.
 */
public final class TimeGridLayout {

    public static final int MINUTES_PER_DAY = 24 * 60;

    /**
     * An entry on one day. Minutes count from midnight in local time, {@code endMinute} is exclusive and at most
     * {@link #MINUTES_PER_DAY}. {@code column} is zero-based, {@code columns} the width of its cluster.
     */
    public record Placed<T>(T entry, LocalDate day, int startMinute, int endMinute, int column, int columns,
                            boolean continuesBefore, boolean continuesAfter) {

        public int durationMinutes() {
            return endMinute - startMinute;
        }
    }

    /**
     * A whole-day entry in the all-day row: it covers {@code span} visible days from {@code firstColumn}
     * (zero-based) and lies in row {@code lane}; the flags tell whether it reaches beyond the visible days.
     */
    public record Lane<T>(T entry, int firstColumn, int span, int lane, boolean continuesBefore,
                          boolean continuesAfter) {
    }

    private record Piece<T>(T entry, int start, int end, boolean before, boolean after) {
    }

    private TimeGridLayout() {
    }

    /**
     * Arranges whole-day entries (first and last day inclusive) in rows so that entries on the same day never share
     * a row. Entries outside the visible days are left out.
     */
    public static <T> List<Lane<T>> lanes(List<T> entries, List<LocalDate> days, Function<T, LocalDate> first,
                                          Function<T, LocalDate> last) {
        if (days.isEmpty()) {
            return List.of();
        }
        LocalDate visibleFirst = days.getFirst();
        LocalDate visibleLast = days.getLast();
        List<T> sorted = entries.stream()
                .filter(e -> !last.apply(e).isBefore(visibleFirst) && !first.apply(e).isAfter(visibleLast))
                .sorted(Comparator.comparing(first).thenComparing(e -> first.apply(e).toEpochDay()
                        - last.apply(e).toEpochDay()))
                .toList();
        List<LocalDate> laneEnds = new ArrayList<>();
        List<Lane<T>> result = new ArrayList<>();
        for (T entry : sorted) {
            LocalDate from = first.apply(entry).isBefore(visibleFirst) ? visibleFirst : first.apply(entry);
            LocalDate to = last.apply(entry).isAfter(visibleLast) ? visibleLast : last.apply(entry);
            int lane = 0;
            while (lane < laneEnds.size() && !laneEnds.get(lane).isBefore(from)) {
                lane++;
            }
            if (lane == laneEnds.size()) {
                laneEnds.add(to);
            } else {
                laneEnds.set(lane, to);
            }
            int column = days.indexOf(from);
            result.add(new Lane<>(entry, column, days.indexOf(to) - column + 1, lane,
                    first.apply(entry).isBefore(visibleFirst), last.apply(entry).isAfter(visibleLast)));
        }
        return result;
    }

    /** Places the entries on the given days; days without entries map to an empty list. */
    public static <T> Map<LocalDate, List<Placed<T>>> layout(List<T> entries, List<LocalDate> days,
                                                              Function<T, ZonedDateTime> start,
                                                              Function<T, ZonedDateTime> end) {
        Map<LocalDate, List<Placed<T>>> result = new LinkedHashMap<>();
        for (LocalDate day : days) {
            List<Piece<T>> pieces = new ArrayList<>();
            for (T entry : entries) {
                piece(entry, day, start.apply(entry), end.apply(entry)).ifPresent(pieces::add);
            }
            result.put(day, arrange(pieces, day));
        }
        return result;
    }

    private static <T> Optional<Piece<T>> piece(T entry, LocalDate day, ZonedDateTime start, ZonedDateTime end) {
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
        LocalDateTime from = start.toLocalDateTime();
        LocalDateTime to = end.toLocalDateTime();
        if (!from.isBefore(dayEnd) || !to.isAfter(dayStart)) {
            return Optional.empty();
        }
        boolean before = from.isBefore(dayStart);
        boolean after = to.isAfter(dayEnd);
        int startMinute = before ? 0 : minuteOfDay(from);
        int endMinute = to.isBefore(dayEnd) ? minuteOfDay(to) : MINUTES_PER_DAY;
        return Optional.of(new Piece<>(entry, startMinute, Math.max(endMinute, startMinute + 1), before, after));
    }

    private static int minuteOfDay(LocalDateTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static <T> List<Placed<T>> arrange(List<Piece<T>> pieces, LocalDate day) {
        pieces.sort(Comparator.<Piece<T>>comparingInt(Piece::start).thenComparing(p -> -(p.end() - p.start())));
        List<Placed<T>> result = new ArrayList<>();
        List<Piece<T>> cluster = new ArrayList<>();
        int clusterEnd = -1;
        for (Piece<T> piece : pieces) {
            if (piece.start() >= clusterEnd && !cluster.isEmpty()) {
                result.addAll(columns(cluster, day));
                cluster.clear();
            }
            cluster.add(piece);
            clusterEnd = Math.max(clusterEnd, piece.end());
        }
        result.addAll(columns(cluster, day));
        return result;
    }

    private static <T> List<Placed<T>> columns(List<Piece<T>> cluster, LocalDate day) {
        List<Integer> columnEnds = new ArrayList<>();
        List<Integer> assigned = new ArrayList<>();
        for (Piece<T> piece : cluster) {
            int column = 0;
            while (column < columnEnds.size() && columnEnds.get(column) > piece.start()) {
                column++;
            }
            if (column == columnEnds.size()) {
                columnEnds.add(piece.end());
            } else {
                columnEnds.set(column, piece.end());
            }
            assigned.add(column);
        }
        List<Placed<T>> result = new ArrayList<>();
        for (int i = 0; i < cluster.size(); i++) {
            Piece<T> piece = cluster.get(i);
            result.add(new Placed<>(piece.entry(), day, piece.start(), piece.end(), assigned.get(i),
                    columnEnds.size(), piece.before(), piece.after()));
        }
        return result;
    }
}
