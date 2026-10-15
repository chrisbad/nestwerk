package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class McpEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired AiTokenService tokens;
    @Autowired UserService userService;
    @Autowired RoleService roleService;

    User mama;
    String token;

    @BeforeEach
    void setUp() {
        Role ki = roleService.create("KI-Nutzer", null, Set.of("AI_ACCESS", "CALENDAR_VIEW"));
        mama = userService.create("mama", "passwort1", Set.of(ki.getId()));
        token = tokens.create("Claude", null, mama).plaintext();
    }

    private ResultActions rpc(String bearer, String method, String params) throws Exception {
        var request = post("/mcp").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mockMvc.perform(request);
    }

    @Test
    void rejectsRequestsWithoutValidToken() throws Exception {
        rpc(null, "tools/list", "{}").andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
        rpc("nw_falsch", "tools/list", "{}").andExpect(status().isUnauthorized());
    }

    @Test
    void initializesAndListsTools() throws Exception {
        rpc(token, "initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.serverInfo.name").value("nestwerk"));
        rpc(token, "tools/list", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[*].name").value(hasItems("whoami", "list_members")));
    }

    @Test
    void toolRunsAsTokenOwner() throws Exception {
        rpc(token, "tools/call", "{\"name\":\"whoami\",\"arguments\":{}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text").value(containsString("\"username\":\"mama\"")));
    }

    @Test
    void revokedTokenIsRejected() throws Exception {
        tokens.revoke(tokens.mine(mama).getFirst().getId(), mama);

        rpc(token, "tools/list", "{}").andExpect(status().isUnauthorized());
    }

    @Test
    void webPagesStillUseFormLogin() throws Exception {
        mockMvc.perform(get("/")).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/").header("Authorization", "Bearer " + token))
                .andExpect(redirectedUrl("/login"));
    }
}
