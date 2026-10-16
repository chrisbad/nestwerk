package club.badernet.nestwerk.update;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Quelle für Tests: liefert, was der Test vorgibt. */
class FakeReleaseSource implements ReleaseSource {

    AvailableUpdate next;
    UpdateSourceException failure;
    int asked;

    void reset() {
        next = null;
        failure = null;
        asked = 0;
    }

    @Override
    public Optional<AvailableUpdate> latest(Channel channel, AppVersion current) {
        asked++;
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(next);
    }

    @Override
    public Map<String, byte[]> files(String ref) {
        if (failure != null) {
            throw failure;
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : GitHubReleaseSource.FILES) {
            files.put(name, ("# " + name + " @ " + ref).getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }
}
