package club.badernet.nestwerk.ai;

import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * KI-Assistenten, die per OAuth mit dem normalen Nestwerk-Login verbunden sind: Zugriffstoken prüfen, Verbindungen
 * eines Benutzers auflisten und trennen, Abgelaufenes aufräumen. Es gelten dieselben Regeln wie für
 * {@link AiTokenService}: aktiver Benutzer mit dem Recht {@code AI_ACCESS}.
 */
@Service
public class McpOAuthService {

    /** Ein verbundener Assistent in der Liste. */
    public record Connection(String clientId, String name, Instant since, Instant lastActive) {
    }

    private static final Logger log = LoggerFactory.getLogger(McpOAuthService.class);
    private static final int MAX_TOKEN_LENGTH = 512;

    private final OAuth2AuthorizationService authorizations;
    private final UserRepository users;
    private final AiTokenService aiTokens;
    private final JdbcTemplate jdbc;

    public McpOAuthService(OAuth2AuthorizationService authorizations, UserRepository users, AiTokenService aiTokens,
                           JdbcTemplate jdbc) {
        this.authorizations = authorizations;
        this.users = users;
        this.aiTokens = aiTokens;
        this.jdbc = jdbc;
    }

    /** Der Benutzer zum OAuth-Zugriffstoken, solange es gültig ist und er KI-Zugänge nutzen darf. */
    @Transactional(readOnly = true)
    public Optional<User> authenticate(String accessToken) {
        if (accessToken == null || accessToken.isBlank() || accessToken.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        OAuth2Authorization authorization = authorizations.findByToken(accessToken, OAuth2TokenType.ACCESS_TOKEN);
        if (authorization == null || authorization.getAccessToken() == null
                || !authorization.getAccessToken().isActive()) {
            return Optional.empty();
        }
        return users.findByUsername(authorization.getPrincipalName()).filter(aiTokens::mayUse);
    }

    /** Darf sich dieser Benutzer mit einem Assistenten verbinden? */
    @Transactional(readOnly = true)
    public boolean mayConnect(String username) {
        return users.findByUsername(username).filter(aiTokens::mayUse).isPresent();
    }

    /** Assistenten mit noch gültigem Zugriff, zuletzt aktive zuerst. */
    @Transactional(readOnly = true)
    public List<Connection> connections(User user) {
        return jdbc.query("""
                        SELECT c.client_id, c.client_name, c.client_id_issued_at, MAX(a.access_token_issued_at) AS last_active
                        FROM oauth2_authorization a JOIN oauth2_registered_client c ON c.id = a.registered_client_id
                        WHERE a.principal_name = ? AND a.access_token_value IS NOT NULL
                          AND (a.refresh_token_expires_at > now() OR a.access_token_expires_at > now())
                        GROUP BY c.client_id, c.client_name, c.client_id_issued_at
                        ORDER BY last_active DESC""",
                (rs, i) -> new Connection(rs.getString("client_id"), rs.getString("client_name"),
                        rs.getTimestamp("client_id_issued_at").toInstant(), rs.getTimestamp("last_active").toInstant()),
                user.getUsername());
    }

    /** Trennt den Assistenten: alle Tokens und die Zustimmung des Benutzers für diesen Client entfallen. */
    @Transactional
    public void disconnect(String clientId, User user) {
        String registeredId = jdbc.query("SELECT id FROM oauth2_registered_client WHERE client_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, clientId);
        if (registeredId == null) {
            return;
        }
        jdbc.update("DELETE FROM oauth2_authorization WHERE registered_client_id = ? AND principal_name = ?",
                registeredId, user.getUsername());
        jdbc.update("DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ? AND principal_name = ?",
                registeredId, user.getUsername());
    }

    /**
     * Entfernt abgelaufene Anmeldungen, abgebrochene Anmeldeversuche und Clients, die seit 30 Tagen niemand
     * benutzt.
     */
    @Scheduled(cron = "0 40 3 * * *")
    @Transactional
    public void cleanup() {
        int expired = jdbc.update("""
                DELETE FROM oauth2_authorization
                WHERE refresh_token_expires_at < now()
                   OR (refresh_token_expires_at IS NULL
                       AND COALESCE(access_token_expires_at, authorization_code_expires_at) < now())
                   OR (refresh_token_expires_at IS NULL AND access_token_expires_at IS NULL
                       AND authorization_code_expires_at IS NULL AND created_at < now() - INTERVAL '1 day')""");
        int clients = jdbc.update("""
                DELETE FROM oauth2_registered_client c
                WHERE c.client_id_issued_at < now() - INTERVAL '30 days'
                  AND NOT EXISTS (SELECT 1 FROM oauth2_authorization a WHERE a.registered_client_id = c.id)
                  AND NOT EXISTS (SELECT 1 FROM oauth2_authorization_consent k WHERE k.registered_client_id = c.id)""");
        if (expired + clients > 0) {
            log.info("OAuth aufgeräumt: {} Anmeldungen, {} Clients", expired, clients);
        }
    }
}
