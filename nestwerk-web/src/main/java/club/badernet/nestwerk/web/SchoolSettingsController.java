package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.GermanHolidays;
import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.LessonInput;
import club.badernet.nestwerk.school.PeriodKind;
import club.badernet.nestwerk.school.PeriodService;
import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolContacts;
import club.badernet.nestwerk.school.SchoolPeriod;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.school.WeekKind;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.user.User;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Settings of the school module behind the gear menu: children and school years, school with period grid, subjects
 * with teachers, and the lessons of the timetable.
 */
@Controller
@RequestMapping("/school")
@PreAuthorize("hasAuthority('SCHOOL_VIEW')")
public class SchoolSettingsController {

    private static final int EMPTY_GRID_ROWS = 3;

    /** A child with its profile and school years. */
    public record ProfileRow(SchoolProfile profile, FamilyMember member, SchoolYear active, List<SchoolYear> years) {
    }

    /** A row of the grid editor. */
    public record GridRow(PeriodKind kind, LocalTime startsAt, LocalTime endsAt, String label) {
    }

    private final SchoolService school;
    private final TimetableService timetable;
    private final PeriodService periods;
    private final SchoolContacts schoolContacts;
    private final SchoolAccess access;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings calendarSettings;
    private final SchoolHeader header;

    public SchoolSettingsController(SchoolService school, TimetableService timetable, PeriodService periods,
                                    SchoolContacts schoolContacts, SchoolAccess access, FamilyMemberService members,
                                    CurrentUser currentUser, CalendarSettings calendarSettings, SchoolHeader header) {
        this.school = school;
        this.timetable = timetable;
        this.periods = periods;
        this.schoolContacts = schoolContacts;
        this.access = access;
        this.members = members;
        this.currentUser = currentUser;
        this.calendarSettings = calendarSettings;
        this.header = header;
    }

    // --- Children and school years -------------------------------------------------------

    @GetMapping("/settings/years")
    String years(@RequestParam(required = false) Long year, HttpSession session, Model model) {
        User user = currentUser.require();
        prepare(model, year, session);
        Map<Long, FamilyMember> byId = members.findAll().stream()
                .collect(Collectors.toMap(FamilyMember::getId, Function.identity()));
        List<ProfileRow> rows = school.profiles().stream()
                .filter(p -> byId.containsKey(p.getMemberId()))
                .map(p -> {
                    List<SchoolYear> years = school.yearsOf(p.getId());
                    SchoolYear active = years.stream().filter(SchoolYear::isActive).findFirst().orElse(null);
                    return new ProfileRow(p, byId.get(p.getMemberId()), active, years);
                })
                .sorted((a, b) -> a.member().getName().compareToIgnoreCase(b.member().getName()))
                .toList();
        Set<Long> withProfile = rows.stream().map(r -> r.member().getId()).collect(Collectors.toSet());
        List<Contact> schools = schoolContacts.schools();
        model.addAttribute("rows", rows);
        model.addAttribute("freeMembers", byId.values().stream().filter(m -> !withProfile.contains(m.getId()))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).toList());
        model.addAttribute("states", new TreeMap<>(GermanHolidays.STATES));
        model.addAttribute("schools", schools);
        model.addAttribute("schoolNames", schools.stream().collect(Collectors.toMap(Contact::getId, Contact::getName)));
        model.addAttribute("canCreateContact", schoolContacts.mayCreate(user));
        LocalDate today = LocalDate.now(calendarSettings.zone());
        int startYear = today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
        model.addAttribute("suggestedLabel", startYear + "/" + String.format("%02d", (startYear + 1) % 100));
        model.addAttribute("suggestedStart", LocalDate.of(startYear, 8, 1));
        model.addAttribute("suggestedEnd", LocalDate.of(startYear + 1, 7, 31));
        return "school/settings-years";
    }

    @PostMapping("/profiles")
    @PreAuthorize("hasAuthority('SCHOOL_SETTINGS')")
    String createProfile(@RequestParam Long memberId, @RequestParam(required = false) String state,
                         RedirectAttributes redirect) {
        return run(redirect, "Schulprofil angelegt.", "years", null, () -> school.createProfile(memberId, state));
    }

    @PostMapping("/profiles/{id}/state")
    @PreAuthorize("hasAuthority('SCHOOL_SETTINGS')")
    String changeState(@PathVariable Long id, @RequestParam(required = false) String state,
                       RedirectAttributes redirect) {
        return run(redirect, "Bundesland gespeichert.", "years", null, () -> school.changeState(id, state));
    }

    @PostMapping("/profiles/{id}/years")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String startYear(@PathVariable Long id, YearForm form, RedirectAttributes redirect) {
        return run(redirect, "Schuljahr angelegt und aktiviert.", "years", null,
                () -> school.startYear(id, form.label(), schoolOf(form), form.className(), form.startsOn(),
                        form.endsOn()));
    }

    @PostMapping("/years/{id}")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String changeYear(@PathVariable Long id, YearForm form, RedirectAttributes redirect) {
        return run(redirect, "Schuljahr gespeichert.", "years", id,
                () -> school.changeYear(id, form.label(), schoolOf(form), form.className(), form.startsOn(),
                        form.endsOn()));
    }

    @PostMapping("/years/{id}/activate")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String activate(@PathVariable Long id, RedirectAttributes redirect) {
        return run(redirect, "Schuljahr aktiviert.", "years", id, () -> school.activate(id));
    }

    /** Form of a school year; a new school is created from {@code newSchool…} when no existing one is chosen. */
    public record YearForm(String label, Long schoolContactId, String newSchoolName, String newSchoolPhone,
                           String newSchoolEmail, String className,
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn) {
    }

    private Long schoolOf(YearForm form) {
        if (form.newSchoolName() == null || form.newSchoolName().isBlank()) {
            return form.schoolContactId();
        }
        return schoolContacts.createSchool(new SchoolContacts.SchoolInput(form.newSchoolName(),
                form.newSchoolPhone(), form.newSchoolEmail(), null, null, null), currentUser.require()).getId();
    }

    // --- School and period grid ----------------------------------------------------------

    @GetMapping("/settings/school")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String schoolPage(@RequestParam(required = false) Long year, HttpSession session, Model model) {
        Optional<SchoolYear> shown = prepare(model, year, session);
        if (shown.isEmpty()) {
            return "redirect:/school/settings/years";
        }
        Long contactId = shown.get().getSchoolContactId();
        List<SchoolPeriod> grid = periods.grid(contactId);
        List<GridRow> rows = new ArrayList<>(grid.isEmpty()
                ? PeriodService.suggestion().stream()
                .map(p -> new GridRow(p.kind(), p.startsAt(), p.endsAt(), p.label())).toList()
                : grid.stream().map(p -> new GridRow(p.getKind(), p.getStartsAt(), p.getEndsAt(), p.getLabel()))
                .toList());
        for (int i = 0; i < EMPTY_GRID_ROWS; i++) {
            rows.add(new GridRow(PeriodKind.LESSON, null, null, null));
        }
        model.addAttribute("school", schoolContacts.get(contactId).orElse(null));
        model.addAttribute("schools", schoolContacts.schools());
        model.addAttribute("gridRows", rows);
        model.addAttribute("gridSaved", !grid.isEmpty());
        model.addAttribute("periodKinds", PeriodKind.values());
        model.addAttribute("canCreateContact", schoolContacts.mayCreate(currentUser.require()));
        return "school/settings-school";
    }

    @PostMapping("/years/{id}/school")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String assignSchool(@PathVariable Long id, YearForm form, RedirectAttributes redirect) {
        SchoolYear year = header.year(id);
        return run(redirect, "Schule zugeordnet.", "school", id,
                () -> school.changeYear(id, year.getLabel(), schoolOf(form), year.getClassName(),
                        year.getStartsOn(), year.getEndsOn()));
    }

    @PostMapping("/years/{id}/grid")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String saveGrid(@PathVariable Long id, @RequestParam(name = "kind", required = false) List<PeriodKind> kinds,
                    @RequestParam(name = "startsAt", required = false) List<String> starts,
                    @RequestParam(name = "endsAt", required = false) List<String> ends,
                    @RequestParam(name = "label", required = false) List<String> labels,
                    RedirectAttributes redirect) {
        SchoolYear year = header.year(id);
        return run(redirect, "Stundenraster gespeichert.", "school", id, () -> periods.saveGrid(
                year.getSchoolContactId(), gridInput(kinds, starts, ends, labels), currentUser.require()));
    }

    private static List<PeriodService.PeriodInput> gridInput(List<PeriodKind> kinds, List<String> starts,
                                                             List<String> ends, List<String> labels) {
        List<PeriodService.PeriodInput> rows = new ArrayList<>();
        int size = starts == null ? 0 : starts.size();
        for (int i = 0; i < size; i++) {
            LocalTime start = time(starts.get(i));
            LocalTime end = ends != null && i < ends.size() ? time(ends.get(i)) : null;
            if (start == null && end == null) {
                continue;
            }
            PeriodKind kind = kinds != null && i < kinds.size() ? kinds.get(i) : PeriodKind.LESSON;
            String label = labels != null && i < labels.size() ? labels.get(i) : null;
            rows.add(new PeriodService.PeriodInput(kind, start, end, label));
        }
        return rows;
    }

    private static LocalTime time(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Uhrzeit „" + value + "“ ist ungültig.");
        }
    }

    // --- Subjects and teachers -----------------------------------------------------------

    @GetMapping("/settings/subjects")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String subjectsPage(@RequestParam(required = false) Long year, HttpSession session, Model model) {
        Optional<SchoolYear> shown = prepare(model, year, session);
        if (shown.isEmpty()) {
            return "redirect:/school/settings/years";
        }
        Long yearId = shown.get().getId();
        model.addAttribute("subjects", timetable.subjects(yearId));
        model.addAttribute("teachersBySubject", timetable.teachers(yearId));
        model.addAttribute("allTeachers", schoolContacts.teachers());
        model.addAttribute("canCreateContact", schoolContacts.mayCreate(currentUser.require()));
        return "school/settings-subjects";
    }

    @PostMapping("/years/{yearId}/subjects")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String addSubject(@PathVariable Long yearId, @RequestParam String name,
                      @RequestParam(required = false) String color, RedirectAttributes redirect) {
        return run(redirect, "Fach angelegt.", "subjects", yearId,
                () -> timetable.addSubject(yearId, name, color, currentUser.require()));
    }

    @PostMapping("/subjects/{id}")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String changeSubject(@PathVariable Long id, @RequestParam Long yearId, @RequestParam String name,
                         @RequestParam(required = false) String color, RedirectAttributes redirect) {
        return run(redirect, "Fach gespeichert.", "subjects", yearId,
                () -> timetable.changeSubject(id, name, color, currentUser.require()));
    }

    @PostMapping("/subjects/{id}/delete")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String deleteSubject(@PathVariable Long id, @RequestParam Long yearId, RedirectAttributes redirect) {
        return run(redirect, "Fach samt Stunden gelöscht.", "subjects", yearId,
                () -> timetable.deleteSubject(id, currentUser.require()));
    }

    @PostMapping("/subjects/{id}/teachers")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String setTeachers(@PathVariable Long id, @RequestParam Long yearId,
                       @RequestParam(name = "teacherId", required = false) Set<Long> teacherIds,
                       RedirectAttributes redirect) {
        return run(redirect, "Lehrkräfte gespeichert.", "subjects", yearId,
                () -> timetable.setTeachers(id, teacherIds == null ? Set.of() : teacherIds, currentUser.require()));
    }

    @PostMapping("/subjects/{id}/teachers/new")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String addTeacher(@PathVariable Long id, @RequestParam Long yearId, @RequestParam String name,
                      @RequestParam(required = false) String email, RedirectAttributes redirect) {
        return run(redirect, "Lehrkraft angelegt und zugeordnet.", "subjects", yearId,
                () -> timetable.addTeacher(id, name, email, currentUser.require()));
    }

    // --- Lessons and A/B weeks -----------------------------------------------------------

    @GetMapping("/settings/lessons")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String lessonsPage(@RequestParam(required = false) Long year, @RequestParam(required = false) DayOfWeek weekday,
                       @RequestParam(required = false) Integer period, HttpSession session, Model model) {
        Optional<SchoolYear> shown = prepare(model, year, session);
        if (shown.isEmpty()) {
            return "redirect:/school/settings/years";
        }
        Long yearId = shown.get().getId();
        List<SchoolPeriod> grid = periods.grid(shown.get().getSchoolContactId());
        model.addAttribute("subjects", timetable.subjects(yearId));
        model.addAttribute("subjectsById", timetable.subjects(yearId).stream()
                .collect(Collectors.toMap(s -> s.getId(), Function.identity())));
        model.addAttribute("lessons", timetable.lessons(yearId));
        model.addAttribute("gridLessons", grid.stream().filter(p -> !p.isBreak()).toList());
        model.addAttribute("weekKinds", WeekKind.values());
        model.addAttribute("weekdays", EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SUNDAY));
        model.addAttribute("presetWeekday", weekday);
        model.addAttribute("presetPeriod", period);
        return "school/settings-lessons";
    }

    /** Form of a lesson; {@code mode} {@code grid} uses the periods, otherwise the free times. */
    public record LessonForm(Long subjectId, DayOfWeek weekday, String mode, Integer periodFrom, Integer periodTo,
                             @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startsAt,
                             @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endsAt, String room,
                             WeekKind weekKind) {

        LessonInput toInput() {
            boolean grid = !"free".equals(mode) && periodFrom != null;
            return new LessonInput(subjectId, weekday, grid ? periodFrom : null, grid ? periodTo : null,
                    grid ? null : startsAt, grid ? null : endsAt, room, weekKind);
        }
    }

    @PostMapping("/years/{yearId}/lessons")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String addLesson(@PathVariable Long yearId, LessonForm form, RedirectAttributes redirect) {
        return run(redirect, "Stunde angelegt.", "lessons", yearId,
                () -> timetable.addLesson(yearId, form.toInput(), currentUser.require()));
    }

    @PostMapping("/lessons/{id}")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String changeLesson(@PathVariable Long id, @RequestParam Long yearId, LessonForm form,
                        RedirectAttributes redirect) {
        return run(redirect, "Stunde gespeichert.", "lessons", yearId,
                () -> timetable.changeLesson(id, form.toInput(), currentUser.require()));
    }

    @PostMapping("/lessons/{id}/delete")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String deleteLesson(@PathVariable Long id, @RequestParam Long yearId, RedirectAttributes redirect) {
        return run(redirect, "Stunde gelöscht.", "lessons", yearId,
                () -> timetable.deleteLesson(id, currentUser.require()));
    }

    @PostMapping("/years/{yearId}/week-plan")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String weekPlan(@PathVariable Long yearId,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekAStart,
                    RedirectAttributes redirect) {
        return run(redirect, weekAStart == null ? "A/B-Wochen abgeschaltet." : "A/B-Wochen gespeichert.", "lessons",
                yearId, () -> timetable.setWeekPlan(yearId, weekAStart, currentUser.require()));
    }

    // --- Helpers -------------------------------------------------------------------------

    /** Adds the year shown in the settings (requested or chosen child) and its child; both may be absent. */
    private Optional<SchoolYear> prepare(Model model, Long yearId, HttpSession session) {
        User user = currentUser.require();
        Optional<SchoolYear> year = yearId != null ? Optional.of(header.year(yearId))
                : header.chooseYear(null, session);
        FamilyMember member = year.flatMap(y -> school.profile(y.getProfileId()))
                .flatMap(p -> members.findAll().stream().filter(m -> Objects.equals(m.getId(), p.getMemberId()))
                        .findFirst())
                .orElse(null);
        model.addAttribute("year", year.orElse(null));
        model.addAttribute("member", member);
        model.addAttribute("canEdit", access.has(user, SchoolPermission.SCHOOL_EDIT));
        model.addAttribute("canSettings", access.has(user, SchoolPermission.SCHOOL_SETTINGS));
        return year;
    }

    private static String run(RedirectAttributes redirect, String success, String page, Long yearId,
                              Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", success);
        } catch (IllegalArgumentException | AccessDeniedException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        if (yearId != null) {
            redirect.addAttribute("year", yearId);
        }
        return "redirect:/school/settings/" + page;
    }
}
