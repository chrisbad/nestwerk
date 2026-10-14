package club.badernet.nestwerk.attachment;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.contact.ContactInput;
import club.badernet.nestwerk.contact.ContactNotFoundException;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoComment;
import club.badernet.nestwerk.todo.TodoCommentService;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoNotFoundException;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw",
        "nestwerk.attachments.dir=target/test-attachments"})
@Transactional
class AttachmentServiceTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};
    static final byte[] PDF = "%PDF-1.7\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1);

    @Autowired AttachmentService attachments;
    @Autowired TodoService todoService;
    @Autowired TodoCommentService comments;
    @Autowired TodoRepository todos;
    @Autowired ContactService contactService;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Value("${nestwerk.attachments.dir}") String dir;

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

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, "application/octet-stream", content);
    }

    @Test
    void storesImageWithTypeFromWhitelist() throws Exception {
        Todo t = todo("Schaden", false);

        Attachment a = attachments.store(AttachmentOwner.todo(t.getId()), file("foto.PNG", PNG), papa);

        assertThat(a.getContentType()).isEqualTo("image/png");
        assertThat(a.isImage()).isTrue();
        assertThat(a.getStorageKey()).doesNotContain("foto");
        assertThat(Files.readAllBytes(Path.of(dir, a.getStorageKey()))).isEqualTo(PNG);
        assertThat(attachments.forTodo(t.getId())).extracting(Attachment::getFilename).containsExactly("foto.PNG");
    }

    @Test
    void disguisedFileIsRejected() {
        Todo t = todo("Schaden", false);
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> attachments.store(AttachmentOwner.todo(t.getId()), file("bild.png", html), mama))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Inhalt");
        assertThatThrownBy(() -> attachments.store(AttachmentOwner.todo(t.getId()), file("seite.html", html), mama))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dateityp");
        assertThatThrownBy(() -> attachments.store(AttachmentOwner.todo(t.getId()),
                file("notiz.txt", new byte[]{'a', 0, 'b'}), mama))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filenameIsSanitized() throws Exception {
        Todo t = todo("Schaden", false);

        Attachment a = attachments.store(AttachmentOwner.todo(t.getId()), file("../../etc/pass\nwd.pdf", PDF), mama);

        assertThat(a.getFilename()).isEqualTo("passwd.pdf");
        assertThat(Path.of(dir, a.getStorageKey()).normalize().startsWith(Path.of(dir).normalize())).isTrue();
    }

    @Test
    void emptyAndOversizedFilesAreRejected() {
        Todo t = todo("Schaden", false);

        assertThatThrownBy(() -> attachments.store(AttachmentOwner.todo(t.getId()), file("leer.pdf", new byte[0]), mama))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] big = new byte[(int) AttachmentService.MAX_SIZE + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        assertThatThrownBy(() -> attachments.store(AttachmentOwner.todo(t.getId()), file("gross.pdf", big), mama))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20 MB");
    }

    @Test
    void uploadingNeedsAttachRightAndVisibleOwner() {
        Todo secret = todo("Geheim", true);

        assertThatThrownBy(() -> attachments.store(AttachmentOwner.todo(secret.getId()), file("a.pdf", PDF), papa))
                .isInstanceOf(TodoNotFoundException.class);
        var contact = contactService.create(new ContactInput("Meier", null, null, null, null, null, null, null, null,
                null), admin);
        assertThatThrownBy(() -> attachments.store(AttachmentOwner.contact(contact.getId()), file("a.pdf", PDF), mama))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void downloadChecksVisibility() {
        Todo secret = todo("Geheim", true);
        Attachment a = attachments.store(AttachmentOwner.todo(secret.getId()), file("a.pdf", PDF), mama);

        assertThat(attachments.load(a.getId(), mama).attachment().getFilename()).isEqualTo("a.pdf");
        assertThatThrownBy(() -> attachments.load(a.getId(), papa)).isInstanceOf(TodoNotFoundException.class);
    }

    @Test
    void commentAttachmentsBelongToTheirComment() {
        Todo t = todo("Angebot", false);
        TodoComment c = comments.add(t.getId(), "Siehe Anhang", papa);

        attachments.store(AttachmentOwner.comment(c.getId()), file("angebot.pdf", PDF), papa);

        assertThat(attachments.forComments(Set.of(c.getId())).get(c.getId()))
                .extracting(Attachment::getFilename).containsExactly("angebot.pdf");
        assertThatThrownBy(() -> attachments.store(AttachmentOwner.comment(c.getId()), file("b.pdf", PDF), mama))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deletingNeedsUploaderOrEditRight() {
        Todo t = todo("Schaden", false);
        Attachment byPapa = attachments.store(AttachmentOwner.todo(t.getId()), file("a.pdf", PDF), papa);
        Attachment byMama = attachments.store(AttachmentOwner.todo(t.getId()), file("b.pdf", PDF), mama);

        assertThatThrownBy(() -> attachments.delete(byMama.getId(), papa)).isInstanceOf(AccessDeniedException.class);
        attachments.delete(byPapa.getId(), papa);
        attachments.delete(byMama.getId(), mama);
        assertThat(attachments.forTodo(t.getId())).isEmpty();
    }

    @Test
    void contactAttachmentsWork() {
        var contact = contactService.create(new ContactInput("Meier", null, null, null, null, null, null, null, null,
                null), admin);

        attachments.store(AttachmentOwner.contact(contact.getId()), file("visitenkarte.jpg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16}), admin);

        assertThat(attachments.forContact(contact.getId())).hasSize(1);
        assertThatThrownBy(() -> attachments.load(attachments.forContact(contact.getId()).getFirst().getId(),
                userService.create("niemand", "passwort1", Set.of())))
                .isInstanceOf(ContactNotFoundException.class);
    }

    @Test
    void sweepRemovesOrphanedFiles() throws Exception {
        Path orphan = Path.of(dir, "verwaist" + "0".repeat(24));
        Files.createDirectories(orphan.getParent());
        Files.write(orphan, PNG);
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minusSeconds(7200)));
        Path fresh = Path.of(dir, "frisch" + "0".repeat(26));
        Files.write(fresh, PNG);
        Todo t = todo("Behalten", false);
        Attachment kept = attachments.store(AttachmentOwner.todo(t.getId()), file("a.pdf", PDF), mama);
        Files.setLastModifiedTime(Path.of(dir, kept.getStorageKey()), FileTime.from(Instant.now().minusSeconds(7200)));
        todos.flush();

        attachments.sweepOrphans();

        assertThat(orphan).doesNotExist();
        assertThat(fresh).exists();
        assertThat(Path.of(dir, kept.getStorageKey())).exists();
        Files.deleteIfExists(fresh);
    }
}
