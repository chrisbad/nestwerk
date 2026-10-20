package club.badernet.nestwerk.web;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.school.ChangeKind;
import club.badernet.nestwerk.school.ExamService;
import club.badernet.nestwerk.school.HomeworkService;
import club.badernet.nestwerk.school.PeriodService;
import club.badernet.nestwerk.school.SchoolDays;
import club.badernet.nestwerk.school.SchoolExam;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.Timetable;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.settings.CalendarSettings;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Main view of the school module: the timetable of a week as a grid, with one-click cancellations. */
@Controller
@RequestMapping("/school")
@PreAuthorize("hasAuthority('SCHOOL_VIEW')")
public class TimetableController {

    private final SchoolService school;
    private final TimetableService timetable;
    private final PeriodService periods;
    private final ExamService exams;
    private final HomeworkService homework;
    private final CurrentUser currentUser;
    private final CalendarSettings calendarSettings;
    private final SchoolDays schoolDays;
    private final SchoolHeader header;

    public TimetableController(SchoolService school, TimetableService timetable, PeriodService periods,
                               ExamService exams, HomeworkService homework, CurrentUser currentUser,
                               CalendarSettings calendarSettings, SchoolDays schoolDays, SchoolHeader header) {
        this.school = school;
        this.timetable = timetable;
        this.periods = periods;
        this.exams = exams;
        this.homework = homework;
        this.currentUser = currentUser;
        this.calendarSettings = calendarSettings;
        this.schoolDays = schoolDays;
        this.header = header;
    }

    /** Monday of the week to show: the requested week, otherwise this week and from Saturday on the next one. */
    static LocalDate shownMonday(LocalDate week, LocalDate today) {
        if (week != null) {
            return week.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }
        LocalDate base = today.getDayOfWeek().getValue() >= DayOfWeek.SATURDAY.getValue() ? today.plusDays(2) : today;
        return base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    @GetMapping("/years/{yearId}/timetable")
    String page(@PathVariable Long yearId,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                HttpSession session, Model model) {
        SchoolYear year = header.year(yearId);
        FamilyMember member = header.prepare(model, year, "timetable", session);
        SchoolProfile profile = school.profile(year.getProfileId()).orElseThrow();
        LocalDate today = LocalDate.now(calendarSettings.zone());
        LocalDate monday = shownMonday(week, today);

        Map<DayOfWeek, List<Timetable.Entry>> byWeekday = timetable.week(yearId, monday);
        boolean weekend = !byWeekday.get(DayOfWeek.SATURDAY).isEmpty() || !byWeekday.get(DayOfWeek.SUNDAY).isEmpty();
        List<LocalDate> days = new ArrayList<>();
        Map<LocalDate, List<Timetable.Entry>> entries = new LinkedHashMap<>();
        for (int i = 0; i < (weekend ? 7 : 5); i++) {
            LocalDate day = monday.plusDays(i);
            days.add(day);
            entries.put(day, byWeekday.get(day.getDayOfWeek()));
        }
        Map<LocalDate, String> free = schoolDays.freeDays(profile.getState(), monday, monday.plusDays(6));
        TimetableGrid grid = TimetableGrid.build(days, today, free, entries,
                periods.grid(year.getSchoolContactId()), timetable.teachers(yearId));

        model.addAttribute("grid", grid);
        model.addAttribute("monday", monday);
        model.addAttribute("sunday", monday.plusDays(6));
        model.addAttribute("today", today);
        model.addAttribute("thisWeek", monday.equals(shownMonday(null, today)));
        model.addAttribute("previousWeek", monday.minusWeeks(1));
        model.addAttribute("nextWeek", monday.plusWeeks(1));
        model.addAttribute("weekKind", Timetable.weekKind(monday, year.getWeekAStart()));
        model.addAttribute("hasLessons", !timetable.lessons(yearId).isEmpty());
        model.addAttribute("nextExams", exams.list(yearId).stream().filter(e -> !e.getDay().isBefore(today))
                .sorted(Comparator.comparing(SchoolExam::getDay)).limit(3).toList());
        model.addAttribute("subjectNames", timetable.subjects(yearId).stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.getId(), s -> s.getName())));
        model.addAttribute("openHomework", homework.list(yearId, null, false, currentUser.require()).stream()
                .limit(5).toList());
        model.addAttribute("member", member);
        return "school/timetable";
    }

    @PostMapping("/lessons/{id}/changes")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String markLesson(@PathVariable Long id, @RequestParam Long yearId,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                      @RequestParam ChangeKind kind, @RequestParam(required = false) String note,
                      @RequestParam(required = false) String week, RedirectAttributes redirect) {
        return run(yearId, week, redirect, kind.label() + " eingetragen.",
                () -> timetable.markLesson(id, day, kind, note, currentUser.require()));
    }

    @PostMapping("/changes/{id}/delete")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String clearChange(@PathVariable Long id, @RequestParam Long yearId,
                       @RequestParam(required = false) String week, RedirectAttributes redirect) {
        return run(yearId, week, redirect, "Wieder normal.", () -> timetable.clearChange(id, currentUser.require()));
    }

    private static String run(Long yearId, String week, RedirectAttributes redirect, String success, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", success);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        if (week != null && week.matches("\\d{4}-\\d{2}-\\d{2}")) {
            redirect.addAttribute("week", week);
        }
        return "redirect:/school/years/" + yearId + "/timetable";
    }
}
