package club.badernet.nestwerk.web;

import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.PermissionCatalog;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;

@Controller
@RequestMapping("/admin/roles")
@PreAuthorize("hasAuthority('ACCESS_ROLE_MANAGE')")
public class AdminRoleController {

    private final RoleService roleService;

    public AdminRoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("roles", roleService.findAll());
        model.addAttribute("permissionsByModule", PermissionCatalog.byModule());
        return "admin/roles";
    }

    @PostMapping
    String create(@RequestParam String name,
                  @RequestParam(required = false) String description,
                  @RequestParam(required = false) Set<String> permissions,
                  RedirectAttributes redirect) {
        return run(redirect, "Rolle '" + name.trim() + "' wurde angelegt.",
                () -> roleService.create(name, description, permissions));
    }

    @PostMapping("/{id}")
    String update(@PathVariable Long id,
                  @RequestParam(required = false) String name,
                  @RequestParam(required = false) String description,
                  @RequestParam(required = false) Set<String> permissions,
                  RedirectAttributes redirect) {
        return run(redirect, "Rolle wurde gespeichert.",
                () -> roleService.update(id, name, description, permissions));
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        return run(redirect, "Rolle wurde gelöscht.", () -> roleService.delete(id));
    }

    private String run(RedirectAttributes redirect, String successMessage, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", successMessage);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/roles";
    }
}
