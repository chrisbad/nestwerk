package club.badernet.nestwerk.web;

import club.badernet.nestwerk.security.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The register of all settings: one searchable, sortable table instead of a hunt through menus. */
@Controller
public class SettingsController {

    private final Settings settings;
    private final CurrentUser currentUser;

    public SettingsController(Settings settings, CurrentUser currentUser) {
        this.settings = settings;
        this.currentUser = currentUser;
    }

    /** Filtering and sorting happen in the browser (data-table), the address carries the state. */
    @GetMapping("/settings")
    String register(Model model) {
        model.addAttribute("entries", settings.list(currentUser.require(), SettingFilter.none()));
        model.addAttribute("areas", SettingArea.values());
        model.addAttribute("scopes", SettingScope.values());
        model.addAttribute("statuses", SettingStatus.values());
        return "settings/index";
    }
}
