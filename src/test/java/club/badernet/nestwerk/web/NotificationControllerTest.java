package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.notification.AppSettings;
import club.badernet.nestwerk.notification.Notification;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class NotificationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired NotificationService notifications;
    @Autowired TodoService todoService;
    @Autowired TodoRepository todos;
    @Autowired AppSettings settings;
    @Autowired Clock clock;

    User admin;
    User mama;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        mama = userService.create("mama", "passwort1",
                Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
    }

    private Todo todo(String title) {
        return todoService.create(new TodoInput(title, null, null, null, null, LocalDate.now(clock), null, false,
                null, Set.of(), Set.of(), null), mama);
    }

    private void notifyMama(Todo t, NotificationType type) {
        notifications.notify(mama.getId(), type, "TODO", t.getId(), "k", t.getTitle(), "Text", "/todos/" + t.getId());
    }

    @Test
    void bellShowsUnreadCount() throws Exception {
        Todo t = todo("Steuer");
        notifyMama(t, NotificationType.TODO_DUE_SOON);

        mockMvc.perform(get("/").with(user("mama")))
                .andExpect(content().string(containsString("class=\"bell-count\"")))
                .andExpect(content().string(containsString(">1<")));
        mockMvc.perform(get("/notifications/count").with(user("mama")))
                .andExpect(content().string(containsString(">1<")));
    }

    @Test
    void menuAndPageListNotifications() throws Exception {
        Todo t = todo("Steuer");
        notifyMama(t, NotificationType.TODO_DUE_SOON);

        mockMvc.perform(get("/notifications/menu").with(user("mama")))
                .andExpect(content().string(containsString("Steuer")))
                .andExpect(content().string(not(containsString("<html"))));
        mockMvc.perform(get("/notifications").with(user("mama")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bald fällig")));
    }

    @Test
    void openingMarksReadAndRedirects() throws Exception {
        Todo t = todo("Steuer");
        notifyMama(t, NotificationType.TODO_DUE_SOON);
        Notification n = notifications.latest(mama.getId(), 1).getFirst();

        mockMvc.perform(get("/notifications/{id}/open", n.getId()).with(user("mama")))
                .andExpect(redirectedUrl("/todos/" + t.getId()));
        assertThat(notifications.unreadCount(mama.getId())).isZero();
        mockMvc.perform(get("/notifications/{id}/open", n.getId()).with(user("chef")))
                .andExpect(status().isNotFound());
    }

    @Test
    void openingNotificationOfDeletedTodo() throws Exception {
        Todo t = todo("Weg");
        notifyMama(t, NotificationType.TODO_DUE_SOON);
        Notification n = notifications.latest(mama.getId(), 1).getFirst();
        todoService.delete(t.getId(), mama);
        todos.flush();

        mockMvc.perform(get("/notifications/{id}/open", n.getId()).with(user("mama")))
                .andExpect(redirectedUrl("/notifications"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void readAllMarksEverything() throws Exception {
        Todo t = todo("Steuer");
        notifyMama(t, NotificationType.TODO_DUE_SOON);
        notifyMama(t, NotificationType.TODO_OVERDUE);

        mockMvc.perform(post("/notifications/read-all").with(csrf()).with(user("mama")))
                .andExpect(status().is3xxRedirection());

        assertThat(notifications.unreadCount(mama.getId())).isZero();
    }

    @Test
    void followUpActions() throws Exception {
        Todo t = todo("Versicherung prüfen");
        notifyMama(t, NotificationType.TODO_FOLLOW_UP);

        mockMvc.perform(get("/notifications").with(user("mama")))
                .andExpect(content().string(containsString("+1 Woche")));
        mockMvc.perform(post("/todos/{id}/follow-up", t.getId()).param("action", "WEEK").with(csrf()).with(user("mama")))
                .andExpect(flash().attributeExists("success"));
        assertThat(todos.findById(t.getId()).orElseThrow().getFollowUpOn()).isEqualTo(LocalDate.now(clock).plusWeeks(1));

        mockMvc.perform(post("/todos/{id}/follow-up", t.getId()).param("action", "DONE").with(csrf()).with(user("mama")))
                .andExpect(flash().attributeExists("success"));
        assertThat(todos.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TodoStatus.DONE);
    }

    @Test
    void adminChangesReminderSettings() throws Exception {
        mockMvc.perform(get("/admin/notifications").with(user("mama"))).andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/notifications/reminders").param("hour", "7").param("leadDays", "3")
                        .with(csrf()).with(user("chef").authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("NOTIFICATION_SETTINGS_MANAGE"))))
                .andExpect(flash().attributeExists("success"));

        assertThat(settings.reminderHour()).isEqualTo(7);
        assertThat(settings.defaultLeadDays()).isEqualTo(3);
    }
}
