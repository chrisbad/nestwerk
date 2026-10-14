package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.security.PermissionDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class AdminPermissionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired PermissionCatalog catalog;

    private String label(String key) {
        return catalog.all().stream().filter(p -> p.key().equals(key)).findFirst()
                .map(PermissionDefinition::label).orElseThrow();
    }

    @Test
    @WithMockUser(authorities = "USER_MANAGE")
    void forbiddenWithoutRolePermission() throws Exception {
        mockMvc.perform(get("/admin/permissions")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void listsPermissionsByModule() throws Exception {
        mockMvc.perform(get("/admin/permissions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Verwaltung")))
                .andExpect(content().string(containsString("Kalender ansehen")));
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void renamesPermissionAndRolePageShowsNewLabel() throws Exception {
        mockMvc.perform(post("/admin/permissions/{key}", "CALENDAR_VIEW").with(csrf())
                        .param("label", "Familienkalender lesen").param("description", "Nur ansehen"))
                .andExpect(flash().attributeExists("success"));

        assertThat(label("CALENDAR_VIEW")).isEqualTo("Familienkalender lesen");
        mockMvc.perform(get("/admin/roles"))
                .andExpect(content().string(containsString("Familienkalender lesen")));
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void blankLabelIsRejected() throws Exception {
        mockMvc.perform(post("/admin/permissions/{key}", "CALENDAR_VIEW").with(csrf()).param("label", "  "))
                .andExpect(flash().attributeExists("error"));

        assertThat(label("CALENDAR_VIEW")).isEqualTo("Kalender ansehen");
    }

    @Test
    @WithMockUser(authorities = "ACCESS_ROLE_MANAGE")
    void unknownKeyIsRejected() throws Exception {
        mockMvc.perform(post("/admin/permissions/{key}", "GIBT_ES_NICHT").with(csrf()).param("label", "x"))
                .andExpect(flash().attributeExists("error"));
    }
}
