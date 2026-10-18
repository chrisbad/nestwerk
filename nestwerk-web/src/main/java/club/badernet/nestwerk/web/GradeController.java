package club.badernet.nestwerk.web;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.GradeKind;
import club.badernet.nestwerk.school.GradeService;
import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.settings.CalendarSettings;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Noten eines Schuljahrs: Übersicht je Halbjahr mit Durchschnitt, Zeugnisnote und Verlauf. */
@Controller
@RequestMapping("/school")
@PreAuthorize("hasAnyAuthority('SCHOOL_GRADES', 'SCHOOL_GRADES_OWN')")
public class GradeController {

    /** Gewichte zur Auswahl in Prozent einer normalen Note. */
    static final Map<Integer, String> WEIGHTS = new LinkedHashMap<>();

    static {
        WEIGHTS.put(100, "einfach");
        WEIGHTS.put(200, "doppelt");
        WEIGHTS.put(50, "halb");
    }

    private final SchoolService school;
    private final GradeService grades;
    private final SchoolAccess access;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings calendarSettings;

    public GradeController(SchoolService school, GradeService grades, SchoolAccess access,
                           FamilyMemberService members, CurrentUser currentUser, CalendarSettings calendarSettings) {
        this.school = school;
        this.grades = grades;
        this.access = access;
        this.members = members;
        this.currentUser = currentUser;
        this.calendarSettings = calendarSettings;
    }

    @GetMapping("/years/{yearId}/grades")
    String page(@PathVariable Long yearId, @RequestParam(defaultValue = "0") int term, Model model) {
        SchoolYear year = school.year(yearId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SchoolProfile profile = school.profile(year.getProfileId()).orElseThrow();
        FamilyMember member = members.findAll().stream().filter(m -> m.getId().equals(profile.getMemberId()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var user = currentUser.require();
        LocalDate today = LocalDate.now(calendarSettings.zone());
        int shownTerm = term == 1 || term == 2 ? term : defaultTerm(year, today);
        GradeService.Overview overview = grades.overview(yearId, shownTerm, user);
        Map<Long, String> spark = new LinkedHashMap<>();
        overview.subjects().forEach(s -> spark.put(s.subject().getId(), sparkline(s.trend())));
        model.addAttribute("year", year);
        model.addAttribute("member", member);
        model.addAttribute("term", shownTerm);
        model.addAttribute("overview", overview);
        model.addAttribute("spark", spark);
        model.addAttribute("kinds", GradeKind.values());
        model.addAttribute("weights", WEIGHTS);
        model.addAttribute("today", today);
        model.addAttribute("canEdit", access.has(user, SchoolPermission.SCHOOL_GRADES));
        return "school/grades";
    }

    @PostMapping("/years/{yearId}/grades")
    @PreAuthorize("hasAuthority('SCHOOL_GRADES')")
    String add(@PathVariable Long yearId, @RequestParam Long subjectId, @RequestParam int term,
               @RequestParam String grade, @RequestParam(defaultValue = "100") int weight,
               @RequestParam GradeKind kind,
               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
               @RequestParam(required = false) String note, RedirectAttributes redirect) {
        return run(yearId, term, redirect, "Note gespeichert.", () -> grades.add(yearId, subjectId, term, grade,
                weight, kind, day, note, currentUser.require()));
    }

    @PostMapping("/grades/{id}/delete")
    @PreAuthorize("hasAuthority('SCHOOL_GRADES')")
    String delete(@PathVariable Long id, @RequestParam Long yearId, @RequestParam int term,
                  RedirectAttributes redirect) {
        return run(yearId, term, redirect, "Note gelöscht.", () -> grades.delete(id, currentUser.require()));
    }

    @PostMapping("/years/{yearId}/report-grades")
    @PreAuthorize("hasAuthority('SCHOOL_GRADES')")
    String report(@PathVariable Long yearId, @RequestParam Long subjectId, @RequestParam int term,
                  @RequestParam(required = false) String grade, RedirectAttributes redirect) {
        return run(yearId, term, redirect, "Zeugnisnote gespeichert.",
                () -> grades.setReportGrade(yearId, subjectId, term, grade, currentUser.require()));
    }

    private static int defaultTerm(SchoolYear year, LocalDate today) {
        LocalDate middle = year.getStartsOn().plusDays(
                java.time.temporal.ChronoUnit.DAYS.between(year.getStartsOn(), year.getEndsOn()) / 2);
        return today.isAfter(middle) ? 2 : 1;
    }

    /** Punkte für eine kleine Verlaufskurve (viewBox 100 x 24); Note 1 liegt oben, Note 6 unten. */
    static String sparkline(List<Double> trend) {
        if (trend.size() < 2) {
            return "";
        }
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < trend.size(); i++) {
            double x = 2 + 96.0 * i / (trend.size() - 1);
            double y = 2 + (Math.max(1, Math.min(6, trend.get(i))) - 1) / 5 * 20;
            if (i > 0) {
                points.append(' ');
            }
            points.append(String.format(java.util.Locale.ROOT, "%.1f,%.1f", x, y));
        }
        return points.toString();
    }

    private static String run(Long yearId, int term, RedirectAttributes redirect, String success, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", success);
        } catch (IllegalArgumentException | AccessDeniedException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        redirect.addAttribute("term", term);
        return "redirect:/school/years/" + yearId + "/grades";
    }
}
