package club.badernet.nestwerk.blocker;

/**
 * Ein Grund, warum eine Person zu einer Zeit besser nicht eingeplant wird; {@code blockerId} ist leer bei
 * Ruhe-Zielen. {@code message} ist für den Handelnden formuliert (verdeckte Blocker ohne Titel).
 */
public record Conflict(Long memberId, String memberName, Long blockerId, BlockerStrength strength, String message) {
}
