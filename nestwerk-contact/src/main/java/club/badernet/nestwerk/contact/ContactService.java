package club.badernet.nestwerk.contact;

import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Freie Kontakte mit Rechteprüfung über das ACL (Objekttyp {@code CONTACT}). */
@Service
public class ContactService {

    static final String TYPE = "CONTACT";
    private static final Pattern PHONE = Pattern.compile("[0-9+()/. \\-]{3,50}");

    private final ContactRepository contacts;
    private final AclService acl;
    private final UserRepository users;

    public ContactService(ContactRepository contacts, AclService acl, UserRepository users) {
        this.contacts = contacts;
        this.acl = acl;
        this.users = users;
    }

    /** Für die Navigation: darf der Benutzer überhaupt Kontakte sehen? */
    @Transactional(readOnly = true)
    public boolean mayOpen(String username) {
        return users.findByUsername(username)
                .map(u -> !acl.grantedRelations(u, TYPE, "VIEW").isEmpty())
                .orElse(false);
    }

    /**
     * Contacts by id without an ACL check, for modules that show contacts linked to their own records (e.g. the
     * teachers of a school subject); those modules apply their own permissions.
     */
    @Transactional(readOnly = true)
    public List<Contact> findLinked(Collection<Long> ids) {
        return contacts.findAllById(ids);
    }

    /** All contacts of a category (case-insensitive) without an ACL check, see {@link #findLinked}. */
    @Transactional(readOnly = true)
    public List<Contact> findLinkedByCategory(String category) {
        return contacts.findAllByOrderByNameAsc().stream()
                .filter(c -> category.equalsIgnoreCase(c.getCategory()))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean exists(Long id) {
        return contacts.existsById(id);
    }

    @Transactional(readOnly = true)
    public List<Contact> findVisible(User viewer) {
        Set<String> granted = acl.grantedRelations(viewer, TYPE, "VIEW");
        return contacts.findAllByOrderByNameAsc().stream()
                .filter(c -> granted.stream().anyMatch(relations(viewer, c)::contains))
                .toList();
    }

    /** Liefert den Kontakt, wenn der Betrachter ihn sehen darf; sonst wie „nicht vorhanden“. */
    @Transactional(readOnly = true)
    public Contact get(Long id, User viewer) {
        Contact contact = contacts.findById(id).orElseThrow(ContactNotFoundException::new);
        if (!can(viewer, "VIEW", contact)) {
            throw new ContactNotFoundException();
        }
        return contact;
    }

    public boolean canCreate(User user) {
        return acl.permits(user, TYPE, "CREATE", Set.of("ANY"));
    }

    public boolean can(User user, String action, Contact contact) {
        return acl.permits(user, TYPE, action, relations(user, contact));
    }

    @Transactional
    public Contact create(ContactInput input, User actor) {
        if (!canCreate(actor)) {
            throw new AccessDeniedException("Keine Berechtigung zum Anlegen von Kontakten");
        }
        ContactInput clean = validate(input);
        Contact contact = new Contact(clean.name(), actor.getId());
        contact.change(clean);
        return contacts.save(contact);
    }

    @Transactional
    public void update(Long id, ContactInput input, User actor) {
        Contact contact = get(id, actor);
        if (!can(actor, "EDIT", contact)) {
            throw new AccessDeniedException("Keine Berechtigung zum Bearbeiten dieses Kontakts");
        }
        contact.change(validate(input));
    }

    @Transactional
    public void delete(Long id, User actor) {
        Contact contact = get(id, actor);
        if (!can(actor, "DELETE", contact)) {
            throw new AccessDeniedException("Keine Berechtigung zum Löschen dieses Kontakts");
        }
        contacts.delete(contact);
    }

    private Set<String> relations(User user, Contact contact) {
        Set<String> relations = new HashSet<>();
        relations.add("ANY");
        if (contact.getCreatedBy().equals(user.getId())) {
            relations.add("CREATOR");
        }
        return relations;
    }

    private ContactInput validate(ContactInput in) {
        String name = clean(in.name());
        if (name == null || name.length() > 150) {
            throw new IllegalArgumentException("Der Name muss 1–150 Zeichen lang sein.");
        }
        String phone = clean(in.phone());
        if (phone != null && !PHONE.matcher(phone).matches()) {
            throw new IllegalArgumentException("Die Telefonnummer darf nur Ziffern, Leerzeichen und + ( ) / - . enthalten.");
        }
        String email = clean(in.email());
        if (email != null && (email.length() > 200 || !email.matches("[^@\\s]+@[^@\\s]+"))) {
            throw new IllegalArgumentException("Die E-Mail-Adresse ist ungültig.");
        }
        String url = clean(in.url());
        if (url != null && (url.length() > 500 || !url.matches("(?i)https?://\\S+"))) {
            throw new IllegalArgumentException("Die Webadresse muss mit http:// oder https:// beginnen.");
        }
        return new ContactInput(name,
                limited(in.category(), 100, "Die Kategorie"),
                limited(in.company(), 150, "Die Firma"),
                phone, email,
                limited(in.street(), 200, "Die Straße"),
                limited(in.postalCode(), 20, "Die Postleitzahl"),
                limited(in.city(), 100, "Der Ort"),
                url,
                limited(in.note(), 5000, "Die Notiz"));
    }

    private static String limited(String text, int max, String what) {
        String cleaned = clean(text);
        if (cleaned != null && cleaned.length() > max) {
            throw new IllegalArgumentException(what + " darf höchstens " + max + " Zeichen lang sein.");
        }
        return cleaned;
    }

    private static String clean(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
