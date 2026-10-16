package club.badernet.nestwerk.update;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Import({TestcontainersConfiguration.class, UpdateCheckTest.Fakes.class})
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.update.current-version=1.4.0"})
@Transactional
class UpdateCheckTest {

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeReleaseSource fakeReleaseSource() {
            return new FakeReleaseSource();
        }
    }

    @Autowired UpdateCheck check;
    @Autowired UpdateSettings settings;
    @Autowired FakeReleaseSource source;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    User admin;

    @BeforeEach
    void setUp() {
        source.reset();
        admin = users.findByUsername("chef").orElseGet(() -> userService.createAdministrator("chef", "passwort1"));
    }

    private int notifications(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = ?",
                Integer.class, admin.getId(), type);
    }

    @Test
    void newVersionNotifiesAdminsOnce() {
        source.next = new AvailableUpdate("1.5.0", "1.5.0", "v1.5.0", "- Neu", Instant.now());

        UpdateCheck.CheckResult first = check.check();
        check.check();

        assertThat(first.update()).get().extracting(AvailableUpdate::version).isEqualTo("1.5.0");
        assertThat(first.error()).isNull();
        assertThat(notifications("UPDATE_AVAILABLE")).isEqualTo(1);
        assertThat(check.cached()).isPresent();
        assertThat(settings.lastChecked()).isPresent();
    }

    @Test
    void sourceErrorIsRecordedNotThrown() {
        source.failure = new UpdateSourceException("GitHub ist nicht erreichbar.");

        UpdateCheck.CheckResult result = check.check();

        assertThat(result.update()).isEmpty();
        assertThat(result.error()).isEqualTo("GitHub ist nicht erreichbar.");
        assertThat(settings.lastError()).contains("GitHub ist nicht erreichbar.");
        assertThat(notifications("UPDATE_AVAILABLE")).isZero();
    }

    @Test
    void runningVersionIsReported() {
        assertThat(check.current().raw()).isEqualTo("1.4.0");
        assertThat(source.asked).isZero();
    }
}
