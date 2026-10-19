package club.badernet.nestwerk.school;

import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Fächer, Stundenplan, Ausfall und Vertretung eines Schuljahrs; Änderungen gleichen die Schul-Blocker ab. */
@Service
public class TimetableService {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final String[] PALETTE = {"#4d74ff", "#e07a3f", "#3f9d6b", "#b04fa0", "#c9a227", "#2f9cb0",
            "#d1495b", "#7a6ff0"};

    private final SchoolYearRepository years;
    private final SchoolSubjectRepository subjects;
    private final LessonRepository lessons;
    private final LessonChangeRepository changes;
    private final SchoolAccess access;
    private final SchoolBlockerSync sync;
    private final CancellationNotifier notifier;
    private final SchoolProfileRepository profiles;
    private final FamilyMemberRepository members;

    public TimetableService(SchoolYearRepository years, SchoolSubjectRepository subjects, LessonRepository lessons,
                            LessonChangeRepository changes, SchoolAccess access, SchoolBlockerSync sync,
                            CancellationNotifier notifier, SchoolProfileRepository profiles,
                            FamilyMemberRepository members) {
        this.notifier = notifier;
        this.profiles = profiles;
        this.members = members;
        this.years = years;
        this.subjects = subjects;
        this.lessons = lessons;
        this.changes = changes;
        this.access = access;
        this.sync = sync;
    }

    // --- Lesen ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SchoolSubject> subjects(Long yearId) {
        return subjects.findAllByYearIdOrderByNameAsc(yearId);
    }

    @Transactional(readOnly = true)
    public List<Lesson> lessons(Long yearId) {
        return lessons.findAllByYearIdOrderByWeekdayAscStartsAtAsc(yearId);
    }

    /** Die Stunden eines Tages mit Ausfall und Vertretung. */
    @Transactional(readOnly = true)
    public List<Timetable.Entry> day(Long yearId, LocalDate day) {
        return week(yearId, day).getOrDefault(day.getDayOfWeek(), List.of());
    }

    /** Die Stunden der Woche, die den Tag enthält, je Wochentag. */
    @Transactional(readOnly = true)
    public Map<DayOfWeek, List<Timetable.Entry>> week(Long yearId, LocalDate anyDayOfWeek) {
        SchoolYear year = requireYear(yearId);
        LocalDate monday = anyDayOfWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Lesson> all = lessons(yearId);
        Map<Long, SchoolSubject> bySubject = subjects(yearId).stream()
                .collect(Collectors.toMap(SchoolSubject::getId, Function.identity()));
        Map<LocalDate, Map<Long, LessonChange>> byDay = changes
                .findInRange(all.stream().map(Lesson::getId).toList(), monday, monday.plusDays(6)).stream()
                .collect(Collectors.groupingBy(LessonChange::getDay,
                        Collectors.toMap(LessonChange::getLessonId, Function.identity())));
        Map<DayOfWeek, List<Timetable.Entry>> result = new EnumMap<>(DayOfWeek.class);
        for (int i = 0; i < 7; i++) {
            LocalDate date = monday.plusDays(i);
            result.put(date.getDayOfWeek(),
                    Timetable.day(date, year, all, bySubject, byDay.getOrDefault(date, Map.of())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<LessonChange> change(Long lessonId, LocalDate day) {
        return changes.findByLessonIdAndDay(lessonId, day);
    }

    // --- Fächer --------------------------------------------------------------------------

    @Transactional
    public SchoolSubject addSubject(Long yearId, String name, String color, String teacher, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        requireYear(yearId);
        String cleanName = requireName(name);
        if (subjects.existsByYearIdAndNameIgnoreCase(yearId, cleanName)) {
            throw new IllegalArgumentException("Das Fach „" + cleanName + "“ gibt es schon.");
        }
        long count = subjects.findAllByYearIdOrderByNameAsc(yearId).size();
        return subjects.save(new SchoolSubject(yearId, cleanName, cleanColor(color, (int) count),
                cleanTeacher(teacher)));
    }

    @Transactional
    public void changeSubject(Long id, String name, String color, String teacher, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolSubject subject = requireSubject(id);
        String cleanName = requireName(name);
        boolean renamed = !cleanName.equalsIgnoreCase(subject.getName());
        if (renamed && subjects.existsByYearIdAndNameIgnoreCase(subject.getYearId(), cleanName)) {
            throw new IllegalArgumentException("Das Fach „" + cleanName + "“ gibt es schon.");
        }
        subject.change(cleanName, cleanColor(color, 0), cleanTeacher(teacher));
    }

    @Transactional
    public void deleteSubject(Long id, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolSubject subject = requireSubject(id);
        subjects.delete(subject);
        subjects.flush();
        sync.sync(subject.getYearId());
    }

    // --- Stunden -------------------------------------------------------------------------

    @Transactional
    public Lesson addLesson(Long yearId, Long subjectId, DayOfWeek weekday, LocalTime startsAt, LocalTime endsAt,
                            String room, WeekKind weekKind, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolYear year = requireYear(yearId);
        SchoolSubject subject = requireSubject(subjectId);
        if (!subject.getYearId().equals(yearId)) {
            throw new IllegalArgumentException("Das Fach gehört zu einem anderen Schuljahr.");
        }
        if (weekday == null || startsAt == null || endsAt == null) {
            throw new IllegalArgumentException("Wochentag, Beginn und Ende sind erforderlich.");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Die Stunde muss nach ihrem Beginn enden.");
        }
        WeekKind kind = weekKind == null ? WeekKind.EVERY : weekKind;
        if (kind != WeekKind.EVERY && year.getWeekAStart() == null) {
            throw new IllegalArgumentException("Für A/B-Wochen zuerst festlegen, welche Woche eine A-Woche ist.");
        }
        for (Lesson other : lessons(yearId)) {
            if (other.getWeekday() == weekday && sameWeeks(other.getWeekKind(), kind)
                    && startsAt.isBefore(other.getEndsAt()) && endsAt.isAfter(other.getStartsAt())) {
                throw new IllegalArgumentException("Die Stunde überschneidet sich mit einer anderen am selben Tag.");
            }
        }
        String cleanRoom = room == null || room.isBlank() ? null : room.trim();
        if (cleanRoom != null && cleanRoom.length() > 30) {
            throw new IllegalArgumentException("Der Raum darf höchstens 30 Zeichen lang sein.");
        }
        Lesson saved = lessons.save(new Lesson(yearId, subjectId, weekday, startsAt, endsAt, cleanRoom, kind));
        sync.sync(yearId);
        return saved;
    }

    @Transactional
    public void deleteLesson(Long id, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        Lesson lesson = lessons.findById(id).orElseThrow(() -> new IllegalArgumentException("Stunde nicht gefunden"));
        lessons.delete(lesson);
        lessons.flush();
        sync.sync(lesson.getYearId());
    }

    /** Legt fest, welche Woche eine A-Woche ist; leer schaltet A/B-Wochen ab. */
    @Transactional
    public void setWeekPlan(Long yearId, LocalDate weekAStart, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolYear year = requireYear(yearId);
        if (weekAStart == null && lessons(yearId).stream().anyMatch(l -> l.getWeekKind() != WeekKind.EVERY)) {
            throw new IllegalArgumentException("Es gibt noch Stunden für A- oder B-Wochen.");
        }
        year.changeWeekAStart(weekAStart == null ? null
                : weekAStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
        sync.sync(yearId);
    }

    // --- Ausfall und Vertretung ----------------------------------------------------------

    @Transactional
    public void markLesson(Long lessonId, LocalDate day, ChangeKind kind, String note, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        Lesson lesson = lessons.findById(lessonId)
                .orElseThrow(() -> new IllegalArgumentException("Stunde nicht gefunden"));
        if (day == null || kind == null) {
            throw new IllegalArgumentException("Datum und Art sind erforderlich.");
        }
        if (day.getDayOfWeek() != lesson.getWeekday()) {
            throw new IllegalArgumentException("Die Stunde findet an diesem Wochentag nicht statt.");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > 200) {
            throw new IllegalArgumentException("Der Hinweis darf höchstens 200 Zeichen lang sein.");
        }
        Optional<LessonChange> existing = changes.findByLessonIdAndDay(lessonId, day);
        if (existing.isPresent()) {
            existing.get().change(kind, cleanNote);
        } else {
            changes.save(new LessonChange(lessonId, day, kind, cleanNote));
        }
        changes.flush();
        sync.sync(lesson.getYearId());
        if (kind == ChangeKind.CANCELLED) {
            SchoolYear year = requireYear(lesson.getYearId());
            String child = profiles.findById(year.getProfileId()).flatMap(p -> members.findById(p.getMemberId()))
                    .map(m -> m.getName()).orElse("");
            notifier.cancelled(lesson, requireSubject(lesson.getSubjectId()), year, child, day, cleanNote);
        }
    }

    @Transactional
    public void clearChange(Long changeId, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        LessonChange change = changes.findById(changeId)
                .orElseThrow(() -> new IllegalArgumentException("Änderung nicht gefunden"));
        Long yearId = lessons.findById(change.getLessonId()).map(Lesson::getYearId).orElseThrow();
        changes.delete(change);
        changes.flush();
        sync.sync(yearId);
    }

    // --- Hilfen --------------------------------------------------------------------------

    private static boolean sameWeeks(WeekKind a, WeekKind b) {
        return a == WeekKind.EVERY || b == WeekKind.EVERY || a == b;
    }

    private SchoolYear requireYear(Long id) {
        return years.findById(id).orElseThrow(() -> new IllegalArgumentException("Schuljahr nicht gefunden"));
    }

    private SchoolSubject requireSubject(Long id) {
        return subjects.findById(id).orElseThrow(() -> new IllegalArgumentException("Fach nicht gefunden"));
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 60) {
            throw new IllegalArgumentException("Der Name des Fachs muss 1–60 Zeichen lang sein.");
        }
        return clean;
    }

    private static String cleanColor(String color, int index) {
        if (color == null || color.isBlank()) {
            return PALETTE[Math.floorMod(index, PALETTE.length)];
        }
        if (!COLOR.matcher(color.trim()).matches()) {
            throw new IllegalArgumentException("Die Farbe muss wie #aabbcc aussehen.");
        }
        return color.trim();
    }

    private static String cleanTeacher(String teacher) {
        if (teacher == null || teacher.isBlank()) {
            return null;
        }
        String clean = teacher.trim();
        if (clean.length() > 100) {
            throw new IllegalArgumentException("Der Name der Lehrkraft darf höchstens 100 Zeichen lang sein.");
        }
        return clean;
    }
}
