package club.badernet.nestwerk.backup;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.backup.target.DirectoryTarget;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ziele, Verschlüsselung, Aufbewahrung, Benachrichtigung und Zeitplan; gleicher Kontext wie die Round-Trip-Tests. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw",
        "nestwerk.attachments.dir=target/backup-test/attachments",
        "nestwerk.backup.dir=target/backup-test/backups",
        "nestwerk.backup.external-dir=target/backup-test/external"})
class BackupAutomationTest {

    private static final String PASS = "passphrase-123";

    @Autowired BackupService backups;
    @Autowired BackupSettings settings;
    @Autowired BackupDistributor distributor;
    @Autowired BackupScheduler scheduler;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    User admin;
    String baseline;
    Path external;

    @BeforeEach
    void setUp() throws IOException {
        clear(backups.directory());
        external = distributor.external().directory();
        clear(external);
        Files.createDirectories(external);
        admin = users.findByUsername("chef").orElseGet(() -> userService.createAdministrator("chef", "passwort1"));
        // als „hochgeladen“ ablegen, damit die Aufbewahrungsregel sie nicht löscht
        String first = backups.backupNow(BackupRun.Kind.PRE_RESTORE, admin).getFileName();
        try (InputStream in = Files.newInputStream(backups.file(first, admin))) {
            baseline = backups.upload(in, admin);
        }
        backups.delete(first, admin);
    }

    @AfterEach
    void tearDown() {
        backups.restore(baseline, users.findByUsername("chef").orElseThrow());
    }

    private static void clear(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!p.equals(dir)) {
                        Files.delete(p);
                    }
                }
            }
        }
    }

    private List<String> externalFiles() throws IOException {
        try (Stream<Path> files = Files.list(external)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("nestwerk-")).toList();
        }
    }

    private int notifications(NotificationType type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = ?",
                Integer.class, admin.getId(), type.name());
    }

    @Test
    void unpreparedDiskGivesWarningAndNotification() throws IOException {
        settings.updateExternal(true);

        BackupRun run = backups.backupNow(BackupRun.Kind.MANUAL, admin);

        assertThat(run.getStatus()).isEqualTo(BackupRun.Status.WARNING);
        assertThat(run.getMessage()).contains("Externe Platte", "nicht eingehängt");
        assertThat(notifications(NotificationType.BACKUP_FAILED)).isEqualTo(1);
        assertThat(externalFiles()).isEmpty();
    }

    @Test
    void encryptedCopyGoesToDiskAndCanBeUploadedAgain() throws IOException {
        settings.updateExternal(true);
        settings.updateEncryption(true, PASS);
        distributor.external().prepare();

        BackupRun run = backups.backupNow(BackupRun.Kind.MANUAL, admin);

        assertThat(run.getStatus()).isEqualTo(BackupRun.Status.SUCCESS);
        assertThat(externalFiles()).containsExactly(run.getFileName() + ".enc");
        assertThat(notifications(NotificationType.BACKUP_DONE)).isZero();
        Path copy = external.resolve(run.getFileName() + ".enc");
        try (InputStream in = Files.newInputStream(copy)) {
            assertThatThrownBy(() -> backups.upload(in, "falsche-passphrase", admin))
                    .isInstanceOf(InvalidBackupException.class).hasMessageContaining("Falsche Passphrase");
        }
        try (InputStream in = Files.newInputStream(copy)) {
            assertThat(backups.upload(in, PASS, admin)).startsWith("nestwerk-hochgeladen-");
        }
        // ohne Angabe gilt die gespeicherte Passphrase
        try (InputStream in = Files.newInputStream(copy)) {
            assertThat(backups.upload(in, null, admin)).startsWith("nestwerk-hochgeladen-");
        }
    }

    @Test
    void retentionPrunesLocalAndTargetCopies() throws IOException {
        settings.updateExternal(true);
        distributor.external().prepare();
        settings.updateRetention(new BackupSettings.RetentionRule(2, 0, 0), true);

        for (int i = 0; i < 4; i++) {
            backups.backupNow(BackupRun.Kind.MANUAL, admin);
        }

        assertThat(backups.files(admin)).extracting(BackupService.BackupFile::name)
                .filteredOn(Retention::managed).hasSize(2);
        assertThat(externalFiles()).hasSize(2);
        assertThat(notifications(NotificationType.BACKUP_DONE)).isEqualTo(4);
    }

    @Test
    void schedulerRunsOnceForEachDueSlot() {
        settings.updateSchedule(new BackupSettings.Schedule(true, BackupSettings.Frequency.DAILY, DayOfWeek.MONDAY,
                LocalTime.now(clock).minusMinutes(5)), clock.instant());
        scheduler.tick();
        assertThat(scheduledRuns()).isZero();

        settings.markScheduled(clock.instant().minus(Duration.ofDays(3)));
        scheduler.tick();
        scheduler.tick();

        assertThat(scheduledRuns()).isEqualTo(1);
    }

    private int scheduledRuns() {
        return jdbc.queryForObject("SELECT count(*) FROM backup_runs WHERE kind = 'SCHEDULED'", Integer.class);
    }
}
