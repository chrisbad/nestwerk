package club.badernet.nestwerk.web;

import club.badernet.nestwerk.user.Role;
import club.badernet.nestwerk.user.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
        addListAttributes(model);
        return "admin/users";
    }

    /**
     * Legt einen Benutzer an. Bei htmx-Anfragen ({@code HX-Request}) wird nur die
     * aktualisierte Tabelle geliefert, sonst wie gewohnt per Redirect mit Flash-Meldung.
     */
    @PostMapping
    String create(@RequestParam String username,
                  @RequestParam String password,
                  @RequestParam Role role,
                  @RequestHeader(name = "HX-Request", required = false) String htmxRequest,
                  Model model,
                  RedirectAttributes redirect) {
        String name = username.trim();
        String error = null;
        if (name.isEmpty() || name.length() > 100 || password.length() < 8) {
            error = "Benutzername (1–100 Zeichen) und Passwort (mindestens 8 Zeichen) sind erforderlich.";
        } else {
            try {
                userService.create(name, password, role);
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
            }
        }
        String success = error == null ? "Benutzer '" + name + "' wurde angelegt." : null;

        if ("true".equals(htmxRequest)) {
            addListAttributes(model);
            model.addAttribute("error", error);
            model.addAttribute("success", success);
            model.addAttribute("created", error == null ? name : null);
            return "admin/users :: panel";
        }
        if (error != null) {
            redirect.addFlashAttribute("error", error);
        } else {
            redirect.addFlashAttribute("success", success);
            redirect.addFlashAttribute("created", name);
        }
        return "redirect:/admin/users";
    }

    private void addListAttributes(Model model) {
        model.addAttribute("users", userService.findAll());
        model.addAttribute("roles", Role.values());
    }
}
