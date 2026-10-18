package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.backup.BackupRun;
import club.badernet.nestwerk.backup.BackupService;
import club.badernet.nestwerk.backup.BackupSettings;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Gleiche Properties wie {@code BackupRoundTripTest}: gemeinsame, eigene Datenbank ohne Test-Transaktion. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw",
        "nestwerk.attachments.dir=target/backup-test/attachments",
        "nestwerk.backup.dir=target/backup-test/backups",
        "nestwerk.backup.external-dir=target/backup-test/external"})
@AutoConfigureMockMvc
class AdminBackupControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired BackupService backups;
    @Autowired BackupSettings settings;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    User admin;

    @BeforeEach
    void setUp() {
        admin = users.findByUsername("chef").orElseGet(() -> userService.createAdministrator("chef", "passwort1"));
        if (users.findByUsername("ohnerecht").isEmpty()) {
            userService.create("ohnerecht", "passwort1", Set.of());
        }
    }

    private static RequestPostProcessor chef() {
        return user("chef").authorities(new SimpleGrantedAuthority("BACKUP_MANAGE"));
    }

    @Test
    void pageNeedsPermission() throws Exception {
        mockMvc.perform(get("/admin/backup").with(user("ohnerecht"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/backup").with(chef()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Jetzt sichern")))
                .andExpect(content().string(containsString("Sicherungen</a>")));
    }

    @Test
    void startsBackupInBackgroundAndOffersDownload() throws Exception {
        mockMvc.perform(post("/admin/backup/run").with(csrf()).with(chef()))
                .andExpect(flash().attributeExists("success"));
        for (int i = 0; i < 100 && backups.isBusy(); i++) {
            Thread.sleep(100);
        }
        assertThat(backups.isBusy()).isFalse();
        mockMvc.perform(get("/admin/backup/status").with(chef()))
                .andExpect(header().string("HX-Refresh", "true"));

        String name = backups.files(admin).getFirst().name();
        byte[] zip = mockMvc.perform(get("/admin/backup/files/{n}", name).with(chef()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(name)))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(zip).startsWith('P', 'K');

        mockMvc.perform(post("/admin/backup/upload").with(csrf()).with(chef())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(zip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(containsString("hochgeladen")));
        mockMvc.perform(post("/admin/backup/upload").with(csrf()).with(chef())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content("kein zip".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void restoreNeedsExactNameAndLogsOut() throws Exception {
        BackupRun run = backups.backupNow(BackupRun.Kind.MANUAL, admin);
        jdbc.update("INSERT INTO app_settings (key, value) VALUES ('test.marker', 'nach der Sicherung') "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value");

        mockMvc.perform(post("/admin/backup/files/{n}/restore", run.getFileName()).with(csrf()).with(chef())
                        .param("confirm", "falsch"))
                .andExpect(flash().attributeExists("error"));
        assertThat(jdbc.queryForList("SELECT value FROM app_settings WHERE key = 'test.marker'")).hasSize(1);

        mockMvc.perform(post("/admin/backup/files/{n}/restore", run.getFileName()).with(csrf()).with(chef())
                        .param("confirm", run.getFileName()))
                .andExpect(redirectedUrl("/login?restored"));
        assertThat(jdbc.queryForList("SELECT value FROM app_settings WHERE key = 'test.marker'")).isEmpty();
    }

    @Test
    void savesSettingsAndValidatesInput() throws Exception {
        mockMvc.perform(post("/admin/backup/settings/schedule").with(csrf()).with(chef())
                        .param("enabled", "true").param("frequency", "WEEKLY").param("weekday", "SATURDAY")
                        .param("time", "03:15"))
                .andExpect(flash().attributeExists("success"));
        assertThat(settings.schedule()).isEqualTo(new BackupSettings.Schedule(true, BackupSettings.Frequency.WEEKLY,
                DayOfWeek.SATURDAY, LocalTime.of(3, 15)));
        mockMvc.perform(get("/admin/backup").with(chef()))
                .andExpect(content().string(containsString("Nächste Sicherung: ")));

        mockMvc.perform(post("/admin/backup/settings/webdav").with(csrf()).with(chef())
                        .param("enabled", "true").param("url", "ftp://falsch"))
                .andExpect(flash().attribute("error", containsString("https://")));
        mockMvc.perform(post("/admin/backup/settings/webdav").with(csrf()).with(chef())
                        .param("url", "https://cloud.example.org/remote.php/dav/files/anna/Nestwerk")
                        .param("user", "anna").param("password", "app-passwort"))
                .andExpect(flash().attributeExists("success"));
        assertThat(settings.webDav().url()).endsWith("/Nestwerk/");
        assertThat(settings.webDav().hasPassword()).isTrue();
        assertThat(jdbc.queryForObject("SELECT value FROM app_settings WHERE key = 'backup.webdav.password'",
                String.class)).startsWith("v1:").doesNotContain("app-passwort");

        mockMvc.perform(post("/admin/backup/settings/encryption").with(csrf()).with(chef()).param("enabled", "true"))
                .andExpect(flash().attribute("error", containsString("Passphrase")));
        settings.updateSchedule(new BackupSettings.Schedule(false, BackupSettings.Frequency.DAILY, DayOfWeek.SUNDAY,
                LocalTime.of(2, 30)), java.time.Instant.now());
    }

    @Test
    void homeWarnsManagersAboutMissingBackups() throws Exception {
        jdbc.update("DELETE FROM backup_runs");

        mockMvc.perform(get("/").with(chef()))
                .andExpect(content().string(containsString("Es gibt noch keine Sicherung.")));
        mockMvc.perform(get("/").with(user("ohnerecht")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Sicherung"))));

        backups.backupNow(BackupRun.Kind.MANUAL, admin);
        mockMvc.perform(get("/").with(chef()))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Es gibt noch keine"))));
    }

    @Test
    void rejectsPathsOutsideTheBackupDirectory() throws Exception {
        mockMvc.perform(get("/admin/backup/files/{n}", "..%2F..%2Fpom.xml").with(chef()))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/admin/backup/files/{n}", "nestwerk-gibtesnicht.zip").with(chef()))
                .andExpect(status().isBadRequest());
    }
}
