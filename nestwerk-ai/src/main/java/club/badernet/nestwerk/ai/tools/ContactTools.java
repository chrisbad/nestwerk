package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactInput;
import club.badernet.nestwerk.contact.ContactService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/** Kontakte nachschlagen, anlegen und ändern. */
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

    @McpTool(name = "create_contact", description = "Legt einen Kontakt an (Arzt, Handwerker, Schule …).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ContactItem createContact(McpTransportContext context,
                                     @McpToolParam(description = "Name") String name,
                                     @McpToolParam(description = "Kategorie, z. B. Arzt", required = false)
                                     String category,
                                     @McpToolParam(description = "Firma", required = false) String company,
                                     @McpToolParam(description = "Telefon", required = false) String phone,
                                     @McpToolParam(description = "E-Mail", required = false) String email,
                                     @McpToolParam(description = "Straße und Hausnummer", required = false)
                                     String street,
                                     @McpToolParam(description = "Postleitzahl", required = false) String postalCode,
                                     @McpToolParam(description = "Ort", required = false) String city,
                                     @McpToolParam(description = "Webseite", required = false) String url,
                                     @McpToolParam(description = "Notiz", required = false) String note) {
        return tools.write(context, user -> item(contacts.create(new ContactInput(Tools.required(name, "name"),
                category, company, phone, email, street, postalCode, city, url, note), user)));
    }

    @McpTool(name = "update_contact", description = "Ändert einen Kontakt. Nur angegebene Felder werden geändert; "
            + "ein leerer Text löscht das Feld.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public ContactItem updateContact(McpTransportContext context,
                                     @McpToolParam(description = "ID des Kontakts") Long contactId,
                                     @McpToolParam(description = "Name", required = false) String name,
                                     @McpToolParam(description = "Kategorie", required = false) String category,
                                     @McpToolParam(description = "Firma", required = false) String company,
                                     @McpToolParam(description = "Telefon", required = false) String phone,
                                     @McpToolParam(description = "E-Mail", required = false) String email,
                                     @McpToolParam(description = "Straße und Hausnummer", required = false)
                                     String street,
                                     @McpToolParam(description = "Postleitzahl", required = false) String postalCode,
                                     @McpToolParam(description = "Ort", required = false) String city,
                                     @McpToolParam(description = "Webseite", required = false) String url,
                                     @McpToolParam(description = "Notiz", required = false) String note) {
        return tools.write(context, user -> {
            Long id = Tools.required(contactId, "contactId");
            Contact c = contacts.get(id, user);
            contacts.update(id, new ContactInput(name != null ? name : c.getName(),
                    category != null ? category : c.getCategory(), company != null ? company : c.getCompany(),
                    phone != null ? phone : c.getPhone(), email != null ? email : c.getEmail(),
                    street != null ? street : c.getStreet(), postalCode != null ? postalCode : c.getPostalCode(),
                    city != null ? city : c.getCity(), url != null ? url : c.getUrl(),
                    note != null ? note : c.getNote()), user);
            return item(contacts.get(id, user));
        });
    }

    private static ContactItem item(Contact c) {
        return new ContactItem(c.getId(), c.getName(), c.getCategory(), c.getCompany(), c.getPhone(), c.getEmail(),
                c.getStreet(), c.getPostalCode(), c.getCity(), c.getUrl(), c.getNote());
    }
}
