package club.badernet.nestwerk.web;

import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.security.acl.AclRule;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.security.PermissionCatalog;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/roles")
@PreAuthorize("hasAuthority('ACCESS_ROLE_MANAGE')")
public class AdminRoleController {

    private final RoleService roleService;
    private final PermissionCatalog catalog;
    private final AclService acl;
    private final CurrentUser currentUser;

    public AdminRoleController(RoleService roleService, PermissionCatalog catalog, AclService acl,
                               CurrentUser currentUser) {
        this.roleService = roleService;
        this.catalog = catalog;
        this.acl = acl;
        this.currentUser = currentUser;
    }

    @GetMapping
    String list(Authentication actor, Model model) {
        var allRoles = roleService.findAll();
        model.addAttribute("roles", allRoles);
        model.addAttribute("grantable", permissions(actor));
        model.addAttribute("permissionsByModule", catalog.byModule());

        Map<Long, Set<String>> stored = acl.ruleKeysByRole();
        Map<Long, Set<String>> roleRules = new HashMap<>();
        allRoles.forEach(r -> roleRules.put(r.getId(), stored.getOrDefault(r.getId(), Set.of())));
        Optional<User> me = currentUser.find();
        boolean actorIsAdmin = me.map(User::isAdministrator).orElse(false);
        Set<String> actorRules = new LinkedHashSet<>();
        me.ifPresent(u -> acl.rulesOf(u).forEach(rule -> actorRules.add(rule.key())));

        model.addAttribute("aclMatrix", acl.matrix());
        model.addAttribute("aclPresets", acl.presets());
        model.addAttribute("roleRules", roleRules);
        model.addAttribute("actorRules", actorRules);
        model.addAttribute("actorIsAdmin", actorIsAdmin);
        return "admin/roles";
    }

    @PostMapping
    String create(@RequestParam String name,
                  @RequestParam(required = false) String description,
                  @RequestParam(required = false) Set<String> permissions,
                  Authentication actor,
                  RedirectAttributes redirect) {
        return run(redirect, "Rolle '" + name.trim() + "' wurde angelegt.", () -> {
            roleService.requireMayManage(permissions(actor), null, permissions);
            roleService.create(name, description, permissions);
        });
    }

    @PostMapping("/{id}")
    String update(@PathVariable Long id,
                  @RequestParam(required = false) String name,
                  @RequestParam(required = false) String description,
                  @RequestParam(required = false) Set<String> permissions,
                  Authentication actor,
                  RedirectAttributes redirect) {
        return run(redirect, "Rolle wurde gespeichert.", () -> {
            roleService.requireMayManage(permissions(actor), id, permissions);
            roleService.update(id, name, description, permissions);
        });
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable Long id, Authentication actor, RedirectAttributes redirect) {
        return run(redirect, "Rolle wurde gelöscht.", () -> {
            roleService.requireMayManage(permissions(actor), id, null);
            roleService.delete(id);
        });
    }

    @PostMapping("/{id}/acl")
    String updateAcl(@PathVariable Long id, @RequestParam(required = false) Set<String> rules,
                     RedirectAttributes redirect) {
        return run(redirect, "Zugriffsregeln wurden gespeichert.", () -> {
            Set<AclRule> parsed = (rules == null ? Set.<String>of() : rules).stream()
                    .map(AclRule::parse).collect(Collectors.toSet());
            acl.replaceRules(currentUser.require(), id, parsed);
        });
    }

    @PostMapping("/{id}/acl/preset")
    String applyPreset(@PathVariable Long id, @RequestParam String preset, RedirectAttributes redirect) {
        return run(redirect, "Vorlage wurde übernommen.",
                () -> acl.applyPreset(currentUser.require(), id, preset));
    }

    private static Set<String> permissions(Authentication actor) {
        return actor.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
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
