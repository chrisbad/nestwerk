package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class AttachmentToolsTest {

    @Autowired AttachmentTools attachmentTools;
    @Autowired TodoTools todoTools;
    @Autowired UserService userService;

    User admin;
    User outsider;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        outsider = userService.create("fremd", "passwort1", Set.of());
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    @Test
    void addsListsAndReadsTextAttachments() {
        Long todo = todoTools.createTodo(as(admin), "Einkauf", null, null, null, null, null, null, null, null, null,
                null, null, null).todo().id();

        AttachmentTools.AttachmentItem added = attachmentTools.addAttachment(as(admin), "todo", todo, "liste.csv",
                "Artikel;Menge\nMilch;2\n", null);
        assertThat(added.readableAsText()).isTrue();

        assertThat(attachmentTools.listAttachments(as(admin), "TODO", todo))
                .extracting(AttachmentTools.AttachmentItem::fileName).containsExactly("liste.csv");
        AttachmentTools.AttachmentContent content = attachmentTools.readAttachment(as(admin), added.attachmentId());
        assertThat(content.text()).contains("Milch;2");
        assertThat(content.truncated()).isFalse();

        AttachmentTools.AttachmentItem viaBase64 = attachmentTools.addAttachment(as(admin), "TODO", todo, "notiz.txt",
                null, Base64.getEncoder().encodeToString("Hallo".getBytes()));
        assertThat(attachmentTools.readAttachment(as(admin), viaBase64.attachmentId()).text()).isEqualTo("Hallo");
    }

    @Test
    void rejectsBadFilesAndHiddenOwners() {
        Long todo = todoTools.createTodo(as(admin), "Privat", null, null, null, null, null, null, null, null, null,
                true, null, null).todo().id();

        assertThatThrownBy(() -> attachmentTools.addAttachment(as(admin), "TODO", todo, "virus.exe", "x", null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Dateityp");
        assertThatThrownBy(() -> attachmentTools.addAttachment(as(admin), "TODO", todo, "bild.png", "kein bild", null))
                .isInstanceOf(Tools.ToolException.class);
        assertThatThrownBy(() -> attachmentTools.addAttachment(as(admin), "TODO", todo, "a.txt", null, "###"))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> attachmentTools.listAttachments(as(outsider), "TODO", todo))
                .isInstanceOf(Tools.ToolException.class);
        assertThatThrownBy(() -> attachmentTools.listAttachments(as(admin), "EVENT", todo))
                .isInstanceOf(Tools.ToolException.class);
    }
}
