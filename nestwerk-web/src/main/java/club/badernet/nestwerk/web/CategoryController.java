package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CategoryColor;
import club.badernet.nestwerk.calendar.CategoryInput;
import club.badernet.nestwerk.calendar.EventCategory;
import club.badernet.nestwerk.calendar.EventCategoryService;
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

/** Managing the event categories and their colours. */
@Controller
@RequestMapping("/calendar/categories")
@PreAuthorize("hasAuthority('CALENDAR_CATEGORY_MANAGE')")
public class CategoryController {

    private static final String REDIRECT = "redirect:/calendar/categories";

    private final EventCategoryService categories;
    private final CurrentUser currentUser;

    public CategoryController(EventCategoryService categories, CurrentUser currentUser) {
        this.categories = categories;
        this.currentUser = currentUser;
    }

    private static String backSuffix(String back) {
        return SafeRedirect.withBack("", back);
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("categories", categories.list(currentUser.require()));
        model.addAttribute("colors", CategoryColor.values());
        return "calendar/categories";
    }

    @PostMapping
    String create(@RequestParam String name, @RequestParam(required = false) String color,
                  @RequestParam(required = false) String back, RedirectAttributes redirect) {
        try {
            EventCategory created = categories.create(new CategoryInput(name, CategoryColor.parse(color)),
                    currentUser.require());
            redirect.addFlashAttribute("success", "Kategorie „" + created.getName() + "“ ist angelegt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return REDIRECT + backSuffix(back);
    }

    @PostMapping("/{id}")
    String update(@PathVariable Long id, @RequestParam String name, @RequestParam(required = false) String color,
                  @RequestParam(required = false) String back, RedirectAttributes redirect) {
        try {
            categories.update(id, new CategoryInput(name, CategoryColor.parse(color)), currentUser.require());
            redirect.addFlashAttribute("success", "Gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return REDIRECT + backSuffix(back);
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable Long id, @RequestParam(required = false) String back, RedirectAttributes redirect) {
        categories.delete(id, currentUser.require());
        redirect.addFlashAttribute("success", "Kategorie ist gelöscht. Ihre Termine haben jetzt keine Kategorie.");
        return REDIRECT + backSuffix(back);
    }
}
