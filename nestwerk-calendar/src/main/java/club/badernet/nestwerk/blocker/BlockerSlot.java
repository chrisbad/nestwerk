package club.badernet.nestwerk.blocker;

import java.time.Instant;
import java.time.LocalDate;

/** Ein konkretes Vorkommen eines Blockers; {@code day} ist der Tag, an dem es beginnt. */
public record BlockerSlot(Blocker blocker, Instant start, Instant end, LocalDate day) {

    public boolean overlaps(Instant from, Instant to) {
        return start.isBefore(to) && end.isAfter(from);
    }
}
