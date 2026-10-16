package club.badernet.nestwerk.update;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class UpdateSettingsTest {

    @Autowired UpdateSettings settings;
    @Autowired JdbcTemplate jdbc;

    @Test
    void releasesAreTheDefaultChannel() {
        assertThat(settings.channel()).isEqualTo(Channel.RELEASES);
        assertThat(settings.hasToken()).isFalse();
    }

    @Test
    void storesTokenEncryptedAndKeepsItWhenLeftEmpty() {
        settings.update(Channel.MAIN, "ghp_geheim");

        assertThat(settings.channel()).isEqualTo(Channel.MAIN);
        assertThat(settings.token()).contains("ghp_geheim");
        String raw = jdbc.queryForObject("SELECT value FROM app_settings WHERE key = 'update.token'", String.class);
        assertThat(raw).isNotBlank().doesNotContain("ghp_geheim");

        settings.update(Channel.RELEASES, "");
        assertThat(settings.token()).contains("ghp_geheim");
    }

    @Test
    void remembersChecksAndNotifiedVersion() {
        Instant now = Instant.parse("2026-10-16T10:00:00Z");
        settings.recordCheck(now, "GitHub nicht erreichbar");
        settings.markNotified("1.5.0");

        assertThat(settings.lastChecked()).contains(now);
        assertThat(settings.lastError()).contains("GitHub nicht erreichbar");
        assertThat(settings.lastNotified()).contains("1.5.0");

        settings.recordCheck(now, null);
        assertThat(settings.lastError()).isEmpty();
    }
}
