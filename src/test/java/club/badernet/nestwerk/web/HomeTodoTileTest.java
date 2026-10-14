package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoService;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class HomeTodoTileTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired TodoService todoService;
    @Autowired Clock clock;

    User mama;
    User papa;
    LocalDate today;

    @BeforeEach
    void setUp() {
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        mama = userService.create("mama", "passwort1", Set.of(benutzer));
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
        today = LocalDate.now(clock);
    }

    private void todo(String title, User owner, LocalDate from, LocalDate due, boolean privat) {
        todoService.create(new TodoInput(title, null, null, from, due, null, null, privat, null, Set.of(), Set.of(),
                null), owner);
    }

    @Test
    void showsOwnUrgentTodosOnly() throws Exception {
        todo("Steuer abgeben", mama, null, today.minusDays(1), false);
        todo("Später einmal", mama, today.plusDays(5), null, false);
        todo("Papas Sache", papa, null, today, false);
        todo("Geheimnis", papa, null, today, true);

        mockMvc.perform(get("/").with(user("mama")))
                .andExpect(content().string(containsString("Meine ToDos")))
                .andExpect(content().string(containsString("Steuer abgeben")))
                .andExpect(content().string(not(containsString("Später einmal"))))
                .andExpect(content().string(not(containsString("Papas Sache"))))
                .andExpect(content().string(not(containsString("Geheimnis"))));
    }

    @Test
    void tileIsHiddenWithoutTodoAccess() throws Exception {
        userService.create("niemand", "passwort1", Set.of());

        mockMvc.perform(get("/").with(user("niemand")))
                .andExpect(content().string(not(containsString("Meine ToDos"))));
    }
}
