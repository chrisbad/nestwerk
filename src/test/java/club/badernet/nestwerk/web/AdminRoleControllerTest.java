package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class AdminRoleControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired RoleRepository roles;

    @Test
    @WithMockUser(authorities = "USER_MANAGE")
    void forbiddenWithoutRolePermission() throws Exception {
        mockMvc.perform(get("/admin/roles")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void createsRoleFromForm() throws Exception {
        mockMvc.perform(post("/admin/roles").with(csrf())
                        .param("name", "Teenager")
                        .param("description", "Mit Einschränkungen")
                        .param("permissions", "MEMBER_MANAGE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));

        assertThat(roles.findByNameIgnoreCase("Teenager")).isPresent();
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void showsErrorForDuplicateName() throws Exception {
        mockMvc.perform(post("/admin/roles").with(csrf()).param("name", "Benutzer"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void rendersRolesWithPermissionChoices() throws Exception {
        mockMvc.perform(get("/admin/roles"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Administrator")))
                .andExpect(content().string(containsString("value=\"MEMBER_MANAGE\"")));
    }
}
