package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/** Kontakte nachschlagen. */
@Component
public class ContactTools {

    public record ContactItem(Long id, String name, String category, String company, String phone, String email,
                              String street, String postalCode, String city, String url, String note) {
    }

    private final Tools tools;
    private final ContactService contacts;

    public ContactTools(Tools tools, ContactService contacts) {
        this.tools = tools;
        this.contacts = contacts;
    }

    @McpTool(name = "search_contacts", description = "Sucht sichtbare Kontakte (Ärzte, Handwerker, Schulen …) nach "
            + "Name, Firma, Kategorie, Ort, Telefon oder E-Mail. Ohne Suchtext alle.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<ContactItem> searchContacts(McpTransportContext context,
                                            @McpToolParam(description = "Suchtext", required = false) String text) {
        return tools.read(context, user -> {
            String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            return contacts.findVisible(user).stream()
                    .filter(c -> needle.isEmpty() || Stream.of(c.getName(), c.getCompany(), c.getCategory(),
                                    c.getCity(), c.getPhone(), c.getEmail(), c.getNote())
                            .filter(Objects::nonNull)
                            .anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(needle)))
                    .map(ContactTools::item).toList();
        });
    }

    @McpTool(name = "get_contact", description = "Zeigt einen Kontakt mit allen Angaben.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public ContactItem getContact(McpTransportContext context,
                                  @McpToolParam(description = "ID des Kontakts") Long contactId) {
        return tools.read(context, user -> item(contacts.get(Tools.required(contactId, "contactId"), user)));
    }

    private static ContactItem item(Contact c) {
        return new ContactItem(c.getId(), c.getName(), c.getCategory(), c.getCompany(), c.getPhone(), c.getEmail(),
                c.getStreet(), c.getPostalCode(), c.getCity(), c.getUrl(), c.getNote());
    }
}
