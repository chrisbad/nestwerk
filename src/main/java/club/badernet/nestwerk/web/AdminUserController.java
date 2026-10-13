package club.badernet.nestwerk.web;

import club.badernet.nestwerk.user.Role;
import club.badernet.nestwerk.user.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/users")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("users", userService.findAll());
        model.addAttribute("roles", Role.values());
        return "admin/users";
    }

    @PostMapping
    String create(@RequestParam String username,
                  @RequestParam String password,
                  @RequestParam Role role,
                  RedirectAttributes redirect) {
        String name = username.trim();
        if (name.isEmpty() || name.length() > 100 || password.length() < 8) {
            redirect.addFlashAttribute("error",
                    "Benutzername (1–100 Zeichen) und Passwort (mindestens 8 Zeichen) sind erforderlich.");
            return "redirect:/admin/users";
        }
        try {
            userService.create(name, password, role);
            redirect.addFlashAttribute("success", "Benutzer '" + name + "' wurde angelegt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }
}
