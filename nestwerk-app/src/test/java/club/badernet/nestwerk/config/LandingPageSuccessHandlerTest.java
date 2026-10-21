package club.badernet.nestwerk.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;

class LandingPageSuccessHandlerTest {

    private final LandingPageSuccessHandler handler = new LandingPageSuccessHandler();

    private String landingOf(String... authorities) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                UsernamePasswordAuthenticationToken.authenticated("someone", "n/a",
                        java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
        return response.getRedirectedUrl();
    }

    @Test
    void calendarUsersLandInTheCalendar() throws Exception {
        assertThat(landingOf("CALENDAR_VIEW", "TODO_VIEW")).isEqualTo("/calendar");
    }

    @Test
    void othersLandOnTheStartPage() throws Exception {
        assertThat(landingOf("KITCHEN_VIEW")).isEqualTo("/");
    }

    @Test
    void aPageRequestedBeforeSigningInTakesPrecedence() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("SPRING_SECURITY_SAVED_REQUEST",
                new org.springframework.security.web.savedrequest.DefaultSavedRequest(
                        savedRequestFor("/todos/5"), null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, UsernamePasswordAuthenticationToken.authenticated(
                "someone", "n/a", java.util.List.of(new SimpleGrantedAuthority("CALENDAR_VIEW"))));

        assertThat(response.getRedirectedUrl()).endsWith("/todos/5");
    }

    private static MockHttpServletRequest savedRequestFor(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServerName("localhost");
        return request;
    }
}
