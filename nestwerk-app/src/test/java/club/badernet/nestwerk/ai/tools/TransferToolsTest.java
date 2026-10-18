package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
class TransferToolsTest {

    @Autowired TransferTools transferTools;
    @Autowired ContactTools contactTools;
    @Autowired TodoTools todoTools;
    @Autowired ContactService contactService;
    @Autowired UserRepository users;
    @Autowired UserService userService;

    /** Der Import legt jede Zeile in einer eigenen Transaktion an und braucht daher einen schon gespeicherten Benutzer. */
    User admin;

    @BeforeEach
    void setUp() {
        admin = users.findByUsername("admin").orElseThrow();
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    @Test
    void importsContactsAfterPreview() {
        try {
            importsAndExportsContacts();
        } finally {
            contactTools.searchContacts(as(admin), "weber")
                    .forEach(c -> contactService.delete(c.id(), admin));
        }
    }

    private void importsAndExportsContacts() {
        String vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Dr. Weber\nTEL:0711 123456\nEND:VCARD\n";

        TransferTools.ImportResult preview = transferTools.importData(as(admin), "contacts_vcf", vcf, null);
        assertThat(preview.applied()).isFalse();
        assertThat(preview.created()).isEqualTo(1);
        assertThat(contactTools.searchContacts(as(admin), "weber")).isEmpty();

        TransferTools.ImportResult applied = transferTools.importData(as(admin), "CONTACTS_VCF", vcf, true);
        assertThat(applied.applied()).isTrue();
        assertThat(contactTools.searchContacts(as(admin), "weber")).hasSize(1);
        assertThat(transferTools.exportData(as(admin), "contacts_vcf").content()).contains("Dr. Weber");
        assertThat(transferTools.exportData(as(admin), "CONTACTS_CSV").content()).contains("Weber");
    }

    @Test
    @Transactional
    void exportsTodosAndCalendar() {
        User chef = userService.createAdministrator("chef", "passwort1");
        admin = chef;
        todoTools.createTodo(as(admin), "Keller entrümpeln", null, null, null, null, null, null, null, null, null,
                null, null, null);

        TransferTools.Export csv = transferTools.exportData(as(admin), "TODOS_CSV");
        assertThat(csv.content()).contains("Keller entrümpeln");
        assertThat(csv.truncated()).isFalse();
        assertThat(transferTools.exportData(as(admin), "CALENDAR_ICS").content()).contains("BEGIN:VCALENDAR");
        assertThatThrownBy(() -> transferTools.exportData(as(admin), "PDF")).isInstanceOf(Tools.ToolException.class);
        assertThatThrownBy(() -> transferTools.importData(as(admin), "TODOS_CSV", "", true))
                .isInstanceOf(Tools.ToolException.class);
    }
}
