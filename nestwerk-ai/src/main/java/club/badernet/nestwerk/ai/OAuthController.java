package club.badernet.nestwerk.ai;

import club.badernet.nestwerk.ai.McpClientRegistrations;
import club.badernet.nestwerk.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Öffentliche Seite der OAuth-Anmeldung für KI-Assistenten: die Metadaten des MCP-Servers (RFC 9728), über die
 * ein Client den Authorization Server findet, und die Zustimmungsseite nach dem Login.
 */
@Controller
public class OAuthController {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final RegisteredClientRepository clients;
    private final CurrentUser currentUser;

    public OAuthController(RegisteredClientRepository clients, CurrentUser currentUser) {
        this.clients = clients;
        this.currentUser = currentUser;
    }

    @GetMapping(path = {"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    Map<String, Object> protectedResource() {
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", base + "/mcp");
        metadata.put("authorization_servers", List.of(base));
        metadata.put("scopes_supported", List.of(McpClientRegistrations.SCOPE));
        metadata.put("bearer_methods_supported", List.of("header"));
        metadata.put("resource_name", "Nestwerk");
        return metadata;
    }

    @GetMapping("/oauth2/consent")
    String consent(@RequestParam("client_id") String clientId, @RequestParam String state, Model model) {
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("clientId", clientId);
        model.addAttribute("clientName", client.getClientName());
        model.addAttribute("state", state);
        model.addAttribute("scope", McpClientRegistrations.SCOPE);
        model.addAttribute("targets", client.getRedirectUris().stream().map(OAuthController::target).distinct().toList());
        model.addAttribute("username", currentUser.require().getUsername());
        return "oauth/consent";
    }

    /** Wohin der Assistent nach der Zustimmung zurückkehrt, lesbar für den Benutzer. */
    private static String target(String redirectUri) {
        URI uri = URI.create(redirectUri);
        String host = uri.getHost();
        if (host == null) {
            return uri.getScheme() + ":";
        }
        return LOOPBACK_HOSTS.contains(host) ? "dieses Gerät (" + host + ")" : host;
    }
}
