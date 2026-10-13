package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
class CalendarEditTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired CalendarEventRepository repository;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Editor", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN"));
        userService.create("editor", "passwort1", Set.of(normal.getId()));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor editor() {
        return user("editor").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"));
    }

    @Test
    void createsEventFromForm() throws Exception {
        mockMvc.perform(post("/calendar/events").with(csrf()).with(editor())
                        .param("title", "Elternabend")
                        .param("startDate", "2026-10-20").param("startTime", "19:00")
                        .param("endDate", "2026-10-20").param("endTime", "21:00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));

        List<CalendarEvent> all = repository.findAll();
        assertThat(all).extracting(CalendarEvent::getTitle).contains("Elternabend");
    }

    @Test
    void invalidFormRerendersWithErrorAndKeepsInput() throws Exception {
        mockMvc.perform(post("/calendar/events").with(csrf()).with(editor())
                        .param("title", "Kaputt")
                        .param("startDate", "2026-10-20").param("startTime", "19:00")
                        .param("endDate", "2026-10-20").param("endTime", "18:00"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Das Ende muss nach dem Beginn liegen.")))
                .andExpect(content().string(containsString("Kaputt")));
    }

    @Test
    void newFormIsShown() throws Exception {
        mockMvc.perform(get("/calendar/events/new").param("date", "2026-10-20").with(editor()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2026-10-20")));
    }

    @Test
    void unknownEventIsNotFound() throws Exception {
        mockMvc.perform(get("/calendar/events/{id}", 999_999).with(editor()))
                .andExpect(status().isNotFound());
    }

    @Test
    void seriesOccurrenceCanBeCancelled() throws Exception {
        mockMvc.perform(post("/calendar/events").with(csrf()).with(editor())
                .param("title", "Training").param("rrule", "FREQ=WEEKLY")
                .param("startDate", "2026-10-05").param("startTime", "18:00")
                .param("endDate", "2026-10-05").param("endTime", "19:00"));
        CalendarEvent e = repository.findAll().stream()
                .filter(x -> x.getTitle().equals("Training")).findFirst().orElseThrow();

        mockMvc.perform(post("/calendar/events/{id}/occurrence/cancel", e.getId()).with(csrf()).with(editor())
                        .param("recurrenceId", "2026-10-12T16:00:00Z"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));

        assertThat(repository.findById(e.getId()).orElseThrow().getExceptions()).hasSize(1);
    }

    @Test
    void deleteRemovesEvent() throws Exception {
        mockMvc.perform(post("/calendar/events").with(csrf()).with(editor())
                .param("title", "Weg damit")
                .param("startDate", "2026-10-21").param("startTime", "10:00")
                .param("endDate", "2026-10-21").param("endTime", "11:00"));
        CalendarEvent e = repository.findAll().stream()
                .filter(x -> x.getTitle().equals("Weg damit")).findFirst().orElseThrow();

        mockMvc.perform(post("/calendar/events/{id}/delete", e.getId()).with(csrf()).with(editor()))
                .andExpect(status().is3xxRedirection());

        assertThat(repository.findById(e.getId())).isEmpty();
    }
}
