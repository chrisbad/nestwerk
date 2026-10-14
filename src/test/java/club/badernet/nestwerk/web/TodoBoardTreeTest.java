package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclRule;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.todo.TodoStructureService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
class TodoBoardTreeTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;
    @Autowired TodoService todoService;
    @Autowired TodoStructureService structure;
    @Autowired TodoRepository todos;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;

    User admin;
    User mama;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        mama = userService.create("mama", "passwort1",
                Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
    }

    private Todo todo(String title, boolean privat) {
        return todoService.create(new TodoInput(title, null, null, null, null, null, null, privat, null, Set.of(),
                Set.of(), null), mama);
    }

    @Test
    void boardShowsColumnsAndHidesOldDoneCards() throws Exception {
        todo("Offen bleibt", false);
        Todo working = todo("In Arbeit", false);
        todoService.take(working.getId(), mama);
        Todo recent = todo("Gerade erledigt", false);
        todoService.check(recent.getId(), mama);
        Todo old = todo("Längst erledigt", false);
        todoService.check(old.getId(), mama);
        todos.flush();
        jdbc.update("UPDATE todos SET completed_at = now() - interval '30 days' WHERE id = ?", old.getId());
        entityManager.clear();

        mockMvc.perform(get("/todos/board").with(user("mama")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-board-column=\"OPEN\"")))
                .andExpect(content().string(containsString("Offen bleibt")))
                .andExpect(content().string(containsString("Gerade erledigt")))
                .andExpect(content().string(not(containsString("Längst erledigt"))));
    }

    @Test
    void boardMoveChangesStatus() throws Exception {
        Todo t = todo("Rasen mähen", false);

        mockMvc.perform(post("/todos/{id}/status", t.getId()).param("status", "IN_PROGRESS")
                        .header("HX-Request", "true").with(csrf()).with(user("mama")))
                .andExpect(status().isNoContent());

        assertThat(todos.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
    }

    @Test
    void boardMoveWithoutRightIsRejected() throws Exception {
        Role nurLesen = roleService.create("Nur lesen", null, Set.of());
        acl.replaceRules(admin, nurLesen.getId(), Set.of(new AclRule("TODO", "VIEW", "ANY")));
        userService.create("leser", "passwort1", Set.of(nurLesen.getId()));
        Todo t = todo("Fremd", false);

        mockMvc.perform(post("/todos/{id}/status", t.getId()).param("status", "DONE")
                        .header("HX-Request", "true").with(csrf()).with(user("leser")))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("Berechtigung")));

        assertThat(todos.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TodoStatus.OPEN);
    }

    @Test
    void treeShowsRootsAndLoadsChildrenLazily() throws Exception {
        Todo haus = todo("Haus", false);
        Todo bad = structure.createChild(haus.getId(), new TodoInput("Bad", null, null, null, null, null, null,
                false, null, Set.of(), Set.of(), null), mama);
        structure.createChild(haus.getId(), new TodoInput("Geheim", null, null, null, null, null, null,
                true, null, Set.of(), Set.of(), null), admin);

        mockMvc.perform(get("/todos/tree").with(user("mama")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Haus")))
                .andExpect(content().string(containsString("/todos/" + haus.getId() + "/children")))
                .andExpect(content().string(not(containsString(">Bad<"))));
        mockMvc.perform(get("/todos/{id}/children", haus.getId()).with(user("mama")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bad")))
                .andExpect(content().string(containsString("1 weitere")))
                .andExpect(content().string(not(containsString("<html"))));
        assertThat(bad.getParentId()).isEqualTo(haus.getId());
    }

    @Test
    void viewSwitcherKeepsFilters() throws Exception {
        todo("Irgendwas", false);

        mockMvc.perform(get("/todos").param("involvement", "MINE").with(user("mama")))
                .andExpect(content().string(containsString("/todos/board?involvement=MINE")));
    }
}
