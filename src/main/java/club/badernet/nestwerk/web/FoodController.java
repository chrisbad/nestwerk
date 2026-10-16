package club.badernet.nestwerk.web;

import club.badernet.nestwerk.kitchen.Food;
import club.badernet.nestwerk.kitchen.FoodCategory;
import club.badernet.nestwerk.kitchen.FoodService;
import club.badernet.nestwerk.kitchen.FoodSource;
import club.badernet.nestwerk.kitchen.KitchenAccess;
import club.badernet.nestwerk.kitchen.KitchenPermission;
import club.badernet.nestwerk.kitchen.integration.ExternalServiceException;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Lebensmittel mit Nährwerten pflegen. */
@Controller
@RequestMapping("/kitchen/foods")
@PreAuthorize("hasAuthority('KITCHEN_VIEW')")
public class FoodController {

    private final FoodService foods;
    private final KitchenAccess access;
    private final CurrentUser currentUser;

    public FoodController(FoodService foods, KitchenAccess access, CurrentUser currentUser) {
        this.foods = foods;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping
    String list(@RequestParam(required = false) String q, Model model) {
        User viewer = currentUser.require();
        model.addAttribute("foods", foods.search(q, viewer));
        model.addAttribute("q", q);
        model.addAttribute("canEdit", access.has(viewer, KitchenPermission.KITCHEN_RECIPES_EDIT));
        return "kitchen/foods";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String newForm(@RequestParam(required = false) String name, @RequestParam(required = false) String barcode,
                   Model model) {
        FoodForm form = new FoodForm();
        form.setName(name);
        form.setBarcode(barcode);
        return form(form, null, model);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String create(@ModelAttribute("form") FoodForm form, Model model, RedirectAttributes redirect) {
        try {
            Food food = foods.create(form.toInput(), FoodSource.MANUAL, currentUser.require());
            redirect.addFlashAttribute("success", "„" + food.getName() + "“ ist gespeichert.");
            return "redirect:/kitchen/foods?q=" + java.net.URLEncoder.encode(food.getName(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return form(form, null, model);
        }
    }

    @PostMapping("/barcode")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String barcode(@RequestParam String code, RedirectAttributes redirect) {
        try {
            return foods.fromBarcode(code, currentUser.require()).map(match -> {
                redirect.addFlashAttribute("success", match.created()
                        ? "„" + match.food().getName() + "“ aus Open Food Facts übernommen – bitte Abteilung prüfen."
                        : "„" + match.food().getName() + "“ gefunden.");
                return "redirect:/kitchen/foods/" + match.food().getId();
            }).orElseGet(() -> {
                redirect.addFlashAttribute("error", "Produkt unbekannt – bitte von Hand anlegen.");
                return "redirect:/kitchen/foods/new?barcode=" + code.replaceAll("\\D", "");
            });
        } catch (ExternalServiceException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/kitchen/foods";
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String edit(@PathVariable Long id, Model model) {
        return form(FoodForm.from(foods.get(id)), id, model);
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String update(@PathVariable Long id, @ModelAttribute("form") FoodForm form, Model model,
                  RedirectAttributes redirect) {
        try {
            foods.update(id, form.toInput(), currentUser.require());
            redirect.addFlashAttribute("success", "Gespeichert.");
            return "redirect:/kitchen/foods";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return form(form, id, model);
        }
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        foods.delete(id, currentUser.require());
        redirect.addFlashAttribute("success", "Lebensmittel gelöscht.");
        return "redirect:/kitchen/foods";
    }

    private String form(FoodForm form, Long id, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("foodId", id);
        model.addAttribute("categories", FoodCategory.values());
        return "kitchen/food-form";
    }
}
