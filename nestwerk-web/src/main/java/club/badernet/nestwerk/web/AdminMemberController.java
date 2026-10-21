package club.badernet.nestwerk.web;

import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/members")
@PreAuthorize("hasAuthority('MEMBER_MANAGE')")
public class AdminMemberController {

    private final FamilyMemberService members;
    private final UserService userService;

    public AdminMemberController(FamilyMemberService members, UserService userService) {
        this.members = members;
        this.userService = userService;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("members", members.findAll());
        model.addAttribute("users", userService.findAll());
        model.addAttribute("accountNames", userService.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, User::getUsername)));
        return "admin/members";
    }

    /** Details of one family member for the drawer. */
    @GetMapping("/{id}/edit")
    String edit(@PathVariable Long id, Model model) {
        model.addAttribute("m", members.findAll().stream().filter(m -> m.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        model.addAttribute("users", userService.findAll());
        return "admin/member-drawer :: content";
    }

    @PostMapping
    String create(@RequestParam String name, @RequestParam String color,
                  @RequestParam(required = false) Long userId, RedirectAttributes redirect) {
        return run(redirect, "Mitglied wurde angelegt.", () -> members.create(name, color, userId));
    }

    @PostMapping("/{id}")
    String update(@PathVariable Long id, @RequestParam String name, @RequestParam String color,
                  @RequestParam(required = false) Long userId, RedirectAttributes redirect) {
        return run(redirect, "Mitglied wurde gespeichert.", () -> members.update(id, name, color, userId));
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        return run(redirect, "Mitglied wurde gelöscht.", () -> members.delete(id));
    }

    private String run(RedirectAttributes redirect, String successMessage, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", successMessage);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/members";
    }
}
