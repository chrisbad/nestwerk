package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.notification.Channel;
import club.badernet.nestwerk.notification.NotificationPreferences;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.notification.PushSubscriptionRepository;
import club.badernet.nestwerk.notification.WebPushCrypto;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class NotificationSettingsControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired NotificationPreferences preferences;
    @Autowired PushSubscriptionRepository subscriptions;
    @Autowired TodoService todoService;

    User mama;

    @BeforeEach
    void setUp() {
        userService.createAdministrator("chef", "passwort1");
        mama = userService.create("mama", "passwort1",
                Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
    }

    @Test
    void settingsPageShowsMatrixAndPushKey() throws Exception {
        mockMvc.perform(get("/settings/notifications").with(user("mama")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bald fällig")))
                .andExpect(content().string(containsString("data-vapid-key=\"")));
    }

    @Test
    void savesEmailAndRejectsInvalidOne() throws Exception {
        mockMvc.perform(post("/settings/notifications/email").param("email", "mama@example.org")
                        .with(csrf()).with(user("mama")))
                .andExpect(flash().attributeExists("success"));
        assertThat(users.findByUsername("mama").orElseThrow().getEmail()).isEqualTo("mama@example.org");

        mockMvc.perform(post("/settings/notifications/email").param("email", "kaputt")
                        .with(csrf()).with(user("mama")))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void savesOwnPreferences() throws Exception {
        mockMvc.perform(post("/settings/notifications/preferences")
                        .param("TODO_DUE_SOON:PUSH", "OFF").param("TODO_OVERDUE:EMAIL", "ON")
                        .param("TODO_COMMENT:PUSH", "DEFAULT")
                        .with(csrf()).with(user("mama")))
                .andExpect(flash().attributeExists("success"));

        assertThat(preferences.choice(NotificationPreferences.Scope.USER, mama.getId(), "TODO_DUE_SOON", Channel.PUSH))
                .isEqualTo(NotificationPreferences.Choice.OFF);
        assertThat(preferences.choice(NotificationPreferences.Scope.USER, mama.getId(), "TODO_OVERDUE", Channel.EMAIL))
                .isEqualTo(NotificationPreferences.Choice.ON);
    }

    @Test
    void registersPushSubscriptionFromJson() throws Exception {
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(
                WebPushCrypto.encodePoint((ECPublicKey) WebPushCrypto.generateKeyPair().getPublic()));
        String auth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        String json = "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/abc\",\"keys\":{\"p256dh\":\"" + key
                + "\",\"auth\":\"" + auth + "\"}}";

        mockMvc.perform(post("/settings/notifications/push-subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .content(json).header("User-Agent", "Mozilla/5.0 (Android) Chrome/130").with(csrf()).with(user("mama")))
                .andExpect(status().isCreated());
        assertThat(subscriptions.findAllByUserIdOrderByCreatedAtAsc(mama.getId())).hasSize(1);

        mockMvc.perform(post("/settings/notifications/push-subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.replace("fcm.googleapis.com", "intern.example.org")).with(csrf()).with(user("mama")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminSetsGlobalDefaults() throws Exception {
        mockMvc.perform(post("/admin/notifications/preferences").param("TODO_ASSIGNED:EMAIL", "ON")
                        .with(csrf()).with(user("chef").authorities(new SimpleGrantedAuthority("NOTIFICATION_SETTINGS_MANAGE"))))
                .andExpect(flash().attributeExists("success"));

        assertThat(preferences.globalEnabled(NotificationType.TODO_ASSIGNED, Channel.EMAIL)).isTrue();
        mockMvc.perform(get("/admin/notifications").with(user("chef").authorities(
                        new SimpleGrantedAuthority("NOTIFICATION_SETTINGS_MANAGE"))))
                .andExpect(content().string(containsString("E-Mail ist nicht eingerichtet")));
    }

    @Test
    void todoOverrideNeedsPermission() throws Exception {
        Todo t = todoService.create(new TodoInput("Steuer", null, null, null, null, null, null, false, null,
                Set.of(), Set.of(), null), mama);

        mockMvc.perform(post("/todos/{id}/notification-override", t.getId()).param("PUSH", "OFF")
                        .with(csrf()).with(user("mama")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/todos/{id}/notification-override", t.getId()).param("PUSH", "OFF").param("EMAIL", "DEFAULT")
                        .with(csrf()).with(user("chef")))
                .andExpect(flash().attributeExists("success"));

        assertThat(preferences.enabled(Channel.PUSH, NotificationType.TODO_DUE_SOON, mama.getId(), t.getId())).isFalse();
    }

    @Test
    void serviceWorkerAndManifestArePublic() throws Exception {
        mockMvc.perform(get("/sw.js")).andExpect(status().isOk());
        mockMvc.perform(get("/manifest.webmanifest")).andExpect(status().isOk());
        mockMvc.perform(get("/icons/nestwerk-192.png")).andExpect(status().isOk());
    }
}
