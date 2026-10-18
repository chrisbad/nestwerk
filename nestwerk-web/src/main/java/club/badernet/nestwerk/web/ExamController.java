package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.ExamKind;
import club.badernet.nestwerk.school.ExamService;
import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolSubject;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.TimetableService;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Klassenarbeiten, Tests, Elternabende und Ausflüge eines Schuljahrs. */
@Controller
@RequestMapping("/school")
@PreAuthorize("hasAuthority('SCHOOL_VIEW')")
public class ExamController {

    private final SchoolService school;
    private final ExamService exams;
    private final TimetableService timetable;
    private final SchoolAccess access;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings calendarSettings;

    public ExamController(SchoolService school, ExamService exams, TimetableService timetable, SchoolAccess access,
                          FamilyMemberService members, CurrentUser currentUser, CalendarSettings calendarSettings) {
        this.school = school;
        this.exams = exams;
        this.timetable = timetable;
        this.access = access;
        this.members = members;
        this.currentUser = currentUser;
        this.calendarSettings = calendarSettings;
    }

    @GetMapping("/years/{yearId}/exams")
    String page(@PathVariable Long yearId, Model model) {
        SchoolYear year = school.year(yearId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SchoolProfile profile = school.profile(year.getProfileId()).orElseThrow();
        FamilyMember member = members.findAll().stream().filter(m -> m.getId().equals(profile.getMemberId()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        LocalDate today = LocalDate.now(calendarSettings.zone());
        Map<Long, SchoolSubject> subjects = timetable.subjects(yearId).stream()
                .collect(Collectors.toMap(SchoolSubject::getId, Function.identity()));
        var all = exams.list(yearId);
        model.addAttribute("year", year);
        model.addAttribute("member", member);
        model.addAttribute("upcoming", all.stream().filter(e -> !e.getDay().isBefore(today)).toList());
        model.addAttribute("past", all.stream().filter(e -> e.getDay().isBefore(today)).toList());
        model.addAttribute("subjects", subjects);
        model.addAttribute("subjectList", timetable.subjects(yearId));
        model.addAttribute("kinds", ExamKind.values());
        model.addAttribute("today", today);
        model.addAttribute("canEdit", access.has(currentUser.require(), SchoolPermission.SCHOOL_EDIT));
        return "school/exams";
    }

    @PostMapping("/years/{yearId}/exams")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String create(@PathVariable Long yearId, @RequestParam(required = false) Long subjectId,
                  @RequestParam ExamKind kind, @RequestParam(required = false) String title,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                  @RequestParam(defaultValue = "0") int studyDays, RedirectAttributes redirect) {
        return run(yearId, redirect, "Termin angelegt.",
                () -> exams.create(yearId, subjectId, kind, title, day, studyDays, currentUser.require()));
    }

    @PostMapping("/exams/{id}/delete")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String delete(@PathVariable Long id, @RequestParam Long yearId, RedirectAttributes redirect) {
        return run(yearId, redirect, "Termin gelöscht.", () -> exams.delete(id, currentUser.require()));
    }

    private static String run(Long yearId, RedirectAttributes redirect, String success, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", success);
        } catch (IllegalArgumentException | AccessDeniedException | EventNotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/school/years/" + yearId + "/exams";
    }
}
