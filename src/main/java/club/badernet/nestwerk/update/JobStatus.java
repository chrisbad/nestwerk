package club.badernet.nestwerk.update;

import java.time.Instant;

/** Inhalt von {@code status.json}, den der Updater schreibt. */
public record JobStatus(UpdateRun.Status status, String step, String message, Instant updatedAt) {
}
