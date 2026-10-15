package club.badernet.nestwerk.web;

import club.badernet.nestwerk.backup.BackupDistributor;
import club.badernet.nestwerk.backup.BackupRun;
import club.badernet.nestwerk.backup.BackupScheduler;
import club.badernet.nestwerk.backup.BackupSettings;
import club.badernet.nestwerk.backup.target.BackupTarget;
import club.badernet.nestwerk.backup.target.DirectoryTarget;
import club.badernet.nestwerk.backup.target.SftpTarget;
import club.badernet.nestwerk.backup.BackupService;
import club.badernet.nestwerk.backup.InvalidBackupException;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Arrays;
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
    private final BackupSettings settings;
    private final BackupDistributor targets;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AdminBackupController(BackupService backups, BackupSettings settings, BackupDistributor targets,
                                 CurrentUser currentUser, Clock clock) {
        this.backups = backups;
        this.settings = settings;
        this.targets = targets;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping("/admin/backup")
    String page(Model model) {
        User actor = requireManage();
        model.addAttribute("files", backups.files(actor).stream().map(f -> new FileRow(f.name(), size(f.size()),
                moment(f.modified()))).toList());
        model.addAttribute("directory", backups.directory().toString());
        BackupSettings.Schedule schedule = settings.schedule();
        model.addAttribute("schedule", schedule);
        model.addAttribute("nextRun", schedule.enabled() ? moment(BackupScheduler.nextSlot(schedule,
                ZonedDateTime.now(clock)).toInstant()) : null);
        model.addAttribute("weekdays", Arrays.stream(DayOfWeek.values())
                .map(d -> Map.entry(d.name(), d.getDisplayName(TextStyle.FULL, Locale.GERMAN))).toList());
        model.addAttribute("retention", settings.retention());
        model.addAttribute("notifySuccess", settings.notifySuccess());
        model.addAttribute("encryptionEnabled", settings.encryptionEnabled());
        model.addAttribute("hasPassphrase", settings.hasPassphrase());
        DirectoryTarget external = targets.external();
        model.addAttribute("externalEnabled", settings.externalEnabled());
        model.addAttribute("externalDir", external.directory().toString());
        model.addAttribute("externalPrepared", external.isPrepared());
        model.addAttribute("webdav", settings.webDav());
        model.addAttribute("sftp", settings.sftp());
        addStatus(model, actor);
        return "admin/backup";
    }

    // --- Einstellungen ---------------------------------------------------------------

    @PostMapping("/admin/backup/settings/schedule")
    String saveSchedule(@RequestParam(defaultValue = "false") boolean enabled,
                        @RequestParam(defaultValue = "DAILY") BackupSettings.Frequency frequency,
                        @RequestParam(defaultValue = "SUNDAY") DayOfWeek weekday,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
                        RedirectAttributes redirect) {
        requireManage();
        return save(redirect, "Der Zeitplan ist gespeichert.", () -> settings.updateSchedule(
                new BackupSettings.Schedule(enabled, frequency, weekday, time), clock.instant()));
    }

    @PostMapping("/admin/backup/settings/retention")
    String saveRetention(@RequestParam(defaultValue = "14") int keepLast,
                         @RequestParam(defaultValue = "0") int keepWeekly,
                         @RequestParam(defaultValue = "0") int keepMonthly,
                         @RequestParam(defaultValue = "false") boolean notifySuccess, RedirectAttributes redirect) {
        requireManage();
        return save(redirect, "Die Aufbewahrung ist gespeichert.", () -> settings.updateRetention(
                new BackupSettings.RetentionRule(keepLast, keepWeekly, keepMonthly), notifySuccess));
    }

    @PostMapping("/admin/backup/settings/encryption")
    String saveEncryption(@RequestParam(defaultValue = "false") boolean enabled,
                          @RequestParam(required = false) String passphrase, RedirectAttributes redirect) {
        requireManage();
        return save(redirect, "Die Verschlüsselung ist gespeichert.",
                () -> settings.updateEncryption(enabled, passphrase));
    }

    @PostMapping("/admin/backup/settings/external")
    String saveExternal(@RequestParam(defaultValue = "false") boolean enabled, RedirectAttributes redirect) {
        requireManage();
        return save(redirect, "Die externe Platte ist " + (enabled ? "eingeschaltet." : "ausgeschaltet."),
                () -> settings.updateExternal(enabled));
    }

    @PostMapping("/admin/backup/targets/external/prepare")
    String prepareExternal(RedirectAttributes redirect) {
        requireManage();
        try {
            targets.external().prepare();
            redirect.addFlashAttribute("success", "Die externe Platte ist als Sicherungsziel eingerichtet.");
        } catch (IOException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/backup#ziele";
    }

    @PostMapping("/admin/backup/settings/webdav")
    String saveWebDav(@RequestParam(defaultValue = "false") boolean enabled,
                      @RequestParam(required = false) String url, @RequestParam(required = false) String user,
                      @RequestParam(required = false) String password, RedirectAttributes redirect) {
        requireManage();
        return save(redirect, "WebDAV ist gespeichert.", () -> settings.updateWebDav(enabled, url, user, password));
    }

    @PostMapping("/admin/backup/settings/sftp")
    String saveSftp(@RequestParam(defaultValue = "false") boolean enabled,
                    @RequestParam(required = false) String host, @RequestParam(required = false) Integer port,
                    @RequestParam(required = false) String user, @RequestParam(required = false) String path,
                    @RequestParam(required = false) String password,
                    @RequestParam(required = false) String privateKey, RedirectAttributes redirect) {
        requireManage();
        return save(redirect, "SFTP ist gespeichert.",
                () -> settings.updateSftp(enabled, host, port, user, path, password, privateKey));
    }

    @PostMapping("/admin/backup/targets/{key}/test")
    String testTarget(@PathVariable String key, RedirectAttributes redirect) {
        requireManage();
        try {
            BackupTarget target = switch (key) {
                case "external" -> targets.external();
                case "webdav" -> targets.webDav()
                        .orElseThrow(() -> new IOException("Bitte zuerst die Adresse speichern."));
                case "sftp" -> {
                    SftpTarget sftp = targets.sftp()
                            .orElseThrow(() -> new IOException("Bitte zuerst den Server speichern."));
                    if (settings.sftp().hostKey().isEmpty()) {
                        redirect.addFlashAttribute("sftpHostKey", sftp.fetchHostKey());
                        yield null;
                    }
                    yield sftp;
                }
                default -> throw new IOException("Unbekanntes Ziel.");
            };
            if (target != null) {
                redirect.addFlashAttribute("success", target.label() + ": " + target.test());
            }
        } catch (IOException | RuntimeException e) {
            redirect.addFlashAttribute("error", BackupService.describe(e));
        }
        return "redirect:/admin/backup#ziele";
    }

    @PostMapping("/admin/backup/targets/sftp/trust")
    String trustSftp(@RequestParam String knownHost, RedirectAttributes redirect) {
        requireManage();
        BackupSettings.Sftp sftp = settings.sftp();
        String expected = sftp.port() == 22 ? sftp.host() : "[" + sftp.host() + "]:" + sftp.port();
        String line = knownHost.trim();
        if (!line.startsWith(expected + " ") || line.contains("\n") || line.contains("\r")) {
            redirect.addFlashAttribute("error", "Der Host-Key passt nicht zum eingestellten Server.");
        } else {
            settings.trustSftpHostKey(line);
            redirect.addFlashAttribute("success", "Der Host-Key ist bestätigt. Jetzt erneut „Verbindung testen“.");
        }
        return "redirect:/admin/backup#ziele";
    }

    private String save(RedirectAttributes redirect, String message, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", message);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/backup#einstellungen";
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
    ResponseEntity<Map<String, String>> upload(InputStream body,
                                               @RequestHeader(value = "X-Backup-Passphrase", required = false)
                                               String passphrase) throws IOException {
        User actor = requireManage();
        try {
            String decoded = passphrase == null ? null : URLDecoder.decode(passphrase, StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of("name", backups.upload(body, decoded, actor)));
        } catch (InvalidBackupException | IllegalArgumentException e) {
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
