package club.badernet.nestwerk.web;

import club.badernet.nestwerk.security.AdminPermission;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.update.AvailableUpdate;
import club.badernet.nestwerk.update.Channel;
import club.badernet.nestwerk.update.UpdateCheck;
import club.badernet.nestwerk.update.UpdateJobs;
import club.badernet.nestwerk.update.UpdateRun;
import club.badernet.nestwerk.update.UpdateSettings;
import club.badernet.nestwerk.user.User;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Admin-Seite „Updates“: laufende und verfügbare Version, Kanal, Einspielen und Verlauf. */
@Controller
public class AdminUpdateController {

    public record RunRow(String jobId, String from, String to, String status, String statusKey, String requested,
                         String message, String log) {
    }

    public record Step(String key, String label, String state) {
    }

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMAN);
    private static final Map<UpdateRun.Status, String> STATUS = Map.of(
            UpdateRun.Status.QUEUED, "wartet auf den Updater", UpdateRun.Status.RUNNING, "läuft",
            UpdateRun.Status.DONE, "eingespielt", UpdateRun.Status.ROLLED_BACK, "zurückgenommen",
            UpdateRun.Status.FAILED, "fehlgeschlagen");
    private static final List<Map.Entry<String, String>> STEPS = List.of(
            Map.entry("BACKUP", "Sicherung"), Map.entry("PULL", "Herunterladen"), Map.entry("FILES", "Dateien"),
            Map.entry("START", "Neustart"));

    private final UpdateCheck check;
    private final UpdateSettings settings;
    private final UpdateJobs jobs;
    private final PermissionCatalog permissions;
    private final CurrentUser currentUser;
    private final MarkdownRenderer markdown;
    private final Clock clock;

    public AdminUpdateController(UpdateCheck check, UpdateSettings settings, UpdateJobs jobs,
                                 PermissionCatalog permissions, CurrentUser currentUser, MarkdownRenderer markdown,
                                 Clock clock) {
        this.check = check;
        this.settings = settings;
        this.jobs = jobs;
        this.permissions = permissions;
        this.currentUser = currentUser;
        this.markdown = markdown;
        this.clock = clock;
    }

    @GetMapping("/admin/updates")
    String page(Model model) {
        requireManage();
        model.addAttribute("current", check.current().raw());
        model.addAttribute("dev", check.current().isDev());
        model.addAttribute("channel", settings.channel());
        model.addAttribute("channels", Channel.values());
        model.addAttribute("hasToken", settings.hasToken());
        model.addAttribute("lastChecked", settings.lastChecked().map(this::moment).orElse(null));
        model.addAttribute("lastError", settings.lastError().orElse(null));
        Optional<AvailableUpdate> update = check.cached();
        model.addAttribute("update", update.orElse(null));
        model.addAttribute("notes", update.map(u -> markdown.render(u.notes())).orElse(""));
        model.addAttribute("updaterInstalled", jobs.updaterInstalled());
        model.addAttribute("runs", jobs.recent(10).stream().map(this::row).toList());
        addStatus(model);
        return "admin/updates";
    }

    @GetMapping("/admin/updates/status")
    String status(Model model, HttpServletResponse response) {
        requireManage();
        jobs.sync();
        addStatus(model);
        if (model.getAttribute("open") == null) {
            response.setHeader("HX-Refresh", "true");
        }
        return "admin/updates :: status";
    }

    @PostMapping("/admin/updates/settings")
    String saveSettings(@RequestParam(defaultValue = "RELEASES") Channel channel,
                        @RequestParam(required = false) String token,
                        @RequestParam(defaultValue = "false") boolean removeToken, RedirectAttributes redirect) {
        requireManage();
        settings.update(channel, token);
        if (removeToken) {
            settings.removeToken();
        }
        redirect.addFlashAttribute("success", "Die Einstellungen sind gespeichert.");
        return "redirect:/admin/updates";
    }

    @PostMapping("/admin/updates/check")
    String checkNow(RedirectAttributes redirect) {
        requireManage();
        UpdateCheck.CheckResult result = check.check();
        if (result.error() != null) {
            redirect.addFlashAttribute("error", result.error());
        } else {
            redirect.addFlashAttribute("success", result.update()
                    .map(u -> "Version " + u.version() + " ist verfügbar.")
                    .orElse("Nestwerk ist auf dem neuesten Stand."));
        }
        return "redirect:/admin/updates";
    }

    @PostMapping("/admin/updates/start")
    String start(RedirectAttributes redirect) {
        User actor = requireManage();
        Optional<AvailableUpdate> update = check.cached();
        if (update.isEmpty()) {
            redirect.addFlashAttribute("error", "Es ist keine neuere Version bekannt.");
            return "redirect:/admin/updates";
        }
        try {
            jobs.start(update.get(), settings.channel(), actor);
            redirect.addFlashAttribute("success", "Das Update auf " + update.get().version()
                    + " ist beauftragt. Nestwerk startet gleich neu.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/updates";
    }

    private void addStatus(Model model) {
        Optional<UpdateRun> open = jobs.open();
        model.addAttribute("open", open.map(this::row).orElse(null));
        model.addAttribute("steps", open.map(this::steps).orElse(List.of()));
    }

    /** Schritte mit Zustand: erledigt, aktiv oder offen, je nach aktuellem Schritt des Laufs. */
    private List<Step> steps(UpdateRun run) {
        int active = -1;
        for (int i = 0; i < STEPS.size(); i++) {
            if (STEPS.get(i).getKey().equals(run.getStep())) {
                active = i;
            }
        }
        int current = active;
        return java.util.stream.IntStream.range(0, STEPS.size()).mapToObj(i -> new Step(STEPS.get(i).getKey(),
                STEPS.get(i).getValue(), i < current ? "done" : i == current ? "active" : "todo")).toList();
    }

    private RunRow row(UpdateRun run) {
        return new RunRow(run.getJobId(), run.getFromVersion(), run.getToVersion(), STATUS.get(run.getStatus()),
                run.getStatus().name().toLowerCase(Locale.ROOT), moment(run.getRequestedAt()), run.getMessage(),
                run.getLog());
    }

    private User requireManage() {
        User user = currentUser.require();
        if (!permissions.effective(user).contains(AdminPermission.UPDATE_MANAGE.name())) {
            throw new AccessDeniedException("Keine Berechtigung, Updates zu verwalten");
        }
        return user;
    }

    private String moment(Instant value) {
        return value == null ? "" : value.atZone(clock.getZone()).format(MOMENT);
    }
}
