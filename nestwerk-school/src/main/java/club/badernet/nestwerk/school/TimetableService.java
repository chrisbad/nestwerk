package club.badernet.nestwerk.school;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Subjects, timetable, cancellations and substitutions of a school year; changes re-sync the school blockers. */
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
    private final PeriodService periods;
    private final SchoolContacts schoolContacts;

    public TimetableService(SchoolYearRepository years, SchoolSubjectRepository subjects, LessonRepository lessons,
                            LessonChangeRepository changes, SchoolAccess access, SchoolBlockerSync sync,
                            CancellationNotifier notifier, SchoolProfileRepository profiles,
                            FamilyMemberRepository members, PeriodService periods,
                            SchoolContacts schoolContacts) {
        this.periods = periods;
        this.schoolContacts = schoolContacts;
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

    // --- Reading -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SchoolSubject> subjects(Long yearId) {
        return subjects.findAllByYearIdOrderByNameAsc(yearId);
    }

    @Transactional(readOnly = true)
    public List<Lesson> lessons(Long yearId) {
        return lessons.findAllByYearIdOrderByWeekdayAscStartsAtAsc(yearId);
    }

    /** The lessons of a day with cancellations and substitutions. */
    @Transactional(readOnly = true)
    public List<Timetable.Entry> day(Long yearId, LocalDate day) {
        return week(yearId, day).getOrDefault(day.getDayOfWeek(), List.of());
    }

    /** The lessons of the week containing the day, per weekday. */
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

    // --- Subjects ------------------------------------------------------------------------

    @Transactional
    public SchoolSubject addSubject(Long yearId, String name, String color, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        requireYear(yearId);
        String cleanName = requireName(name);
        if (subjects.existsByYearIdAndNameIgnoreCase(yearId, cleanName)) {
            throw new IllegalArgumentException("Das Fach „" + cleanName + "“ gibt es schon.");
        }
        long count = subjects.findAllByYearIdOrderByNameAsc(yearId).size();
        return subjects.save(new SchoolSubject(yearId, cleanName, cleanColor(color, (int) count)));
    }

    @Transactional
    public void changeSubject(Long id, String name, String color, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolSubject subject = requireSubject(id);
        String cleanName = requireName(name);
        boolean renamed = !cleanName.equalsIgnoreCase(subject.getName());
        if (renamed && subjects.existsByYearIdAndNameIgnoreCase(subject.getYearId(), cleanName)) {
            throw new IllegalArgumentException("Das Fach „" + cleanName + "“ gibt es schon.");
        }
        subject.change(cleanName, cleanColor(color, 0));
    }

    /** Sets the teachers (contacts) of a subject. */
    @Transactional
    public void setTeachers(Long subjectId, Set<Long> contactIds, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolSubject subject = requireSubject(subjectId);
        Set<Long> ids = contactIds == null ? Set.of() : new LinkedHashSet<>(contactIds);
        ids.forEach(id -> schoolContacts.requireLinkable(id, actor));
        subject.setTeacherIds(ids);
    }

    /** Creates a new teacher contact and assigns it to the subject. */
    @Transactional
    public Contact addTeacher(Long subjectId, String name, String email, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolSubject subject = requireSubject(subjectId);
        Contact teacher = schoolContacts.createTeacher(name, email, actor);
        Set<Long> ids = new LinkedHashSet<>(subject.getTeacherIds());
        ids.add(teacher.getId());
        subject.setTeacherIds(ids);
        return teacher;
    }

    /** Teachers per subject id for display, ordered by name. */
    @Transactional(readOnly = true)
    public Map<Long, List<Contact>> teachers(Long yearId) {
        List<SchoolSubject> all = subjects(yearId);
        Map<Long, Contact> byId = schoolContacts.byIds(all.stream()
                .flatMap(s -> s.getTeacherIds().stream()).collect(Collectors.toSet()));
        return all.stream().collect(Collectors.toMap(SchoolSubject::getId, s -> s.getTeacherIds().stream()
                .map(byId::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Contact::getName, String.CASE_INSENSITIVE_ORDER)).toList()));
    }

    @Transactional
    public void deleteSubject(Long id, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolSubject subject = requireSubject(id);
        subjects.delete(subject);
        subjects.flush();
        sync.sync(subject.getYearId());
    }

    // --- Lessons -------------------------------------------------------------------------

    /**
     * Adds a lesson: placed in the school's period grid (times from the grid) or with free times. Overlaps with other
     * lessons on the same day and in the same weeks are rejected.
     */
    @Transactional
    public Lesson addLesson(Long yearId, LessonInput in, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        Lesson lesson = new Lesson(yearId, in.subjectId(), DayOfWeek.MONDAY, LocalTime.MIN, LocalTime.NOON, null,
                WeekKind.EVERY);
        apply(lesson, yearId, in, null);
        Lesson saved = lessons.save(lesson);
        sync.sync(yearId);
        return saved;
    }

    @Transactional
    public void changeLesson(Long id, LessonInput in, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        Lesson lesson = requireLesson(id);
        apply(lesson, lesson.getYearId(), in, id);
        lessons.flush();
        sync.sync(lesson.getYearId());
    }

    private void apply(Lesson lesson, Long yearId, LessonInput in, Long ignoredLessonId) {
        SchoolYear year = requireYear(yearId);
        SchoolSubject subject = requireSubject(in.subjectId());
        if (!subject.getYearId().equals(yearId)) {
            throw new IllegalArgumentException("Das Fach gehört zu einem anderen Schuljahr.");
        }
        if (in.weekday() == null) {
            throw new IllegalArgumentException("Bitte den Wochentag wählen.");
        }
        LocalTime startsAt = in.startsAt();
        LocalTime endsAt = in.endsAt();
        Integer periodTo = null;
        if (in.periodFrom() != null) {
            periodTo = in.periodTo() == null ? in.periodFrom() : in.periodTo();
            PeriodService.Range range = PeriodService.range(periods.grid(year.getSchoolContactId()), in.periodFrom(),
                    periodTo).orElseThrow(() -> new IllegalArgumentException(
                    "Diese Stunden gibt es im Raster der Schule nicht."));
            startsAt = range.startsAt();
            endsAt = range.endsAt();
        }
        if (startsAt == null || endsAt == null) {
            throw new IllegalArgumentException("Bitte Stunden aus dem Raster oder Beginn und Ende angeben.");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Die Stunde muss nach ihrem Beginn enden.");
        }
        WeekKind kind = in.weekKind() == null ? WeekKind.EVERY : in.weekKind();
        if (kind != WeekKind.EVERY && year.getWeekAStart() == null) {
            throw new IllegalArgumentException("Für A/B-Wochen zuerst festlegen, welche Woche eine A-Woche ist.");
        }
        for (Lesson other : lessons(yearId)) {
            if (!other.getId().equals(ignoredLessonId) && other.getWeekday() == in.weekday()
                    && sameWeeks(other.getWeekKind(), kind)
                    && startsAt.isBefore(other.getEndsAt()) && endsAt.isAfter(other.getStartsAt())) {
                throw new IllegalArgumentException("Die Stunde überschneidet sich mit einer anderen am selben Tag.");
            }
        }
        String room = in.room() == null || in.room().isBlank() ? null : in.room().trim();
        if (room != null && room.length() > 30) {
            throw new IllegalArgumentException("Der Raum darf höchstens 30 Zeichen lang sein.");
        }
        lesson.change(in.subjectId(), in.weekday(), startsAt, endsAt, room, kind, in.periodFrom(), periodTo);
    }

    @Transactional
    public void deleteLesson(Long id, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        Lesson lesson = requireLesson(id);
        lessons.delete(lesson);
        lessons.flush();
        sync.sync(lesson.getYearId());
    }

    /** Sets which week is an A week; {@code null} turns A/B weeks off. */
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

    // --- Cancellations and substitutions -------------------------------------------------

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

    /**
     * Marks all lessons of a day that match the selection, e.g. "Mathe" or periods 5–6; returns the marked lessons.
     */
    @Transactional
    public List<Timetable.Entry> markDay(Long yearId, LocalDate day, LessonSelection selection, ChangeKind kind,
                                         String note, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        List<Timetable.Entry> matching = select(yearId, day, selection);
        matching.forEach(e -> markLesson(e.lesson().getId(), day, kind, note, actor));
        return matching;
    }

    /** Resets the selected lessons of a day to normal; returns how many had a change. */
    @Transactional
    public int clearDay(Long yearId, LocalDate day, LessonSelection selection, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        List<Timetable.Entry> changed = select(yearId, day, selection).stream().filter(e -> e.change() != null)
                .toList();
        changed.forEach(e -> clearChange(e.change().getId(), actor));
        return changed.size();
    }

    private List<Timetable.Entry> select(Long yearId, LocalDate day, LessonSelection selection) {
        if (day == null) {
            throw new IllegalArgumentException("Bitte den Tag angeben.");
        }
        List<Timetable.Entry> matching = day(yearId, day).stream().filter(selection::matches).toList();
        if (matching.isEmpty()) {
            throw new IllegalArgumentException("Am " + day + " gibt es keine passende Stunde.");
        }
        return matching;
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

    // --- Helpers -------------------------------------------------------------------------

    private static boolean sameWeeks(WeekKind a, WeekKind b) {
        return a == WeekKind.EVERY || b == WeekKind.EVERY || a == b;
    }

    private Lesson requireLesson(Long id) {
        return lessons.findById(id).orElseThrow(() -> new IllegalArgumentException("Stunde nicht gefunden"));
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
}
