package club.badernet.nestwerk.web;

import club.badernet.nestwerk.notification.Notification;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Controller
@RequestMapping("/notifications")
public class NotificationController {

    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d. MMM, HH:mm", Locale.GERMANY);

    /** Eine Benachrichtigung mit formatierter Zeit; {@code todoId} für Wiedervorlage-Aktionen. */
    public record Item(Notification notification, String when, Long todoId) {
    }

    private final NotificationService notifications;
    private final CurrentUser currentUser;
    private final TodoRepository todos;
    private final Clock clock;

    public NotificationController(NotificationService notifications, CurrentUser currentUser, TodoRepository todos,
                                  Clock clock) {
        this.notifications = notifications;
        this.currentUser = currentUser;
        this.todos = todos;
        this.clock = clock;
    }

    @GetMapping
    String list(@RequestParam(defaultValue = "all") String filter, Model model) {
        User user = currentUser.require();
        boolean onlyUnread = "unread".equals(filter);
        model.addAttribute("items", items(notifications.list(user.getId(), onlyUnread, 200)));
        model.addAttribute("onlyUnread", onlyUnread);
        model.addAttribute("unread", notifications.unreadCount(user.getId()));
        return "notifications/list";
    }

    @GetMapping("/menu")
    String menu(Model model) {
        User user = currentUser.require();
        model.addAttribute("items", items(notifications.latest(user.getId(), 8)));
        return "notifications/fragments :: menu";
    }

    @GetMapping("/count")
    String count(Model model) {
        model.addAttribute("count", notifications.unreadCount(currentUser.require().getId()));
        return "notifications/fragments :: count";
    }

    /** Markiert als gelesen und springt zum Ziel; gibt es das ToDo nicht mehr, gibt es eine Meldung. */
    @GetMapping("/{id}/open")
    String open(@PathVariable Long id, RedirectAttributes redirect) {
        Notification n = notifications.open(id, currentUser.require());
        if ("TODO".equals(n.getSourceType()) && !todos.existsById(n.getSourceId())) {
            redirect.addFlashAttribute("error", "Das ToDo „" + n.getTitle() + "“ gibt es nicht mehr.");
            return "redirect:/notifications";
        }
        return "redirect:" + SafeRedirect.target(n.getLink(), "/notifications");
    }

    @PostMapping("/read-all")
    String readAll(RedirectAttributes redirect) {
        notifications.markAllRead(currentUser.require().getId());
        redirect.addFlashAttribute("success", "Alle Benachrichtigungen sind als gelesen markiert.");
        return "redirect:/notifications";
    }

    private List<Item> items(List<Notification> list) {
        return list.stream().map(n -> new Item(n, WHEN.format(n.getCreatedAt().atZone(clock.getZone())),
                "TODO".equals(n.getSourceType()) ? n.getSourceId() : null)).toList();
    }
}
