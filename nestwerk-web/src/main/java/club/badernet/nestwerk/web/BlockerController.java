package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.Blocker;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerService;
import club.badernet.nestwerk.blocker.BlockerSettings;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BlockerVisibility;
import club.badernet.nestwerk.blocker.GermanHolidays;
import club.badernet.nestwerk.blocker.RestGoal;
import club.badernet.nestwerk.blocker.RestGoals;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
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

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Seite „Zeiten“: Arbeits-, Schlaf-, Ruhe- und Freizeiten je Person, Ruhe-Ziele und Bundesland. */
@Controller
@RequestMapping("/times")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class BlockerController {

    public record BlockerRow(Long id, String title, String kind, String kindLabel, String times, String days,
                             String strength, String notes, boolean canEdit) {
    }

    public record GoalRow(Long id, String label, String text, int percent, boolean met, String kind,
                          LocalTime windowStart, LocalTime windowEnd, int target) {
    }

    public record Person(FamilyMember member, boolean own, boolean canEditGoals, List<BlockerRow> blockers,
                         List<GoalRow> goals) {
    }

    public record WeekdayOption(DayOfWeek day, String label) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BlockerService blockers;
    private final BlockerSettings settings;
    private final RestGoals restGoals;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final Clock clock;

    public BlockerController(BlockerService blockers, BlockerSettings settings, RestGoals restGoals,
                             FamilyMemberService members, CurrentUser currentUser, Clock clock) {
        this.blockers = blockers;
        this.settings = settings;
        this.restGoals = restGoals;
        this.members = members;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping
    String page(Model model) {
        User viewer = currentUser.require();
        Optional<Long> own = blockers.ownMember(viewer);
        LocalDate today = LocalDate.now(clock);
        List<Person> people = new ArrayList<>();
        List<FamilyMember> all = new ArrayList<>(members.findAll());
        all.sort(Comparator.comparing((FamilyMember m) -> !own.map(m.getId()::equals).orElse(false))
                .thenComparing(FamilyMember::getName));
        for (FamilyMember m : all) {
            boolean isOwn = own.map(m.getId()::equals).orElse(false);
            List<BlockerRow> rows = blockers.forMember(m.getId()).stream()
                    .map(b -> row(b, viewer, own.orElse(null))).filter(java.util.Objects::nonNull).toList();
            List<GoalRow> goals = restGoals.status(m.getId(), today).stream().map(s -> new GoalRow(s.goal().getId(),
                    s.goal().getLabel(), s.text(), s.percent(), s.met(), s.goal().getKind().name(),
                    s.goal().getWindowStart(), s.goal().getWindowEnd(), s.goal().getTarget())).toList();
            people.add(new Person(m, isOwn, blockers.canEditGoals(m.getId(), viewer), rows, goals));
        }
        model.addAttribute("people", people);
        model.addAttribute("kinds", BlockerKind.values());
        model.addAttribute("canCreate", blockers.canCreate(viewer));
        model.addAttribute("canManageSettings", blockers.canManageSettings(viewer));
        model.addAttribute("states", new TreeMap<>(GermanHolidays.STATES).entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).toList());
        model.addAttribute("state", settings.state());
        return "times/index";
    }

    @GetMapping("/new")
    String newForm(@RequestParam(defaultValue = "WORK") BlockerKind kind,
                   @RequestParam(required = false) Long member, Model model) {
        User viewer = currentUser.require();
        Long target = member != null ? member : blockers.ownMember(viewer).orElse(null);
        model.addAttribute("form", BlockerForm.forNew(kind, target, LocalDate.now(clock)));
        return form(model, null);
    }

    @PostMapping
    String create(@ModelAttribute("form") BlockerForm form, Model model, RedirectAttributes redirect) {
        try {
            Blocker saved = blockers.create(form.toInput(), currentUser.require());
            redirect.addFlashAttribute("success", "„" + saved.getTitle() + "“ ist gespeichert.");
            return "redirect:/times";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return form(model, null);
        }
    }

    @GetMapping("/{id}")
    String edit(@PathVariable Long id, Model model) {
        Blocker blocker = blockers.get(id);
        if (!blockers.canEdit(blocker, currentUser.require())) {
            throw new AccessDeniedException("Keine Berechtigung zum Bearbeiten dieses Blockers");
        }
        model.addAttribute("form", BlockerForm.from(blocker));
        return form(model, id);
    }

    @PostMapping("/{id}")
    String update(@PathVariable Long id, @ModelAttribute("form") BlockerForm form, Model model,
                  RedirectAttributes redirect) {
        try {
            blockers.update(id, form.toInput(), currentUser.require());
            redirect.addFlashAttribute("success", "Gespeichert.");
            return "redirect:/times";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return form(model, id);
        }
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        blockers.delete(id, currentUser.require());
        redirect.addFlashAttribute("success", "Gelöscht.");
        return "redirect:/times";
    }

    @PostMapping("/goals")
    String saveGoal(@RequestParam(required = false) Long id, @RequestParam Long memberId,
                    @RequestParam RestGoal.Kind kind,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime windowStart,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime windowEnd,
                    @RequestParam(defaultValue = "0") int target, @RequestParam(required = false) String label,
                    RedirectAttributes redirect) {
        try {
            int value = kind == RestGoal.Kind.HOURS ? target * 60 : target;
            blockers.saveGoal(id, memberId, kind, windowStart, windowEnd, value, label, currentUser.require());
            redirect.addFlashAttribute("success", "Das Ruhe-Ziel ist gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/times";
    }

    @PostMapping("/goals/{id}/delete")
    String deleteGoal(@PathVariable Long id, RedirectAttributes redirect) {
        blockers.deleteGoal(id, currentUser.require());
        redirect.addFlashAttribute("success", "Das Ruhe-Ziel ist gelöscht.");
        return "redirect:/times";
    }

    @PostMapping("/state")
    String saveState(@RequestParam(required = false) String state, RedirectAttributes redirect) {
        if (!blockers.canManageSettings(currentUser.require())) {
            throw new AccessDeniedException("Nur für Verwalter des Kalenders");
        }
        try {
            settings.setState(state);
            redirect.addFlashAttribute("success", "Das Bundesland ist gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/times";
    }

    private String form(Model model, Long id) {
        model.addAttribute("blockerId", id);
        model.addAttribute("kinds", BlockerKind.values());
        model.addAttribute("strengths", BlockerStrength.values());
        model.addAttribute("visibilities", BlockerVisibility.values());
        model.addAttribute("weekdays", java.util.Arrays.stream(DayOfWeek.values())
                .map(d -> new WeekdayOption(d, d.getDisplayName(TextStyle.SHORT, Locale.GERMAN).replace(".", "")))
                .toList());
        model.addAttribute("allMembers", members.findAll());
        return "times/form";
    }

    private BlockerRow row(Blocker b, User viewer, Long own) {
        boolean canEdit = blockers.canEdit(b, viewer);
        boolean insider = canEdit || viewer.isAdministrator() || b.getMemberIds().contains(own);
        if (!insider && b.getVisibility() == BlockerVisibility.HIDDEN) {
            return null;
        }
        String title = insider || b.getVisibility() == BlockerVisibility.TITLE ? b.getTitle() : "belegt";
        List<String> notes = new ArrayList<>();
        if (b.isQuiet()) {
            notes.add("Ruhe-Modus");
        }
        if (b.isHolidaysOff()) {
            notes.add("an Feiertagen frei");
        }
        if (b.getValidUntil() != null) {
            notes.add("bis " + b.getValidUntil().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        }
        if (!b.getSkips().isEmpty()) {
            notes.add(b.getSkips().size() == 1 ? "1 Ausnahme" : b.getSkips().size() + " Ausnahmen");
        }
        return new BlockerRow(b.getId(), title, b.getKind().name().toLowerCase(Locale.ROOT), b.getKind().label(),
                TIME.format(b.getStartTime()) + "–" + TIME.format(b.getEndTime()), days(b.getWeekdays()),
                b.getStrength().label(), String.join(" · ", notes), canEdit);
    }

    static String days(Set<DayOfWeek> days) {
        if (days.size() == 7) {
            return "täglich";
        }
        if (days.equals(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))) {
            return "Mo–Fr";
        }
        if (days.equals(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))) {
            return "Sa–So";
        }
        return days.stream().sorted().map(d -> d.getDisplayName(TextStyle.SHORT, Locale.GERMAN).replace(".", ""))
                .collect(Collectors.joining(", "));
    }
}
