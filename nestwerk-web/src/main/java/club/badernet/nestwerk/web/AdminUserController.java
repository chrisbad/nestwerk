package club.badernet.nestwerk.web;

import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasAuthority('USER_MANAGE')")
public class AdminUserController {

    private final UserService userService;
    private final RoleRepository roleRepository;
    private final AclService acl;

    public AdminUserController(UserService userService, RoleRepository roleRepository, AclService acl) {
        this.userService = userService;
        this.roleRepository = roleRepository;
        this.acl = acl;
    }

    @GetMapping
    String list(Model model) {
        addListAttributes(model);
        return "admin/users";
    }

    /** Details of one user for the drawer. */
    @GetMapping("/{id}/edit")
    String edit(@PathVariable Long id, Model model) {
        model.addAttribute("user", userService.findAll().stream().filter(u -> u.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        model.addAttribute("roles", roleRepository.findAllByOrderByNameAsc());
        return "admin/user-drawer :: content";
    }

    /**
     * Legt einen Benutzer an. Bei htmx-Anfragen ({@code HX-Request}) wird nur die
     * aktualisierte Tabelle geliefert, sonst wie gewohnt per Redirect mit Flash-Meldung.
     */
    @PostMapping
    String create(@RequestParam String username,
                  @RequestParam String password,
                  @RequestParam(required = false) Set<Long> roleIds,
                  @RequestHeader(name = "HX-Request", required = false) String htmxRequest,
                  Authentication actor,
                  Model model,
                  RedirectAttributes redirect) {
        String name = username.trim();
        Set<Long> roles = roleIds == null ? Set.of() : roleIds;
        String error = attempt(() -> {
            if (name.isEmpty() || name.length() > 100) {
                throw new IllegalArgumentException("Der Benutzername muss 1–100 Zeichen lang sein.");
            }
            userService.requireMayManage(permissions(actor), null, roles);
            acl.requireMayAssign(actor.getName(), permissions(actor), null, roles);
            userService.create(name, password, roles);
        });
        String success = error == null ? "Benutzer '" + name + "' wurde angelegt." : null;

        if ("true".equals(htmxRequest)) {
            addListAttributes(model);
            model.addAttribute("error", error);
            model.addAttribute("success", success);
            model.addAttribute("created", error == null ? name : null);
            return "admin/users :: panel";
        }
        if (error == null) {
            redirect.addFlashAttribute("created", name);
        }
        return flash(redirect, success, error);
    }

    @PostMapping("/{id}/roles")
    String changeRoles(@PathVariable Long id,
                       @RequestParam(required = false) Set<Long> roleIds,
                       Authentication actor,
                       RedirectAttributes redirect) {
        Set<Long> roles = roleIds == null ? Set.of() : roleIds;
        return flash(redirect, "Rollen wurden gespeichert.", attempt(() -> {
            userService.requireMayManage(permissions(actor), id, roles);
            acl.requireMayAssign(actor.getName(), permissions(actor), id, roles);
            userService.changeRoles(id, roles);
        }));
    }

    @PostMapping("/{id}/enabled")
    String setEnabled(@PathVariable Long id, @RequestParam boolean enabled,
                      Authentication actor, RedirectAttributes redirect) {
        return flash(redirect, enabled ? "Benutzer wurde aktiviert." : "Benutzer wurde deaktiviert.", attempt(() -> {
            userService.requireMayManage(permissions(actor), id, null);
            acl.requireMayAssign(actor.getName(), permissions(actor), id, null);
            userService.setEnabled(id, enabled);
        }));
    }

    @PostMapping("/{id}/password")
    String resetPassword(@PathVariable Long id, @RequestParam String password,
                         Authentication actor, RedirectAttributes redirect) {
        return flash(redirect, "Passwort wurde zurückgesetzt.", attempt(() -> {
            userService.requireMayManage(permissions(actor), id, null);
            acl.requireMayAssign(actor.getName(), permissions(actor), id, null);
            userService.resetPassword(id, password);
        }));
    }

    /** Führt die Aktion aus und liefert die Fehlermeldung bei Regelverstoß, sonst {@code null}. */
    private static String attempt(Runnable action) {
        try {
            action.run();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static String flash(RedirectAttributes redirect, String success, String error) {
        if (error != null) {
            redirect.addFlashAttribute("error", error);
        } else {
            redirect.addFlashAttribute("success", success);
        }
        return "redirect:/admin/users";
    }

    private static Set<String> permissions(Authentication actor) {
        return actor.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private void addListAttributes(Model model) {
        model.addAttribute("users", userService.findAll());
        model.addAttribute("roles", roleRepository.findAllByOrderByNameAsc());
    }
}
