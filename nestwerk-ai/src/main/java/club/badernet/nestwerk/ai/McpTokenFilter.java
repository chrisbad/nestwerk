package club.badernet.nestwerk.ai;

import club.badernet.nestwerk.user.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Prüft das Bearer-Token vor dem MCP-Endpunkt: ein persönliches KI-Zugangstoken ({@code nw_…}) oder ein
 * OAuth-Zugriffstoken aus dem Nestwerk-Login. Ohne gültiges Token gibt es 401 mit dem Verweis auf die
 * OAuth-Metadaten (RFC 9728), sonst läuft der Request als Besitzer des Tokens mit dessen Rechten.
 */
public class McpTokenFilter extends OncePerRequestFilter {

    /** Pfad der Protected-Resource-Metadaten für {@code /mcp}. */
    public static final String RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource/mcp";

    private static final String BEARER = "Bearer ";

    private final AiTokenService tokens;
    private final McpOAuthService oauth;
    private final UserDetailsService userDetails;

    public McpTokenFilter(AiTokenService tokens, McpOAuthService oauth, UserDetailsService userDetails) {
        this.tokens = tokens;
        this.oauth = oauth;
        this.userDetails = userDetails;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String bearer = header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                ? header.substring(BEARER.length()).trim() : null;
        Optional<User> user = bearer == null ? Optional.empty()
                : bearer.startsWith(AiTokenService.PREFIX) ? tokens.authenticate(bearer) : oauth.authenticate(bearer);
        if (user.isEmpty()) {
            String metadata = ServletUriComponentsBuilder.fromContextPath(request).path(RESOURCE_METADATA_PATH)
                    .toUriString();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"nestwerk\""
                    + (bearer == null ? "" : ", error=\"invalid_token\"")
                    + ", resource_metadata=\"" + metadata + "\", scope=\"" + McpClientRegistrations.SCOPE + "\"");
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"Anmeldung erforderlich: KI-Zugangstoken oder Nestwerk-Login\"}");
            return;
        }
        UserDetails details = userDetails.loadUserByUsername(user.get().getUsername());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                details.getUsername(), null, details.getAuthorities()));
        SecurityContextHolder.setContext(context);
        request.setAttribute(McpCaller.USER_ID, user.get().getId());
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
