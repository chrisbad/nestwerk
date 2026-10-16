package club.badernet.nestwerk.update;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, UpdateCheckTestSupport.Fakes.class})
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.update.current-version=1.4.0",
        "nestwerk.update.dir=target/update-test"})
@Transactional
class UpdateJobsTest {

    static final Path DIR = Path.of("target/update-test");

    @Autowired UpdateJobs jobs;
    @Autowired UpdateRunRepository runs;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    User admin;
    AvailableUpdate update = new AvailableUpdate("1.5.0", "1.5.0", "v1.5.0", "- Neu", Instant.now());

    @BeforeEach
    void setUp() throws IOException {
        FileSystemUtils.deleteRecursively(DIR);
        admin = users.findByUsername("chef").orElseGet(() -> userService.createAdministrator("chef", "passwort1"));
    }

    @Test
    void startWritesRequestFilesAndReadyLast() throws IOException {
        UpdateRun run = jobs.start(update, Channel.RELEASES, admin);

        Path job = DIR.resolve("jobs").resolve(run.getJobId());
        assertThat(run.getJobId()).matches("\\d{8}-\\d{6}-[a-z0-9]{4}");
        assertThat(run.getStatus()).isEqualTo(UpdateRun.Status.QUEUED);
        assertThat(run.getFromVersion()).isEqualTo("1.4.0");
        assertThat(Files.readString(job.resolve("request.json")))
                .contains("\"imageTag\":\"1.5.0\"", "\"toVersion\":\"1.5.0\"", "\"channel\":\"RELEASES\"");
        assertThat(Files.readString(job.resolve("files/update.sh"))).isEqualTo("# update.sh @ v1.5.0");
        assertThat(job.resolve("ready")).exists();
        // ready ist die jüngste Datei: der Updater sieht den Auftrag erst, wenn alles geschrieben ist
        assertThat(Files.getLastModifiedTime(job.resolve("ready")))
                .isGreaterThanOrEqualTo(Files.getLastModifiedTime(job.resolve("request.json")));
    }

    @Test
    void onlyOneUpdateAtATime() {
        jobs.start(update, Channel.RELEASES, admin);

        assertThatThrownBy(() -> jobs.start(update, Channel.RELEASES, admin))
                .isInstanceOf(IllegalStateException.class).hasMessage("Es läuft bereits ein Update.");
    }

    @Test
    void syncTakesOverFinishedStatusAndNotifies() throws IOException {
        UpdateRun run = jobs.start(update, Channel.RELEASES, admin);
        Path job = DIR.resolve("jobs").resolve(run.getJobId());
        Files.writeString(job.resolve("status.json"), """
                {"status":"DONE","step":"START","message":"Version 1.5.0 läuft.","updatedAt":"2026-10-16T12:00:00Z"}
                """);
        Files.writeString(job.resolve("log.txt"), "Sicherung …\nfertig\n", StandardCharsets.UTF_8);
        Files.writeString(job.resolve("backup"), "nestwerk-vor-update.zip");

        jobs.sync();

        UpdateRun synced = runs.findById(run.getId()).orElseThrow();
        assertThat(synced.getStatus()).isEqualTo(UpdateRun.Status.DONE);
        assertThat(synced.getMessage()).isEqualTo("Version 1.5.0 läuft.");
        assertThat(synced.getLog()).contains("fertig");
        assertThat(synced.getBackupFile()).isEqualTo("nestwerk-vor-update.zip");
        assertThat(synced.getFinishedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'UPDATE_DONE'",
                Integer.class, admin.getId())).isEqualTo(1);
        // danach ist ein neues Update möglich
        assertThat(jobs.start(update, Channel.RELEASES, admin)).isNotNull();
    }

    @Test
    void brokenStatusFileIsIgnored() throws IOException {
        UpdateRun run = jobs.start(update, Channel.RELEASES, admin);
        Files.writeString(DIR.resolve("jobs").resolve(run.getJobId()).resolve("status.json"), "{\"status\":");

        assertThat(jobs.status(run.getJobId())).isEmpty();
        jobs.sync();
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(UpdateRun.Status.QUEUED);
    }

    @Test
    void updaterCountsAsInstalledOnlyWithFreshHeartbeat() throws IOException {
        assertThat(jobs.updaterInstalled()).isFalse();

        Files.createDirectories(DIR);
        Path alive = DIR.resolve("updater.alive");
        Files.writeString(alive, "");
        assertThat(jobs.updaterInstalled()).isTrue();

        Files.setLastModifiedTime(alive, FileTime.from(Instant.now().minus(5, ChronoUnit.MINUTES)));
        assertThat(jobs.updaterInstalled()).isFalse();
    }
}
