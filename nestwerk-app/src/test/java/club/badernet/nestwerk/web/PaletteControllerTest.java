package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

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
class PaletteControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;

    @BeforeEach
    void setUp() {
        Role viewers = roleService.create("Kalenderfreunde", null, Set.of("CALENDAR_VIEW"));
        userService.create("kalender", "passwort1", Set.of(viewers.getId()));
    }

    private static RequestPostProcessor calendarUser() {
        return user("kalender").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"));
    }

    @Test
    void offersAreasActionsAndSettingsTheUserMayUse() throws Exception {
        mockMvc.perform(get("/palette").with(calendarUser()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-path=\"/calendar\"")))
                .andExpect(content().string(containsString("Zeit (Arbeit, Schule, Ruhe)")))
                .andExpect(content().string(containsString("Zeiten und Ruhe-Ziele")))
                .andExpect(content().string(containsString("Meine Benachrichtigungswege")));
    }

    @Test
    void hidesWhatTheUserMayNotUse() throws Exception {
        mockMvc.perform(get("/palette").with(calendarUser()))
                .andExpect(content().string(not(containsString("/admin/users"))))
                .andExpect(content().string(not(containsString("Terminkategorien"))))
                .andExpect(content().string(not(containsString("data-path=\"/calendar/events/new\""))));
    }

    @Test
    void creatingAndSettingsAreExcursionsButAreasAreNot() throws Exception {
        String page = mockMvc.perform(get("/palette").with(calendarUser())).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(page)
                .contains("data-path=\"/calendar\"")
                .containsPattern("data-excursion=\"false\"[^>]*>|data-path=\"/calendar\"[^>]*data-excursion=\"false\"")
                .containsPattern("data-path=\"/times/new\"[^>]*data-excursion=\"true\"|data-excursion=\"true\"[^>]*data-path=\"/times/new\"");
    }

    @Test
    void isOnlyForSignedInUsers() throws Exception {
        mockMvc.perform(get("/palette")).andExpect(status().is3xxRedirection());
    }

    @Test
    void layoutOffersPlusMenuAndPaletteTrigger() throws Exception {
        mockMvc.perform(get("/manual").with(calendarUser()))
                .andExpect(content().string(containsString("data-open-cmd")))
                .andExpect(content().string(containsString("quick-menu")))
                .andExpect(content().string(containsString("href=\"/times/new\"")))
                .andExpect(content().string(not(containsString("href=\"/calendar/events/new\""))));
    }
}
