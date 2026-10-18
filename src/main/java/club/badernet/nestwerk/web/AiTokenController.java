package club.badernet.nestwerk.web;

import club.badernet.nestwerk.ai.AiTokenService;
import club.badernet.nestwerk.ai.McpOAuthService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Eigene Zugangstoken für KI-Assistenten (MCP) anlegen und widerrufen, per Login verbundene Assistenten trennen. */
@Controller
public class AiTokenController {

    /** Ein Token für die Liste. */
    public record TokenRow(Long id, String name, String created, String lastUsed, String expires, boolean active) {
    }

    /** Ein per Nestwerk-Login verbundener Assistent für die Liste. */
    public record ConnectionRow(String clientId, String name, String since, String lastActive) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");

    private final AiTokenService tokens;
    private final McpOAuthService oauth;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final String mcpUrl;

    public AiTokenController(AiTokenService tokens, McpOAuthService oauth, CurrentUser currentUser, Clock clock,
                             @Value("${nestwerk.base-url:http://localhost:8080}") String baseUrl) {
        this.tokens = tokens;
        this.oauth = oauth;
        this.currentUser = currentUser;
        this.clock = clock;
        this.mcpUrl = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + "/mcp";
    }

    @GetMapping("/settings/ai")
    String list(Model model) {
        User user = requireAccess();
        LocalDate today = LocalDate.now(clock);
        model.addAttribute("tokens", tokens.mine(user).stream().map(t -> new TokenRow(t.getId(), t.getName(),
                moment(t.getCreatedAt()), moment(t.getLastUsedAt()),
                t.getExpiresOn() == null ? null : t.getExpiresOn().format(DAY), t.isActive(today))).toList());
        model.addAttribute("connections", oauth.connections(user).stream().map(c -> new ConnectionRow(c.clientId(),
                c.name(), moment(c.since()), moment(c.lastActive()))).toList());
        model.addAttribute("mcpUrl", mcpUrl);
        return "settings/ai";
    }

    @PostMapping("/settings/ai")
    String create(@RequestParam(required = false) String name,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn,
                  RedirectAttributes redirect) {
        User user = requireAccess();
        try {
            if (expiresOn != null && expiresOn.isBefore(LocalDate.now(clock))) {
                throw new IllegalArgumentException("Das Ablaufdatum liegt in der Vergangenheit.");
            }
            AiTokenService.Issued issued = tokens.create(name, expiresOn, user);
            redirect.addFlashAttribute("newToken", issued.plaintext());
            redirect.addFlashAttribute("newTokenName", issued.token().getName());
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings/ai";
    }

    @PostMapping("/settings/ai/{id}/revoke")
    String revoke(@PathVariable Long id, RedirectAttributes redirect) {
        tokens.revoke(id, currentUser.require());
        redirect.addFlashAttribute("success", "Der Zugang ist widerrufen.");
        return "redirect:/settings/ai";
    }

    @PostMapping("/settings/ai/connections/{clientId}/disconnect")
    String disconnect(@PathVariable String clientId, RedirectAttributes redirect) {
        oauth.disconnect(clientId, currentUser.require());
        redirect.addFlashAttribute("success", "Der Assistent ist getrennt.");
        return "redirect:/settings/ai";
    }

    private User requireAccess() {
        User user = currentUser.require();
        if (!tokens.mayUse(user)) {
            throw new AccessDeniedException("Keine Berechtigung für KI-Zugänge");
        }
        return user;
    }

    private String moment(Instant value) {
        return value == null ? null : value.atZone(clock.getZone()).format(MOMENT);
    }
}
