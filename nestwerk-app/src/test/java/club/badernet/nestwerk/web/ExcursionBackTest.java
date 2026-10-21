package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.RoleRepository;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class ExcursionBackTest {

    private static final String ORIGIN = "/calendar/week?date=2026-10-21";

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;

    @BeforeEach
    void setUp() {
        userService.create("manager", "passwort1", Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
    }

    private static RequestPostProcessor manager() {
        return user("manager").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"),
                new SimpleGrantedAuthority("CALENDAR_CATEGORY_MANAGE"));
    }

    @Test
    void categoriesLeadBackToTheOriginAndKeepItInForms() throws Exception {
        mockMvc.perform(get("/calendar/categories").param("back", ORIGIN).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"" + ORIGIN + "\"")))
                .andExpect(content().string(containsString("name=\"back\"")))
                .andExpect(content().string(containsString("Kalender")));
    }

    @Test
    void categoriesWithoutOriginFallBackToTheCalendar() throws Exception {
        mockMvc.perform(get("/calendar/categories").with(manager()))
                .andExpect(content().string(containsString("data-back-fallback=\"calendar\"")))
                .andExpect(content().string(not(containsString("name=\"back\""))));
    }

    @Test
    void foreignOriginsAreIgnored() throws Exception {
        mockMvc.perform(get("/calendar/categories").param("back", "//evil.example").with(manager()))
                .andExpect(content().string(not(containsString("evil.example"))));
    }

    @Test
    void savingKeepsTheOriginOnTheCategoriesPage() throws Exception {
        mockMvc.perform(post("/calendar/categories").param("name", "Sport").param("back", ORIGIN)
                        .with(manager()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/calendar/categories?back=%2Fcalendar%2Fweek%3Fdate%3D2026-10-21"));
    }

    @Test
    void savingWithoutOriginStaysOnThePlainCategoriesPage() throws Exception {
        mockMvc.perform(post("/calendar/categories").param("name", "Musik").with(manager()).with(csrf()))
                .andExpect(redirectedUrl("/calendar/categories"));
    }
}
