package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.attachment.Attachment;
import club.badernet.nestwerk.attachment.AttachmentOwner;
import club.badernet.nestwerk.attachment.AttachmentService;
import club.badernet.nestwerk.contact.ContactInput;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoComment;
import club.badernet.nestwerk.todo.TodoCommentService;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Set;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw",
        "nestwerk.attachments.dir=target/test-attachments"})
@AutoConfigureMockMvc
@Transactional
class TodoCollaborationControllerTest {

    static final byte[] PDF = "%PDF-1.7\nInhalt".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired TodoService todoService;
    @Autowired TodoCommentService comments;
    @Autowired AttachmentService attachments;
    @Autowired ContactService contactService;

    User admin;
    User mama;
    User papa;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        mama = userService.create("mama", "passwort1", Set.of(benutzer));
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
    }

    private Todo todo(String title, boolean privat) {
        return todoService.create(new TodoInput(title, null, null, null, null, null, null, privat, null, Set.of(),
                Set.of(), null), mama);
    }

    @Test
    void commentWithAttachmentAppearsInTimeline() throws Exception {
        Todo t = todo("Angebot einholen", false);

        mockMvc.perform(multipart("/todos/{id}/comments", t.getId())
                        .file(new MockMultipartFile("files", "angebot.pdf", "application/pdf", PDF))
                        .param("body", "Hier ist das **Angebot** @mama").with(csrf()).with(user("papa")))
                .andExpect(flash().attributeExists("success"));

        mockMvc.perform(get("/todos/{id}", t.getId()).with(user("mama")))
                .andExpect(content().string(containsString("<strong>Angebot</strong>")))
                .andExpect(content().string(containsString("angebot.pdf")))
                .andExpect(content().string(containsString("hat das ToDo angelegt")));
    }

    @Test
    void timelineCanShowOnlyComments() throws Exception {
        Todo t = todo("Fenster", false);
        comments.add(t.getId(), "Nur dieser Kommentar", papa);

        mockMvc.perform(get("/todos/{id}", t.getId()).param("timeline", "comments").with(user("mama")))
                .andExpect(content().string(containsString("Nur dieser Kommentar")))
                .andExpect(content().string(not(containsString("hat das ToDo angelegt"))));
    }

    @Test
    void ownCommentCanBeEditedAndDeleted() throws Exception {
        Todo t = todo("Fenster", false);
        TodoComment c = comments.add(t.getId(), "Tippfehlr", papa);

        mockMvc.perform(post("/todos/{id}/comments/{c}/edit", t.getId(), c.getId()).param("body", "Tippfehler")
                        .with(csrf()).with(user("papa")))
                .andExpect(flash().attributeExists("success"));
        mockMvc.perform(post("/todos/{id}/comments/{c}/delete", t.getId(), c.getId()).with(csrf()).with(user("mama")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/todos/{id}/comments/{c}/delete", t.getId(), c.getId()).with(csrf()).with(user("papa")))
                .andExpect(flash().attributeExists("success"));
    }

    @Test
    void watchAndUnwatch() throws Exception {
        Todo t = todo("Rasen", false);

        mockMvc.perform(post("/todos/{id}/watch", t.getId()).with(csrf()).with(user("papa")))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/todos/{id}", t.getId()).with(user("papa")))
                .andExpect(content().string(containsString("Nicht mehr beobachten")));
        mockMvc.perform(post("/todos/{id}/unwatch", t.getId()).with(csrf()).with(user("papa")))
                .andExpect(status().is3xxRedirection());
        assertThat(todoService.get(t.getId(), papa).getWatcherUserIds()).isEmpty();
    }

    @Test
    void uploadsAttachmentToTodoAndRejectsBadType() throws Exception {
        Todo t = todo("Schaden", false);

        mockMvc.perform(multipart("/todos/{id}/attachments", t.getId())
                        .file(new MockMultipartFile("files", "foto.png", "image/png", PNG)).with(csrf()).with(user("papa")))
                .andExpect(flash().attributeExists("success"));
        mockMvc.perform(multipart("/todos/{id}/attachments", t.getId())
                        .file(new MockMultipartFile("files", "virus.exe", "application/octet-stream", PNG))
                        .with(csrf()).with(user("papa")))
                .andExpect(flash().attributeExists("error"));
        assertThat(attachments.forTodo(t.getId())).hasSize(1);
    }

    @Test
    void downloadSendsSafeHeaders() throws Exception {
        Todo t = todo("Schaden", false);
        Attachment img = attachments.store(AttachmentOwner.todo(t.getId()),
                new MockMultipartFile("files", "foto.png", "x", PNG), mama);
        Attachment csv = attachments.store(AttachmentOwner.todo(t.getId()),
                new MockMultipartFile("files", "liste.csv", "x", "a;b\n1;2".getBytes(StandardCharsets.UTF_8)), mama);

        mockMvc.perform(get("/attachments/{id}", img.getId()).with(user("papa")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", containsString("inline")));
        mockMvc.perform(get("/attachments/{id}", csv.getId()).with(user("papa")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("liste.csv")));
    }

    @Test
    void foreignAttachmentIsNotFound() throws Exception {
        Todo secret = todo("Geheim", true);
        Attachment a = attachments.store(AttachmentOwner.todo(secret.getId()),
                new MockMultipartFile("files", "geheim.pdf", "x", PDF), mama);

        mockMvc.perform(get("/attachments/{id}", a.getId()).with(user("papa"))).andExpect(status().isNotFound());
    }

    @Test
    void contactPageShowsAttachments() throws Exception {
        var contact = contactService.create(new ContactInput("Meier", null, null, null, null, null, null, null, null,
                null), admin);

        mockMvc.perform(multipart("/contacts/{id}/attachments", contact.getId())
                        .file(new MockMultipartFile("files", "visitenkarte.pdf", "application/pdf", PDF))
                        .with(csrf()).with(user("chef")))
                .andExpect(flash().attributeExists("success"));
        mockMvc.perform(get("/contacts/{id}", contact.getId()).with(user("mama")))
                .andExpect(content().string(containsString("visitenkarte.pdf")));
    }
}
