package club.badernet.nestwerk.update;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version der laufenden App, wie sie der Release-Workflow setzt: {@code X.Y.Z} für Releases, {@code 0.0.0-<sha7>}
 * für Stände von main. Alles andere (lokal gebaut) gilt als {@code dev} und wird nicht aktualisiert.
 */
public record AppVersion(String raw) {

    private static final Pattern RELEASE = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern MAIN = Pattern.compile("0\\.0\\.0-([0-9a-f]{7,40})");

    public static AppVersion of(String raw) {
        return new AppVersion(raw == null ? "dev" : raw.trim());
    }

    public boolean isDev() {
        return commit().isEmpty() && semver().isEmpty();
    }

    public Optional<String> commit() {
        Matcher m = MAIN.matcher(raw);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    public Optional<int[]> semver() {
        return parse(raw).filter(v -> commit().isEmpty());
    }

    /** Tag des Images dieser Version: {@code X.Y.Z} oder {@code sha-<sha7>}. */
    public String imageTag() {
        return commit().map(sha -> "sha-" + sha.substring(0, 7)).orElse(raw);
    }

    /** Ob das Release {@code version} neuer ist; ein main-Stand ist älter als jedes Release. */
    public boolean olderThanRelease(String version) {
        Optional<int[]> other = parse(version);
        if (other.isEmpty() || isDev()) {
            return false;
        }
        return semver().map(own -> Arrays.compare(own, other.get()) < 0).orElse(true);
    }

    private static Optional<int[]> parse(String version) {
        Matcher m = version == null ? null : RELEASE.matcher(version.trim());
        if (m == null || !m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))});
    }

    @Override
    public String toString() {
        return raw;
    }
}
