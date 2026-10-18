package club.badernet.nestwerk.web;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.ChangeKind;
import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolDays;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.Timetable;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.school.WeekKind;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.settings.CalendarSettings;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stundenplan eines Schuljahrs: Wochenansicht, Fächer, Stunden, A/B-Wochen sowie Ausfall und Vertretung. */
@Controller
@RequestMapping("/school")
@PreAuthorize("hasAuthority('SCHOOL_VIEW')")
public class TimetableController {

    private final SchoolService school;
    private final TimetableService timetable;
    private final SchoolAccess access;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings calendarSettings;
    private final SchoolDays schoolDays;

    public TimetableController(SchoolService school, TimetableService timetable, SchoolAccess access,
                               FamilyMemberService members, CurrentUser currentUser,
                               CalendarSettings calendarSettings, SchoolDays schoolDays) {
        this.schoolDays = schoolDays;
        this.school = school;
        this.timetable = timetable;
        this.access = access;
        this.members = members;
        this.currentUser = currentUser;
        this.calendarSettings = calendarSettings;
    }

    /** Ein Wochentag der Ansicht. */
    public record DayView(LocalDate date, String name, WeekKind weekKind, List<Timetable.Entry> entries,
                          String free) {
    }

    @GetMapping("/years/{yearId}/timetable")
    String page(@PathVariable Long yearId,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                Model model) {
        SchoolYear year = school.year(yearId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SchoolProfile profile = school.profile(year.getProfileId()).orElseThrow();
        FamilyMember member = members.findAll().stream().filter(m -> m.getId().equals(profile.getMemberId()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        LocalDate today = LocalDate.now(calendarSettings.zone());
        LocalDate monday = (week != null ? week : today).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        Map<DayOfWeek, List<Timetable.Entry>> entries = timetable.week(yearId, monday);
        boolean weekend = !entries.get(DayOfWeek.SATURDAY).isEmpty() || !entries.get(DayOfWeek.SUNDAY).isEmpty();
        Map<LocalDate, String> free = schoolDays.freeDays(profile.getState(), monday, monday.plusDays(6));
        List<DayView> days = new ArrayList<>();
        for (int i = 0; i < (weekend ? 7 : 5); i++) {
            LocalDate date = monday.plusDays(i);
            days.add(new DayView(date, date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.GERMAN),
                    Timetable.weekKind(date, year.getWeekAStart()), entries.get(date.getDayOfWeek()),
                    free.get(date)));
        }

        var user = currentUser.require();
        model.addAttribute("year", year);
        model.addAttribute("member", member);
        model.addAttribute("days", days);
        model.addAttribute("monday", monday);
        model.addAttribute("today", today);
        model.addAttribute("previousWeek", monday.minusWeeks(1));
        model.addAttribute("nextWeek", monday.plusWeeks(1));
        model.addAttribute("weekKind", Timetable.weekKind(monday, year.getWeekAStart()));
        model.addAttribute("subjects", timetable.subjects(yearId));
        model.addAttribute("lessons", timetable.lessons(yearId));
        model.addAttribute("weekKinds", WeekKind.values());
        model.addAttribute("changeKinds", ChangeKind.values());
        model.addAttribute("weekdays", java.util.EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SUNDAY));
        model.addAttribute("canEdit", access.has(user, SchoolPermission.SCHOOL_EDIT));
        return "school/timetable";
    }

    @PostMapping("/years/{yearId}/subjects")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String addSubject(@PathVariable Long yearId, @RequestParam String name,
                      @RequestParam(required = false) String color, @RequestParam(required = false) String teacher,
                      @RequestParam(required = false) String week, RedirectAttributes redirect) {
        return run(yearId, week, redirect, "Fach angelegt.",
                () -> timetable.addSubject(yearId, name, color, teacher, currentUser.require()));
    }

    @PostMapping("/subjects/{id}")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String changeSubject(@PathVariable Long id, @RequestParam Long yearId, @RequestParam String name,
                         @RequestParam(required = false) String color, @RequestParam(required = false) String teacher,
                         @RequestParam(required = false) String week, RedirectAttributes redirect) {
        return run(yearId, week, redirect, "Fach gespeichert.",
                () -> timetable.changeSubject(id, name, color, teacher, currentUser.require()));
    }

    @PostMapping("/subjects/{id}/delete")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String deleteSubject(@PathVariable Long id, @RequestParam Long yearId,
                         @RequestParam(required = false) String week, RedirectAttributes redirect) {
        return run(yearId, week, redirect, "Fach samt Stunden gelöscht.",
                () -> timetable.deleteSubject(id, currentUser.require()));
    }

    @PostMapping("/years/{yearId}/lessons")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String addLesson(@PathVariable Long yearId, @RequestParam Long subjectId, @RequestParam DayOfWeek weekday,
                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startsAt,
                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endsAt,
                     @RequestParam(required = false) String room,
                     @RequestParam(defaultValue = "EVERY") WeekKind weekKind,
                     @RequestParam(required = false) String week, RedirectAttributes redirect) {
        return run(yearId, week, redirect, "Stunde angelegt.", () -> timetable.addLesson(yearId, subjectId, weekday,
                startsAt, endsAt, room, weekKind, currentUser.require()));
    }

    @PostMapping("/lessons/{id}/delete")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String deleteLesson(@PathVariable Long id, @RequestParam Long yearId,
                        @RequestParam(required = false) String week, RedirectAttributes redirect) {
        return run(yearId, week, redirect, "Stunde gelöscht.", () -> timetable.deleteLesson(id, currentUser.require()));
    }

    @PostMapping("/years/{yearId}/week-plan")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String weekPlan(@PathVariable Long yearId,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekAStart,
                    @RequestParam(required = false) String week, RedirectAttributes redirect) {
        return run(yearId, week, redirect, weekAStart == null ? "A/B-Wochen abgeschaltet." : "A/B-Wochen gespeichert.",
                () -> timetable.setWeekPlan(yearId, weekAStart, currentUser.require()));
    }

    @PostMapping("/lessons/{id}/changes")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String markLesson(@PathVariable Long id, @RequestParam Long yearId,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                      @RequestParam ChangeKind kind, @RequestParam(required = false) String note,
                      @RequestParam(required = false) String week, RedirectAttributes redirect) {
        return run(yearId, week, redirect, "Gespeichert.",
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
