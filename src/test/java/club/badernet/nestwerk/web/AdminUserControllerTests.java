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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@WithMockUser(authorities = "USER_MANAGE")
class AdminUserControllerTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void classicPostRedirectsToList() throws Exception {
        mockMvc.perform(post("/admin/users").with(csrf())
                        .param("username", "klassisch")
                        .param("password", "geheim1234"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"));
    }

    @Test
    void htmxPostReturnsOnlyThePanel() throws Exception {
        mockMvc.perform(post("/admin/users").with(csrf())
                        .header("HX-Request", "true")
                        .param("username", "htmx-kind")
                        .param("password", "geheim1234"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"users-panel\"")))
                .andExpect(content().string(containsString("htmx-kind")))
                .andExpect(content().string(containsString("data-toast=\"success\"")))
                .andExpect(content().string(not(containsString("<html"))));
    }

    @Test
    void htmxPostWithShortPasswordReturnsError() throws Exception {
        mockMvc.perform(post("/admin/users").with(csrf())
                        .header("HX-Request", "true")
                        .param("username", "zukurz")
                        .param("password", "kurz"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-form-error")))
                .andExpect(content().string(containsString("8 Zeichen")));
    }
}
