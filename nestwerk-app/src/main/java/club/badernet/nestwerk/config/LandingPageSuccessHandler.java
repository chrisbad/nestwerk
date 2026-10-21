package club.badernet.nestwerk.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;

/**
 * After signing in, the calendar is the starting point for everyone who may use it; others land on the start page.
 * A page the user wanted to open before being asked to sign in still takes precedence.
 */
class LandingPageSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    static final String CALENDAR_PATH = "/calendar";
    static final String START_PATH = "/";
    private static final String CALENDAR_AUTHORITY = "CALENDAR_VIEW";

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        setDefaultTargetUrl(mayUseCalendar(authentication) ? CALENDAR_PATH : START_PATH);
        super.onAuthenticationSuccess(request, response, authentication);
    }

    private static boolean mayUseCalendar(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(CALENDAR_AUTHORITY::equals);
    }
}
