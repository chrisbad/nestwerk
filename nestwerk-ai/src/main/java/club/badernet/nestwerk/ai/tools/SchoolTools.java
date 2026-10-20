package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.school.ChangeKind;
import club.badernet.nestwerk.school.ExamInput;
import club.badernet.nestwerk.school.ExamKind;
import club.badernet.nestwerk.school.ExamService;
import club.badernet.nestwerk.school.GradeKind;
import club.badernet.nestwerk.school.GradeService;
import club.badernet.nestwerk.school.HolidayService;
import club.badernet.nestwerk.school.HomeworkService;
import club.badernet.nestwerk.school.LetterService;
import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolContacts;
import club.badernet.nestwerk.school.SchoolDays;
import club.badernet.nestwerk.school.SchoolExam;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolToday;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.Timetable;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.user.User;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * School: timetable, homework, exams and appointments, holidays, grades and letters. The school permissions apply as
 * in the web UI; grades are only visible with {@code SCHOOL_GRADES} or, with {@code SCHOOL_GRADES_OWN}, for the own
 * child. The structure of the timetable is handled by {@link SchoolPlanTools}.
 */
@Component
public class SchoolTools {

    public record ChildItem(Long memberId, String name, Long yearId, String yearLabel, String school,
                            String className, String state) {
    }

    public record LessonItem(Long lessonId, String subject, Integer periodFrom, Integer periodTo, String start,
                             String end, String room, String weekKind, String change, String note) {
    }

    public record DayItem(LocalDate date, String weekday, String free, List<LessonItem> lessons) {
    }

    public record HomeworkItem(Long todoId, String title, String subject, LocalDate dueOn, boolean done,
                               boolean recurring) {
    }

    public record ExamItem(Long examId, String kind, String subject, String title, LocalDate day, int studyDays,
                           String start, String end, String location, List<Long> participantIds) {
    }

    public record HolidayItem(String name, LocalDate from, LocalDate to, String source) {
    }

    public record GradeItem(Long gradeId, String grade, String kind, LocalDate day, int weightPercent, String note) {
    }

    public record SubjectGradesItem(Long subjectId, String subject, String average, String reportGrade,
                                    List<GradeItem> grades) {
    }

    public record GradesItem(int term, String overallAverage, List<SubjectGradesItem> subjects) {
    }

    public record LetterItem(Long letterId, String title, LocalDate dueOn, boolean signed, String note,
                             int attachments) {
    }

    public record TodayItem(String name, String free, String schoolHours, List<String> cancelled,
                            List<String> homeworkDue, String nextAppointment) {
    }

    private final Tools tools;
    private final SchoolService school;
    private final SchoolAccess access;
    private final TimetableService timetable;
    private final HomeworkService homework;
    private final ExamService exams;
    private final HolidayService holidays;
    private final GradeService grades;
    private final LetterService letters;
    private final SchoolToday today;
    private final SchoolDays days;
    private final FamilyMemberRepository members;
    private final Clock clock;
    private final SchoolContacts contacts;

    public SchoolTools(Tools tools, SchoolService school, SchoolAccess access, TimetableService timetable,
                       HomeworkService homework, ExamService exams, HolidayService holidays, GradeService grades,
                       LetterService letters, SchoolToday today, SchoolDays days, FamilyMemberRepository members,
                       Clock clock, SchoolContacts contacts) {
        this.contacts = contacts;
        this.days = days;
        this.tools = tools;
        this.school = school;
        this.access = access;
        this.timetable = timetable;
        this.homework = homework;
        this.exams = exams;
        this.holidays = holidays;
        this.grades = grades;
        this.letters = letters;
        this.today = today;
        this.members = members;
        this.clock = clock;
    }

    // --- Reading ---------------------------------------------------------------------------

    @McpTool(name = "list_school_children", description = "Listet die Kinder mit Schulprofil und aktivem Schuljahr "
            + "(yearId wird für die anderen Schul-Tools gebraucht).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<ChildItem> listSchoolChildren(McpTransportContext context) {
        return tools.read(context, user -> {
            access.require(user, SchoolPermission.SCHOOL_VIEW);
            Map<Long, FamilyMember> byId = members.findAll().stream()
                    .collect(Collectors.toMap(FamilyMember::getId, m -> m));
            return school.profiles().stream().filter(p -> byId.containsKey(p.getMemberId())).map(p -> {
                SchoolYear year = school.activeYear(p.getId()).orElse(null);
                return new ChildItem(p.getMemberId(), byId.get(p.getMemberId()).getName(),
                        year == null ? null : year.getId(), year == null ? null : year.getLabel(),
                        year == null ? null : contacts.get(year.getSchoolContactId()).map(c -> c.getName())
                                .orElse(null),
                        year == null ? null : year.getClassName(),
                        p.getState());
            }).toList();
        });
    }

    @McpTool(name = "school_today", description = "Was heute (oder am angegebenen Tag) in der Schule los ist: "
            + "Schulzeit, ausfallende Stunden, fällige Hausaufgaben und der nächste Termin je Kind.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<TodayItem> schoolToday(McpTransportContext context,
                                       @McpToolParam(description = "Tag JJJJ-MM-TT (Standard heute)",
                                               required = false) String date) {
        return tools.read(context, user -> {
            access.require(user, SchoolPermission.SCHOOL_VIEW);
            LocalDate day = date == null || date.isBlank() ? LocalDate.now(clock) : Tools.date(date, "date");
            return today.forDate(day, user).stream().map(c -> new TodayItem(c.member().getName(), c.free(),
                    c.span(), c.lessons().stream().filter(Timetable.Entry::cancelled)
                    .map(e -> e.subject().getName()).toList(),
                    c.homework().stream().map(h -> h.title() + " (" + h.dueOn() + ")").toList(),
                    c.nextExam() == null ? null : c.nextExam().getKind().label() + " am " + c.nextExam().getDay()))
                    .toList();
        });
    }

    @McpTool(name = "get_timetable", description = "Stundenplan einer Woche mit Ausfall und Vertretung. Die Woche "
            + "enthält das angegebene Datum (Standard heute).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<DayItem> getTimetable(McpTransportContext context,
                                      @McpToolParam(description = "Schuljahr-ID (list_school_children)") Long yearId,
                                      @McpToolParam(description = "Ein Tag der Woche, JJJJ-MM-TT", required = false)
                                      String date) {
        return tools.read(context, user -> {
            access.require(user, SchoolPermission.SCHOOL_VIEW);
            SchoolYear year = year(yearId);
            LocalDate day = date == null || date.isBlank() ? LocalDate.now(clock) : Tools.date(date, "date");
            LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            Map<DayOfWeek, List<Timetable.Entry>> week = timetable.week(year.getId(), monday);
            String state = school.profile(year.getProfileId()).map(SchoolProfile::getState).orElse(null);
            Map<LocalDate, String> free = days.freeDays(state, monday, monday.plusDays(6));
            List<DayItem> result = new java.util.ArrayList<>();
            for (int i = 0; i < 7; i++) {
                LocalDate d = monday.plusDays(i);
                List<Timetable.Entry> entries = week.get(d.getDayOfWeek());
                if (entries.isEmpty() && i >= 5) {
                    continue;
                }
                result.add(new DayItem(d, d.getDayOfWeek().name(), free.get(d), entries.stream()
                        .map(e -> new LessonItem(e.lesson().getId(), e.subject().getName(),
                                e.lesson().getPeriodFrom(), e.lesson().getPeriodTo(),
                                e.lesson().getStartsAt().toString(), e.lesson().getEndsAt().toString(),
                                e.room(), e.lesson().getWeekKind().name(),
                                e.change() == null ? null : e.change().getKind().name(),
                                e.change() == null ? null : e.change().getNote())).toList()));
            }
            return result;
        });
    }

    @McpTool(name = "list_homework", description = "Hausaufgaben eines Schuljahrs, nach Fälligkeit sortiert. "
            + "done=true zeigt erledigte.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<HomeworkItem> listHomework(McpTransportContext context,
                                           @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                           @McpToolParam(description = "Nur dieses Fach (Fach-ID)", required = false)
                                           Long subjectId,
                                           @McpToolParam(description = "true = erledigte (Standard false)",
                                                   required = false) Boolean done) {
        return tools.read(context, user -> {
            access.require(user, SchoolPermission.SCHOOL_VIEW);
            return homework.list(Tools.required(yearId, "yearId"), subjectId, Boolean.TRUE.equals(done), user)
                    .stream().map(i -> item(i.todo(), i.subject() == null ? null : i.subject().getName())).toList();
        });
    }

    @McpTool(name = "list_school_exams", description = "Klassenarbeiten, Tests und Schultermine (Elternabend, "
            + "Elterngespräch, Ausflug …) eines Schuljahrs mit Uhrzeit, Ort und Teilnehmenden (Mitglieds-IDs).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<ExamItem> listSchoolExams(McpTransportContext context,
                                          @McpToolParam(description = "Schuljahr-ID") Long yearId) {
        return tools.read(context, user -> {
            access.require(user, SchoolPermission.SCHOOL_VIEW);
            Map<Long, String> names = timetable.subjects(Tools.required(yearId, "yearId")).stream()
                    .collect(Collectors.toMap(s -> s.getId(), s -> s.getName()));
            return exams.list(yearId).stream().map(e -> examItem(e, names)).toList();
        });
    }

    @McpTool(name = "list_school_holidays", description = "Schulferien des Bundeslands eines Kindes für die "
            + "nächsten zwölf Monate.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<HolidayItem> listSchoolHolidays(McpTransportContext context,
                                                @McpToolParam(description = "Schuljahr-ID") Long yearId) {
        return tools.read(context, user -> {
            access.require(user, SchoolPermission.SCHOOL_VIEW);
            SchoolYear year = year(yearId);
            String state = school.profile(year.getProfileId()).map(SchoolProfile::getState).orElse(null);
            if (state == null) {
                throw new Tools.ToolException("Für dieses Kind ist kein Bundesland eingestellt.");
            }
            LocalDate now = LocalDate.now(clock);
            return holidays.list(state, now.minusMonths(1), now.plusYears(1)).stream()
                    .map(h -> new HolidayItem(h.getName(), h.getStartsOn(), h.getEndsOn(), h.getSource())).toList();
        });
    }

    @McpTool(name = "list_school_grades", description = "Noten eines Halbjahrs mit Durchschnitt und Zeugnisnote je "
            + "Fach. Nur mit Notenrecht (alle Kinder) oder für das eigene Kind.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public GradesItem listSchoolGrades(McpTransportContext context,
                                       @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                       @McpToolParam(description = "Halbjahr 1 oder 2") Integer term) {
        return tools.read(context, user -> {
            int t = Tools.required(term, "term");
            GradeService.Overview overview = grades.overview(Tools.required(yearId, "yearId"), t, user);
            return new GradesItem(t, overview.overallLabel(), overview.subjects().stream()
                    .map(s -> new SubjectGradesItem(s.subject().getId(), s.subject().getName(), s.averageLabel(),
                            s.report() == null ? null : s.report().getLabel(), s.grades().stream()
                            .map(g -> new GradeItem(g.getId(), g.getLabel(), g.getKind().name(), g.getDay(),
                                    g.getWeight(), g.getNote())).toList())).toList());
        });
    }

    @McpTool(name = "list_school_letters", description = "Elternbriefe eines Schuljahrs mit Frist und Unterschrift.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<LetterItem> listSchoolLetters(McpTransportContext context,
                                              @McpToolParam(description = "Schuljahr-ID") Long yearId) {
        return tools.read(context, user -> {
            access.require(user, SchoolPermission.SCHOOL_VIEW);
            return letters.list(Tools.required(yearId, "yearId")).stream().map(i -> new LetterItem(
                    i.letter().getId(), i.letter().getTitle(), i.letter().getDueOn(), i.letter().isSigned(),
                    i.letter().getNote(), i.attachments().size())).toList();
        });
    }

    // --- Writing ---------------------------------------------------------------------------

    @McpTool(name = "add_homework", description = "Legt eine Hausaufgabe als ToDo für das Kind an. repeat: NONE, "
            + "SCHOOLDAYS (jeden Schultag) oder WEEKLY.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public HomeworkItem addHomework(McpTransportContext context,
                                    @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                    @McpToolParam(description = "Aufgabe") String title,
                                    @McpToolParam(description = "Fällig am, JJJJ-MM-TT") String dueOn,
                                    @McpToolParam(description = "Fach-ID", required = false) Long subjectId,
                                    @McpToolParam(description = "Notiz", required = false) String description,
                                    @McpToolParam(description = "NONE, SCHOOLDAYS oder WEEKLY", required = false)
                                    String repeat) {
        return tools.write(context, user -> {
            Todo todo = homework.create(Tools.required(yearId, "yearId"), subjectId, Tools.required(title, "title"),
                    description, Tools.required(Tools.date(dueOn, "dueOn"), "dueOn"), rule(repeat), user);
            return item(todo, null);
        });
    }

    @McpTool(name = "set_homework_done", description = "Erledigt eine Hausaufgabe oder öffnet sie wieder.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String setHomeworkDone(McpTransportContext context,
                                  @McpToolParam(description = "ToDo-ID der Hausaufgabe") Long todoId,
                                  @McpToolParam(description = "true = erledigt, false = wieder offen") Boolean done) {
        return tools.write(context, user -> {
            boolean value = done == null || done;
            homework.setDone(Tools.required(todoId, "todoId"), value, user);
            return value ? "Erledigt." : "Wieder offen.";
        });
    }

    @McpTool(name = "mark_lesson", description = "Lässt eine Stunde an einem Tag ausfallen (CANCELLED), markiert "
            + "eine Vertretung (SUBSTITUTE) oder einen Raumwechsel (ROOM, neuer Raum in note). Die Stunden-ID steht in "
            + "get_timetable; schneller per Fach oder Stunde geht report_lesson_change.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String markLesson(McpTransportContext context,
                             @McpToolParam(description = "Stunden-ID") Long lessonId,
                             @McpToolParam(description = "Tag JJJJ-MM-TT") String date,
                             @McpToolParam(description = "CANCELLED, SUBSTITUTE oder ROOM") String kind,
                             @McpToolParam(description = "Hinweis", required = false) String note) {
        return tools.write(context, user -> {
            timetable.markLesson(Tools.required(lessonId, "lessonId"),
                    Tools.required(Tools.date(date, "date"), "date"),
                    Tools.required(Tools.enumValue(ChangeKind.class, kind, "kind"), "kind"), note, user);
            return "Gespeichert.";
        });
    }

    @McpTool(name = "add_school_exam", description = "Trägt eine Klassenarbeit, einen Test oder einen Schultermin "
            + "ein (kind EXAM, TEST, PARENTS_EVENING, PARENT_TALK, TRIP oder OTHER); steht auch im Kalender. Ohne "
            + "Uhrzeit ganztägig. Ohne participantIds: das Kind bzw. bei Elternabend und Elterngespräch die Eltern.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ExamItem addSchoolExam(McpTransportContext context,
                                  @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                  @McpToolParam(description = "EXAM, TEST, PARENTS_EVENING, PARENT_TALK, TRIP oder "
                                          + "OTHER") String kind,
                                  @McpToolParam(description = "Tag JJJJ-MM-TT") String day,
                                  @McpToolParam(description = "Fach-ID", required = false) Long subjectId,
                                  @McpToolParam(description = "Thema", required = false) String title,
                                  @McpToolParam(description = "Lernerinnerung so viele Tage vorher (Standard 0)",
                                          required = false) Integer studyDays,
                                  @McpToolParam(description = "Beginn HH:MM", required = false) String startTime,
                                  @McpToolParam(description = "Ende HH:MM", required = false) String endTime,
                                  @McpToolParam(description = "Ort", required = false) String location,
                                  @McpToolParam(description = "Mitglieds-IDs der Teilnehmenden (list_members)",
                                          required = false) List<Long> participantIds) {
        return tools.write(context, user -> {
            SchoolExam exam = exams.create(Tools.required(yearId, "yearId"), examInput(subjectId, kind, title, day,
                    studyDays, startTime, endTime, location, participantIds), user);
            return examItem(exam, subjectNames(yearId));
        });
    }

    @McpTool(name = "update_school_exam", description = "Ändert eine Klassenarbeit oder einen Schultermin (alle "
            + "Angaben wie bei add_school_exam; examId aus list_school_exams). Der Kalendertermin wird angepasst.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String updateSchoolExam(McpTransportContext context,
                                   @McpToolParam(description = "Termin-ID") Long examId,
                                   @McpToolParam(description = "EXAM, TEST, PARENTS_EVENING, PARENT_TALK, TRIP oder "
                                           + "OTHER") String kind,
                                   @McpToolParam(description = "Tag JJJJ-MM-TT") String day,
                                   @McpToolParam(description = "Fach-ID", required = false) Long subjectId,
                                   @McpToolParam(description = "Thema", required = false) String title,
                                   @McpToolParam(description = "Lernerinnerung Tage vorher", required = false)
                                   Integer studyDays,
                                   @McpToolParam(description = "Beginn HH:MM", required = false) String startTime,
                                   @McpToolParam(description = "Ende HH:MM", required = false) String endTime,
                                   @McpToolParam(description = "Ort", required = false) String location,
                                   @McpToolParam(description = "Mitglieds-IDs der Teilnehmenden", required = false)
                                   List<Long> participantIds) {
        return tools.write(context, user -> {
            exams.update(Tools.required(examId, "examId"), examInput(subjectId, kind, title, day, studyDays,
                    startTime, endTime, location, participantIds), user);
            return "Gespeichert.";
        });
    }

    @McpTool(name = "delete_school_exam", description = "Löscht eine Klassenarbeit oder einen Schultermin samt "
            + "Kalendertermin.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false))
    public String deleteSchoolExam(McpTransportContext context,
                                   @McpToolParam(description = "Termin-ID") Long examId) {
        return tools.write(context, user -> {
            exams.delete(Tools.required(examId, "examId"), user);
            return "Gelöscht.";
        });
    }

    @McpTool(name = "add_school_grade", description = "Trägt eine Note ein (nur mit Notenrecht). grade z. B. 2+, 3 "
            + "oder 2,5; kind WRITTEN, ORAL oder OTHER; weightPercent 100 = einfach, 200 = doppelt.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public GradeItem addSchoolGrade(McpTransportContext context,
                                    @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                    @McpToolParam(description = "Fach-ID") Long subjectId,
                                    @McpToolParam(description = "Halbjahr 1 oder 2") Integer term,
                                    @McpToolParam(description = "Note, z. B. 2+") String grade,
                                    @McpToolParam(description = "WRITTEN, ORAL oder OTHER (Standard WRITTEN)",
                                            required = false) String kind,
                                    @McpToolParam(description = "Datum JJJJ-MM-TT (Standard heute)", required = false)
                                    String day,
                                    @McpToolParam(description = "Gewicht in Prozent (Standard 100)", required = false)
                                    Integer weightPercent,
                                    @McpToolParam(description = "Notiz", required = false) String note) {
        return tools.write(context, user -> {
            GradeKind gradeKind = Tools.enumValue(GradeKind.class, kind, "kind");
            var saved = grades.add(Tools.required(yearId, "yearId"), subjectId, Tools.required(term, "term"),
                    Tools.required(grade, "grade"), weightPercent == null ? 100 : weightPercent,
                    gradeKind == null ? GradeKind.WRITTEN : gradeKind,
                    day == null || day.isBlank() ? LocalDate.now(clock) : Tools.date(day, "day"), note, user);
            return new GradeItem(saved.getId(), saved.getLabel(), saved.getKind().name(), saved.getDay(),
                    saved.getWeight(), saved.getNote());
        });
    }

    @McpTool(name = "set_school_report_grade", description = "Setzt die Zeugnisnote eines Fachs im Halbjahr (leer "
            + "entfernt sie). Nur mit Notenrecht.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String setSchoolReportGrade(McpTransportContext context,
                                       @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                       @McpToolParam(description = "Fach-ID") Long subjectId,
                                       @McpToolParam(description = "Halbjahr 1 oder 2") Integer term,
                                       @McpToolParam(description = "Zeugnisnote, z. B. 2; leer entfernt", required = false)
                                       String grade) {
        return tools.write(context, user -> {
            grades.setReportGrade(Tools.required(yearId, "yearId"), subjectId, Tools.required(term, "term"), grade,
                    user);
            return "Gespeichert.";
        });
    }

    @McpTool(name = "add_school_letter", description = "Legt einen Elternbrief mit Frist an (Dateien später über "
            + "add_attachment mit ownerType LETTER).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public LetterItem addSchoolLetter(McpTransportContext context,
                                      @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                      @McpToolParam(description = "Titel") String title,
                                      @McpToolParam(description = "Frist JJJJ-MM-TT", required = false) String dueOn,
                                      @McpToolParam(description = "Notiz", required = false) String note) {
        return tools.write(context, user -> {
            var letter = letters.create(Tools.required(yearId, "yearId"), Tools.required(title, "title"),
                    Tools.date(dueOn, "dueOn"), note, user);
            return new LetterItem(letter.getId(), letter.getTitle(), letter.getDueOn(), false, letter.getNote(), 0);
        });
    }

    @McpTool(name = "set_school_letter_signed", description = "Markiert einen Elternbrief als unterschrieben oder "
            + "nimmt die Unterschrift zurück.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String setSchoolLetterSigned(McpTransportContext context,
                                        @McpToolParam(description = "Brief-ID") Long letterId,
                                        @McpToolParam(description = "true = unterschrieben") Boolean signed) {
        return tools.write(context, user -> {
            boolean value = signed == null || signed;
            letters.setSigned(Tools.required(letterId, "letterId"), value, user);
            return value ? "Als unterschrieben markiert." : "Unterschrift zurückgenommen.";
        });
    }

    // --- Helpers ---------------------------------------------------------------------------

    private SchoolYear year(Long id) {
        return school.year(Tools.required(id, "yearId"))
                .orElseThrow(() -> new Tools.ToolException("Schuljahr nicht gefunden."));
    }

    private static HomeworkItem item(Todo todo, String subject) {
        return new HomeworkItem(todo.getId(), todo.getTitle(), subject, todo.getDueOn(), todo.isClosed(),
                todo.getRrule() != null);
    }

    private static ExamItem examItem(SchoolExam e, Map<Long, String> subjects) {
        return new ExamItem(e.getId(), e.getKind().name(), e.getSubjectId() == null ? null
                : subjects.get(e.getSubjectId()), e.getTitle(), e.getDay(), e.getStudyDays(),
                e.getStartsAt() == null ? null : e.getStartsAt().toString(),
                e.getEndsAt() == null ? null : e.getEndsAt().toString(), e.getLocation(),
                e.getMemberIds().stream().sorted().toList());
    }

    private static ExamInput examInput(Long subjectId, String kind, String title, String day, Integer studyDays,
                                       String startTime, String endTime, String location, List<Long> participantIds) {
        return new ExamInput(subjectId, Tools.required(Tools.enumValue(ExamKind.class, kind, "kind"), "kind"), title,
                Tools.required(Tools.date(day, "day"), "day"), studyDays == null ? 0 : studyDays,
                Tools.time(startTime, "startTime"), Tools.time(endTime, "endTime"), location,
                participantIds == null ? null : new HashSet<>(participantIds));
    }

    private Map<Long, String> subjectNames(Long yearId) {
        return timetable.subjects(yearId).stream().collect(Collectors.toMap(s -> s.getId(), s -> s.getName()));
    }

    private static String rule(String repeat) {
        if (repeat == null || repeat.isBlank() || "NONE".equalsIgnoreCase(repeat)) {
            return null;
        }
        return switch (repeat.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SCHOOLDAYS" -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR";
            case "WEEKLY" -> "FREQ=WEEKLY";
            default -> throw new Tools.ToolException("repeat: erlaubt sind NONE, SCHOOLDAYS und WEEKLY.");
        };
    }
}
