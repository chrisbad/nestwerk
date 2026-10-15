package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.AiToken;
import club.badernet.nestwerk.ai.AiTokenService;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw",
        "nestwerk.base-url=https://nest.example.org/"})
@AutoConfigureMockMvc
@Transactional
class AiTokenControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired AiTokenService tokens;
    @Autowired UserService userService;
    @Autowired RoleService roleService;

    User mama;

    @BeforeEach
    void setUp() {
        Role ki = roleService.create("KI-Nutzer", null, Set.of("AI_ACCESS"));
        mama = userService.create("mama", "passwort1", Set.of(ki.getId()));
        userService.create("kind", "passwort1", Set.of());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor mama() {
        return user("mama").authorities(new SimpleGrantedAuthority("AI_ACCESS"));
    }

    @Test
    void pageShowsTokensAndConnectionHelp() throws Exception {
        tokens.create("Laptop", null, mama);

        mockMvc.perform(get("/settings/ai").with(mama()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Laptop")))
                .andExpect(content().string(containsString("https://nest.example.org/mcp")))
                .andExpect(content().string(containsString("KI-Zugänge</a>")));
    }

    @Test
    void createsTokenAndShowsItOnce() throws Exception {
        MvcResult result = mockMvc.perform(post("/settings/ai").with(csrf()).with(mama()).param("name", "Claude"))
                .andExpect(flash().attributeExists("newToken"))
                .andReturn();
        String plaintext = (String) result.getFlashMap().get("newToken");

        assertThat(tokens.authenticate(plaintext)).map(User::getUsername).contains("mama");
        mockMvc.perform(get("/settings/ai").with(mama()))
                .andExpect(content().string(not(containsString(plaintext))));
    }

    @Test
    void rejectsExpiryInThePast() throws Exception {
        mockMvc.perform(post("/settings/ai").with(csrf()).with(mama()).param("name", "Alt")
                        .param("expiresOn", "2000-01-01"))
                .andExpect(flash().attributeExists("error"));

        assertThat(tokens.mine(mama)).isEmpty();
    }

    @Test
    void revokesOwnToken() throws Exception {
        AiTokenService.Issued issued = tokens.create("Claude", null, mama);

        mockMvc.perform(post("/settings/ai/{id}/revoke", issued.token().getId()).with(csrf()).with(mama()))
                .andExpect(flash().attributeExists("success"));

        assertThat(tokens.mine(mama)).extracting(AiToken::getRevokedAt).doesNotContainNull();
        assertThat(tokens.authenticate(issued.plaintext())).isEmpty();
    }

    @Test
    void withoutPermissionThereIsNoPageAndNoMenuEntry() throws Exception {
        mockMvc.perform(get("/settings/ai").with(user("kind"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/").with(user("kind")))
                .andExpect(content().string(not(containsString("KI-Zugänge"))));
    }
}
