package club.badernet.nestwerk.web;

import club.badernet.nestwerk.attachment.Attachment;
import club.badernet.nestwerk.attachment.AttachmentService;
import club.badernet.nestwerk.kitchen.Amounts;
import club.badernet.nestwerk.kitchen.KitchenAccess;
import club.badernet.nestwerk.kitchen.KitchenPermission;
import club.badernet.nestwerk.kitchen.Meal;
import club.badernet.nestwerk.kitchen.MealEater;
import club.badernet.nestwerk.kitchen.MealPlanService;
import club.badernet.nestwerk.kitchen.MealSlot;
import club.badernet.nestwerk.kitchen.RecipeService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Wochenplan fürs Essen mit Mitessern, Kalorienbilanz und „Woche auffüllen“. */
@Controller
@RequestMapping("/kitchen")
@PreAuthorize("hasAuthority('KITCHEN_VIEW')")
public class MealPlanController {

    record Eater(String name, String initial, String color, String portions) {
    }

    record MealChip(Long id, String title, Long recipeId, String kcal, Long photoId,
                    List<Eater> eaters, boolean cooked) {
    }

    record SlotCell(MealSlot slot, List<MealChip> meals) {
    }

    record Balance(String name, String color, String text, int percent, boolean over) {
    }

    record DayColumn(LocalDate date, String weekday, String label, boolean today, List<SlotCell> cells,
                     List<Balance> balances) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d.M.");

    private final MealPlanService plan;
    private final RecipeService recipes;
    private final AttachmentService attachments;
    private final KitchenAccess access;
    private final CurrentUser currentUser;

    public MealPlanController(MealPlanService plan, RecipeService recipes, AttachmentService attachments,
                              KitchenAccess access, CurrentUser currentUser) {
        this.plan = plan;
        this.recipes = recipes;
        this.attachments = attachments;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping
    String start() {
        return "redirect:/kitchen/plan";
    }

    @GetMapping("/plan")
    String week(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                Model model) {
        User viewer = currentUser.require();
        LocalDate today = plan.today();
        MealPlanService.Week w = plan.week(week == null ? today : week, viewer);
        Map<Long, FamilyMember> people = new LinkedHashMap<>();
        w.members().forEach(m -> people.put(m.getId(), m));
        List<DayColumn> columns = new ArrayList<>();
        for (LocalDate day : w.days()) {
            List<SlotCell> cells = new ArrayList<>();
            Map<Long, Double> kcal = new HashMap<>();
            for (MealSlot slot : w.slots()) {
                List<MealChip> chips = new ArrayList<>();
                for (Meal m : w.meals()) {
                    if (!m.getDay().equals(day) || !m.getSlot().getId().equals(slot.getId())) {
                        continue;
                    }
                    Double perServing = MealPlanService.kcalPerServing(m.getRecipe());
                    List<Eater> eaters = new ArrayList<>();
                    for (MealEater e : m.getEaters()) {
                        FamilyMember p = people.get(e.getMemberId());
                        if (p == null) {
                            continue;
                        }
                        eaters.add(new Eater(p.getName(), p.getName().substring(0, 1), p.getColor(),
                                e.getPortions() == 1.0 ? "" : Amounts.number(e.getPortions()) + "×"));
                        if (perServing != null) {
                            kcal.merge(p.getId(), perServing * e.getPortions(), Double::sum);
                        }
                    }
                    chips.add(new MealChip(m.getId(), m.getTitle(), m.getRecipe() == null ? null : m.getRecipe().getId(),
                            perServing == null ? null : KitchenViews.kcal(perServing),
                            m.getRecipe() == null ? null : photoId(m.getRecipe().getId()), eaters, m.isCooked()));
                }
                cells.add(new SlotCell(slot, chips));
            }
            List<Balance> balances = new ArrayList<>();
            for (FamilyMember p : w.members()) {
                double sum = kcal.getOrDefault(p.getId(), 0.0);
                if (p.getKcalGoal() == null && sum == 0) {
                    continue;
                }
                String text = Amounts.number(Math.round(sum)) + (p.getKcalGoal() == null ? " kcal"
                        : " / " + Amounts.number(p.getKcalGoal()) + " kcal");
                int percent = p.getKcalGoal() == null ? 0 : (int) Math.min(100, Math.round(sum * 100 / p.getKcalGoal()));
                boolean over = p.getKcalGoal() != null && sum > p.getKcalGoal() * 1.1;
                balances.add(new Balance(p.getName(), p.getColor(), text, percent, over));
            }
            columns.add(new DayColumn(day, day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.GERMAN),
                    DAY.format(day), day.equals(today), cells, balances));
        }
        model.addAttribute("days", columns);
        model.addAttribute("monday", w.monday());
        model.addAttribute("previous", w.monday().minusWeeks(1));
        model.addAttribute("next", w.monday().plusWeeks(1));
        model.addAttribute("thisWeek", MealPlanService.monday(today));
        model.addAttribute("weekLabel", DAY.format(w.monday()) + " – " + DAY.format(w.monday().plusDays(6))
                + w.monday().plusDays(6).getYear());
        model.addAttribute("recipes", recipes.search(null, viewer).stream().map(r -> KitchenViews.card(r, null)).toList());
        model.addAttribute("canPlan", access.has(viewer, KitchenPermission.KITCHEN_PLAN));
        return "kitchen/plan";
    }

    /** Neue Mahlzeit; per Skript ohne {@code back} (204), aus dem Formular mit {@code back} (Weiterleitung). */
    @PostMapping("/plan/meals")
    @PreAuthorize("hasAuthority('KITCHEN_PLAN')")
    Object add(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
               @RequestParam Long slotId, @RequestParam(required = false) Long recipeId,
               @RequestParam(required = false) String title, @RequestParam(required = false) String back,
               RedirectAttributes redirect) {
        try {
            plan.add(day, slotId, recipeId, title, currentUser.require());
        } catch (IllegalArgumentException e) {
            if (back == null) {
                return ResponseEntity.badRequest().body(e.getMessage());
            }
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return back == null ? ResponseEntity.noContent().build() : "redirect:" + weekUrl(day);
    }

    @PostMapping("/plan/meals/{id}/move")
    @PreAuthorize("hasAuthority('KITCHEN_PLAN')")
    ResponseEntity<Void> move(@PathVariable Long id,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                              @RequestParam Long slotId) {
        plan.move(id, day, slotId, currentUser.require());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/plan/meals/{id}")
    String meal(@PathVariable Long id, Model model) {
        User viewer = currentUser.require();
        Meal meal = plan.get(id, viewer);
        List<FamilyMember> people = plan.week(meal.getDay(), viewer).members();
        Map<Long, Double> portions = new HashMap<>();
        meal.getEaters().forEach(e -> portions.put(e.getMemberId(), e.getPortions()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FamilyMember p : people) {
            Map<String, Object> row = new HashMap<>();
            row.put("member", p);
            row.put("eats", portions.containsKey(p.getId()));
            row.put("portions", Amounts.plain(portions.getOrDefault(p.getId(),
                    meal.getRecipe() == null ? 1.0 : plan.suggestedPortions(meal.getRecipe(), meal.getSlot(), p))));
            row.put("suggested", meal.getRecipe() == null ? null
                    : Amounts.number(plan.suggestedPortions(meal.getRecipe(), meal.getSlot(), p)));
            rows.add(row);
        }
        Double perServing = MealPlanService.kcalPerServing(meal.getRecipe());
        model.addAttribute("meal", meal);
        model.addAttribute("dayLabel", meal.getDay().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.GERMAN)
                + ", " + DAY.format(meal.getDay()));
        model.addAttribute("rows", rows);
        model.addAttribute("kcal", perServing == null ? null : KitchenViews.kcal(perServing));
        model.addAttribute("canPlan", access.has(viewer, KitchenPermission.KITCHEN_PLAN));
        model.addAttribute("back", weekUrl(meal.getDay()));
        return "kitchen/meal";
    }

    @PostMapping("/plan/meals/{id}")
    @PreAuthorize("hasAuthority('KITCHEN_PLAN')")
    String updateEaters(@PathVariable Long id, @RequestParam(name = "eater", required = false) List<Long> eaters,
                        @RequestParam Map<String, String> params, RedirectAttributes redirect) {
        User actor = currentUser.require();
        Map<Long, Double> portions = new LinkedHashMap<>();
        try {
            for (Long member : eaters == null ? List.<Long>of() : eaters) {
                String raw = params.getOrDefault("portions_" + member, "1");
                portions.put(member, raw.isBlank() ? 1.0 : Amounts.parse(raw));
            }
            Meal meal = plan.updateEaters(id, portions, actor);
            redirect.addFlashAttribute("success", "Gespeichert.");
            return "redirect:" + weekUrl(meal.getDay());
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Portionen bitte als Zahl angeben.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/plan/meals/" + id;
    }

    @PostMapping("/plan/meals/{id}/delete")
    @PreAuthorize("hasAuthority('KITCHEN_PLAN')")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        User actor = currentUser.require();
        LocalDate day = plan.get(id, actor).getDay();
        plan.delete(id, actor);
        redirect.addFlashAttribute("success", "Aus dem Plan genommen.");
        return "redirect:" + weekUrl(day);
    }

    @PostMapping("/plan/fill")
    @PreAuthorize("hasAuthority('KITCHEN_PLAN')")
    String fill(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                RedirectAttributes redirect) {
        int filled = plan.autoFill(week, currentUser.require());
        redirect.addFlashAttribute(filled > 0 ? "success" : "error", filled > 0
                ? filled + (filled == 1 ? " Mahlzeit" : " Mahlzeiten") + " eingeplant."
                : "Nichts aufgefüllt – alles belegt oder keine passenden Rezepte.");
        return "redirect:" + weekUrl(week);
    }

    private Long photoId(Long recipeId) {
        return attachments.forRecipe(recipeId).stream().filter(Attachment::isImage).map(Attachment::getId)
                .findFirst().orElse(null);
    }

    static String weekUrl(LocalDate day) {
        return "/kitchen/plan?week=" + MealPlanService.monday(day);
    }
}
