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
class ManualTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @WithMockUser
    void everyoneSeesTheManualAndTheMenuEntry() throws Exception {
        mockMvc.perform(get("/manual"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Erste Schritte")))
                .andExpect(content().string(containsString("Essensplan")))
                .andExpect(content().string(containsString("href=\"/manual\"")))
                .andExpect(content().string(not(containsString("Sicherungen</h3>"))));
    }

    @Test
    @WithMockUser(authorities = "BACKUP_MANAGE")
    void administrationChapterAppearsForAdministrators() throws Exception {
        mockMvc.perform(get("/manual"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sicherungen</h3>")));
    }

    @Test
    void anonymousUsersAreSentToTheLogin() throws Exception {
        mockMvc.perform(get("/manual")).andExpect(status().is3xxRedirection());
    }
}
