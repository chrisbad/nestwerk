package club.badernet.nestwerk.school;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactInput;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Schools (with their office) and teachers are contacts. Within the school module their name, phone and e-mail are
 * visible to everyone with school access; creating them goes through the contact module and its permissions.
 */
@Service
public class SchoolContacts {

    public static final String SCHOOL = "Schule";
    public static final String TEACHER = "Lehrkraft";

    private final ContactService contacts;
    private final SchoolAccess access;

    public SchoolContacts(ContactService contacts, SchoolAccess access) {
        this.contacts = contacts;
        this.access = access;
    }

    /** Contacts of the category "Schule", by name. */
    @Transactional(readOnly = true)
    public List<Contact> schools() {
        return byCategory(SCHOOL);
    }

    /** Contacts of the category "Lehrkraft", by name. */
    @Transactional(readOnly = true)
    public List<Contact> teachers() {
        return byCategory(TEACHER);
    }

    @Transactional(readOnly = true)
    public Optional<Contact> get(Long id) {
        return id == null ? Optional.empty() : contacts.findLinked(List.of(id)).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public boolean exists(Long id) {
        return id != null && contacts.exists(id);
    }

    @Transactional(readOnly = true)
    public Map<Long, Contact> byIds(Collection<Long> ids) {
        return contacts.findLinked(ids).stream().collect(Collectors.toMap(Contact::getId, Function.identity()));
    }

    /**
     * A contact the actor may link as school or teacher. Linking shows the contact's details to everyone with school
     * access, so only contacts the actor may see themselves qualify; others are reported as not found.
     */
    @Transactional(readOnly = true)
    public Contact requireLinkable(Long id, User actor) {
        return contacts.get(id, actor);
    }

    public boolean mayCreate(User user) {
        return contacts.canCreate(user);
    }

    /** Creates a school with its office as a contact. */
    @Transactional
    public Contact createSchool(SchoolInput in, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        return contacts.create(new ContactInput(in.name(), SCHOOL, null, in.phone(), in.email(), in.street(),
                in.postalCode(), in.city(), null, null), actor);
    }

    /** Creates a teacher as a contact. */
    @Transactional
    public Contact createTeacher(String name, String email, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        return contacts.create(new ContactInput(name, TEACHER, null, null, email, null, null, null, null, null),
                actor);
    }

    private List<Contact> byCategory(String category) {
        return contacts.findLinkedByCategory(category).stream()
                .sorted(Comparator.comparing(Contact::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Office details of a new school; empty fields are {@code null}. */
    public record SchoolInput(String name, String phone, String email, String street, String postalCode,
                              String city) {
    }
}
