package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class CalendarViewTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired CalendarEventService events;

    User owner;
    User other;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Kalendernutzer", null, Set.of("CALENDAR_VIEW",
                "CALENDAR_EVENT_CREATE", "CALENDAR_PRIVATE_CREATE", "CALENDAR_EVENT_EDIT_OWN"));
        owner = userService.create("kal-besitzer", "passwort1", Set.of(normal.getId()));
        other = userService.create("kal-anderer", "passwort1", Set.of(normal.getId()));
        events.create(new EventInput("Zahnarzt", null, null, LocalDate.of(2026, 10, 12), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 12), LocalTime.of(11, 0), false, null, false, Set.of()), owner);
        events.create(new EventInput("Geheimtermin", null, null, LocalDate.of(2026, 10, 13), LocalTime.of(10, 0),
                LocalDate.of(2026, 10, 13), LocalTime.of(11, 0), false, null, true, Set.of()), owner);
    }

    @Test
    void monthViewShowsEventsForOwner() throws Exception {
        mockMvc.perform(get("/calendar/month").param("date", "2026-10-15")
                        .with(user("kal-besitzer").authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zahnarzt")))
                .andExpect(content().string(containsString("Geheimtermin")));
    }

    @Test
    void privateEventIsHiddenFromOtherUserInAllViews() throws Exception {
        var auth = new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_VIEW");
        for (String path : new String[]{"/calendar/month", "/calendar/week", "/calendar/agenda"}) {
            var param = path.endsWith("agenda") ? "from" : "date";
            mockMvc.perform(get(path).param(param, "2026-10-12").with(user("kal-anderer").authorities(auth)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Zahnarzt")))
                    .andExpect(content().string(not(containsString("Geheimtermin"))));
        }
    }

    @Test
    void invalidDateFallsBackToToday() throws Exception {
        mockMvc.perform(get("/calendar/month").param("date", "abc")
                        .with(user("kal-besitzer").authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(status().isOk());
    }

    @Test
    void forbiddenWithoutViewPermission() throws Exception {
        mockMvc.perform(get("/calendar/month").with(user("kal-besitzer").authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("MEMBER_MANAGE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void calendarRedirectsToMonth() throws Exception {
        mockMvc.perform(get("/calendar").with(user("kal-besitzer").authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(status().is3xxRedirection());
    }
}
