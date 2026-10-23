package club.badernet.nestwerk.web;

import club.badernet.nestwerk.household.ChoreConflictException;
import club.badernet.nestwerk.household.ChoreCredit;
import club.badernet.nestwerk.household.ChoreStats;
import club.badernet.nestwerk.household.DurationUnit;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.household.HouseholdSettings;
import club.badernet.nestwerk.household.Redemption;
import club.badernet.nestwerk.household.Reward;
import club.badernet.nestwerk.household.RewardService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Auswertung mit Korrektur sowie Minutenkonto und Belohnungen. */
@Controller
@RequestMapping("/household")
public class HouseholdAccountController {

    /** Ausgewerteter Zeitraum mit Beschriftung und Nachbarn; {@code anchor} ist das gewählte Datum innerhalb des Zeitraums. */
    public record Period(String key, LocalDate anchor, LocalDate from, LocalDate to, String label, LocalDate previous,
                         LocalDate next) {
    }

    /** Eine Gutschrift für die Liste. */
    public record CreditRow(Long id, String day, String title, Long taskId, FamilyMember member, int minutes,
                            int planned, boolean corrected, String reason) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d.M.", Locale.GERMAN);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN);

    private final ChoreStats stats;
    private final RewardService rewards;
    private final HouseholdAccess access;
    private final HouseholdViews views;
    private final CurrentUser currentUser;
    private final HouseholdSettings settings;

    public HouseholdAccountController(ChoreStats stats, RewardService rewards, HouseholdAccess access,
                                      HouseholdViews views, CurrentUser currentUser, HouseholdSettings settings) {
        this.stats = stats;
        this.rewards = rewards;
        this.access = access;
        this.views = views;
        this.currentUser = currentUser;
        this.settings = settings;
    }

    /* --- Auswertung ------------------------------------------------------------ */

    @GetMapping("/stats")
    String stats(@RequestParam(defaultValue = "week") String period, @RequestParam(required = false) String date,
                 @RequestParam(defaultValue = "minutes") String unit, Model model) {
        User viewer = requireModule();
        HouseholdViews.Context ctx = views.context(viewer);
        Period p = period(period, parse(date, ctx.today()));
        List<ChoreStats.MemberStats> rows = stats.forPeriod(viewer, p.from(), p.to());
        int max = rows.stream().mapToInt(ChoreStats.MemberStats::minutes).max().orElse(0);
        DurationUnit selected = DurationUnit.fromKey(unit);
        model.addAttribute("period", p);
        model.addAttribute("unit", selected);
        model.addAttribute("units", DurationUnit.values());
        model.addAttribute("format", settings.format(selected, p.key()));
        model.addAttribute("today", ctx.today());
        model.addAttribute("rows", rows);
        model.addAttribute("maxMinutes", Math.max(max, 1));
        model.addAttribute("credits", stats.credits(viewer, p.from(), p.to()).stream()
                .map(c -> row(c, ctx.members())).toList());
        model.addAttribute("seesAll", stats.seesAll(viewer));
        model.addAttribute("canCorrect", access.canAny(viewer, "CORRECT"));
        model.addAttribute("tab", "stats");
        return "household/stats";
    }

    @PostMapping("/credits/{id}/correct")
    String correct(@PathVariable Long id, @RequestParam(required = false) Integer minutes,
                   @RequestParam(required = false) String reason, @RequestParam(required = false) String back,
                   RedirectAttributes redirect) {
        try {
            stats.correct(id, minutes, reason, requireModule());
            redirect.addFlashAttribute("success", "Gutschrift wurde korrigiert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + SafeRedirect.target(back, "/household/stats");
    }

    private static CreditRow row(ChoreCredit c, Map<Long, FamilyMember> members) {
        return new CreditRow(c.getId(), c.getTask().getDueOn().format(DAY), c.getTask().getChore().getTitle(),
                c.getTask().getId(), members.get(c.getMemberId()), c.effectiveMinutes(), c.getMinutes(),
                c.isCorrected(), c.getCorrectionReason());
    }

    static Period period(String key, LocalDate date) {
        return switch (key) {
            case "day" -> new Period("day", date, date, date, HouseholdViews.longDay(date), date.minusDays(1), date.plusDays(1));
            case "month" -> {
                LocalDate first = date.withDayOfMonth(1);
                yield new Period("month", date, first, first.plusMonths(1).minusDays(1), first.format(MONTH),
                        first.minusMonths(1), first.plusMonths(1));
            }
            case "year" -> {
                LocalDate first = date.withDayOfYear(1);
                yield new Period("year", date, first, first.plusYears(1).minusDays(1), String.valueOf(first.getYear()),
                        first.minusYears(1), first.plusYears(1));
            }
            default -> {
                LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Period("week", date, monday, monday.plusDays(6),
                        "KW " + monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + ": " + monday.format(DAY) + " – "
                                + monday.plusDays(6).format(DAY), monday.minusWeeks(1), monday.plusWeeks(1));
            }
        };
    }

    /* --- Einstellungen --------------------------------------------------------- */

    @GetMapping("/settings")
    String settings(Model model) {
        User viewer = requireModule();
        model.addAttribute("hoursPerPersonDay", settings.hoursPerPersonDay().stripTrailingZeros().toPlainString());
        model.addAttribute("maxHours", HouseholdSettings.MAX_HOURS_PER_PERSON_DAY);
        model.addAttribute("canManage", settings.canManage(viewer));
        model.addAttribute("tab", "settings");
        return "household/settings";
    }

    @PostMapping("/settings")
    String saveSettings(@RequestParam(required = false) BigDecimal hoursPerPersonDay, RedirectAttributes redirect) {
        try {
            settings.setHoursPerPersonDay(hoursPerPersonDay, requireModule());
            redirect.addFlashAttribute("success", "Die Einstellung wurde gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/household/settings";
    }

    /* --- Belohnungen ----------------------------------------------------------- */

    @GetMapping("/rewards")
    String rewards(Model model) {
        User viewer = requireRewards();
        HouseholdViews.Context ctx = views.context(viewer);
        boolean forOthers = rewards.can(viewer, "REDEEM");
        boolean approve = rewards.can(viewer, "APPROVE");
        Map<Long, Integer> balances = rewards.balances();
        List<FamilyMember> accounts = ctx.members().values().stream()
                .filter(m -> forOthers || approve || m.getId().equals(ctx.ownMember())).toList();
        model.addAttribute("accounts", accounts);
        model.addAttribute("balances", balances);
        model.addAttribute("ownMember", ctx.ownMember());
        model.addAttribute("ownBalance", ctx.ownMember() == null ? 0 : balances.getOrDefault(ctx.ownMember(), 0));
        model.addAttribute("rewards", rewards.rewards().stream().filter(r -> r.isActive() || rewards.can(viewer, "MANAGE")).toList());
        model.addAttribute("pending", approve ? rewards.pending() : List.<Redemption>of());
        model.addAttribute("recent", rewards.recent(viewer, 20));
        model.addAttribute("members", ctx.members());
        model.addAttribute("forOthers", forOthers);
        model.addAttribute("canApprove", approve);
        model.addAttribute("canManage", rewards.can(viewer, "MANAGE"));
        model.addAttribute("tab", "rewards");
        return "household/rewards";
    }

    @PostMapping("/rewards")
    String createReward(@RequestParam(required = false) String title, @RequestParam(required = false) String description,
                        @RequestParam(required = false) Integer cost, RedirectAttributes redirect) {
        try {
            Reward r = rewards.create(title, description, cost, requireRewards());
            redirect.addFlashAttribute("success", "Belohnung „" + r.getTitle() + "“ wurde angelegt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/household/rewards";
    }

    @PostMapping("/rewards/{id}")
    String updateReward(@PathVariable Long id, @RequestParam(required = false) String title,
                        @RequestParam(required = false) String description, @RequestParam(required = false) Integer cost,
                        @RequestParam(defaultValue = "false") boolean active, RedirectAttributes redirect) {
        try {
            rewards.update(id, title, description, cost, active, requireRewards());
            redirect.addFlashAttribute("success", "Belohnung wurde gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/household/rewards";
    }

    @PostMapping("/rewards/{id}/delete")
    String deleteReward(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            rewards.delete(id, requireRewards());
            redirect.addFlashAttribute("success", "Belohnung wurde gelöscht.");
        } catch (ChoreConflictException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/household/rewards";
    }

    @PostMapping("/rewards/{id}/redeem")
    String redeem(@PathVariable Long id, @RequestParam(required = false) Long member, RedirectAttributes redirect) {
        try {
            Redemption r = rewards.request(id, member, requireRewards());
            redirect.addFlashAttribute("success", "„" + r.getReward().getTitle()
                    + "“ ist beantragt. Die Minuten sind vorgemerkt, bis jemand bestätigt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/household/rewards";
    }

    @PostMapping("/redemptions/{id}/decide")
    String decide(@PathVariable Long id, @RequestParam boolean approve, RedirectAttributes redirect) {
        try {
            rewards.decide(id, approve, requireRewards());
            redirect.addFlashAttribute("success", approve ? "Einlösung bestätigt." : "Einlösung abgelehnt.");
        } catch (ChoreConflictException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/household/rewards";
    }

    private User requireModule() {
        User user = currentUser.require();
        if (!access.mayOpen(user.getUsername())) {
            throw new AccessDeniedException("Keine Berechtigung für den Haushaltsplan");
        }
        return user;
    }

    private User requireRewards() {
        User user = currentUser.require();
        if (!rewards.can(user, "VIEW")) {
            throw new AccessDeniedException("Keine Berechtigung für Belohnungen");
        }
        return user;
    }

    private static LocalDate parse(String value, LocalDate fallback) {
        try {
            return value == null || value.isBlank() ? fallback : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
