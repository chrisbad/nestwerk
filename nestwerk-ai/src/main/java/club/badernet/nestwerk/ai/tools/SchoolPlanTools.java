package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.school.ChangeKind;
import club.badernet.nestwerk.school.Lesson;
import club.badernet.nestwerk.school.LessonInput;
import club.badernet.nestwerk.school.LessonSelection;
import club.badernet.nestwerk.school.PeriodKind;
import club.badernet.nestwerk.school.PeriodService;
import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolContacts;
import club.badernet.nestwerk.school.SchoolPeriod;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolSubject;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.Timetable;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.school.WeekKind;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structure of the timetable via MCP: school and office, period grid with breaks, subjects with teachers, lessons and
 * quick cancellations by subject or period. Same permissions as the web settings.
 */
@Component
public class SchoolPlanTools {

    private static final Pattern GRID_ROW =
            Pattern.compile("(?i)^\\s*(P(?:ause)?\\s+)?(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})\\s*(.*)$");

    public record ContactItem(Long contactId, String name, String phone, String email) {
    }

    public record SubjectItem(Long subjectId, String name, String color, List<ContactItem> teachers) {
    }

    public record TeachersItem(ContactItem school, List<SubjectItem> subjects) {
    }

    public record PeriodItem(String kind, Integer number, String label, String start, String end) {
    }

    public record LessonItem(Long lessonId, String subject, String weekday, Integer periodFrom, Integer periodTo,
                             String start, String end, String room, String weekKind) {
    }

    public record ChangeItem(int lessons, List<String> subjects) {
    }

    private final Tools tools;
    private final SchoolService school;
    private final SchoolAccess access;
    private final TimetableService timetable;
    private final PeriodService periods;
    private final SchoolContacts contacts;

    public SchoolPlanTools(Tools tools, SchoolService school, SchoolAccess access, TimetableService timetable,
                           PeriodService periods, SchoolContacts contacts) {
        this.tools = tools;
        this.school = school;
        this.access = access;
        this.timetable = timetable;
        this.periods = periods;
        this.contacts = contacts;
    }

    // --- Reading ---------------------------------------------------------------------------

    @McpTool(name = "list_school_teachers", description = "Schule mit Sekretariat (Telefon, E-Mail) und die Fächer "
            + "eines Schuljahrs mit ihren Lehrkräften und deren E-Mail-Adressen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public TeachersItem listSchoolTeachers(McpTransportContext context,
                                           @McpToolParam(description = "Schuljahr-ID (list_school_children)")
                                           Long yearId) {
        return tools.read(context, user -> {
            access.require(user, SchoolPermission.SCHOOL_VIEW);
            SchoolYear year = year(yearId);
            Map<Long, List<Contact>> teachers = timetable.teachers(year.getId());
            return new TeachersItem(contacts.get(year.getSchoolContactId()).map(SchoolPlanTools::contact).orElse(null),
                    timetable.subjects(year.getId()).stream().map(s -> subject(s, teachers)).toList());
        });
    }

    @McpTool(name = "get_school_period_grid", description = "Stundenraster der Schule eines Schuljahrs: Stunden "
            + "(mit Nummer) und Pausen mit Uhrzeiten.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<PeriodItem> getSchoolPeriodGrid(McpTransportContext context,
                                                @McpToolParam(description = "Schuljahr-ID") Long yearId) {
        return tools.read(context, user -> {
            access.require(user, SchoolPermission.SCHOOL_VIEW);
            return periods.grid(year(yearId).getSchoolContactId()).stream().map(SchoolPlanTools::period).toList();
        });
    }

    @McpTool(name = "list_school_lessons", description = "Alle Stunden des Stundenplans eines Schuljahrs (Wochenraster "
            + "ohne Ausfall; für einzelne Wochen get_timetable).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<LessonItem> listSchoolLessons(McpTransportContext context,
                                              @McpToolParam(description = "Schuljahr-ID") Long yearId) {
        return tools.read(context, user -> {
            access.require(user, SchoolPermission.SCHOOL_VIEW);
            Long id = year(yearId).getId();
            Map<Long, String> names = subjectNames(id);
            return timetable.lessons(id).stream().map(l -> lesson(l, names)).toList();
        });
    }

    // --- School, grid, subjects and teachers --------------------------------------------------

    @McpTool(name = "set_school_of_year", description = "Ordnet dem Schuljahr eine Schule zu: vorhandenen Kontakt "
            + "(schoolContactId aus list_school_teachers bzw. den Kontakten) oder neue Schule mit Sekretariat anlegen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ContactItem setSchoolOfYear(McpTransportContext context,
                                       @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                       @McpToolParam(description = "Kontakt-ID einer vorhandenen Schule",
                                               required = false) Long schoolContactId,
                                       @McpToolParam(description = "Name einer neuen Schule", required = false)
                                       String newSchoolName,
                                       @McpToolParam(description = "Telefon des Sekretariats (neue Schule)",
                                               required = false) String phone,
                                       @McpToolParam(description = "E-Mail des Sekretariats (neue Schule)",
                                               required = false) String email) {
        return tools.write(context, user -> {
            access.require(user, SchoolPermission.SCHOOL_EDIT);
            SchoolYear year = year(yearId);
            Long contactId = schoolContactId;
            if (newSchoolName != null && !newSchoolName.isBlank()) {
                contactId = contacts.createSchool(new SchoolContacts.SchoolInput(newSchoolName, phone, email, null,
                        null, null), user).getId();
            }
            school.changeYear(year.getId(), year.getLabel(), Tools.required(contactId, "schoolContactId"),
                    year.getClassName(), year.getStartsOn(), year.getEndsOn());
            return contacts.get(contactId).map(SchoolPlanTools::contact).orElseThrow();
        });
    }

    @McpTool(name = "set_school_period_grid", description = "Ersetzt das Stundenraster der Schule des Schuljahrs. "
            + "Zeilen durch Zeilenumbruch oder Semikolon getrennt: Stunde als \"08:00-08:45\", Pause als "
            + "\"P 09:30-09:50\"; dahinter optional eine Bezeichnung. Stunden werden durchnummeriert; Stunden im "
            + "Stundenplan übernehmen die neuen Zeiten.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public List<PeriodItem> setSchoolPeriodGrid(McpTransportContext context,
                                                @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                                @McpToolParam(description = "Raster, z. B. \"08:00-08:45; "
                                                        + "08:50-09:35; P 09:35-09:55 Hofpause\"") String grid) {
        return tools.write(context, user -> {
            SchoolYear year = year(yearId);
            if (year.getSchoolContactId() == null) {
                throw new Tools.ToolException("Dem Schuljahr ist keine Schule zugeordnet (set_school_of_year).");
            }
            return periods.saveGrid(year.getSchoolContactId(), parseGrid(Tools.required(grid, "grid")), user)
                    .stream().map(SchoolPlanTools::period).toList();
        });
    }

    @McpTool(name = "add_school_subject", description = "Legt ein Fach im Schuljahr an (Farbe optional als #rrggbb).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public SubjectItem addSchoolSubject(McpTransportContext context,
                                        @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                        @McpToolParam(description = "Name des Fachs") String name,
                                        @McpToolParam(description = "Farbe #rrggbb", required = false) String color) {
        return tools.write(context, user -> subject(timetable.addSubject(year(yearId).getId(),
                Tools.required(name, "name"), color, user), Map.of()));
    }

    @McpTool(name = "update_school_subject", description = "Benennt ein Fach um oder ändert seine Farbe.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String updateSchoolSubject(McpTransportContext context,
                                      @McpToolParam(description = "Fach-ID") Long subjectId,
                                      @McpToolParam(description = "Name des Fachs") String name,
                                      @McpToolParam(description = "Farbe #rrggbb", required = false) String color) {
        return tools.write(context, user -> {
            timetable.changeSubject(Tools.required(subjectId, "subjectId"), Tools.required(name, "name"), color,
                    user);
            return "Gespeichert.";
        });
    }

    @McpTool(name = "delete_school_subject", description = "Löscht ein Fach samt seinen Stunden im Stundenplan.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false))
    public String deleteSchoolSubject(McpTransportContext context,
                                      @McpToolParam(description = "Fach-ID") Long subjectId) {
        return tools.write(context, user -> {
            timetable.deleteSubject(Tools.required(subjectId, "subjectId"), user);
            return "Fach samt Stunden gelöscht.";
        });
    }

    @McpTool(name = "set_subject_teachers", description = "Legt die Lehrkräfte eines Fachs fest (Kontakt-IDs, z. B. "
            + "aus list_school_teachers oder search_contacts); leere Liste entfernt alle.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String setSubjectTeachers(McpTransportContext context,
                                     @McpToolParam(description = "Fach-ID") Long subjectId,
                                     @McpToolParam(description = "Kontakt-IDs der Lehrkräfte") List<Long> contactIds) {
        return tools.write(context, user -> {
            timetable.setTeachers(Tools.required(subjectId, "subjectId"),
                    new HashSet<>(contactIds == null ? List.of() : contactIds), user);
            return "Lehrkräfte gespeichert.";
        });
    }

    @McpTool(name = "add_subject_teacher", description = "Legt eine Lehrkraft als Kontakt (Kategorie Lehrkraft) mit "
            + "E-Mail an und ordnet sie dem Fach zu.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ContactItem addSubjectTeacher(McpTransportContext context,
                                         @McpToolParam(description = "Fach-ID") Long subjectId,
                                         @McpToolParam(description = "Name, z. B. Frau Weber") String name,
                                         @McpToolParam(description = "E-Mail-Adresse", required = false)
                                         String email) {
        return tools.write(context, user -> contact(timetable.addTeacher(Tools.required(subjectId, "subjectId"),
                Tools.required(name, "name"), email, user)));
    }

    // --- Lessons -----------------------------------------------------------------------------

    @McpTool(name = "add_school_lesson", description = "Trägt eine Stunde in den Stundenplan ein: entweder im "
            + "Stundenraster (periodFrom/periodTo, z. B. 3 bis 4 für eine Doppelstunde) oder mit freier Uhrzeit "
            + "(start/end HH:MM, z. B. für eine AG). weekKind EVERY, A oder B.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public LessonItem addSchoolLesson(McpTransportContext context,
                                      @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                      @McpToolParam(description = "Fach-ID") Long subjectId,
                                      @McpToolParam(description = "MONDAY … SUNDAY") String weekday,
                                      @McpToolParam(description = "Erste Stunde im Raster", required = false)
                                      Integer periodFrom,
                                      @McpToolParam(description = "Letzte Stunde im Raster (Standard = erste)",
                                              required = false) Integer periodTo,
                                      @McpToolParam(description = "Beginn HH:MM (ohne Raster)", required = false)
                                      String start,
                                      @McpToolParam(description = "Ende HH:MM (ohne Raster)", required = false)
                                      String end,
                                      @McpToolParam(description = "Raum", required = false) String room,
                                      @McpToolParam(description = "EVERY, A oder B (Standard EVERY)", required = false)
                                      String weekKind) {
        return tools.write(context, user -> {
            Long id = year(yearId).getId();
            Lesson saved = timetable.addLesson(id, lessonInput(subjectId, weekday, periodFrom, periodTo, start, end,
                    room, weekKind), user);
            return lesson(saved, subjectNames(id));
        });
    }

    @McpTool(name = "update_school_lesson", description = "Ändert eine Stunde (alle Angaben wie bei "
            + "add_school_lesson; lessonId aus list_school_lessons).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String updateSchoolLesson(McpTransportContext context,
                                     @McpToolParam(description = "Stunden-ID") Long lessonId,
                                     @McpToolParam(description = "Fach-ID") Long subjectId,
                                     @McpToolParam(description = "MONDAY … SUNDAY") String weekday,
                                     @McpToolParam(description = "Erste Stunde im Raster", required = false)
                                     Integer periodFrom,
                                     @McpToolParam(description = "Letzte Stunde im Raster", required = false)
                                     Integer periodTo,
                                     @McpToolParam(description = "Beginn HH:MM (ohne Raster)", required = false)
                                     String start,
                                     @McpToolParam(description = "Ende HH:MM (ohne Raster)", required = false)
                                     String end,
                                     @McpToolParam(description = "Raum", required = false) String room,
                                     @McpToolParam(description = "EVERY, A oder B", required = false)
                                     String weekKind) {
        return tools.write(context, user -> {
            timetable.changeLesson(Tools.required(lessonId, "lessonId"), lessonInput(subjectId, weekday, periodFrom,
                    periodTo, start, end, room, weekKind), user);
            return "Gespeichert.";
        });
    }

    @McpTool(name = "delete_school_lesson", description = "Löscht eine Stunde aus dem Stundenplan.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false))
    public String deleteSchoolLesson(McpTransportContext context,
                                     @McpToolParam(description = "Stunden-ID") Long lessonId) {
        return tools.write(context, user -> {
            timetable.deleteLesson(Tools.required(lessonId, "lessonId"), user);
            return "Stunde gelöscht.";
        });
    }

    // --- Cancellations -------------------------------------------------------------------------

    @McpTool(name = "report_lesson_change", description = "Trägt schnell Ausfall, Vertretung oder Raumwechsel ein, "
            + "z. B. \"Mathe morgen fällt aus\" (subject=Mathe) oder \"ab der 5. Stunde frei\" (periodFrom=5, "
            + "periodTo=10). Ohne Fach und Stunden gilt es für den ganzen Tag. kind CANCELLED, SUBSTITUTE oder "
            + "ROOM (bei ROOM steht der neue Raum in note).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ChangeItem reportLessonChange(McpTransportContext context,
                                         @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                         @McpToolParam(description = "Tag JJJJ-MM-TT") String date,
                                         @McpToolParam(description = "CANCELLED, SUBSTITUTE oder ROOM") String kind,
                                         @McpToolParam(description = "Fachname, z. B. Mathe", required = false)
                                         String subject,
                                         @McpToolParam(description = "Erste betroffene Stunde", required = false)
                                         Integer periodFrom,
                                         @McpToolParam(description = "Letzte betroffene Stunde", required = false)
                                         Integer periodTo,
                                         @McpToolParam(description = "Hinweis bzw. neuer Raum", required = false)
                                         String note) {
        return tools.write(context, user -> {
            List<Timetable.Entry> marked = timetable.markDay(year(yearId).getId(),
                    Tools.required(Tools.date(date, "date"), "date"),
                    new LessonSelection(subject, periodFrom, periodTo),
                    Tools.required(Tools.enumValue(ChangeKind.class, kind, "kind"), "kind"), note, user);
            return new ChangeItem(marked.size(), marked.stream().map(e -> e.subject().getName()).toList());
        });
    }

    @McpTool(name = "clear_lesson_change", description = "Setzt Stunden eines Tages wieder auf normal (hebt Ausfall, "
            + "Vertretung oder Raumwechsel auf). Auswahl wie bei report_lesson_change.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String clearLessonChange(McpTransportContext context,
                                    @McpToolParam(description = "Schuljahr-ID") Long yearId,
                                    @McpToolParam(description = "Tag JJJJ-MM-TT") String date,
                                    @McpToolParam(description = "Fachname", required = false) String subject,
                                    @McpToolParam(description = "Erste Stunde", required = false) Integer periodFrom,
                                    @McpToolParam(description = "Letzte Stunde", required = false) Integer periodTo) {
        return tools.write(context, user -> {
            int cleared = timetable.clearDay(year(yearId).getId(), Tools.required(Tools.date(date, "date"), "date"),
                    new LessonSelection(subject, periodFrom, periodTo), user);
            return cleared + " Stunde(n) wieder normal.";
        });
    }

    // --- Helpers -------------------------------------------------------------------------------

    /** Parses grid rows like "08:00-08:45", "P 09:30-09:50 Hofpause". */
    static List<PeriodService.PeriodInput> parseGrid(String grid) {
        List<PeriodService.PeriodInput> rows = new ArrayList<>();
        for (String line : grid.split("[;\\n]")) {
            if (line.isBlank()) {
                continue;
            }
            Matcher m = GRID_ROW.matcher(line);
            if (!m.matches()) {
                throw new Tools.ToolException("Rasterzeile „" + line.trim() + "“ nicht verstanden; erwartet "
                        + "\"08:00-08:45\" oder \"P 09:30-09:50\".");
            }
            rows.add(new PeriodService.PeriodInput(m.group(1) == null ? PeriodKind.LESSON : PeriodKind.BREAK,
                    LocalTime.parse(pad(m.group(2))), LocalTime.parse(pad(m.group(3))),
                    m.group(4).isBlank() ? null : m.group(4).trim()));
        }
        return rows;
    }

    private static String pad(String time) {
        return time.length() == 4 ? "0" + time : time;
    }

    private static LessonInput lessonInput(Long subjectId, String weekday, Integer periodFrom, Integer periodTo,
                                           String start, String end, String room, String weekKind) {
        return new LessonInput(Tools.required(subjectId, "subjectId"),
                Tools.required(Tools.enumValue(DayOfWeek.class, weekday, "weekday"), "weekday"), periodFrom,
                periodTo, Tools.time(start, "start"), Tools.time(end, "end"), room,
                Tools.enumValue(WeekKind.class, weekKind, "weekKind"));
    }

    private SchoolYear year(Long id) {
        return school.year(Tools.required(id, "yearId"))
                .orElseThrow(() -> new Tools.ToolException("Schuljahr nicht gefunden."));
    }

    private Map<Long, String> subjectNames(Long yearId) {
        return timetable.subjects(yearId).stream()
                .collect(java.util.stream.Collectors.toMap(SchoolSubject::getId, SchoolSubject::getName));
    }

    private static ContactItem contact(Contact c) {
        return new ContactItem(c.getId(), c.getName(), c.getPhone(), c.getEmail());
    }

    private static SubjectItem subject(SchoolSubject s, Map<Long, List<Contact>> teachers) {
        return new SubjectItem(s.getId(), s.getName(), s.getColor(),
                teachers.getOrDefault(s.getId(), List.of()).stream().map(SchoolPlanTools::contact).toList());
    }

    private static PeriodItem period(SchoolPeriod p) {
        return new PeriodItem(p.getKind().name(), p.getNumber(), p.getLabel(), p.getStartsAt().toString(),
                p.getEndsAt().toString());
    }

    private static LessonItem lesson(Lesson l, Map<Long, String> subjects) {
        return new LessonItem(l.getId(), subjects.get(l.getSubjectId()), l.getWeekday().name(), l.getPeriodFrom(),
                l.getPeriodTo(), l.getStartsAt().toString(), l.getEndsAt().toString(), l.getRoom(),
                l.getWeekKind().name());
    }
}
