package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpOAuthService;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class McpOAuthTest {

    private static final String REDIRECT = "http://localhost:33418/callback";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk-nestwerk-test";

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired McpOAuthService oauth;

    User mama;

    @BeforeEach
    void setUp() {
        Role ki = roleService.create("KI-Nutzer", null, Set.of("AI_ACCESS", "CALENDAR_VIEW"));
        mama = userService.create("mama", "passwort1", Set.of(ki.getId()));
        userService.create("kind", "passwort1", Set.of());
    }

    @Test
    void unauthenticatedMcpPointsToMetadata() throws Exception {
        mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString(
                        "resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"")));

        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("http://localhost"))
                .andExpect(jsonPath("$.scopes_supported[0]").value("mcp"));

        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost"))
                .andExpect(jsonPath("$.registration_endpoint").value("http://localhost/oauth2/register"))
                .andExpect(jsonPath("$.authorization_endpoint").value("http://localhost/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value("http://localhost/oauth2/token"))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported").value(hasItem("none")))
                .andExpect(jsonPath("$.code_challenge_methods_supported").value(hasItem("S256")));
    }

    @Test
    void fullLoginFlowConnectsAssistant() throws Exception {
        String clientId = register("Claude Code", REDIRECT, "none");

        mockMvc.perform(get("/oauth2/authorize").queryParams(authorizeParams(clientId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        String code = authorizeWithConsent(clientId, "mama");

        String tokenResponse = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT)
                        .param("client_id", clientId)
                        .param("code_verifier", VERIFIER)
                        .param("resource", "http://localhost/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("mcp"))
                .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(tokenResponse, "$.access_token");
        String refreshToken = JsonPath.read(tokenResponse, "$.refresh_token");

        whoami(accessToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text").value(containsString("\"username\":\"mama\"")));

        String refreshed = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("client_id", clientId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newAccessToken = JsonPath.read(refreshed, "$.access_token");
        assertThat((String) JsonPath.read(refreshed, "$.refresh_token")).isNotEqualTo(refreshToken);
        whoami(accessToken).andExpect(status().isUnauthorized());
        whoami(newAccessToken).andExpect(status().isOk());

        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("client_id", clientId))
                .andExpect(status().isBadRequest());

        assertThat(oauth.connections(mama)).extracting(McpOAuthService.Connection::name).containsExactly("Claude Code");
        mockMvc.perform(get("/settings/ai").with(user("mama")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Verbundene Assistenten")));

        mockMvc.perform(post("/settings/ai/connections/{id}/disconnect", clientId).with(user("mama")).with(csrf()))
                .andExpect(redirectedUrl("/settings/ai"));
        whoami(newAccessToken).andExpect(status().isUnauthorized());
        assertThat(oauth.connections(mama)).isEmpty();
    }

    @Test
    void confidentialClientGetsSecretAndRefreshToken() throws Exception {
        String registration = mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"client_name":"Claude","redirect_uris":["https://claude.ai/api/mcp/auth_callback"],
                                 "grant_types":["authorization_code","refresh_token"],"response_types":["code"],
                                 "token_endpoint_auth_method":"client_secret_post","scope":"claudeai"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String clientId = JsonPath.read(registration, "$.client_id");
        String secret = JsonPath.read(registration, "$.client_secret");

        String code = authorizeWithConsent(clientId, "mama", "https://claude.ai/api/mcp/auth_callback");
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", "https://claude.ai/api/mcp/auth_callback")
                        .param("client_id", clientId)
                        .param("client_secret", secret)
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refresh_token").isString());
    }

    @Test
    void userWithoutAiAccessIsDenied() throws Exception {
        String clientId = register("Claude Code", REDIRECT, "none");

        mockMvc.perform(get("/oauth2/authorize").queryParams(authorizeParams(clientId)).with(user("kind")))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith(REDIRECT + "?error=access_denied")));
    }

    @Test
    void deniedConsentReturnsAccessDenied() throws Exception {
        String clientId = register("Claude Code", REDIRECT, "none");
        String consentUrl = mockMvc.perform(get("/oauth2/authorize").queryParams(authorizeParams(clientId))
                        .with(user("mama")))
                .andReturn().getResponse().getRedirectedUrl();
        String state = query(consentUrl, "state");

        mockMvc.perform(post("/oauth2/authorize").param("client_id", clientId).param("state", state)
                        .with(user("mama")))
                .andExpect(header().string("Location", startsWith(REDIRECT + "?error=access_denied")));
        assertThat(oauth.connections(mama)).isEmpty();
    }

    @Test
    void registrationRejectsPlainHttpToOtherHosts() throws Exception {
        mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_name\":\"Böse\",\"redirect_uris\":[\"http://evil.example/cb\"],"
                                + "\"token_endpoint_auth_method\":\"none\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_name\":\"Ohne\",\"token_endpoint_auth_method\":\"none\"}"))
                .andExpect(status().isBadRequest());
    }

    private String register(String name, String redirectUri, String authMethod) throws Exception {
        String response = mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_name\":\"" + name + "\",\"redirect_uris\":[\"" + redirectUri + "\"],"
                                + "\"grant_types\":[\"authorization_code\",\"refresh_token\"],"
                                + "\"response_types\":[\"code\"],\"token_endpoint_auth_method\":\"" + authMethod + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("mcp"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.client_id");
    }

    private String authorizeWithConsent(String clientId, String username) throws Exception {
        return authorizeWithConsent(clientId, username, REDIRECT);
    }

    private String authorizeWithConsent(String clientId, String username, String redirectUri) throws Exception {
        var params = authorizeParams(clientId);
        params.set("redirect_uri", redirectUri);
        String consentUrl = mockMvc.perform(get("/oauth2/authorize").queryParams(params).with(user(username)))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(consentUrl).contains("/oauth2/consent");
        String state = query(consentUrl, "state");

        mockMvc.perform(get("/oauth2/consent").param("client_id", clientId).param("state", state)
                        .param("scope", "mcp").with(user(username)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Assistent verbinden")));

        String location = mockMvc.perform(post("/oauth2/authorize").param("client_id", clientId)
                        .param("state", state).param("scope", "mcp").with(user(username)))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(location).startsWith(redirectUri);
        assertThat(query(location, "state")).isEqualTo("xyz");
        return query(location, "code");
    }

    private LinkedMultiValueMap<String, String> authorizeParams(String clientId)
            throws Exception {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("response_type", "code");
        params.add("client_id", clientId);
        params.add("redirect_uri", REDIRECT);
        params.add("state", "xyz");
        params.add("code_challenge", challenge());
        params.add("code_challenge_method", "S256");
        params.add("resource", "http://localhost/mcp");
        return params;
    }

    private static String query(String url, String name) {
        String value = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String challenge() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private ResultActions whoami(String accessToken) throws Exception {
        return mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + accessToken)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"whoami\",\"arguments\":{}}}"));
    }
}
