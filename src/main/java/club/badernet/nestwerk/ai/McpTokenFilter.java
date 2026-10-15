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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Prüft das Bearer-Token vor dem MCP-Endpunkt. Ohne gültiges Token gibt es 401, sonst läuft der Request als
 * Besitzer des Tokens mit dessen Rechten.
 */
public class McpTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final AiTokenService tokens;
    private final UserDetailsService userDetails;

    public McpTokenFilter(AiTokenService tokens, UserDetailsService userDetails) {
        this.tokens = tokens;
        this.userDetails = userDetails;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        Optional<User> user = header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                ? tokens.authenticate(header.substring(BEARER.length()).trim())
                : Optional.empty();
        if (user.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"nestwerk\"");
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"Gültiges KI-Zugangstoken erforderlich\"}");
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
