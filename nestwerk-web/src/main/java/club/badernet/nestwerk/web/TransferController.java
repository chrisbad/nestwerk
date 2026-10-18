package club.badernet.nestwerk.web;

import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.transfer.CalendarTransfer;
import club.badernet.nestwerk.transfer.ContactTransfer;
import club.badernet.nestwerk.transfer.ImportReport;
import club.badernet.nestwerk.transfer.TodoTransfer;
import club.badernet.nestwerk.user.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * Export (Kalender als .ics, Kontakte als .vcf/.csv, ToDos als .csv) und Import mit Vorschau. Die hochgeladene
 * Datei liegt bis zur Bestätigung höchstens 15 Minuten in der Sitzung.
 */
@Controller
public class TransferController {

    /** Was sich importieren lässt: Titel, erlaubte Formate, Rücksprung. */
    public record Kind(String key, String title, String formats, String accept, String back, String hint) {
    }

    /** Eine Datei, die auf Bestätigung wartet. */
    record Pending(String kind, String format, String fileName, byte[] data, Instant uploaded) {
    }

    static final Map<String, Kind> KINDS = Map.of(
            "calendar", new Kind("calendar", "Termine importieren", "iCal (.ics)", ".ics,text/calendar", "/calendar",
                    "Zum Beispiel ein Export aus Google Kalender, Outlook oder Apple Kalender. Termine mit gleicher "
                            + "UID werden aktualisiert statt doppelt angelegt."),
            "contacts", new Kind("contacts", "Kontakte importieren", "vCard (.vcf) oder CSV", ".vcf,.csv,text/vcard,text/csv",
                    "/contacts", "CSV-Dateien aus Nestwerk, Google Kontakte oder Outlook werden erkannt. Vorhandene "
                            + "Kontakte (gleicher Name und gleiche E-Mail oder Telefonnummer) werden ergänzt."),
            "todos", new Kind("todos", "ToDos importieren", "CSV", ".csv,text/csv", "/todos",
                    "Spalten wie im Export: Titel, Beschreibung, Status, Priorität, Ab, Bis, Wiedervorlage, "
                            + "Schlagworte, Zuständige, Verantwortlich, Privat. Mit ID wird ein vorhandenes ToDo "
                            + "aktualisiert."));

    private static final Duration PENDING_VALIDITY = Duration.ofMinutes(15);
    private static final String SESSION_KEY = "nestwerk.pendingImport";

    private final CalendarTransfer calendar;
    private final ContactTransfer contacts;
    private final TodoTransfer todos;
    private final CurrentUser currentUser;
    private final Clock clock;

    public TransferController(CalendarTransfer calendar, ContactTransfer contacts, TodoTransfer todos,
                              CurrentUser currentUser, Clock clock) {
        this.calendar = calendar;
        this.contacts = contacts;
        this.todos = todos;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // --- Export ------------------------------------------------------------------------

    @GetMapping("/export/calendar.ics")
    ResponseEntity<byte[]> exportCalendar() {
        return file(calendar.exportIcs(currentUser.require()).getBytes(StandardCharsets.UTF_8),
                "text/calendar; charset=utf-8", "nestwerk-kalender-" + LocalDate.now(clock) + ".ics");
    }

    @GetMapping("/export/contacts.vcf")
    ResponseEntity<byte[]> exportContactsVcf() {
        return file(contacts.exportVcf(currentUser.require()).getBytes(StandardCharsets.UTF_8),
                "text/vcard; charset=utf-8", "nestwerk-kontakte-" + LocalDate.now(clock) + ".vcf");
    }

    @GetMapping("/export/contacts.csv")
    ResponseEntity<byte[]> exportContactsCsv() {
        return file(contacts.exportCsv(currentUser.require()), "text/csv; charset=utf-8",
                "nestwerk-kontakte-" + LocalDate.now(clock) + ".csv");
    }

    @GetMapping("/export/todos.csv")
    ResponseEntity<byte[]> exportTodos(@ModelAttribute TodoFilterParams params) {
        return file(todos.exportCsv(currentUser.require(), params.toFilter()), "text/csv; charset=utf-8",
                "nestwerk-todos-" + LocalDate.now(clock) + ".csv");
    }

    private static ResponseEntity<byte[]> file(byte[] body, String type, String name) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build()
                        .toString())
                .body(body);
    }

    // --- Import ------------------------------------------------------------------------

    @GetMapping("/import/{kind}")
    String page(@PathVariable String kind, HttpSession session, Model model) {
        model.addAttribute("kind", kind(kind));
        pending(session, kind).ifPresent(p -> model.addAttribute("pendingName", p.fileName()));
        return "transfer/import";
    }

    @PostMapping("/import/{kind}")
    String upload(@PathVariable String kind, @RequestParam("file") MultipartFile file, HttpSession session,
                  Model model, RedirectAttributes redirect) throws IOException {
        Kind k = kind(kind);
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String format = format(kind, name, file.getContentType());
        if (file.isEmpty() || format == null) {
            redirect.addFlashAttribute("error", "Bitte eine Datei im Format " + k.formats() + " wählen.");
            return "redirect:/import/" + kind;
        }
        Pending pending = new Pending(kind, format, name, file.getBytes(), clock.instant());
        ImportReport report;
        try {
            report = run(pending, currentUser.require(), false);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/import/" + kind;
        }
        session.setAttribute(SESSION_KEY, pending);
        model.addAttribute("kind", k);
        model.addAttribute("report", report);
        model.addAttribute("fileName", name);
        return "transfer/import";
    }

    @PostMapping("/import/{kind}/confirm")
    String confirm(@PathVariable String kind, HttpSession session, Model model, RedirectAttributes redirect) {
        Kind k = kind(kind);
        var pending = pending(session, kind);
        if (pending.isEmpty()) {
            redirect.addFlashAttribute("error", "Die Datei ist nicht mehr vorhanden. Bitte erneut hochladen.");
            return "redirect:/import/" + kind;
        }
        session.removeAttribute(SESSION_KEY);
        ImportReport report = run(pending.get(), currentUser.require(), true);
        model.addAttribute("kind", k);
        model.addAttribute("report", report);
        model.addAttribute("fileName", pending.get().fileName());
        return "transfer/import";
    }

    private ImportReport run(Pending p, User actor, boolean commit) {
        return switch (p.kind() + ":" + p.format()) {
            case "calendar:ics" -> calendar.importIcs(p.data(), actor, commit);
            case "contacts:vcf" -> contacts.importVcf(p.data(), actor, commit);
            case "contacts:csv" -> contacts.importCsv(p.data(), actor, commit);
            case "todos:csv" -> todos.importCsv(p.data(), actor, commit);
            default -> throw new IllegalArgumentException("Dieses Format wird hier nicht unterstützt.");
        };
    }

    private java.util.Optional<Pending> pending(HttpSession session, String kind) {
        if (session.getAttribute(SESSION_KEY) instanceof Pending p && p.kind().equals(kind)
                && p.uploaded().plus(PENDING_VALIDITY).isAfter(clock.instant())) {
            return java.util.Optional.of(p);
        }
        return java.util.Optional.empty();
    }

    private static Kind kind(String key) {
        Kind kind = KINDS.get(key);
        if (kind == null) {
            throw new AccessDeniedException("Unbekannter Import");
        }
        return kind;
    }

    private static String format(String kind, String name, String contentType) {
        String lower = name.toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ics") || type.startsWith("text/calendar")) {
            return kind.equals("calendar") ? "ics" : null;
        }
        if (lower.endsWith(".vcf") || type.startsWith("text/vcard") || type.startsWith("text/x-vcard")) {
            return kind.equals("contacts") ? "vcf" : null;
        }
        if (lower.endsWith(".csv") || type.startsWith("text/csv")) {
            return kind.equals("contacts") || kind.equals("todos") ? "csv" : null;
        }
        return null;
    }
}
