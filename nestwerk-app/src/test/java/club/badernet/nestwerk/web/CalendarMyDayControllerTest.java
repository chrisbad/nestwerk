package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.AssigneeRef;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class CalendarMyDayControllerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 18);

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired FamilyMemberRepository members;
    @Autowired CalendarEventService events;
    @Autowired TodoService todoService;
    @Autowired CalendarPageData pageData;

    User mama;
    User papa;
    FamilyMember mamaMember;
    FamilyMember papaMember;

    @BeforeEach
    void setUp() {
        Long normal = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        mama = userService.create("mama", "passwort1", Set.of(normal));
        papa = userService.create("papa", "passwort1", Set.of(normal));
        mamaMember = members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        papaMember = members.save(new FamilyMember("Papa", "#00aa00", papa.getId()));
    }

    private static RequestPostProcessor as(String name) {
        return user(name).authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"));
    }

    private void event(String title, LocalDate day, Set<Long> memberIds) {
        events.create(new EventInput(title, null, null, day, LocalTime.of(10, 0), day, LocalTime.of(11, 0), false,
                null, false, memberIds), mama);
    }

    private void todo(String title, LocalDate due, AssigneeRef assignee) {
        todoService.create(new TodoInput(title, null, null, null, due, null, null, false, null, Set.of(),
                Set.of(assignee), null), mama);
    }

    @Test
    void calendarRootLeadsToThePersonalDay() throws Exception {
        mockMvc.perform(get("/calendar").with(as("papa")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/calendar/my"));
    }

    @Test
    void showsOwnEventsButNotThoseOfOthers() throws Exception {
        event("Zahnarzt Papa", DAY, Set.of(papaMember.getId()));
        event("Elternabend Mama", DAY, Set.of(mamaMember.getId()));

        mockMvc.perform(get("/calendar/my").param("date", DAY.toString()).with(as("papa")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zahnarzt Papa")))
                .andExpect(content().string(not(containsString("Elternabend Mama"))));
    }

    @Test
    void showsEventsWithoutMembersOnlyToTheirCreator() throws Exception {
        event("Ohne Personen", DAY, Set.of());

        mockMvc.perform(get("/calendar/my").param("date", DAY.toString()).with(as("mama")))
                .andExpect(content().string(containsString("Ohne Personen")));
        mockMvc.perform(get("/calendar/my").param("date", DAY.toString()).with(as("papa")))
                .andExpect(content().string(not(containsString("Ohne Personen"))));
    }

    @Test
    void showsAssignedTodosDueOnTheDay() throws Exception {
        todo("Reifen wechseln", DAY, AssigneeRef.member(papaMember.getId()));
        todo("Steuer abgeben", DAY, AssigneeRef.member(mamaMember.getId()));

        mockMvc.perform(get("/calendar/my").param("date", DAY.toString()).with(as("papa")))
                .andExpect(content().string(containsString("Reifen wechseln")))
                .andExpect(content().string(not(containsString("Steuer abgeben"))));
    }

    @Test
    void listsOverdueTodosOnlyOnToday() throws Exception {
        LocalDate today = pageData.today();
        todo("Längst fällig", today.minusDays(3), AssigneeRef.member(papaMember.getId()));

        mockMvc.perform(get("/calendar/my").param("date", today.toString()).with(as("papa")))
                .andExpect(content().string(containsString("Überfällig")))
                .andExpect(content().string(containsString("Längst fällig")));
        mockMvc.perform(get("/calendar/my").param("date", today.plusDays(1).toString()).with(as("papa")))
                .andExpect(content().string(not(containsString("Längst fällig"))));
    }

    @Test
    void hintsAtAMissingMemberLink() throws Exception {
        userService.create("gast", "passwort1", Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));

        mockMvc.perform(get("/calendar/my").param("date", DAY.toString()).with(as("gast")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("keinem Familienmitglied zugeordnet")));
    }

    @Test
    void isForbiddenWithoutCalendarPermission() throws Exception {
        mockMvc.perform(get("/calendar/my").with(user("papa").authorities(new SimpleGrantedAuthority("MEMBER_MANAGE"))))
                .andExpect(status().isForbidden());
    }
}
