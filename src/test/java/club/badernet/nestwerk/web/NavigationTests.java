package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
class NavigationTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    @WithMockUser(authorities = "USER_MANAGE")
    void showsOnlyPermittedMenuEntries() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"topnav\"")))
                .andExpect(content().string(containsString("/admin/users")))
                .andExpect(content().string(not(containsString("/admin/roles"))))
                .andExpect(content().string(not(containsString("/admin/members"))));
    }

    @Test
    @WithMockUser(authorities = {"ACCESS_ROLE_MANAGE", "MEMBER_MANAGE"})
    void showsRoleAndMemberEntriesWithTheirPermissions() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/roles")))
                .andExpect(content().string(containsString("/admin/members")))
                .andExpect(content().string(not(containsString("/admin/users"))));
    }

    @Test
    @WithMockUser
    void userWithoutPermissionsSeesNoAdminEntries() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/admin/"))));
    }

    @Test
    @WithMockUser(authorities = "MEMBER_MANAGE")
    void homeShowsAreaForEachPermittedSection() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"area\" href=\"/admin/members\"")))
                .andExpect(content().string(not(containsString("href=\"/admin/users\""))));
    }

    @Test
    @WithMockUser(authorities = "CALENDAR_VIEW")
    void calendarIsReachableFromNavigationAndHome() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<h2>Kalender</h2>")))
                .andExpect(content().string(containsString("href=\"/calendar\"")));
    }
}
