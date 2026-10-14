package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoLinkType;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStructureService;
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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class TodoStructureControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired TodoService todoService;
    @Autowired TodoStructureService structure;
    @Autowired TodoRepository todos;

    User mama;

    @BeforeEach
    void setUp() {
        mama = userService.create("mama", "passwort1",
                Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
    }

    private Todo todo(String title) {
        return todoService.create(new TodoInput(title, null, null, null, null, null, null, false, null, Set.of(),
                Set.of(), null), mama);
    }

    @Test
    void createsSubtaskFromForm() throws Exception {
        Todo keller = todo("Keller entrümpeln");

        mockMvc.perform(get("/todos/new").param("parent", keller.getId().toString()).with(user("mama")))
                .andExpect(content().string(containsString("Unteraufgabe von")));
        mockMvc.perform(post("/todos").with(csrf()).with(user("mama"))
                        .param("title", "Regal abbauen").param("parentId", keller.getId().toString())
                        .param("completionMode", "ANY"))
                .andExpect(status().is3xxRedirection());

        assertThat(todos.findAllByParentId(keller.getId())).extracting(Todo::getTitle).containsExactly("Regal abbauen");
    }

    @Test
    void detailShowsChildrenBreadcrumbAndLinks() throws Exception {
        Todo haus = todo("Haus renovieren");
        Todo bad = structure.createChild(haus.getId(), new TodoInput("Bad", null, null, null, null, null, null,
                false, null, Set.of(), Set.of(), null), mama);
        Todo estrich = todo("Estrich trocknen");
        structure.link(estrich.getId(), bad.getId(), TodoLinkType.BLOCKS, mama);

        mockMvc.perform(get("/todos/{id}", haus.getId()).with(user("mama")))
                .andExpect(content().string(containsString("Unteraufgaben")))
                .andExpect(content().string(containsString("Bad")));
        mockMvc.perform(get("/todos/{id}", bad.getId()).with(user("mama")))
                .andExpect(content().string(containsString("Haus renovieren")))
                .andExpect(content().string(containsString("wird blockiert von")))
                .andExpect(content().string(containsString("Estrich trocknen")))
                .andExpect(content().string(containsString("Trotzdem erledigen")));
    }

    @Test
    void addsAndRemovesLinkByHashId() throws Exception {
        Todo a = todo("Fliesen legen");
        Todo b = todo("Fliesen kaufen");

        mockMvc.perform(post("/todos/{id}/links", a.getId()).with(csrf()).with(user("mama"))
                        .param("type", "RELATES").param("target", "#" + b.getId() + " Fliesen kaufen"))
                .andExpect(flash().attributeExists("success"));
        Long linkId = structure.links(a.getId(), mama).getFirst().linkId();

        mockMvc.perform(post("/todos/{id}/links/{link}/delete", a.getId(), linkId).with(csrf()).with(user("mama")))
                .andExpect(flash().attributeExists("success"));
        assertThat(structure.links(a.getId(), mama)).isEmpty();
    }

    @Test
    void invalidLinkTargetShowsError() throws Exception {
        Todo a = todo("A");

        mockMvc.perform(post("/todos/{id}/links", a.getId()).with(csrf()).with(user("mama"))
                        .param("type", "BLOCKS").param("target", "irgendwas"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void parentRelationCanBeSetViaLinkForm() throws Exception {
        Todo parent = todo("Umzug");
        Todo child = todo("Kartons");

        mockMvc.perform(post("/todos/{id}/links", child.getId()).with(csrf()).with(user("mama"))
                        .param("type", "PARENT").param("target", "#" + parent.getId()))
                .andExpect(flash().attributeExists("success"));

        assertThat(todos.findById(child.getId()).orElseThrow().getParentId()).isEqualTo(parent.getId());
    }

    @Test
    void recurringTodoShowsRepeatInForm() throws Exception {
        mockMvc.perform(post("/todos").with(csrf()).with(user("mama"))
                        .param("title", "Rauchmelder").param("rrule", "FREQ=YEARLY").param("completionMode", "ANY"))
                .andExpect(status().is3xxRedirection());

        Todo t = todos.findAll().stream().filter(x -> x.getTitle().equals("Rauchmelder")).findFirst().orElseThrow();
        assertThat(t.getRrule()).isEqualTo("FREQ=YEARLY");
        mockMvc.perform(get("/todos/{id}", t.getId()).with(user("mama")))
                .andExpect(content().string(containsString("Jährlich")));
    }
}
