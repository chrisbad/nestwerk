package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
class CalendarTodoTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 18);

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired TodoService todoService;
    @Autowired FamilyMemberRepository members;
    @Autowired CalendarEventRepository events;
    @Autowired JdbcTemplate jdbc;

    User mama;
    User papa;

    @BeforeEach
    void setUp() {
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        mama = userService.create("mama", "passwort1", Set.of(benutzer));
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(String name) {
        return user(name).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_VIEW"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_EVENT_CREATE"));
    }

    private Todo todo(String title, LocalDate due, boolean privat, AssigneeRef... refs) {
        return todoService.create(new TodoInput(title, null, null, null, due, null, null, privat, null, Set.of(),
                Set.of(refs), null), mama);
    }

    @Test
    void monthShowsVisibleDueTodos() throws Exception {
        Todo t = todo("Reifen wechseln", DAY, false);
        todo("Geschenk für Papa", DAY, true);

        mockMvc.perform(get("/calendar/month").param("date", DAY.toString()).with(as("papa")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("occ-todo")))
                .andExpect(content().string(containsString("/todos/" + t.getId())))
                .andExpect(content().string(containsString("Reifen wechseln")))
                .andExpect(content().string(not(containsString("Geschenk für Papa"))))
                .andExpect(content().string(containsString("data-toggle-todos")));
    }

    @Test
    void weekAndAgendaShowTodosToo() throws Exception {
        todo("Steuer abgeben", DAY, false);

        mockMvc.perform(get("/calendar/week").param("date", DAY.toString()).with(as("mama")))
                .andExpect(content().string(containsString("Steuer abgeben")));
        mockMvc.perform(get("/calendar/agenda").param("from", DAY.minusDays(1).toString()).with(as("mama")))
                .andExpect(content().string(containsString("Steuer abgeben")));
    }

    @Test
    void eventFromTodoIsPrefilledAndLinked() throws Exception {
        FamilyMember papaMember = members.save(new FamilyMember("Papa", "#00aa00", papa.getId()));
        Todo t = todo("Handwerker Bad", DAY, false, AssigneeRef.member(papaMember.getId()));

        mockMvc.perform(get("/calendar/events/new").param("todo", t.getId().toString()).with(as("mama")))
                .andExpect(content().string(containsString("value=\"Handwerker Bad\"")))
                .andExpect(content().string(containsString("value=\"" + DAY + "\"")))
                .andExpect(content().string(containsString("name=\"todoId\"")));

        mockMvc.perform(post("/calendar/events").with(csrf()).with(as("mama"))
                        .param("title", "Handwerker Bad").param("todoId", t.getId().toString())
                        .param("startDate", DAY.toString()).param("startTime", "09:00")
                        .param("endDate", DAY.toString()).param("endTime", "11:00")
                        .param("memberIds", papaMember.getId().toString()))
                .andExpect(status().is3xxRedirection());

        CalendarEvent created = events.findAll().stream().filter(e -> e.getTitle().equals("Handwerker Bad"))
                .findFirst().orElseThrow();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM todo_event_links WHERE todo_id = ? AND event_id = ?",
                Integer.class, t.getId(), created.getId())).isEqualTo(1);
        mockMvc.perform(get("/todos/{id}", t.getId()).with(as("mama")))
                .andExpect(content().string(containsString("/calendar/events/" + created.getId())));
        mockMvc.perform(get("/calendar/events/{id}", created.getId()).with(as("mama")))
                .andExpect(content().string(containsString("/todos/" + t.getId())));
    }

    @Test
    void linkingEventToTodoNeedsLinkRight() throws Exception {
        Todo t = todo("Mamas ToDo", DAY, false);

        mockMvc.perform(get("/calendar/events/new").param("todo", t.getId().toString()).with(as("papa")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/calendar/events").with(csrf()).with(as("papa"))
                        .param("title", "Untergeschoben").param("todoId", t.getId().toString())
                        .param("startDate", DAY.toString()).param("startTime", "09:00")
                        .param("endDate", DAY.toString()).param("endTime", "10:00"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM todo_event_links WHERE todo_id = ?", Integer.class,
                t.getId())).isZero();
        assertThat(events.findAll()).noneMatch(e -> e.getTitle().equals("Untergeschoben"));
        mockMvc.perform(get("/todos/{id}", t.getId()).with(as("papa")))
                .andExpect(content().string(not(containsString("/calendar/events/new?todo="))));
    }

    @Test
    void invisibleTodoCannotBeUsedForEvent() throws Exception {
        Todo secret = todo("Geheim", DAY, true);

        mockMvc.perform(get("/calendar/events/new").param("todo", secret.getId().toString()).with(as("papa")))
                .andExpect(status().isNotFound());
    }
}
