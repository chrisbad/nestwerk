package club.badernet.nestwerk.web;

import club.badernet.nestwerk.attachment.Attachment;
import club.badernet.nestwerk.attachment.AttachmentOwner;
import club.badernet.nestwerk.attachment.AttachmentService;
import club.badernet.nestwerk.kitchen.FoodRepository;
import club.badernet.nestwerk.kitchen.KitchenAccess;
import club.badernet.nestwerk.kitchen.KitchenPermission;
import club.badernet.nestwerk.kitchen.NutritionCalculator;
import club.badernet.nestwerk.kitchen.Recipe;
import club.badernet.nestwerk.kitchen.RecipeNutrition;
import club.badernet.nestwerk.kitchen.RecipeService;
import club.badernet.nestwerk.kitchen.Unit;
import club.badernet.nestwerk.kitchen.integration.RecipeImporter;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** Rezepte: Liste mit Suche, Ansicht mit Nährwerten und Skalierung, Formular, Foto. */
@Controller
@RequestMapping("/kitchen/recipes")
@PreAuthorize("hasAuthority('KITCHEN_VIEW')")
public class RecipeController {

    private final RecipeService recipes;
    private final FoodRepository foods;
    private final AttachmentService attachments;
    private final KitchenAccess access;
    private final MarkdownRenderer markdown;
    private final CurrentUser currentUser;
    private final RecipeImporter importer;

    public RecipeController(RecipeService recipes, FoodRepository foods, AttachmentService attachments,
                            KitchenAccess access, MarkdownRenderer markdown, CurrentUser currentUser,
                            RecipeImporter importer) {
        this.importer = importer;
        this.recipes = recipes;
        this.foods = foods;
        this.attachments = attachments;
        this.access = access;
        this.markdown = markdown;
        this.currentUser = currentUser;
    }

    @GetMapping
    String list(@RequestParam(required = false) String q, Model model) {
        User viewer = currentUser.require();
        List<KitchenViews.RecipeCard> cards = recipes.search(q, viewer).stream()
                .map(r -> KitchenViews.card(r, photoId(r.getId()))).toList();
        model.addAttribute("recipes", cards);
        model.addAttribute("q", q);
        model.addAttribute("canEdit", access.has(viewer, KitchenPermission.KITCHEN_RECIPES_EDIT));
        return "kitchen/recipes";
    }

    @GetMapping("/{id}")
    String show(@PathVariable Long id, @RequestParam(required = false) Double servings, Model model) {
        User viewer = currentUser.require();
        Recipe recipe = recipes.get(id, viewer);
        double wanted = servings == null || servings <= 0 || servings > 100 ? recipe.getServings() : servings;
        RecipeNutrition nutrition = NutritionCalculator.forRecipe(recipe);
        model.addAttribute("recipe", recipe);
        model.addAttribute("servings", wanted);
        model.addAttribute("servingsLabel", club.badernet.nestwerk.kitchen.Amounts.number(wanted));
        model.addAttribute("ingredients", KitchenViews.ingredients(recipe, wanted));
        model.addAttribute("perServing", KitchenViews.NutritionView.of(nutrition.perServing()));
        model.addAttribute("total", KitchenViews.NutritionView.of(nutrition.perServing().times(wanted)));
        model.addAttribute("missing", nutrition.missing());
        model.addAttribute("stepsHtml", recipe.getSteps() == null ? null : markdown.render(recipe.getSteps()));
        model.addAttribute("photos", attachments.forRecipe(id));
        model.addAttribute("canEdit", access.has(viewer, KitchenPermission.KITCHEN_RECIPES_EDIT));
        return "kitchen/recipe";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String newForm(Model model) {
        RecipeForm form = new RecipeForm();
        return form(form, null, model);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String create(@ModelAttribute("form") RecipeForm form, Model model, RedirectAttributes redirect) {
        try {
            Recipe saved = recipes.create(form.toInput(), currentUser.require());
            redirect.addFlashAttribute("success", "„" + saved.getTitle() + "“ ist gespeichert.");
            return "redirect:/kitchen/recipes/" + saved.getId();
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return form(form, null, model);
        }
    }

    @GetMapping("/import")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String importForm() {
        return "kitchen/recipe-import";
    }

    /** Liest das Rezept einer Seite und öffnet das Formular vorausgefüllt; gespeichert wird erst nach Prüfung. */
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String importRecipe(@RequestParam String url, Model model) {
        try {
            RecipeForm form = RecipeForm.from(importer.importFrom(url));
            model.addAttribute("imported", true);
            return form(form, null, model);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("url", url);
            return "kitchen/recipe-import";
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String edit(@PathVariable Long id, Model model) {
        return form(RecipeForm.from(recipes.get(id, currentUser.require())), id, model);
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String update(@PathVariable Long id, @ModelAttribute("form") RecipeForm form, Model model,
                  RedirectAttributes redirect) {
        try {
            recipes.update(id, form.toInput(), currentUser.require());
            redirect.addFlashAttribute("success", "Gespeichert.");
            return "redirect:/kitchen/recipes/" + id;
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return form(form, id, model);
        }
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        recipes.delete(id, currentUser.require());
        redirect.addFlashAttribute("success", "Rezept gelöscht.");
        return "redirect:/kitchen/recipes";
    }

    @PostMapping("/{id}/photo")
    @PreAuthorize("hasAuthority('KITCHEN_RECIPES_EDIT')")
    String photo(@PathVariable Long id, @RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        User actor = currentUser.require();
        recipes.get(id, actor);
        try {
            attachments.store(AttachmentOwner.recipe(id), file, actor);
            redirect.addFlashAttribute("success", "Foto gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/recipes/" + id;
    }

    private String form(RecipeForm form, Long id, Model model) {
        form.setPaste(null);
        form.padRows(form.getRows().isEmpty() ? 3 : 1);
        model.addAttribute("form", form);
        model.addAttribute("recipeId", id);
        model.addAttribute("units", Unit.values());
        model.addAttribute("foodNames", foods.findAllByOrderByNameAsc().stream().map(f -> f.getName()).toList());
        return "kitchen/recipe-form";
    }

    private Long photoId(Long recipeId) {
        return attachments.forRecipe(recipeId).stream().filter(Attachment::isImage).map(Attachment::getId)
                .findFirst().orElse(null);
    }
}
