package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.config.RememberMeProperties;
import club.badernet.nestwerk.user.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.remember-me.validity=14d"})
@AutoConfigureMockMvc
@Transactional
class RememberMeLoginTest {

    private static final String COOKIE_NAME = "remember-me";

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;
    @Autowired RememberMeProperties properties;

    @BeforeEach
    void setUp() {
        userService.createAdministrator("chef", "passwort1");
    }

    private MockHttpServletResponse login(String rememberMe) throws Exception {
        var request = post("/login").with(csrf()).param("username", "chef").param("password", "passwort1");
        if (rememberMe != null) {
            request.param(COOKIE_NAME, rememberMe);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private boolean redirectsToLogin(Cookie cookie) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/household/stats").cookie(cookie)).andReturn()
                .getResponse();
        return response.getStatus() == 302 && "/login".equals(response.getRedirectedUrl());
    }

    @Test
    void loginPageOffersStayingSignedIn() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"remember-me\"")))
                .andExpect(content().string(containsString("Angemeldet bleiben")));
    }

    @Test
    void loginWithOptionSetsSecureLongLivedCookieAndStoresTheToken() throws Exception {
        MockHttpServletResponse response = login("on");

        Cookie cookie = response.getCookie(COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getMaxAge()).isEqualTo((int) Duration.ofDays(14).toSeconds());
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM persistent_logins WHERE username = 'chef'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void loginWithoutOptionSetsNoCookie() throws Exception {
        MockHttpServletResponse response = login(null);

        assertThat(response.getCookie(COOKIE_NAME)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM persistent_logins", Integer.class)).isZero();
    }

    @Test
    void cookieAloneSignsTheUserInAgain() throws Exception {
        Cookie cookie = login("on").getCookie(COOKIE_NAME);

        assertThat(redirectsToLogin(cookie)).isFalse();
    }

    @Test
    void loggingOutInvalidatesTheToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/login").with(csrf()).param("username", "chef")
                .param("password", "passwort1").param(COOKIE_NAME, "on")).andReturn();
        Cookie cookie = result.getResponse().getCookie(COOKIE_NAME);

        mockMvc.perform(post("/logout").with(csrf()).cookie(cookie).session(
                (MockHttpSession) result.getRequest().getSession()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM persistent_logins", Integer.class)).isZero();
        assertThat(redirectsToLogin(cookie)).isTrue();
    }

    @Test
    void loggingOutWithOnlyTheCookieAlsoInvalidatesTheToken() throws Exception {
        Cookie cookie = login("on").getCookie(COOKIE_NAME);

        mockMvc.perform(post("/logout").with(csrf()).cookie(cookie));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM persistent_logins", Integer.class)).isZero();
        assertThat(redirectsToLogin(cookie)).isTrue();
    }

    @Test
    void validityMustBePositive() {
        assertThat(properties.validity()).isEqualTo(Duration.ofDays(14));
        assertThatThrownBy(() -> new RememberMeProperties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
