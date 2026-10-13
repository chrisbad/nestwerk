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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
class SecurityAccessTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginPageIsPublic() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotAccessAdminArea() throws Exception {
        mockMvc.perform(get("/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanAccessAdminArea() throws Exception {
        mockMvc.perform(get("/admin/users")).andExpect(status().isOk());
    }

    @Test
    void staticAssetsArePublic() throws Exception {
        mockMvc.perform(get("/css/nestwerk.css")).andExpect(status().isOk());
        mockMvc.perform(get("/js/nestwerk.js")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userSeesLayoutWithoutAdminLinks() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"topnav\"")))
                .andExpect(content().string(not(containsString("/admin/"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminSeesUserManagementLink() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/users")));
    }
}
