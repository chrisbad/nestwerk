package club.badernet.nestwerk.web;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.HomeworkService;
import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.todo.TodoNotFoundException;
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
import java.util.Map;

/** Hausaufgaben eines Schuljahrs; sie sind ToDos, die dem Kind zugewiesen sind. */
@Controller
@RequestMapping("/school")
@PreAuthorize("hasAuthority('SCHOOL_VIEW')")
public class HomeworkController {

    /** Wiederholungen zur Auswahl: Schlüssel im Formular, Regel für die ToDo-Serie. */
    static final Map<String, String[]> REPEATS = new LinkedHashMap<>();

    static {
        REPEATS.put("NONE", new String[] {"Einmalig", null});
        REPEATS.put("SCHOOLDAYS", new String[] {"An jedem Schultag", "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"});
        REPEATS.put("WEEKLY", new String[] {"Jede Woche", "FREQ=WEEKLY"});
    }

    private final SchoolService school;
    private final HomeworkService homework;
    private final TimetableService timetable;
    private final SchoolAccess access;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings calendarSettings;

    public HomeworkController(SchoolService school, HomeworkService homework, TimetableService timetable,
                              SchoolAccess access, FamilyMemberService members, CurrentUser currentUser,
                              CalendarSettings calendarSettings) {
        this.school = school;
        this.homework = homework;
        this.timetable = timetable;
        this.access = access;
        this.members = members;
        this.currentUser = currentUser;
        this.calendarSettings = calendarSettings;
    }

    @GetMapping("/years/{yearId}/homework")
    String page(@PathVariable Long yearId, @RequestParam(required = false) Long subject,
                @RequestParam(defaultValue = "false") boolean done, Model model) {
        SchoolYear year = school.year(yearId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SchoolProfile profile = school.profile(year.getProfileId()).orElseThrow();
        FamilyMember member = members.findAll().stream().filter(m -> m.getId().equals(profile.getMemberId()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var user = currentUser.require();
        model.addAttribute("year", year);
        model.addAttribute("member", member);
        model.addAttribute("items", homework.list(yearId, subject, done, user));
        model.addAttribute("subjects", timetable.subjects(yearId));
        model.addAttribute("subjectFilter", subject);
        model.addAttribute("showDone", done);
        model.addAttribute("repeats", REPEATS);
        model.addAttribute("today", LocalDate.now(calendarSettings.zone()));
        model.addAttribute("canHomework", access.has(user, SchoolPermission.SCHOOL_HOMEWORK));
        return "school/homework";
    }

    @PostMapping("/years/{yearId}/homework")
    @PreAuthorize("hasAuthority('SCHOOL_HOMEWORK')")
    String create(@PathVariable Long yearId, @RequestParam(required = false) Long subjectId,
                  @RequestParam String title, @RequestParam(required = false) String description,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueOn,
                  @RequestParam(defaultValue = "NONE") String repeat, RedirectAttributes redirect) {
        String[] rule = REPEATS.get(repeat);
        try {
            if (rule == null) {
                throw new IllegalArgumentException("Unbekannte Wiederholung");
            }
            homework.create(yearId, subjectId, title, description, dueOn, rule[1], currentUser.require());
            redirect.addFlashAttribute("success", "Hausaufgabe angelegt.");
        } catch (IllegalArgumentException | AccessDeniedException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/school/years/" + yearId + "/homework";
    }

    @PostMapping("/homework/{todoId}/done")
    @PreAuthorize("hasAuthority('SCHOOL_HOMEWORK')")
    String done(@PathVariable Long todoId, @RequestParam Long yearId,
                @RequestParam(defaultValue = "true") boolean done, RedirectAttributes redirect) {
        try {
            homework.setDone(todoId, done, currentUser.require());
            redirect.addFlashAttribute("success", done ? "Erledigt." : "Wieder offen.");
        } catch (IllegalArgumentException | AccessDeniedException | TodoNotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage() == null ? "Nicht möglich." : e.getMessage());
        }
        if (!done) {
            redirect.addAttribute("done", true);
        }
        return "redirect:/school/years/" + yearId + "/homework";
    }
}
