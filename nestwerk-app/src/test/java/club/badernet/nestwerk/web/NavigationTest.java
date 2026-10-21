package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class NavigationTest {

    @Autowired MockMvc mockMvc;
    @Autowired Navigation navigation;

    private static UsernamePasswordAuthenticationToken asUser(String... authorities) {
        return UsernamePasswordAuthenticationToken.authenticated("nobody", "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void offersOnlyAreasTheUserMayOpen() {
        List<String> ids = navigation.visible(asUser("CALENDAR_VIEW"), NavigationGroup.AREA).stream()
                .map(NavigationItem::id).toList();

        assertThat(ids).containsExactly("home", "calendar", "times", "manual");
    }

    @Test
    void administrationFollowsTheManagementAuthorities() {
        assertThat(navigation.visible(asUser("CALENDAR_VIEW"), NavigationGroup.ADMINISTRATION)).isEmpty();
        assertThat(navigation.visible(asUser("USER_MANAGE", "MEMBER_MANAGE"), NavigationGroup.ADMINISTRATION))
                .extracting(NavigationItem::id).containsExactly("users", "members");
    }

    @Test
    void accountMenuListsSettingsPagesByAuthority() {
        assertThat(navigation.visible(asUser(), NavigationGroup.ACCOUNT)).extracting(NavigationItem::id)
                .containsExactly("settings", "notifications", "my-notification-channels");
        assertThat(navigation.visible(asUser("BACKUP_MANAGE", "UPDATE_MANAGE"), NavigationGroup.ACCOUNT))
                .extracting(NavigationItem::id).contains("backup", "updates");
    }

    @Test
    void tabBarHoldsTheFrequentAreas() {
        assertThat(navigation.tabs(asUser("CALENDAR_VIEW"))).extracting(NavigationItem::id)
                .containsExactly("home", "calendar");
    }

    @Test
    void findsTheAreaOfAPath() {
        assertThat(navigation.forPath("/calendar/week?date=2026-10-21")).map(NavigationItem::label).contains("Kalender");
        assertThat(navigation.forPath("/todos/5")).map(NavigationItem::label).contains("ToDos");
        assertThat(navigation.forPath("/")).map(NavigationItem::label).contains("Start");
        assertThat(navigation.forPath("/unknown")).isEmpty();
        assertThat(navigation.forPath(null)).isEmpty();
    }

    @Test
    void sideBarAndTabBarAreRenderedFromTheRegistry() throws Exception {
        mockMvc.perform(get("/manual").with(user("nobody").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"),
                        new SimpleGrantedAuthority("USER_MANAGE"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/calendar\"")))
                .andExpect(content().string(containsString("href=\"/admin/users\"")))
                .andExpect(content().string(containsString("Verwaltung")))
                .andExpect(content().string(not(containsString("href=\"/admin/roles\""))));
    }

    @Test
    void manualHasNoAdministrationSectionWithoutAuthority() throws Exception {
        mockMvc.perform(get("/manual").with(user("nobody").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(content().string(not(containsString("nav-section"))));
    }
}
