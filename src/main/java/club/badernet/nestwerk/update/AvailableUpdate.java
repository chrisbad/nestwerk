package club.badernet.nestwerk.update;

import java.time.Instant;

/**
 * Eine neuere Version: {@code version} wie sie die App danach meldet, {@code imageTag} für {@code NESTWERK_VERSION},
 * {@code ref} (Tag oder Commit) für die Installationsdateien, {@code notes} als Markdown.
 */
public record AvailableUpdate(String version, String imageTag, String ref, String notes, Instant publishedAt) {
}
