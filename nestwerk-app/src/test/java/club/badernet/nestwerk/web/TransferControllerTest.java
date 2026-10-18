package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.backup.BackupRun;
import club.badernet.nestwerk.backup.BackupService;
import club.badernet.nestwerk.contact.ContactRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Gleicher Kontext wie die Sicherungstests; der Ausgangsstand wird am Ende wiederhergestellt. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw",
        "nestwerk.attachments.dir=target/backup-test/attachments",
        "nestwerk.backup.dir=target/backup-test/backups",
        "nestwerk.backup.external-dir=target/backup-test/external"})
@AutoConfigureMockMvc
class TransferControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired ContactRepository contacts;
    @Autowired BackupService backups;

    User admin;
    String baseline;

    @BeforeEach
    void setUp() {
        admin = users.findByUsername("chef").orElseGet(() -> userService.createAdministrator("chef", "passwort1"));
        baseline = backups.backupNow(BackupRun.Kind.PRE_RESTORE, admin).getFileName();
    }

    @AfterEach
    void tearDown() {
        backups.restore(baseline, users.findByUsername("chef").orElseThrow());
    }

    @Test
    void exportsAreDownloads() throws Exception {
        mockMvc.perform(get("/export/calendar.ics").with(user("chef")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/calendar")))
                .andExpect(header().string("Content-Disposition", containsString("nestwerk-kalender-")))
                .andExpect(content().string(containsString("BEGIN:VCALENDAR")));
        mockMvc.perform(get("/export/contacts.csv").with(user("chef")))
                .andExpect(header().string("Content-Disposition", containsString(".csv")));
        mockMvc.perform(get("/export/todos.csv").param("status", "OPEN").with(user("chef")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Titel")));
    }

    @Test
    void importShowsPreviewThenAppliesOnConfirm() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockMultipartFile file = new MockMultipartFile("file", "kontakte.csv", "text/csv",
                "Name;Telefon\nKinderarzt Dr. Weber;030 555\n".getBytes(StandardCharsets.UTF_8));
        long before = contacts.count();

        mockMvc.perform(multipart("/import/contacts").file(file).session(session).with(csrf()).with(user("chef")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vorschau")))
                .andExpect(content().string(containsString("Kinderarzt Dr. Weber")));
        assertThat(contacts.count()).isEqualTo(before);

        mockMvc.perform(post("/import/contacts/confirm").session(session).with(csrf()).with(user("chef")))
                .andExpect(content().string(containsString("Import abgeschlossen")));
        assertThat(contacts.count()).isEqualTo(before + 1);

        // ein zweites Bestätigen ohne neue Datei geht nicht
        mockMvc.perform(post("/import/contacts/confirm").session(session).with(csrf()).with(user("chef")))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void rejectsWrongFormatsAndUnknownKinds() throws Exception {
        MockMultipartFile ics = new MockMultipartFile("file", "termine.ics", "text/calendar", "x".getBytes());

        mockMvc.perform(multipart("/import/todos").file(ics).with(csrf()).with(user("chef")))
                .andExpect(flash().attribute("error", containsString("CSV")));
        mockMvc.perform(multipart("/import/calendar").file(ics).with(csrf()).with(user("chef")))
                .andExpect(flash().attribute("error", containsString("kein gültiger iCal")));
        mockMvc.perform(get("/import/geheim").with(user("chef"))).andExpect(status().isForbidden());
    }

    @Test
    void importWithoutRightIsForbidden() throws Exception {
        if (users.findByUsername("ohnerecht").isEmpty()) {
            userService.create("ohnerecht", "passwort1", Set.of());
        }
        MockMultipartFile file = new MockMultipartFile("file", "k.csv", "text/csv", "Name\nA\n".getBytes());

        mockMvc.perform(multipart("/import/contacts").file(file).with(csrf()).with(user("ohnerecht")))
                .andExpect(status().isForbidden());
    }
}
