package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
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
class AdminMemberControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired FamilyMemberService members;

    @Test
    @WithMockUser(authorities = "USER_MANAGE")
    void forbiddenWithoutMemberPermission() throws Exception {
        mockMvc.perform(get("/admin/members")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "MEMBER_MANAGE")
    void createsMemberAndListsIt() throws Exception {
        mockMvc.perform(post("/admin/members").with(csrf()).param("name", "Oma Inge").param("color", "#aa5500"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));

        assertThat(members.findAll()).extracting(FamilyMember::getName).contains("Oma Inge");
        mockMvc.perform(get("/admin/members"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Oma Inge")))
                .andExpect(content().string(containsString("#aa5500")));
    }

    @Test
    @WithMockUser(authorities = "MEMBER_MANAGE")
    void invalidColorShowsError() throws Exception {
        mockMvc.perform(post("/admin/members").with(csrf()).param("name", "X").param("color", "rot"))
                .andExpect(flash().attributeExists("error"));
    }
}
