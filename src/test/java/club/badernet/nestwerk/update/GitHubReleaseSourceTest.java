package club.badernet.nestwerk.update;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubReleaseSourceTest {

    static final String API = "https://api.github.test/repos/chrisbad/nestwerk";

    MockRestServiceServer server;
    GitHubReleaseSource source;
    Optional<String> token = Optional.of("ghp_test");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        source = new GitHubReleaseSource(builder, API, () -> token);
    }

    @Test
    void newerReleaseIsOfferedWithNotes() {
        server.expect(requestTo(API + "/releases/latest"))
                .andExpect(header("Authorization", "Bearer ghp_test"))
                .andRespond(withSuccess("""
                        {"tag_name":"v1.5.0","body":"## Neu\\n- Küche","published_at":"2026-10-20T08:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        AvailableUpdate update = source.latest(Channel.RELEASES, AppVersion.of("1.4.2")).orElseThrow();

        assertThat(update.version()).isEqualTo("1.5.0");
        assertThat(update.imageTag()).isEqualTo("1.5.0");
        assertThat(update.ref()).isEqualTo("v1.5.0");
        assertThat(update.notes()).contains("Küche");
        assertThat(update.publishedAt()).isEqualTo("2026-10-20T08:00:00Z");
    }

    @Test
    void sameOrOlderReleaseIsNoUpdate() {
        server.expect(requestTo(API + "/releases/latest"))
                .andRespond(withSuccess("{\"tag_name\":\"v1.4.2\",\"body\":\"\",\"published_at\":null}",
                        MediaType.APPLICATION_JSON));

        assertThat(source.latest(Channel.RELEASES, AppVersion.of("1.4.2"))).isEmpty();
    }

    @Test
    void noReleaseYetIsNoUpdate() {
        server.expect(requestTo(API + "/releases/latest")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(source.latest(Channel.RELEASES, AppVersion.of("1.4.2"))).isEmpty();
    }

    @Test
    void newMainBuildListsCommitsSinceRunningOne() {
        server.expect(requestTo(API + "/actions/workflows/release.yml/runs?branch=main&status=success&per_page=1"))
                .andRespond(withSuccess("""
                        {"workflow_runs":[{"head_sha":"def5678aaaabbbbccccddddeeeeffff00001111",
                         "updated_at":"2026-10-17T09:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/compare/abc1234...def5678aaaabbbbccccddddeeeeffff00001111"))
                .andRespond(withSuccess("""
                        {"status":"ahead","commits":[
                          {"commit":{"message":"feat: Blocker\\n\\nDetails"}},
                          {"commit":{"message":"fix: Kalender"}}]}
                        """, MediaType.APPLICATION_JSON));

        AvailableUpdate update = source.latest(Channel.MAIN, AppVersion.of("0.0.0-abc1234")).orElseThrow();

        assertThat(update.imageTag()).isEqualTo("sha-def5678");
        assertThat(update.version()).isEqualTo("0.0.0-def5678");
        assertThat(update.ref()).isEqualTo("def5678aaaabbbbccccddddeeeeffff00001111");
        assertThat(update.notes()).isEqualTo("- feat: Blocker\n- fix: Kalender");
    }

    @Test
    void sameMainBuildIsNoUpdate() {
        server.expect(requestTo(API + "/actions/workflows/release.yml/runs?branch=main&status=success&per_page=1"))
                .andRespond(withSuccess("{\"workflow_runs\":[{\"head_sha\":\"abc1234ffff\",\"updated_at\":null}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(source.latest(Channel.MAIN, AppVersion.of("0.0.0-abc1234"))).isEmpty();
    }

    @Test
    void mainBuildBehindTheRunningOneIsNoUpdate() {
        server.expect(requestTo(API + "/actions/workflows/release.yml/runs?branch=main&status=success&per_page=1"))
                .andRespond(withSuccess("{\"workflow_runs\":[{\"head_sha\":\"0000000aaaa\",\"updated_at\":null}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/compare/abc1234...0000000aaaa"))
                .andRespond(withSuccess("{\"status\":\"behind\",\"commits\":[]}", MediaType.APPLICATION_JSON));

        assertThat(source.latest(Channel.MAIN, AppVersion.of("0.0.0-abc1234"))).isEmpty();
    }

    @Test
    void refusedAccessExplainsTheToken() {
        server.expect(requestTo(API + "/releases/latest")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> source.latest(Channel.RELEASES, AppVersion.of("1.0.0")))
                .isInstanceOf(UpdateSourceException.class).hasMessageContaining("GitHub-Token prüfen");
    }

    @Test
    void privateRepositoryWithoutTokenAsksForOne() {
        token = Optional.empty();
        server.expect(requestTo(API + "/actions/workflows/release.yml/runs?branch=main&status=success&per_page=1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> source.latest(Channel.MAIN, AppVersion.of("0.0.0-abc1234")))
                .isInstanceOf(UpdateSourceException.class).hasMessageContaining("GitHub-Token hinterlegen");
    }

    @Test
    void loadsInstallationFilesOfAVersion() {
        for (String file : GitHubReleaseSource.FILES) {
            server.expect(requestTo(API + "/contents/" + file + "?ref=v1.5.0"))
                    .andExpect(header("Accept", "application/vnd.github.raw"))
                    .andRespond(withSuccess("inhalt " + file, MediaType.TEXT_PLAIN));
        }

        Map<String, byte[]> files = source.files("v1.5.0");

        assertThat(files).containsOnlyKeys("compose.prod.yaml", "compose.proxy.yaml", "update.sh");
        assertThat(new String(files.get("update.sh"), StandardCharsets.UTF_8)).isEqualTo("inhalt update.sh");
    }
}
