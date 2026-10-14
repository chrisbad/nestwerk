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
import club.badernet.nestwerk.member.FamilyMember;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Controller
@RequestMapping("/household")
public class HouseholdController {

    /** Ein Tag als Spalte im Wochenplan. */
    public record WeekDay(LocalDate date, String label, boolean today) {
    }

    /** Eine Zeile im Wochenplan: ein Mitglied (oder „offen“) mit seinen Aufgaben je Tag. */
    public record WeekRow(String label, String color, List<List<HouseholdViews.TaskChip>> cells, int minutes) {
    }

    /** Ein Tag mit seinen Aufgaben (Wochenplan auf dem Handy). */
    public record DayGroup(WeekDay day, List<HouseholdViews.TaskChip> chips) {
    }

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

    @GetMapping
    String week(@RequestParam(required = false) String week, Model model) {
        User viewer = requireModule();
        HouseholdViews.Context ctx = views.context(viewer);
        LocalDate today = ctx.today();
        LocalDate monday = parseDate(week, today).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<WeekDay> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = monday.plusDays(i);
            days.add(new WeekDay(d, HouseholdViews.shortDay(d), d.equals(today)));
        }
        List<ChoreTask> inWeek = tasks.between(viewer, monday, monday.plusDays(6));
        Map<Long, List<ChoreTask>> byMember = new LinkedHashMap<>();
        ctx.members().keySet().forEach(id -> byMember.put(id, new ArrayList<>()));
        List<ChoreTask> unassigned = new ArrayList<>();
        for (ChoreTask t : inWeek) {
            if (t.getAssigneeMemberId() == null || !byMember.containsKey(t.getAssigneeMemberId())) {
                unassigned.add(t);
            } else {
                byMember.get(t.getAssigneeMemberId()).add(t);
            }
        }
        List<WeekRow> rows = new ArrayList<>();
        byMember.forEach((id, list) -> {
            FamilyMember m = ctx.members().get(id);
            rows.add(row(m.getName(), m.getColor(), list, days, ctx));
        });
        if (!unassigned.isEmpty()) {
            rows.add(row("Offen", null, unassigned, days, ctx));
        }
        List<DayGroup> byDay = days.stream().map(d -> new DayGroup(d, views.chips(inWeek.stream()
                .filter(t -> t.getDueOn().equals(d.date())).toList(), ctx))).toList();
        model.addAttribute("days", days);
        model.addAttribute("rows", rows);
        model.addAttribute("byDay", byDay);
        model.addAttribute("overdue", views.chips(tasks.overdue(viewer, today), ctx));
        model.addAttribute("monday", monday);
        model.addAttribute("weekNumber", monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        model.addAttribute("weekRange", HouseholdViews.longDay(monday) + " – " + HouseholdViews.longDay(monday.plusDays(6)));
        model.addAttribute("isCurrentWeek", !today.isBefore(monday) && !today.isAfter(monday.plusDays(6)));
        model.addAttribute("back", "/household?week=" + monday);
        addCommon(model, viewer, "week");
        return "household/week";
    }

    private WeekRow row(String label, String color, List<ChoreTask> list, List<WeekDay> days,
                       HouseholdViews.Context ctx) {
        List<List<HouseholdViews.TaskChip>> cells = new ArrayList<>();
        for (WeekDay d : days) {
            cells.add(views.chips(list.stream().filter(t -> t.getDueOn().equals(d.date())).toList(), ctx));
        }
        int minutes = list.stream().mapToInt(t -> t.getChore().getMinutes()).sum();
        return new WeekRow(label, color, cells, minutes);
    }

    @GetMapping("/today")
    String today(Model model) {
        User viewer = requireModule();
        HouseholdViews.Context ctx = views.context(viewer);
        List<HouseholdViews.TaskChip> mine = views.chips(tasks.mine(viewer, ctx.today()), ctx);
        model.addAttribute("overdue", mine.stream().filter(HouseholdViews.TaskChip::overdue).toList());
        model.addAttribute("dueToday", mine.stream().filter(c -> !c.overdue()).toList());
        model.addAttribute("hasMember", ctx.ownMember() != null);
        model.addAttribute("todayLabel", HouseholdViews.longDay(ctx.today()));
        model.addAttribute("back", "/household/today");
        addCommon(model, viewer, "today");
        return "household/today";
    }

    /* --- Hausarbeiten ------------------------------------------------------ */

    @GetMapping("/chores")
    String chores(Model model) {
        User viewer = requireModule();
        Map<String, List<Chore>> groups = new LinkedHashMap<>();
        for (Chore c : chores.visible(viewer)) {
            String key = !c.isActive() ? "Deaktiviert" : (c.getArea() == null ? "Ohne Bereich" : c.getArea());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        model.addAttribute("groups", groups);
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
        model.addAttribute("form", ChoreForm.forNew(tasks.today()));
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
        model.addAttribute("back", "/household/chores/" + id);
        addCommon(model, viewer, "chores");
        return "household/chore";
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
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            chores.delete(id, requireModule());
            redirect.addFlashAttribute("success", "Hausarbeit wurde gelöscht.");
            return "redirect:/household/chores";
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/household/chores/" + id;
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
    String task(@PathVariable Long id, Model model) {
        User viewer = requireModule();
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

    @PostMapping("/tasks/{id}/complete")
    String complete(@PathVariable Long id, @RequestParam(required = false) Long doer,
                    @RequestParam(name = "helpers", required = false) Set<Long> helpers,
                    @RequestParam(required = false) String back,
                    @RequestHeader(name = "HX-Request", required = false) String htmx,
                    Model model, RedirectAttributes redirect) {
        User actor = requireModule();
        String message = "Erledigt – danke!";
        boolean ok = true;
        try {
            tasks.complete(id, actor, doer, helpers);
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
            model.addAttribute("back", TodoController.safeBack(back, "/household"));
            model.addAttribute("toast", message);
            model.addAttribute("toastType", ok ? "success" : "error");
            return "household/fragments :: chip";
        }
        redirect.addFlashAttribute(ok ? "success" : "error", message);
        return "redirect:" + TodoController.safeBack(back, "/household/tasks/" + id);
    }

    private void prepareForm(Model model, User viewer, Chore chore) {
        model.addAttribute("isNew", chore == null);
        model.addAttribute("chore", chore);
        model.addAttribute("action", chore == null ? "/household/chores" : "/household/chores/" + chore.getId());
        model.addAttribute("kinds", ChoreKind.values());
        model.addAttribute("efforts", ChoreEffort.values());
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
            throw new AccessDeniedException("Keine Berechtigung für den Putzplan");
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
