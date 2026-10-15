package club.badernet.nestwerk.web;

import club.badernet.nestwerk.backup.BackupRun;
import club.badernet.nestwerk.backup.BackupService;
import club.badernet.nestwerk.backup.InvalidBackupException;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sicherungen anlegen, herunterladen, hochladen und wiederherstellen. */
@Controller
public class AdminBackupController {

    /** Eine Sicherungsdatei für die Liste. */
    public record FileRow(String name, String size, String modified) {
    }

    /** Ein Lauf für den Verlauf. */
    public record RunRow(String kind, String status, String statusKey, String started, String file, String message) {
    }

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");
    private static final Map<BackupRun.Status, String> STATUS = Map.of(BackupRun.Status.RUNNING, "läuft",
            BackupRun.Status.SUCCESS, "erfolgreich", BackupRun.Status.WARNING, "mit Hinweisen",
            BackupRun.Status.FAILED, "fehlgeschlagen");

    private final BackupService backups;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AdminBackupController(BackupService backups, CurrentUser currentUser, Clock clock) {
        this.backups = backups;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping("/admin/backup")
    String page(Model model) {
        User actor = requireManage();
        model.addAttribute("files", backups.files(actor).stream().map(f -> new FileRow(f.name(), size(f.size()),
                moment(f.modified()))).toList());
        model.addAttribute("directory", backups.directory().toString());
        addStatus(model, actor);
        return "admin/backup";
    }

    /** Wird abgefragt, solange eine Sicherung läuft; danach lädt die Seite neu, um die neue Datei zu zeigen. */
    @GetMapping("/admin/backup/status")
    String status(Model model, HttpServletResponse response) {
        addStatus(model, requireManage());
        if (!backups.isBusy()) {
            response.setHeader("HX-Refresh", "true");
        }
        return "admin/backup :: status";
    }

    private void addStatus(Model model, User actor) {
        model.addAttribute("busy", backups.isBusy());
        model.addAttribute("runs", backups.recentRuns(actor, 15).stream().map(this::row).toList());
    }

    @PostMapping("/admin/backup/run")
    String run(RedirectAttributes redirect) {
        try {
            backups.startBackup(requireManage());
            redirect.addFlashAttribute("success", "Die Sicherung läuft. Der Verlauf zeigt, wann sie fertig ist.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/backup";
    }

    @GetMapping("/admin/backup/files/{name}")
    ResponseEntity<Resource> download(@PathVariable String name) {
        Path file = backups.file(name, requireManage());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build()
                        .toString())
                .body(new FileSystemResource(file));
    }

    @PostMapping("/admin/backup/files/{name}/delete")
    String delete(@PathVariable String name, RedirectAttributes redirect) {
        backups.delete(name, requireManage());
        redirect.addFlashAttribute("success", "Die Sicherung " + name + " wurde gelöscht.");
        return "redirect:/admin/backup";
    }

    @PostMapping("/admin/backup/files/{name}/restore")
    String restore(@PathVariable String name, @RequestParam(required = false) String confirm,
                   HttpServletRequest request, RedirectAttributes redirect) {
        User actor = requireManage();
        if (!name.equals(confirm == null ? null : confirm.trim())) {
            redirect.addFlashAttribute("error", "Zum Wiederherstellen bitte den Dateinamen genau eintippen.");
            return "redirect:/admin/backup";
        }
        try {
            backups.restore(name, actor);
        } catch (InvalidBackupException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/backup";
        }
        // Benutzer und Rechte stammen jetzt aus der Sicherung: neu anmelden
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return "redirect:/login?restored";
    }

    /** Hochladen als roher Datenstrom (fetch mit der Datei als Body), damit die Größe nicht am Multipart-Limit hängt. */
    @PostMapping(value = "/admin/backup/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseBody
    ResponseEntity<Map<String, String>> upload(InputStream body) throws IOException {
        User actor = requireManage();
        try {
            return ResponseEntity.ok(Map.of("name", backups.upload(body, actor)));
        } catch (InvalidBackupException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private User requireManage() {
        User user = currentUser.require();
        if (!backups.canManage(user)) {
            throw new AccessDeniedException("Keine Berechtigung, Sicherungen zu verwalten");
        }
        return user;
    }

    private RunRow row(BackupRun run) {
        return new RunRow(run.getKind().label(), STATUS.get(run.getStatus()),
                run.getStatus().name().toLowerCase(Locale.ROOT), moment(run.getStartedAt()), run.getFileName(),
                run.getMessage());
    }

    private String moment(Instant value) {
        return value == null ? "" : value.atZone(clock.getZone()).format(MOMENT);
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        List<String> units = List.of("KB", "MB", "GB", "TB");
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.size() - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.GERMAN, value < 10 ? "%.1f %s" : "%.0f %s", value, units.get(unit));
    }
}
