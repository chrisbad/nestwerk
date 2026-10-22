package club.badernet.nestwerk.web;

import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreConflictException;
import club.badernet.nestwerk.household.ChoreEffort;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.household.IcalImporter;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Controller
@RequestMapping("/household")
public class HouseholdController {

    private final ChoreService chores;
    private final ChoreTaskService tasks;
    private final HouseholdAccess access;
    private final HouseholdViews views;
    private final CurrentUser currentUser;
    private final IcalImporter importer;

    public HouseholdController(ChoreService chores, ChoreTaskService tasks, HouseholdAccess access,
                               HouseholdViews views, CurrentUser currentUser, IcalImporter importer) {
        this.importer = importer;
        this.chores = chores;
        this.tasks = tasks;
        this.access = access;
        this.views = views;
        this.currentUser = currentUser;
    }

    /**
     * The household plan: the day (default today) with a filter by person. The week lives in the calendar. {@code week}
     * is the older name of {@code date} and still works in links.
     */
    @GetMapping
    String plan(@RequestParam(required = false) String date, @RequestParam(required = false) String week,
                Model model) {
        User viewer = requireModule();
        HouseholdViews.Context ctx = views.context(viewer);
        LocalDate today = ctx.today();
        LocalDate focus = parseDate(date != null ? date : week, today);
        List<HouseholdViews.TaskChip> dayChips = views.chips(tasks.between(viewer, focus, focus), ctx);
        List<HouseholdViews.TaskChip> overdue = focus.equals(today)
                ? views.chips(tasks.overdue(viewer, today), ctx) : List.of();
        model.addAttribute("overdue", overdue);
        model.addAttribute("dayChips", dayChips);
        model.addAttribute("hasOpenTasks", Stream.concat(overdue.stream(), dayChips.stream())
                .anyMatch(c -> c.assignee() == null));
        model.addAttribute("filterMembers", ctx.members().values());
        model.addAttribute("ownMember", ctx.ownMember());
        model.addAttribute("focus", focus);
        model.addAttribute("focusLabel", HouseholdViews.longDay(focus));
        model.addAttribute("focusIsToday", focus.equals(today));
        model.addAttribute("back", "/household?date=" + focus);
        addCommon(model, viewer, "plan");
        return "household/plan";
    }

    /** The day view is the start page of the household now; old links keep working. */
    @GetMapping("/today")
    String today() {
        return "redirect:/household";
    }

    /* --- Hausarbeiten ------------------------------------------------------ */

    @GetMapping("/chores")
    String chores(Model model) {
        User viewer = requireModule();
        List<ChoreRow> rows = chores.visible(viewer).stream()
                .map(c -> ChoreRow.of(c, tasks.canBook(viewer, c), access.can(viewer, "EDIT", c),
                        access.can(viewer, "DELETE", c)))
                .toList();
        model.addAttribute("rows", rows);
        model.addAttribute("areas", rows.stream().map(ChoreRow::areaKey).distinct().sorted().toList());
        model.addAttribute("kinds", ChoreKind.values());
        model.addAttribute("canCreate", chores.canCreate(viewer));
        addCommon(model, viewer, "chores");
        return "household/chores";
    }

    @GetMapping("/chores/new")
    String newChore(Model model) {
        User viewer = requireModule();
        if (!chores.canCreate(viewer)) {
            throw new AccessDeniedException("Keine Berechtigung zum Anlegen von Hausarbeiten");
        }
        model.addAttribute("form", ChoreForm.forNew());
        prepareForm(model, viewer, null);
        return "household/chore-form";
    }

    @PostMapping("/chores")
    String create(@ModelAttribute("form") ChoreForm form, Model model, RedirectAttributes redirect) {
        User actor = requireModule();
        try {
            Chore c = chores.create(form.toInput(), actor);
            redirect.addFlashAttribute("success", "Hausarbeit wurde angelegt.");
            return "redirect:/household/chores/" + c.getId();
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            prepareForm(model, actor, null);
            return "household/chore-form";
        }
    }

    @GetMapping("/chores/{id}")
    String chore(@PathVariable Long id, Model model) {
        User viewer = requireModule();
        Chore chore = chores.get(id, viewer);
        HouseholdViews.Context ctx = views.context(viewer);
        model.addAttribute("chore", chore);
        model.addAttribute("schedule", HouseholdViews.schedule(chore));
        model.addAttribute("candidates", chore.getMemberIds().stream().map(ctx.members()::get)
                .filter(Objects::nonNull).toList());
        model.addAttribute("upcoming", views.chips(tasks.between(viewer, ctx.today(), ctx.today().plusDays(21))
                .stream().filter(t -> t.getChore().getId().equals(id)).toList(), ctx));
        model.addAttribute("dates", chore.getKind() == ChoreKind.DATES ? chores.dates(id).stream()
                .filter(d -> !d.getDueOn().isBefore(ctx.today())).toList() : List.of());
        model.addAttribute("canEdit", access.can(viewer, "EDIT", chore));
        model.addAttribute("canDelete", access.can(viewer, "DELETE", chore));
        model.addAttribute("canBook", tasks.canBook(viewer, chore));
        model.addAttribute("ownMember", ctx.ownMember());
        model.addAttribute("completeAny", ctx.completeAny());
        model.addAttribute("allMembers", ctx.members());
        model.addAttribute("back", "/household/chores/" + id);
        addCommon(model, viewer, "chores");
        return "household/chore";
    }

    /** Booking form for the side panel of the chore list: book a chore that was not planned. */
    @GetMapping("/chores/{id}/book-form")
    String bookForm(@PathVariable Long id, Model model) {
        User viewer = requireModule();
        Chore chore = chores.get(id, viewer);
        if (!tasks.canBook(viewer, chore)) {
            throw new AccessDeniedException("Keine Berechtigung, diese Hausarbeit zu buchen");
        }
        HouseholdViews.Context ctx = views.context(viewer);
        model.addAttribute("chore", chore);
        model.addAttribute("ownMember", ctx.ownMember());
        model.addAttribute("completeAny", ctx.completeAny());
        model.addAttribute("allMembers", ctx.members());
        return "household/chore-book-drawer :: content";
    }

    @GetMapping("/chores/{id}/edit")
    String editChore(@PathVariable Long id, Model model) {
        User viewer = requireModule();
        Chore chore = chores.get(id, viewer);
        if (!access.can(viewer, "EDIT", chore)) {
            throw new AccessDeniedException("Keine Berechtigung zum Bearbeiten dieser Hausarbeit");
        }
        model.addAttribute("form", ChoreForm.from(chore));
        prepareForm(model, viewer, chore);
        return "household/chore-form";
    }

    @PostMapping("/chores/{id}")
    String update(@PathVariable Long id, @ModelAttribute("form") ChoreForm form, Model model,
                  RedirectAttributes redirect) {
        User actor = requireModule();
        try {
            chores.update(id, form.toInput(), actor);
            redirect.addFlashAttribute("success", "Hausarbeit wurde gespeichert. Die kommenden Termine sind neu geplant.");
            return "redirect:/household/chores/" + id;
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            prepareForm(model, actor, chores.get(id, actor));
            return "household/chore-form";
        }
    }

    @PostMapping("/chores/{id}/deactivate")
    String deactivate(@PathVariable Long id, RedirectAttributes redirect) {
        chores.deactivate(id, requireModule());
        redirect.addFlashAttribute("success", "Hausarbeit wurde deaktiviert. Offene künftige Termine sind entfernt.");
        return "redirect:/household/chores/" + id;
    }

    @PostMapping("/chores/{id}/activate")
    String activate(@PathVariable Long id, RedirectAttributes redirect) {
        chores.activate(id, requireModule());
        redirect.addFlashAttribute("success", "Hausarbeit ist wieder aktiv.");
        return "redirect:/household/chores/" + id;
    }

    @PostMapping("/chores/{id}/delete")
    String delete(@PathVariable Long id, @RequestParam(required = false) String back, RedirectAttributes redirect) {
        try {
            chores.delete(id, requireModule());
            redirect.addFlashAttribute("success", "Hausarbeit wurde gelöscht.");
            return "redirect:" + SafeRedirect.target(back, "/household/chores");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:" + SafeRedirect.target(back, "/household/chores/" + id);
        }
    }

    @PostMapping("/chores/{id}/dates")
    String addDate(@PathVariable Long id,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
                   RedirectAttributes redirect) {
        try {
            chores.addDate(id, day, time, requireModule());
            redirect.addFlashAttribute("success", "Termin wurde hinzugefügt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/household/chores/" + id + "#dates";
    }

    @PostMapping("/chores/{id}/dates/{dateId}/delete")
    String removeDate(@PathVariable Long id, @PathVariable Long dateId, RedirectAttributes redirect) {
        chores.removeDate(id, dateId, requireModule());
        redirect.addFlashAttribute("success", "Termin wurde entfernt.");
        return "redirect:/household/chores/" + id + "#dates";
    }

    @PostMapping("/chores/{id}/ical")
    String importIcal(@PathVariable Long id,
                      @RequestParam(name = "file", required = false) org.springframework.web.multipart.MultipartFile file,
                      @RequestParam(required = false) String url, @RequestParam(required = false) String filter,
                      @RequestParam(defaultValue = "0") int daysBefore,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
                      RedirectAttributes redirect) {
        User actor = requireModule();
        try {
            IcalImporter.Result result;
            if (url != null && !url.isBlank()) {
                result = importer.subscribe(id, url, filter, daysBefore, time, actor);
            } else {
                result = importer.importFile(id, file == null ? null : file.getBytes(), filter, daysBefore, time, actor);
            }
            redirect.addFlashAttribute("success", result.total() == 0
                    ? "Im Kalender wurden keine passenden künftigen Termine gefunden."
                    : result.total() + " Termine übernommen (" + result.added() + " neu, " + result.moved()
                    + " verschoben, " + result.removed() + " entfernt).");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (java.io.IOException e) {
            redirect.addFlashAttribute("error", "Die Datei konnte nicht gelesen werden.");
        }
        return "redirect:/household/chores/" + id + "#dates";
    }

    @PostMapping("/chores/{id}/ical/unsubscribe")
    String unsubscribe(@PathVariable Long id, RedirectAttributes redirect) {
        importer.unsubscribe(id, requireModule());
        redirect.addFlashAttribute("success", "Das Kalender-Abo wurde beendet. Die übernommenen Termine bleiben.");
        return "redirect:/household/chores/" + id + "#dates";
    }

    /* --- Aufgaben ---------------------------------------------------------- */

    @GetMapping("/tasks/{id}")
    String task(@PathVariable Long id, @RequestParam(required = false) String back, Model model) {
        User viewer = requireModule();
        model.addAttribute("returnTo", SafeRedirect.target(back, null));
        ChoreTask task = tasks.get(id, viewer);
        HouseholdViews.Context ctx = views.context(viewer);
        model.addAttribute("task", task);
        model.addAttribute("c", views.chip(task, ctx));
        model.addAttribute("dueLabel", HouseholdViews.longDay(task.getDueOn())
                + (task.getDueTime() == null ? "" : ", " + task.timeLabel() + " Uhr"));
        model.addAttribute("credits", tasks.credits(id));
        model.addAttribute("allMembers", ctx.members());
        model.addAttribute("canCompleteHere", task.isOpen() && access.can(viewer, "COMPLETE", task));
        model.addAttribute("completeAny", ctx.completeAny());
        model.addAttribute("ownMember", ctx.ownMember());
        model.addAttribute("canAssign", task.isOpen() && access.can(viewer, "ASSIGN", task));
        model.addAttribute("assignAny", access.canAny(viewer, "ASSIGN"));
        model.addAttribute("back", "/household/tasks/" + id);
        addCommon(model, viewer, "week");
        return "household/task";
    }

    @PostMapping("/chores/{id}/book")
    String book(@PathVariable Long id, @RequestParam(required = false) Long doer,
                @RequestParam(name = "helpers", required = false) Set<Long> helpers,
                @RequestParam(required = false) ChoreEffort effort, @RequestParam(required = false) Integer minutes,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime performedAt,
                @RequestParam(required = false) String back, RedirectAttributes redirect) {
        User actor = requireModule();
        try {
            tasks.bookAdHoc(id, actor, doer, helpers, new ChoreTaskService.Booking(effort, minutes, performedAt));
            redirect.addFlashAttribute("success", "Gebucht – danke!");
        } catch (ChoreConflictException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + SafeRedirect.target(back, "/household/chores/" + id);
    }

    @PostMapping("/tasks/{id}/complete")
    String complete(@PathVariable Long id, @RequestParam(required = false) Long doer,
                    @RequestParam(name = "helpers", required = false) Set<Long> helpers,
                    @RequestParam(required = false) ChoreEffort effort, @RequestParam(required = false) Integer minutes,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime performedAt,
                    @RequestParam(required = false) String back,
                    @RequestHeader(name = "HX-Request", required = false) String htmx,
                    Model model, RedirectAttributes redirect) {
        User actor = requireModule();
        String message = "Erledigt – danke!";
        boolean ok = true;
        try {
            tasks.complete(id, actor, doer, helpers, new ChoreTaskService.Booking(effort, minutes, performedAt));
        } catch (ChoreConflictException | IllegalArgumentException e) {
            message = e.getMessage();
            ok = false;
        }
        return answer(id, actor, ok, message, back, htmx, model, redirect);
    }

    @PostMapping("/tasks/{id}/undo")
    String undo(@PathVariable Long id, @RequestParam(required = false) String back,
                @RequestHeader(name = "HX-Request", required = false) String htmx,
                Model model, RedirectAttributes redirect) {
        User actor = requireModule();
        String message = "Wieder offen.";
        boolean ok = true;
        try {
            tasks.undo(id, actor);
        } catch (ChoreConflictException e) {
            message = e.getMessage();
            ok = false;
        }
        return answer(id, actor, ok, message, back, htmx, model, redirect);
    }

    @PostMapping("/tasks/{id}/skip")
    String skip(@PathVariable Long id, @RequestParam(required = false) String back,
                @RequestHeader(name = "HX-Request", required = false) String htmx,
                Model model, RedirectAttributes redirect) {
        User actor = requireModule();
        String message = "Als nicht nötig markiert.";
        boolean ok = true;
        try {
            tasks.skip(id, actor);
        } catch (ChoreConflictException e) {
            message = e.getMessage();
            ok = false;
        }
        return answer(id, actor, ok, message, back, htmx, model, redirect);
    }

    @PostMapping("/tasks/{id}/assign")
    String assign(@PathVariable Long id, @RequestParam(required = false) Long member,
                  RedirectAttributes redirect) {
        User actor = requireModule();
        try {
            tasks.assign(id, member, actor);
            redirect.addFlashAttribute("success", member == null ? "Die Hausarbeit ist jetzt offen zum Nehmen."
                    : "Zuständigkeit wurde geändert.");
        } catch (ChoreConflictException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/household/tasks/" + id;
    }

    private String answer(Long id, User actor, boolean ok, String message, String back, String htmx, Model model,
                          RedirectAttributes redirect) {
        if (htmx != null) {
            model.addAttribute("c", views.chip(tasks.get(id, actor), views.context(actor)));
            model.addAttribute("back", SafeRedirect.target(back, "/household"));
            model.addAttribute("toast", message);
            model.addAttribute("toastType", ok ? "success" : "error");
            return "household/fragments :: chip";
        }
        redirect.addFlashAttribute(ok ? "success" : "error", message);
        return "redirect:" + SafeRedirect.target(back, "/household/tasks/" + id);
    }

    private void prepareForm(Model model, User viewer, Chore chore) {
        model.addAttribute("isNew", chore == null);
        model.addAttribute("chore", chore);
        model.addAttribute("action", chore == null ? "/household/chores" : "/household/chores/" + chore.getId());
        model.addAttribute("kinds", ChoreKind.values());
        model.addAttribute("distributions", Distribution.values());
        model.addAttribute("allMembers", views.context(viewer).members().values());
        addCommon(model, viewer, "chores");
    }

    private void addCommon(Model model, User viewer, String tab) {
        model.addAttribute("tab", tab);
    }

    private User requireModule() {
        User user = currentUser.require();
        if (!access.mayOpen(user.getUsername())) {
            throw new AccessDeniedException("Keine Berechtigung für den Haushaltsplan");
        }
        return user;
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
