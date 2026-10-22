package club.badernet.nestwerk.blocker;

import java.time.Instant;

/**
 * A timed, blocked range of one member on one day, as the calendar grid draws it. {@code kind} selects colour and
 * icon (e.g. {@code work}), {@code strength} tells how hard it holds when planning.
 */
public record BusyBlock(Long memberId, Instant start, Instant end, String kind, String label,
                        BlockerStrength strength) {
}
