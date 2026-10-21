package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

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
class SettingsRegisterTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired Settings settings;

    User manager;
    User guest;

    @BeforeEach
    void setUp() {
        Role managers = roleService.create("Verwalter", null, Set.of("CALENDAR_VIEW", "CALENDAR_CATEGORY_MANAGE",
                "USER_MANAGE"));
        manager = userService.create("verwalter", "passwort1", Set.of(managers.getId()));
        Role viewers = roleService.create("Gäste", null, Set.of("CALENDAR_VIEW"));
        guest = userService.create("gast", "passwort1", Set.of(viewers.getId()));
    }

    private static RequestPostProcessor as(String name, String... authorities) {
        return user(name).authorities(java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void registerListsEveryAllowedSettingWithItsValue() throws Exception {
        mockMvc.perform(get("/settings").with(as("verwalter", "CALENDAR_VIEW", "CALENDAR_CATEGORY_MANAGE", "USER_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-table=\"settings\"")))
                .andExpect(content().string(containsString("Terminkategorien")))
                .andExpect(content().string(containsString("href=\"/calendar/categories\"")))
                .andExpect(content().string(containsString("Benutzer")))
                .andExpect(content().string(containsString("Meine Benachrichtigungswege")))
                .andExpect(content().string(not(containsString("Sicherungen"))));
    }

    @Test
    void settingsWithoutPermissionAreNotShown() throws Exception {
        mockMvc.perform(get("/settings").with(as("gast", "CALENDAR_VIEW")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Terminkategorien"))))
                .andExpect(content().string(not(containsString("/admin/users"))))
                .andExpect(content().string(containsString("Zeiten und Ruhe-Ziele")));
    }

    @Test
    void registerIsOnlyForSignedInUsers() throws Exception {
        mockMvc.perform(get("/settings")).andExpect(status().is3xxRedirection());
    }

    @Test
    void filtersByTextAreaAndScope() {
        assertThat(settings.list(manager, new SettingFilter("farbe", Set.of(), null, null)))
                .extracting(SettingEntry::id).contains("calendar.categories");
        assertThat(settings.list(manager, new SettingFilter("", Set.of(SettingArea.SYSTEM), null, null)))
                .extracting(SettingEntry::id).containsExactly("system.users");
        assertThat(settings.list(manager, new SettingFilter("", Set.of(), SettingScope.PERSONAL, null)))
                .extracting(SettingEntry::id).containsExactly("notifications.mine");
    }

    @Test
    void sortsByTitleWithGermanCollation() {
        assertThat(settings.list(manager, SettingFilter.none())).extracting(SettingEntry::title)
                .isSortedAccordingTo(java.text.Collator.getInstance(java.util.Locale.GERMAN));
    }

    @Test
    void reportsCategoryCountAsCurrentValue() {
        assertThat(settings.list(manager, new SettingFilter("Terminkategorien", Set.of(), null, null)))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.summary()).endsWith("Kategorien");
                    assertThat(entry.status()).isEqualTo(SettingStatus.OK);
                });
    }

    @Test
    void guestSeesNoAttentionItems() {
        assertThat(settings.attentionCount(guest)).isZero();
    }
}
