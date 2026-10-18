package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class TodoControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;
    @Autowired TodoService todoService;
    @Autowired TodoRepository todos;
    @Autowired Clock clock;

    User admin;
    User mama;
    User papa;
    LocalDate today;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        mama = userService.create("mama", "passwort1", Set.of(benutzer));
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
        today = LocalDate.now(clock);
    }

    private Todo todo(String title, User owner, LocalDate due, boolean privat) {
        return todoService.create(new TodoInput(title, null, null, null, due, null, null, privat, null, Set.of(),
                Set.of(), null), owner);
    }

    @Test
    void listShowsGroupsAndHidesForeignPrivateTodos() throws Exception {
        todo("Steuererklärung", mama, today.minusDays(3), false);
        todo("Geschenk für Papa", mama, today, true);

        mockMvc.perform(get("/todos").with(user("papa")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Überfällig")))
                .andExpect(content().string(containsString("Steuererklärung")))
                .andExpect(content().string(not(containsString("Geschenk für Papa"))));
    }

    @Test
    void filterComesFromUrl() throws Exception {
        todo("Fenster putzen", mama, null, false);
        todo("Reifen wechseln", papa, null, false);

        mockMvc.perform(get("/todos").param("involvement", "MINE").with(user("papa")))
                .andExpect(content().string(containsString("Reifen wechseln")))
                .andExpect(content().string(not(containsString("Fenster putzen"))));
    }

    @Test
    void userWithoutTodoRulesIsForbidden() throws Exception {
        userService.create("niemand", "passwort1", Set.of());

        mockMvc.perform(get("/todos").with(user("niemand"))).andExpect(status().isForbidden());
    }

    @Test
    void createsTodoFromForm() throws Exception {
        mockMvc.perform(post("/todos").with(csrf()).with(user("mama"))
                        .param("title", "Keller entrümpeln").param("priority", "HIGH")
                        .param("dueOn", today.plusDays(10).toString()).param("tags", "haus, keller")
                        .param("completionMode", "ANY"))
                .andExpect(status().is3xxRedirection());

        Todo t = todos.findAll().stream().filter(x -> x.getTitle().equals("Keller entrümpeln")).findFirst()
                .orElseThrow();
        assertThat(t.getTags()).containsExactlyInAnyOrder("haus", "keller");
    }

    @Test
    void invalidFormKeepsInput() throws Exception {
        mockMvc.perform(post("/todos").with(csrf()).with(user("mama"))
                        .param("title", "Kaputt").param("availableFrom", today.plusDays(5).toString())
                        .param("dueOn", today.toString()).param("completionMode", "ANY"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("darf nicht nach „bis“ liegen")))
                .andExpect(content().string(containsString("value=\"Kaputt\"")));
    }

    @Test
    void staleVersionKeepsInput() throws Exception {
        Todo t = todo("Fenster", mama, null, false);
        int stale = t.getVersion();
        todoService.update(t.getId(), new TodoInput("Fenster putzen", null, null, null, null, null, null, false,
                null, Set.of(), Set.of(), null), stale, mama);
        todos.flush();

        mockMvc.perform(post("/todos/{id}", t.getId()).with(csrf()).with(user("mama"))
                        .param("title", "Fenster streichen").param("version", String.valueOf(stale))
                        .param("completionMode", "ANY"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("inzwischen von mama geändert")))
                .andExpect(content().string(containsString("value=\"Fenster streichen\"")));
    }

    @Test
    void htmxCheckReturnsOnlyTheRow() throws Exception {
        Todo t = todo("Müll rausbringen", mama, null, false);

        String body = mockMvc.perform(post("/todos/{id}/check", t.getId()).with(csrf()).with(user("mama"))
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("todo-" + t.getId()).doesNotContain("<html");
        assertThat(todos.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TodoStatus.DONE);
    }

    @Test
    void foreignPrivateTodoIsNotFound() throws Exception {
        Todo secret = todo("Geheim", mama, null, true);

        mockMvc.perform(get("/todos/{id}", secret.getId()).with(user("papa"))).andExpect(status().isNotFound());
    }

    @Test
    void detailIsReadOnlyWithoutEditRight() throws Exception {
        Todo t = todo("Rasen mähen", mama, null, false);

        mockMvc.perform(get("/todos/{id}", t.getId()).with(user("papa")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rasen mähen")))
                .andExpect(content().string(not(containsString("/todos/" + t.getId() + "/edit"))));
        mockMvc.perform(get("/todos/{id}", t.getId()).with(user("mama")))
                .andExpect(content().string(containsString("/todos/" + t.getId() + "/edit")));
    }

    @Test
    void descriptionMarkdownIsSanitized() throws Exception {
        Todo t = todoService.create(new TodoInput("Markdown", "**fett** <script>alert(1)</script>", null, null,
                null, null, null, false, null, Set.of(), Set.of(), null), mama);

        mockMvc.perform(get("/todos/{id}", t.getId()).with(user("mama")))
                .andExpect(content().string(containsString("<strong>fett</strong>")))
                .andExpect(content().string(not(containsString("<script>alert(1)"))));
    }

    @Test
    void assigneeEditorWithoutEditRightCanStillReassign() throws Exception {
        Role eltern = roleService.create("Eltern", null, Set.of());
        acl.applyPreset(admin, eltern.getId(), "ELTERNTEIL");
        Role nurZuweisen = roleService.create("Nur zuweisen", null, Set.of());
        acl.replaceRules(admin, nurZuweisen.getId(), Set.of(
                new club.badernet.nestwerk.security.acl.AclRule("TODO", "VIEW", "ANY"),
                new club.badernet.nestwerk.security.acl.AclRule("TODO", "ASSIGN", "ANY")));
        userService.create("zuteiler", "passwort1", Set.of(nurZuweisen.getId()));
        Todo t = todo("Auto waschen", mama, null, false);

        mockMvc.perform(post("/todos/{id}", t.getId()).with(csrf()).with(user("zuteiler"))
                        .param("version", String.valueOf(t.getVersion()))
                        .param("assignees", "role:" + eltern.getId()).param("completionMode", "ANY"))
                .andExpect(status().is3xxRedirection());

        assertThat(todos.findById(t.getId()).orElseThrow().getTitle()).isEqualTo("Auto waschen");
    }

    @Test
    void contactsPageListsOpenTodosOfContact() throws Exception {
        var contact = new club.badernet.nestwerk.contact.ContactInput("Meier", null, null, null, null, null, null,
                null, null, null);
        var c = mockMvcContact(contact);
        todoService.create(new TodoInput("Steckdose", null, null, null, null, null, null, false, null, Set.of(),
                Set.of(club.badernet.nestwerk.todo.AssigneeRef.contact(c)), null), mama);

        mockMvc.perform(get("/contacts/{id}", c).with(user("mama")))
                .andExpect(content().string(containsString("Steckdose")));
    }

    @Autowired club.badernet.nestwerk.contact.ContactService contactService;

    private Long mockMvcContact(club.badernet.nestwerk.contact.ContactInput in) {
        return contactService.create(in, admin).getId();
    }
}
