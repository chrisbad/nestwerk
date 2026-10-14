package club.badernet.nestwerk.web;

import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoQuery;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Controller
public class HomeController {

    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN);
    private static final int TILE_LIMIT = 5;

    private final CurrentUser currentUser;
    private final TodoAccess todoAccess;
    private final TodoQuery todoQuery;
    private final TodoViews todoViews;
    private final ContactService contactService;

    public HomeController(CurrentUser currentUser, TodoAccess todoAccess, TodoQuery todoQuery, TodoViews todoViews,
                          ContactService contactService) {
        this.currentUser = currentUser;
        this.todoAccess = todoAccess;
        this.todoQuery = todoQuery;
        this.todoViews = todoViews;
        this.contactService = contactService;
    }

    @GetMapping("/")
    String index(Model model) {
        LocalDate today = todoQuery.today();
        model.addAttribute("today", today);
        model.addAttribute("todayLabel", today.format(DAY_LABEL));
        Optional<User> me = currentUser.find();
        boolean todos = me.map(u -> todoAccess.mayOpen(u.getUsername())).orElse(false);
        model.addAttribute("showTodos", todos);
        model.addAttribute("showContacts", me.map(u -> contactService.mayOpen(u.getUsername())).orElse(false));
        List<TodoViews.TodoRow> myTodos = List.of();
        if (todos) {
            User user = me.get();
            TodoViews.Labels labels = todoViews.labels(user);
            myTodos = todoQuery.mine(user, TILE_LIMIT).stream()
                    .map(t -> todoViews.row(t, user, labels, today)).toList();
        }
        model.addAttribute("myTodos", myTodos);
        return "index";
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }
}
