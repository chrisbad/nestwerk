package club.badernet.nestwerk.ai;

import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Liefert den Benutzer, in dessen Namen ein MCP-Tool läuft. Der {@link McpTokenFilter} legt die Benutzer-ID am
 * Request ab, der Transport reicht sie im {@link McpTransportContext} weiter. Das funktioniert auch, wenn der
 * Tool-Aufruf nicht auf dem Request-Thread läuft. Ohne Kontext (z. B. in Tests) gilt der Security-Kontext.
 */
@Component
public class McpCaller {

    /** Schlüssel der Benutzer-ID am Request und im Transportkontext. */
    public static final String USER_ID = "nestwerk.mcp.userId";

    private final UserRepository users;
    private final CurrentUser currentUser;

    public McpCaller(UserRepository users, CurrentUser currentUser) {
        this.users = users;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public User require(McpTransportContext context) {
        Object id = context == null ? null : context.get(USER_ID);
        if (id instanceof Long userId) {
            return users.findById(userId).filter(User::isEnabled)
                    .orElseThrow(() -> new AccessDeniedException("Benutzer unbekannt"));
        }
        return currentUser.require();
    }
}
