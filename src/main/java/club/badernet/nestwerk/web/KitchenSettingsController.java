package club.badernet.nestwerk.web;

import club.badernet.nestwerk.kitchen.BringSync;
import club.badernet.nestwerk.kitchen.KitchenSettings;
import club.badernet.nestwerk.kitchen.KitchenSettingsService;
import club.badernet.nestwerk.kitchen.integration.BringList;
import club.badernet.nestwerk.kitchen.integration.ExternalServiceException;
import club.badernet.nestwerk.kitchen.MealSlotRepository;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Einstellungen der Küche: Mahlzeiten, Kalorienziele und Bring!. */
@Controller
@RequestMapping("/kitchen/settings")
@PreAuthorize("hasAuthority('KITCHEN_SETTINGS')")
public class KitchenSettingsController {

    private final KitchenSettingsService settings;
    private final MealSlotRepository slots;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final BringSync bring;
    private final KitchenSettings kitchenSettings;

    public KitchenSettingsController(KitchenSettingsService settings, MealSlotRepository slots,
                                     FamilyMemberService members, CurrentUser currentUser, BringSync bring,
                                     KitchenSettings kitchenSettings) {
        this.bring = bring;
        this.kitchenSettings = kitchenSettings;
        this.settings = settings;
        this.slots = slots;
        this.members = members;
        this.currentUser = currentUser;
    }

    @GetMapping
    String page(Model model) {
        model.addAttribute("slots", slots.findAllByOrderBySortOrderAsc());
        model.addAttribute("members", members.findAll());
        KitchenSettings.BringAccount account = kitchenSettings.bring();
        model.addAttribute("bringEmail", account.email());
        model.addAttribute("bringConnected", account.configured());
        model.addAttribute("bringListUuid", account.listUuid());
        model.addAttribute("bringListName", account.listName());
        List<BringList> lists = List.of();
        if (account.configured()) {
            try {
                lists = bring.lists(currentUser.require());
            } catch (ExternalServiceException e) {
                model.addAttribute("bringError", e.getMessage());
            }
        }
        model.addAttribute("bringLists", lists);
        return "kitchen/settings";
    }

    @PostMapping("/bring")
    String connectBring(@RequestParam String email, @RequestParam String password, RedirectAttributes redirect) {
        try {
            bring.connect(email, password, currentUser.require());
            redirect.addFlashAttribute("success", "Mit Bring! verbunden.");
        } catch (IllegalArgumentException | ExternalServiceException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/settings";
    }

    @PostMapping("/bring/list")
    String chooseBringList(@RequestParam String listUuid, RedirectAttributes redirect) {
        try {
            bring.chooseList(listUuid, currentUser.require());
            redirect.addFlashAttribute("success", "Liste gewählt.");
        } catch (IllegalArgumentException | ExternalServiceException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/settings";
    }

    @PostMapping("/bring/disconnect")
    String disconnectBring(RedirectAttributes redirect) {
        bring.disconnect(currentUser.require());
        redirect.addFlashAttribute("success", "Bring! getrennt.");
        return "redirect:/kitchen/settings";
    }

    @PostMapping("/goals")
    String goals(@RequestParam Map<String, String> params, RedirectAttributes redirect) {
        Map<Long, Integer> goals = new HashMap<>();
        try {
            params.forEach((key, value) -> {
                if (key.startsWith("goal_")) {
                    goals.put(Long.valueOf(key.substring(5)), value.isBlank() ? null : Integer.valueOf(value.strip()));
                }
            });
            settings.setGoals(goals, currentUser.require());
            redirect.addFlashAttribute("success", "Kalorienziele gespeichert.");
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Kalorienziele bitte als ganze Zahl angeben.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/settings";
    }

    @PostMapping("/slots")
    String createSlot(@RequestParam String name, @RequestParam(defaultValue = "0") int share,
                      @RequestParam(defaultValue = "false") boolean autoFill, RedirectAttributes redirect) {
        try {
            settings.createSlot(name, share, autoFill, currentUser.require());
            redirect.addFlashAttribute("success", "Mahlzeit angelegt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/settings";
    }

    @PostMapping("/slots/{id}")
    String updateSlot(@PathVariable Long id, @RequestParam String name, @RequestParam(defaultValue = "0") int share,
                      @RequestParam(defaultValue = "false") boolean autoFill, RedirectAttributes redirect) {
        try {
            settings.updateSlot(id, name, share, autoFill, currentUser.require());
            redirect.addFlashAttribute("success", "Gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/settings";
    }

    @PostMapping("/slots/{id}/delete")
    String deleteSlot(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            settings.deleteSlot(id, currentUser.require());
            redirect.addFlashAttribute("success", "Mahlzeit gelöscht.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/settings";
    }
}
