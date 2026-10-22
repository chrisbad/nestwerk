package club.badernet.nestwerk.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

/**
 * Persistent remember-me services that also revoke the stored tokens when the user logs out with only the cookie
 * (the session already expired), which the default implementation skips because there is no authentication.
 */
class RevokingRememberMeServices extends PersistentTokenBasedRememberMeServices {

    private static final int COOKIE_PARTS = 2;

    private final PersistentTokenRepository tokens;

    RevokingRememberMeServices(String key, UserDetailsService userDetailsService, PersistentTokenRepository tokens) {
        super(key, userDetailsService, tokens);
        this.tokens = tokens;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        super.logout(request, response, authentication);
        if (authentication == null) {
            revokeTokensOfCookie(request);
        }
    }

    private void revokeTokensOfCookie(HttpServletRequest request) {
        String cookie = extractRememberMeCookie(request);
        if (cookie == null || cookie.isEmpty()) {
            return;
        }
        try {
            String[] parts = decodeCookie(cookie);
            if (parts.length == COOKIE_PARTS) {
                PersistentRememberMeToken token = tokens.getTokenForSeries(parts[0]);
                if (token != null) {
                    tokens.removeUserTokens(token.getUsername());
                }
            }
        } catch (InvalidCookieException e) {
            // A malformed cookie has no stored token that could be revoked.
            logger.debug("Ignoring malformed remember-me cookie on logout");
        }
    }
}
