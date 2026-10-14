package club.badernet.nestwerk.web;

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

@Controller
@RequestMapping("/admin/permissions")
@PreAuthorize("hasAuthority('ACCESS_ROLE_MANAGE')")
public class AdminPermissionController {

    private final PermissionCatalog catalog;

    public AdminPermissionController(PermissionCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("permissionsByModule", catalog.byModule());
        return "admin/permissions";
    }

    @PostMapping("/{key}")
    String update(@PathVariable String key, @RequestParam(required = false) String label,
                  @RequestParam(required = false) String description, RedirectAttributes redirect) {
        try {
            catalog.updateText(key, label, description);
            redirect.addFlashAttribute("success", "Recht wurde gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/permissions";
    }
}
