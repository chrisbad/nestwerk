package club.badernet.nestwerk.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SafeRedirectTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/calendar/week", "/calendar/week?date=2026-10-21&split=person", "/todos/5#comments"})
    void acceptsLocalPaths(String path) {
        assertThat(SafeRedirect.target(path, "/fallback")).isEqualTo(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"//evil.example/x", "https://evil.example", "javascript:alert(1)", "/a\\b", "calendar",
            "/logout", "/logout?x=1", "/logout/now", "/line\nbreak", "/tab\tbreak"})
    void rejectsEverythingThatLeavesTheApp(String path) {
        assertThat(SafeRedirect.target(path, "/fallback")).isEqualTo("/fallback");
    }

    @Test
    void rejectsMissingAndTooLongTargets() {
        assertThat(SafeRedirect.target(null, "/fallback")).isEqualTo("/fallback");
        assertThat(SafeRedirect.target("", "/fallback")).isEqualTo("/fallback");
        assertThat(SafeRedirect.target("/" + "a".repeat(SafeRedirect.MAX_LENGTH), "/fallback")).isEqualTo("/fallback");
    }

    @Test
    void keepsTheFallbackNullWhenAskedTo() {
        assertThat(SafeRedirect.target("//evil", null)).isNull();
    }

    @Test
    void passesTheOriginOnAsEncodedQueryParameter() {
        String result = SafeRedirect.withBack("/calendar/categories", "/calendar/week?date=2026-10-21&split=person");

        assertThat(result).isEqualTo("/calendar/categories?back=%2Fcalendar%2Fweek%3Fdate%3D2026-10-21%26split%3Dperson");
    }

    @Test
    void leavesThePathAloneWithoutValidOrigin() {
        assertThat(SafeRedirect.withBack("/calendar/categories", null)).isEqualTo("/calendar/categories");
        assertThat(SafeRedirect.withBack("/calendar/categories", "//evil")).isEqualTo("/calendar/categories");
    }
}
