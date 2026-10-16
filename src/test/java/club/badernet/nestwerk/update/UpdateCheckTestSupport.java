package club.badernet.nestwerk.update;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Ersetzt GitHub in Tests durch eine Quelle, die liefert, was der Test vorgibt. */
public final class UpdateCheckTestSupport {

    private UpdateCheckTestSupport() {
    }

    @TestConfiguration
    public static class Fakes {
        @Bean
        @Primary
        Source fakeReleaseSource() {
            return new Source();
        }
    }

    public static class Source implements ReleaseSource {

        AvailableUpdate next;
        UpdateSourceException failure;
        int asked;

        public void reset() {
            next = null;
            failure = null;
            asked = 0;
        }

        public void offer(AvailableUpdate update) {
            next = update;
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
}
