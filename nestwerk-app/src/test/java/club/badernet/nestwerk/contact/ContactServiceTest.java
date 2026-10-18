package club.badernet.nestwerk.contact;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclRule;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class ContactServiceTest {

    @Autowired ContactService contacts;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;
    @Autowired TodoRepository todos;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    User admin;
    User reader;
    User owner;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        reader = userService.create("leser", "passwort1", Set.of(benutzer));
        Role own = roleService.create("Eigene Kontakte", null, Set.of());
        acl.replaceRules(admin, own.getId(), Set.of(new AclRule("CONTACT", "VIEW", "CREATOR"),
                new AclRule("CONTACT", "CREATE", "ANY"), new AclRule("CONTACT", "EDIT", "CREATOR"),
                new AclRule("CONTACT", "DELETE", "CREATOR")));
        owner = userService.create("eigner", "passwort1", Set.of(own.getId()));
    }

    private static ContactInput named(String name) {
        return new ContactInput(name, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void createsContactWithCleanedFields() {
        Contact c = contacts.create(new ContactInput("  Elektriker Meier ", " Handwerker ", "", "0711 / 12 34",
                "info@meier.de", "Hauptstr. 1", "70173", "Stuttgart", "https://meier.de", "  "), admin);

        assertThat(c.getName()).isEqualTo("Elektriker Meier");
        assertThat(c.getCategory()).isEqualTo("Handwerker");
        assertThat(c.getCompany()).isNull();
        assertThat(c.getNote()).isNull();
        assertThat(c.addressLine()).isEqualTo("Hauptstr. 1, 70173 Stuttgart");
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> contacts.create(named(" "), admin)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contacts.create(new ContactInput("X", null, null, null, "keine-mail", null, null,
                null, null, null), admin)).hasMessageContaining("E-Mail");
        assertThatThrownBy(() -> contacts.create(new ContactInput("X", null, null, null, null, null, null, null,
                "javascript:alert(1)", null), admin)).hasMessageContaining("http");
        assertThatThrownBy(() -> contacts.create(new ContactInput("X", null, null, "ruf mich an", null, null, null,
                null, null, null), admin)).hasMessageContaining("Telefon");
    }

    @Test
    void creatingNeedsPermission() {
        assertThatThrownBy(() -> contacts.create(named("Schule"), reader))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void creatorRuleLimitsToOwnContacts() {
        Contact foreign = contacts.create(named("Fremd"), admin);
        Contact mine = contacts.create(named("Meins"), owner);

        assertThat(contacts.findVisible(owner)).extracting(Contact::getName).containsExactly("Meins");
        assertThatThrownBy(() -> contacts.get(foreign.getId(), owner)).isInstanceOf(ContactNotFoundException.class);
        contacts.update(mine.getId(), named("Meins neu"), owner);
        assertThat(contacts.get(mine.getId(), owner).getName()).isEqualTo("Meins neu");
    }

    @Test
    void anyRuleShowsAllButEditingNeedsItsOwnRule() {
        Contact c = contacts.create(named("Schule"), admin);

        assertThat(contacts.findVisible(reader)).extracting(Contact::getName).contains("Schule");
        assertThatThrownBy(() -> contacts.update(c.getId(), named("Anders"), reader))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(contacts.mayOpen("leser")).isTrue();
    }

    @Test
    void deletingContactRemovesAssignment() {
        Contact c = contacts.create(named("Meier"), admin);
        Todo todo = new Todo("Steckdose", admin.getId());
        todo.replaceAssignees(Set.of(AssigneeRef.contact(c.getId())));
        todos.saveAndFlush(todo);

        contacts.delete(c.getId(), admin);
        todos.flush();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM todo_assignees WHERE todo_id = ?", Integer.class,
                todo.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM todos WHERE id = ?", Integer.class, todo.getId()))
                .isEqualTo(1);
    }
}
