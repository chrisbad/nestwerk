package club.badernet.nestwerk.blocker;

import java.time.Duration;
import java.time.Instant;

/** Ein Zeitraum {@code [start, end)}; ganztägige Termine reichen von Mitternacht bis Mitternacht. */
public record TimeRange(Instant start, Instant end) {

    public boolean overlaps(Instant from, Instant to) {
        return start.isBefore(to) && end.isAfter(from);
    }

    public boolean overlaps(TimeRange other) {
        return overlaps(other.start, other.end);
    }

    /** Minuten, in denen sich beide Zeiträume überschneiden. */
    public long overlapMinutes(TimeRange other) {
        Instant from = start.isAfter(other.start) ? start : other.start;
        Instant to = end.isBefore(other.end) ? end : other.end;
        return to.isAfter(from) ? Duration.between(from, to).toMinutes() : 0;
    }

    public Duration duration() {
        return Duration.between(start, end);
    }
}
