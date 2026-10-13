package club.badernet.nestwerk.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Controller
public class HomeController {

    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN);

    @GetMapping("/")
    String index(Model model) {
        LocalDate today = LocalDate.now();
        model.addAttribute("today", today);
        model.addAttribute("todayLabel", today.format(DAY_LABEL));
        return "index";
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }
}
