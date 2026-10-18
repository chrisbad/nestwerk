package club.badernet.nestwerk.web;

import club.badernet.nestwerk.kitchen.Amounts;
import club.badernet.nestwerk.kitchen.BringSync;
import club.badernet.nestwerk.kitchen.FoodCategory;
import club.badernet.nestwerk.kitchen.KitchenAccess;
import club.badernet.nestwerk.kitchen.KitchenPermission;
import club.badernet.nestwerk.kitchen.MealPlanService;
import club.badernet.nestwerk.kitchen.ShoppingItem;
import club.badernet.nestwerk.kitchen.ShoppingListService;
import club.badernet.nestwerk.kitchen.ShoppingStatus;
import club.badernet.nestwerk.kitchen.Unit;
import club.badernet.nestwerk.kitchen.integration.ExternalServiceException;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Einkaufsliste: aus dem Plan erzeugen, abhaken, an Bring! senden und einräumen. */
@Controller
@RequestMapping("/kitchen/shopping")
@PreAuthorize("hasAuthority('KITCHEN_VIEW')")
public class ShoppingController {

    record ItemRow(Long id, String name, String amount, String status, boolean done, boolean sent, String note) {
    }

    record Section(String category, List<ItemRow> items) {
    }

    record StowRow(Long id, String name, String amount, String amountPlain, String unit, String bestBefore,
                   boolean known) {
    }

    private final ShoppingListService shopping;
    private final BringSync bring;
    private final MealPlanService plan;
    private final KitchenAccess access;
    private final CurrentUser currentUser;

    public ShoppingController(ShoppingListService shopping, BringSync bring, MealPlanService plan,
                              KitchenAccess access, CurrentUser currentUser) {
        this.shopping = shopping;
        this.bring = bring;
        this.plan = plan;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping
    String page(Model model) {
        User viewer = currentUser.require();
        Map<String, List<ItemRow>> sections = new LinkedHashMap<>();
        int open = 0;
        int bought = 0;
        for (ShoppingItem i : shopping.list(viewer)) {
            String category = (i.getFood() == null ? FoodCategory.OTHER : i.getFood().getCategory()).getLabel();
            boolean done = i.getStatus() == ShoppingStatus.BOUGHT;
            sections.computeIfAbsent(category, k -> new ArrayList<>()).add(new ItemRow(i.getId(), i.getName(),
                    i.specification(), i.getStatus().name(), done, i.getStatus() == ShoppingStatus.SENT, i.getNote()));
            if (done) {
                bought++;
            } else {
                open++;
            }
        }
        LocalDate today = plan.today();
        model.addAttribute("sections", sections.entrySet().stream().map(e -> new Section(e.getKey(), e.getValue()))
                .toList());
        model.addAttribute("open", open);
        model.addAttribute("bought", bought);
        model.addAttribute("from", today);
        model.addAttribute("to", MealPlanService.monday(today).plusDays(6));
        model.addAttribute("bringReady", bring.configured());
        model.addAttribute("canEdit", access.has(viewer, KitchenPermission.KITCHEN_SHOPPING));
        return "kitchen/shopping";
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String generate(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                    RedirectAttributes redirect) {
        try {
            int created = shopping.generate(from, to, currentUser.require());
            redirect.addFlashAttribute("success", created == 0
                    ? "Der Plan braucht nichts Neues – Vorrat und Liste decken alles ab."
                    : created + " Artikel aus dem Plan auf die Liste gesetzt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/shopping";
    }

    @PostMapping("/items")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String add(@RequestParam String line, RedirectAttributes redirect) {
        try {
            shopping.addManual(line, currentUser.require());
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/shopping";
    }

    @PostMapping("/items/{id}/toggle")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String toggle(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            shopping.toggle(id, currentUser.require());
        } catch (IllegalArgumentException | NoSuchElementException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/shopping";
    }

    @PostMapping("/items/{id}/delete")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            shopping.delete(id, currentUser.require());
        } catch (NoSuchElementException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/kitchen/shopping";
    }

    @PostMapping("/send")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String send(RedirectAttributes redirect) {
        BringSync.Result result = bring.send(currentUser.require());
        if (result.failed()) {
            redirect.addFlashAttribute("error", result.error() + (result.count() > 0
                    ? " (" + result.count() + " Artikel wurden vorher noch gesendet.)" : ""));
        } else {
            redirect.addFlashAttribute("success", result.count() == 0 ? "Es gab nichts zu senden."
                    : result.count() + " Artikel an Bring! gesendet.");
        }
        return "redirect:/kitchen/shopping";
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String sync(RedirectAttributes redirect) {
        BringSync.Result result = bring.sync(currentUser.require());
        if (result.failed()) {
            redirect.addFlashAttribute("error", result.error());
        } else {
            redirect.addFlashAttribute("success", result.count() == 0 ? "Bring! meldet nichts Neues."
                    : result.count() + " Artikel bei Bring! als gekauft erkannt.");
        }
        return "redirect:/kitchen/shopping";
    }

    // --- Einräumen -------------------------------------------------------------------

    @GetMapping("/stow")
    String stowPage(Model model) {
        User viewer = currentUser.require();
        List<StowRow> rows = new ArrayList<>();
        for (ShoppingItem i : shopping.toStow(viewer)) {
            LocalDate best = shopping.suggestedBestBefore(i);
            rows.add(new StowRow(i.getId(), i.getName(), i.specification(),
                    i.getAmount() == null ? "1" : Amounts.plain(i.getAmount()),
                    i.getUnit() == null ? Unit.PIECE.name() : i.getUnit().name(), best == null ? "" : best.toString(),
                    i.getFood() != null));
        }
        model.addAttribute("rows", rows);
        model.addAttribute("units", Unit.values());
        model.addAttribute("canEdit", access.has(viewer, KitchenPermission.KITCHEN_SHOPPING));
        return "kitchen/stow";
    }

    @PostMapping("/stow")
    @PreAuthorize("hasAuthority('KITCHEN_SHOPPING')")
    String stow(@RequestParam(name = "item", required = false) List<Long> ids,
                @RequestParam Map<String, String> params, RedirectAttributes redirect) {
        User actor = currentUser.require();
        int stored = 0;
        List<String> problems = new ArrayList<>();
        for (Long id : ids == null ? List.<Long>of() : ids) {
            try {
                String code = params.getOrDefault("barcode_" + id, "").replaceAll("\\s", "");
                if (!code.isEmpty() && !shopping.assignBarcode(id, code, actor)) {
                    problems.add("Produkt " + code + " ist unbekannt.");
                }
                String best = params.getOrDefault("best_" + id, "");
                shopping.stow(id, Amounts.parse(params.getOrDefault("amount_" + id, "1")),
                        unit(params.get("unit_" + id)), params.get("location_" + id),
                        best.isBlank() ? null : LocalDate.parse(best), actor);
                stored++;
            } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
                problems.add("Angaben zu einem Artikel sind ungültig.");
            } catch (IllegalArgumentException | NoSuchElementException | ExternalServiceException e) {
                problems.add(e.getMessage());
            }
        }
        if (stored > 0) {
            redirect.addFlashAttribute("success", stored + " Artikel eingeräumt.");
        }
        if (!problems.isEmpty()) {
            redirect.addFlashAttribute("error", String.join(" ", problems));
        }
        return "redirect:/kitchen/shopping/stow";
    }

    private static Unit unit(String raw) {
        if (raw == null || raw.isBlank()) {
            return Unit.PIECE;
        }
        try {
            return Unit.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannte Einheit.");
        }
    }
}
