package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.attachment.AttachmentService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.LetterService;
import club.badernet.nestwerk.school.SchoolLetter;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class LetterControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService users;
    @Autowired club.badernet.nestwerk.role.RoleRepository roles;
    @Autowired FamilyMemberService members;
    @Autowired SchoolService school;
    @Autowired LetterService letters;
    @Autowired AttachmentService attachments;

    User admin;
    SchoolYear year;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        users.create("mama", "passwort1", java.util.Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        FamilyMember lena = members.create("Lena", "#aabbcc", null);
        SchoolProfile profile = school.createProfile(lena.getId(), null);
        year = school.startYear(profile.getId(), "2026/27", null, "3a", LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 7, 31));
    }

    private static RequestPostProcessor viewer() {
        return user("mama").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"));
    }

    private static RequestPostProcessor editor() {
        return user("chef").authorities(new SimpleGrantedAuthority("SCHOOL_VIEW"),
                new SimpleGrantedAuthority("SCHOOL_EDIT"));
    }

    @Test
    void createsALetterWithAnAttachment() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "zoo.txt", "text/plain", "Treffpunkt".getBytes());

        mockMvc.perform(multipart("/school/years/{id}/letters", year.getId()).file(file).param("title", "Ausflug Zoo")
                        .param("dueOn", "2026-10-20").param("note", "Bitte unterschreiben").with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));

        SchoolLetter letter = letters.list(year.getId()).getFirst().letter();
        assertThat(letter.getTitle()).isEqualTo("Ausflug Zoo");
        assertThat(attachments.forLetter(letter.getId())).hasSize(1);

        mockMvc.perform(get("/school/years/{id}/letters", year.getId()).with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Schule Lena")))
                .andExpect(content().string(containsString("Ausflug Zoo")))
                .andExpect(content().string(containsString("zoo.txt")))
                .andExpect(content().string(containsString("Frist 20.10.2026")))
                .andExpect(content().string(not(containsString("Neuer Brief"))));
    }

    @Test
    void signingMovesTheLetterToSigned() throws Exception {
        SchoolLetter letter = letters.create(year.getId(), "Zoo", LocalDate.of(2026, 10, 20), null, admin);

        mockMvc.perform(post("/school/letters/{id}/sign", letter.getId()).param("signed", "true")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));
        assertThat(letters.get(letter.getId()).isSigned()).isTrue();

        mockMvc.perform(get("/school/years/{id}/letters", year.getId()).with(editor()))
                .andExpect(content().string(containsString("Unterschrift zurücknehmen")));

        mockMvc.perform(post("/school/letters/{id}/sign", letter.getId()).param("signed", "false")
                        .with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));
        assertThat(letters.get(letter.getId()).isSigned()).isFalse();
    }

    @Test
    void uploadAndDeleteAndRightsAndFileErrors() throws Exception {
        SchoolLetter letter = letters.create(year.getId(), "Zoo", null, null, admin);

        mockMvc.perform(multipart("/school/letters/{id}/attachments", letter.getId())
                        .file(new MockMultipartFile("files", "a.exe", "application/octet-stream", "x".getBytes()))
                        .with(csrf()).with(editor()))
                .andExpect(flash().attribute("error", containsString("nicht erlaubt")));
        mockMvc.perform(multipart("/school/letters/{id}/attachments", letter.getId())
                        .file(new MockMultipartFile("files", "a.txt", "text/plain", "x".getBytes()))
                        .with(csrf()).with(viewer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/school/letters/{id}/delete", letter.getId()).with(csrf()).with(viewer()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/school/letters/{id}/delete", letter.getId()).with(csrf()).with(editor()))
                .andExpect(flash().attributeExists("success"));
        assertThat(letters.list(year.getId())).isEmpty();
    }

    @Test
    void attachmentDownloadWorksThroughTheGenericController() throws Exception {
        SchoolLetter letter = letters.create(year.getId(), "Zoo", null, null, admin);
        var stored = attachments.store(club.badernet.nestwerk.attachment.AttachmentOwner.letter(letter.getId()),
                new MockMultipartFile("files", "zoo.txt", "text/plain", "Treffpunkt".getBytes()), admin);

        mockMvc.perform(get("/attachments/{id}", stored.getId()).with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string("Treffpunkt"));
    }
}
