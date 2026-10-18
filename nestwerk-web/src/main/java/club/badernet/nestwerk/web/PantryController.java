package club.badernet.nestwerk.web;

import club.badernet.nestwerk.kitchen.Amounts;
import club.badernet.nestwerk.kitchen.Food;
import club.badernet.nestwerk.kitchen.FoodService;
import club.badernet.nestwerk.kitchen.KitchenAccess;
import club.badernet.nestwerk.kitchen.KitchenPermission;
import club.badernet.nestwerk.kitchen.Meal;
import club.badernet.nestwerk.kitchen.MealPlanService;
import club.badernet.nestwerk.kitchen.PantryItem;
import club.badernet.nestwerk.kitchen.PantryService;
import club.badernet.nestwerk.kitchen.Recipe;
import club.badernet.nestwerk.kitchen.Unit;
import club.badernet.nestwerk.kitchen.integration.ExternalServiceException;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Vorrat und „Gekocht“-Blatt. */
@Controller
@RequestMapping("/kitchen")
@PreAuthorize("hasAuthority('KITCHEN_VIEW')")
public class PantryController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d.M.yyyy");
    private static final String NO_PLACE = "Ohne Ort";

    record PantryRow(Long id, String name, String amount, String amountPlain, Unit unit, String location,
                     String bestBefore, String bestBeforeInput, String state, String hint) {
    }

    record PantryGroup(String place, List<PantryRow> rows) {
    }

    record CookRow(Long ingredientId, String text, String needed, String neededPlain, String unit, String from,
                   String note, boolean warning) {
    }

    private final PantryService pantry;
    private final FoodService foods;
    private final KitchenAccess access;
    private final CurrentUser currentUser;

    public PantryController(PantryService pantry, FoodService foods, KitchenAccess access, CurrentUser currentUser) {
        this.pantry = pantry;
        this.foods = foods;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping("/pantry")
    String page(@RequestParam(required = false) String name, @RequestParam(required = false) String amount,
                @RequestParam(required = false) String unit, Model model) {
        User viewer = currentUser.require();
        LocalDate today = pantry.today();
        Map<String, List<PantryRow>> groups = new LinkedHashMap<>();
        for (PantryItem i : pantry.list(viewer)) {
            groups.computeIfAbsent(i.getLocation() == null ? NO_PLACE : i.getLocation(), k -> new ArrayList<>())
                    .add(row(i, today));
        }
        List<PantryGroup> list = groups.entrySet().stream().map(e -> new PantryGroup(e.getKey(), e.getValue())).toList();
        model.addAttribute("groups", list);
        model.addAttribute("useUp", pantry.useUp(viewer).stream().map(r -> KitchenViews.card(r, null)).toList());
        model.addAttribute("foodNames", foods.search(null, viewer).stream().map(Food::getName).toList());
        model.addAttribute("units", Unit.values());
        model.addAttribute("prefillName", name);
        model.addAttribute("prefillAmount", amount == null ? "1" : amount);
        model.addAttribute("prefillUnit", unit == null ? Unit.PIECE.name() : unit);
        model.addAttribute("canEdit", access.has(viewer, KitchenPermission.KITCHEN_SHOPPING));
        return "kitchen/pantry";
    }

    private PantryRow row(PantryItem i, LocalDate today) {
        String state = "ok";
        String hint = null;
        if (i.getBestBefore() != null) {
            long days = ChronoUnit.DAYS.between(today, i.getBestBefore());
            if (days < 0) {
                state = "expired";
                hint = "abgelaufen";
            } else if (days <= PantryService.WARN_DAYS) {
                state = "soon";
                hint = days == 0 ? "läuft heute ab" : days == 1 ? "läuft morgen ab" : "läuft in " + days + " Tagen ab";
            }
        }
        return new PantryRow(i.getId(), i.getName(), Amounts.format(i.getAmount(), i.getUnit()),
                Amounts.plain(i.getAmount()), i.getUnit(), i.getLocation() == null ? "" : i.getLocation(),
                i.getBestBefore() == null ? null : DAY.format(i.getBestBefore()),
                i.getBestBefore() == null ? "" : i.getBestBefore().toString(), state, hint);
    }

    @PostMapping("/pantry")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String add(@RequestParam String name, @RequestParam String amount, @RequestParam(required = false) String unit,
               @RequestParam(required = false) String location,
               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bestBefore,
               RedirectAttributes redirect) {
        try {
            PantryItem item = pantry.add(name, Amounts.parse(amount), unit(unit), location, bestBefore,
                    currentUser.require());
            redirect.addFlashAttribute("success", "„" + item.getName() + "“ liegt jetzt im Vorrat.");
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Menge bitte als Zahl angeben.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/pantry";
    }

    @PostMapping("/pantry/{id}")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String change(@PathVariable Long id, @RequestParam String amount, @RequestParam(required = false) String unit,
                  @RequestParam(required = false) String location,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bestBefore,
                  RedirectAttributes redirect) {
        try {
            pantry.change(id, Amounts.parse(amount), unit(unit), location, bestBefore, currentUser.require());
            redirect.addFlashAttribute("success", "Gespeichert.");
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Menge bitte als Zahl angeben.");
        } catch (IllegalArgumentException | NoSuchElementException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/pantry";
    }

    @PostMapping("/pantry/{id}/take")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String take(@PathVariable Long id, @RequestParam String amount, RedirectAttributes redirect) {
        try {
            pantry.take(id, Amounts.parse(amount), currentUser.require());
            redirect.addFlashAttribute("success", "Entnommen.");
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Menge bitte als Zahl angeben.");
        } catch (IllegalArgumentException | NoSuchElementException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/pantry";
    }

    @PostMapping("/pantry/{id}/delete")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            pantry.delete(id, currentUser.require());
            redirect.addFlashAttribute("success", "Aus dem Vorrat entfernt.");
        } catch (NoSuchElementException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/pantry";
    }

    /** Barcode: Lebensmittel finden und das Formular damit vorbelegen. */
    @PostMapping("/pantry/barcode")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String barcode(@RequestParam String code, RedirectAttributes redirect) {
        try {
            var match = foods.fromBarcode(code, currentUser.require());
            if (match.isEmpty()) {
                redirect.addFlashAttribute("error", "Produkt unbekannt – bitte den Namen von Hand eingeben.");
                return "redirect:/kitchen/pantry";
            }
            Food food = match.get().food();
            boolean packaged = food.getPackageGrams() != null;
            redirect.addFlashAttribute("success", "„" + food.getName() + "“ erkannt – Menge und MHD prüfen.");
            return "redirect:/kitchen/pantry?name=" + java.net.URLEncoder.encode(food.getName(),
                    java.nio.charset.StandardCharsets.UTF_8) + "&amount="
                    + (packaged ? Amounts.plain(food.getPackageGrams()) : "1") + "&unit=" + (packaged ? "G" : "PIECE");
        } catch (ExternalServiceException | IllegalArgumentException | AccessDeniedException e) {
            redirect.addFlashAttribute("error", e instanceof AccessDeniedException
                    ? "Unbekanntes Produkt – dafür fehlt das Recht, Lebensmittel anzulegen." : e.getMessage());
            return "redirect:/kitchen/pantry";
        }
    }

    // --- Gekocht ---------------------------------------------------------------------

    @GetMapping("/plan/meals/{id}/cook")
    String cookSheet(@PathVariable Long id, @RequestParam Map<String, String> params, Model model,
                     RedirectAttributes redirect) {
        User viewer = currentUser.require();
        PantryService.CookPreview preview;
        try {
            preview = pantry.preview(id, overrides(params), viewer);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e instanceof NumberFormatException
                    ? "Mengen bitte als Zahl angeben." : e.getMessage());
            return "redirect:/kitchen/plan/meals/" + id + "/cook";
        }
        Meal meal = preview.meal();
        List<CookRow> rows = new ArrayList<>();
        for (PantryService.Deduction d : preview.deductions()) {
            String from = d.takes().stream().map(t -> Amounts.format(t.amount(), t.item().getUnit()) + " "
                    + (t.item().getLocation() == null ? "" : t.item().getLocation() + " ")
                    + (t.item().getBestBefore() == null ? "" : "(MHD " + DAY.format(t.item().getBestBefore()) + ")"))
                    .map(String::strip).reduce((a, b) -> a + " + " + b).orElse("");
            boolean warning = d.note() != null && !d.note().equals("nichts abbuchen");
            rows.add(new CookRow(d.ingredient().getId(), d.ingredient().getText(),
                    Amounts.format(d.needed(), d.ingredient().getUnit()), Amounts.plain(d.needed()),
                    d.ingredient().getUnit() == null ? "" : d.ingredient().getUnit().getLabel(), from, d.note(),
                    warning));
        }
        model.addAttribute("meal", meal);
        model.addAttribute("rows", rows);
        model.addAttribute("portions", Amounts.number(meal.totalPortions()));
        model.addAttribute("back", "/kitchen/plan/meals/" + id);
        model.addAttribute("canPlan", access.has(viewer, KitchenPermission.KITCHEN_PLAN));
        return "kitchen/cook-sheet";
    }

    @PostMapping("/plan/meals/{id}/cook")
    @PreAuthorize("hasAuthority('KITCHEN_PLAN')")
    String cook(@PathVariable Long id, @RequestParam Map<String, String> params, RedirectAttributes redirect) {
        try {
            Meal meal = pantry.cook(id, overrides(params), currentUser.require()).meal();
            redirect.addFlashAttribute("success", "Als gekocht eingetragen und vom Vorrat abgebucht.");
            return "redirect:" + MealPlanController.weekUrl(meal.getDay());
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Mengen bitte als Zahl angeben.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/plan/meals/" + id + "/cook";
    }

    private static Map<Long, Double> overrides(Map<String, String> params) {
        Map<Long, Double> result = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (key.startsWith("amount_") && value != null && !value.isBlank()) {
                result.put(Long.valueOf(key.substring(7)), Amounts.parse(value));
            }
        });
        return result;
    }

    private static Unit unit(String raw) {
        return raw == null || raw.isBlank() ? Unit.PIECE
                : Arrays.stream(Unit.values()).filter(u -> u.name().equals(raw)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekannte Einheit."));
    }
}
