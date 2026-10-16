package club.badernet.nestwerk.update;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionTest {

    @Test
    void comparesReleasesNumerically() {
        assertThat(AppVersion.of("1.4.0").olderThanRelease("1.10.0")).isTrue();
        assertThat(AppVersion.of("1.4.0").olderThanRelease("1.4.0")).isFalse();
        assertThat(AppVersion.of("1.4.1").olderThanRelease("1.4.0")).isFalse();
        assertThat(AppVersion.of("1.4.0").olderThanRelease("kaputt")).isFalse();
    }

    @Test
    void mainBuildsCarryTheirCommit() {
        AppVersion main = AppVersion.of("0.0.0-abc1234");

        assertThat(main.commit()).contains("abc1234");
        assertThat(main.imageTag()).isEqualTo("sha-abc1234");
        assertThat(main.isDev()).isFalse();
        // jeder Release ist neuer als ein main-Stand
        assertThat(main.olderThanRelease("1.0.0")).isTrue();
    }

    @Test
    void releasesUseTheirNumberAsImageTag() {
        assertThat(AppVersion.of("1.2.0").imageTag()).isEqualTo("1.2.0");
        assertThat(AppVersion.of("1.2.0").commit()).isEmpty();
    }

    @Test
    void localBuildsAreDev() {
        assertThat(AppVersion.of("dev").isDev()).isTrue();
        assertThat(AppVersion.of("0.0.1-SNAPSHOT").isDev()).isTrue();
        assertThat(AppVersion.of(null).isDev()).isTrue();
        assertThat(AppVersion.of("dev").olderThanRelease("9.9.9")).isFalse();
    }
}
