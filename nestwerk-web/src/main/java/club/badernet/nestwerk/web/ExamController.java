package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.ExamInput;
import club.badernet.nestwerk.school.ExamKind;
import club.badernet.nestwerk.school.ExamService;
import club.badernet.nestwerk.school.SchoolExam;
import club.badernet.nestwerk.school.SchoolSubject;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.settings.CalendarSettings;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Exams, tests and school appointments of a school year in one list, told apart by their look. */
@Controller
@RequestMapping("/school")
@PreAuthorize("hasAuthority('SCHOOL_VIEW')")
public class ExamController {

    private final ExamService exams;
    private final TimetableService timetable;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings calendarSettings;
    private final SchoolHeader header;

    public ExamController(ExamService exams, TimetableService timetable, FamilyMemberService members,
                          CurrentUser currentUser, CalendarSettings calendarSettings, SchoolHeader header) {
        this.exams = exams;
        this.timetable = timetable;
        this.members = members;
        this.currentUser = currentUser;
        this.calendarSettings = calendarSettings;
        this.header = header;
    }

    @GetMapping("/years/{yearId}/exams")
    String page(@PathVariable Long yearId, @RequestParam(required = false) Long edit, HttpSession session,
                Model model) {
        SchoolYear year = header.year(yearId);
        header.prepare(model, year, "exams", session);
        LocalDate today = LocalDate.now(calendarSettings.zone());
        Map<Long, SchoolSubject> subjects = timetable.subjects(yearId).stream()
                .collect(Collectors.toMap(SchoolSubject::getId, Function.identity()));
        List<SchoolExam> all = exams.list(yearId);
        List<FamilyMember> people = members.findAll().stream()
                .sorted(Comparator.comparing(FamilyMember::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        Map<String, Set<Long>> defaults = new LinkedHashMap<>();
        Arrays.stream(ExamKind.values()).forEach(k -> defaults.put(k.name(), exams.defaultMembers(yearId, k)));
        model.addAttribute("upcoming", all.stream().filter(e -> !e.getDay().isBefore(today)).toList());
        model.addAttribute("past", all.stream().filter(e -> e.getDay().isBefore(today))
                .sorted(Comparator.comparing(SchoolExam::getDay).reversed()).toList());
        model.addAttribute("editing", all.stream().filter(e -> e.getId().equals(edit)).findFirst().orElse(null));
        model.addAttribute("subjects", subjects);
        model.addAttribute("subjectList", timetable.subjects(yearId));
        model.addAttribute("people", people);
        model.addAttribute("peopleById", people.stream()
                .collect(Collectors.toMap(FamilyMember::getId, Function.identity())));
        model.addAttribute("defaultsJson", defaults.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + e.getValue().stream().map(String::valueOf)
                        .collect(Collectors.joining(",", "[", "]")))
                .collect(Collectors.joining(",", "{", "}")));
        model.addAttribute("childDefaults", exams.defaultMembers(yearId, ExamKind.EXAM));
        model.addAttribute("kinds", ExamKind.values());
        model.addAttribute("today", today);
        return "school/exams";
    }

    /** Form of an exam or appointment. */
    public record ExamForm(Long subjectId, ExamKind kind, String title,
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day, Integer studyDays,
                           @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startsAt,
                           @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endsAt, String location,
                           Set<Long> memberId) {

        ExamInput toInput() {
            boolean exam = kind != null && kind.isExam();
            return new ExamInput(subjectId, kind, title, day, exam && studyDays != null ? studyDays : 0, startsAt,
                    endsAt, location, memberId);
        }
    }

    @PostMapping("/years/{yearId}/exams")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String create(@PathVariable Long yearId, ExamForm form, RedirectAttributes redirect) {
        return run(yearId, redirect, "Eingetragen.", () -> exams.create(yearId, form.toInput(), currentUser.require()));
    }

    @PostMapping("/exams/{id}")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String update(@PathVariable Long id, @RequestParam Long yearId, ExamForm form, RedirectAttributes redirect) {
        return run(yearId, redirect, "Gespeichert.", () -> exams.update(id, form.toInput(), currentUser.require()));
    }

    @PostMapping("/exams/{id}/delete")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String delete(@PathVariable Long id, @RequestParam Long yearId, RedirectAttributes redirect) {
        return run(yearId, redirect, "Gelöscht.", () -> exams.delete(id, currentUser.require()));
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
