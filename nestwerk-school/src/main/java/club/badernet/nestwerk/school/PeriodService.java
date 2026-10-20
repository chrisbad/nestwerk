package club.badernet.nestwerk.school;

import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Period grid of a school with lessons and breaks. Timetable lessons placed in the grid take its times; when the grid
 * changes, the lessons of all school years at that school follow.
 */
@Service
public class PeriodService {

    /** One row when saving the grid. */
    public record PeriodInput(PeriodKind kind, LocalTime startsAt, LocalTime endsAt, String label) {
    }

    /** Start and end of a range of periods. */
    public record Range(LocalTime startsAt, LocalTime endsAt) {
    }

    private final SchoolPeriodRepository periods;
    private final SchoolYearRepository years;
    private final LessonRepository lessons;
    private final SchoolContacts contacts;
    private final SchoolAccess access;
    private final SchoolBlockerSync sync;

    public PeriodService(SchoolPeriodRepository periods, SchoolYearRepository years, LessonRepository lessons,
                         SchoolContacts contacts, SchoolAccess access, SchoolBlockerSync sync) {
        this.periods = periods;
        this.years = years;
        this.lessons = lessons;
        this.contacts = contacts;
        this.access = access;
        this.sync = sync;
    }

    /** The grid of a school ordered by start; empty without school or grid. */
    @Transactional(readOnly = true)
    public List<SchoolPeriod> grid(Long schoolContactId) {
        return schoolContactId == null ? List.of()
                : periods.findAllBySchoolContactIdOrderByStartsAtAsc(schoolContactId);
    }

    /** The grid of the school of a school year. */
    @Transactional(readOnly = true)
    public List<SchoolPeriod> gridOfYear(Long yearId) {
        return years.findById(yearId).map(y -> grid(y.getSchoolContactId())).orElse(List.of());
    }

    /** Time span from period {@code from} to period {@code to} in the grid. */
    public static Optional<Range> range(List<SchoolPeriod> grid, int from, int to) {
        Map<Integer, SchoolPeriod> byNumber = lessonsByNumber(grid);
        SchoolPeriod first = byNumber.get(from);
        SchoolPeriod last = byNumber.get(to);
        if (first == null || last == null || to < from) {
            return Optional.empty();
        }
        return Optional.of(new Range(first.getStartsAt(), last.getEndsAt()));
    }

    /** Suggestion for a new grid: six 45-minute periods from 8 o'clock with two breaks. */
    public static List<PeriodInput> suggestion() {
        List<PeriodInput> rows = new ArrayList<>();
        LocalTime t = LocalTime.of(8, 0);
        for (int n = 1; n <= 6; n++) {
            rows.add(new PeriodInput(PeriodKind.LESSON, t, t.plusMinutes(45), null));
            t = t.plusMinutes(45);
            if (n == 2) {
                rows.add(new PeriodInput(PeriodKind.BREAK, t, t.plusMinutes(20), null));
                t = t.plusMinutes(20);
            } else if (n == 4) {
                rows.add(new PeriodInput(PeriodKind.BREAK, t, t.plusMinutes(15), null));
                t = t.plusMinutes(15);
            } else if (n < 6) {
                t = t.plusMinutes(5);
            }
        }
        return rows;
    }

    /**
     * Replaces the grid of the school. Lessons are numbered in order; rows must not overlap. Afterwards the lessons
     * of all school years at this school take the new times.
     */
    @Transactional
    public List<SchoolPeriod> saveGrid(Long schoolContactId, List<PeriodInput> rows, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        if (!contacts.exists(schoolContactId)) {
            throw new IllegalArgumentException("Schule nicht gefunden");
        }
        List<PeriodInput> sorted = rows.stream()
                .filter(r -> r.startsAt() != null || r.endsAt() != null)
                .peek(PeriodService::validate)
                .sorted(Comparator.comparing(PeriodInput::startsAt))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startsAt().isBefore(sorted.get(i - 1).endsAt())) {
                throw new IllegalArgumentException("Die Zeilen des Rasters dürfen sich nicht überschneiden ("
                        + sorted.get(i - 1).startsAt() + " und " + sorted.get(i).startsAt() + ").");
            }
        }
        periods.deleteAllBySchoolContactId(schoolContactId);
        periods.flush();
        int number = 0;
        List<SchoolPeriod> saved = new ArrayList<>();
        for (PeriodInput row : sorted) {
            boolean lesson = row.kind() != PeriodKind.BREAK;
            saved.add(periods.save(new SchoolPeriod(schoolContactId, lesson ? PeriodKind.LESSON : PeriodKind.BREAK,
                    lesson ? ++number : null, cleanLabel(row.label()), row.startsAt(), row.endsAt())));
        }
        periods.flush();
        for (SchoolYear year : years.findAllBySchoolContactId(schoolContactId)) {
            align(year.getId(), saved);
        }
        return saved;
    }

    /** Aligns the lessons of a school year with the grid of its school, e.g. after changing schools. */
    @Transactional
    public void align(Long yearId) {
        align(yearId, gridOfYear(yearId));
    }

    /**
     * Lessons placed in the grid take its times (if their periods no longer exist they keep their times as free
     * times); lessons with free times that exactly match grid periods are placed in the grid.
     */
    private void align(Long yearId, List<SchoolPeriod> grid) {
        Map<Integer, SchoolPeriod> byNumber = lessonsByNumber(grid);
        Map<LocalTime, SchoolPeriod> byStart = byNumber.values().stream()
                .collect(Collectors.toMap(SchoolPeriod::getStartsAt, Function.identity(), (a, b) -> a));
        Map<LocalTime, SchoolPeriod> byEnd = byNumber.values().stream()
                .collect(Collectors.toMap(SchoolPeriod::getEndsAt, Function.identity(), (a, b) -> a));
        boolean changed = false;
        for (Lesson lesson : lessons.findAllByYearIdOrderByWeekdayAscStartsAtAsc(yearId)) {
            if (lesson.inPeriods()) {
                Optional<Range> range = range(grid, lesson.getPeriodFrom(), lesson.getPeriodTo());
                if (range.isPresent()) {
                    if (!range.get().startsAt().equals(lesson.getStartsAt())
                            || !range.get().endsAt().equals(lesson.getEndsAt())) {
                        lesson.placeInPeriods(lesson.getPeriodFrom(), lesson.getPeriodTo(), range.get().startsAt(),
                                range.get().endsAt());
                        changed = true;
                    }
                } else {
                    lesson.change(lesson.getSubjectId(), lesson.getWeekday(), lesson.getStartsAt(),
                            lesson.getEndsAt(), lesson.getRoom(), lesson.getWeekKind(), null, null);
                    changed = true;
                }
            } else {
                SchoolPeriod first = byStart.get(lesson.getStartsAt());
                SchoolPeriod last = byEnd.get(lesson.getEndsAt());
                if (first != null && last != null && last.getNumber() >= first.getNumber()) {
                    lesson.placeInPeriods(first.getNumber(), last.getNumber(), first.getStartsAt(), last.getEndsAt());
                    changed = true;
                }
            }
        }
        if (changed) {
            lessons.flush();
            sync.sync(yearId);
        }
    }

    private static Map<Integer, SchoolPeriod> lessonsByNumber(List<SchoolPeriod> grid) {
        return grid.stream().filter(p -> !p.isBreak())
                .collect(Collectors.toMap(SchoolPeriod::getNumber, Function.identity(), (a, b) -> a));
    }

    private static void validate(PeriodInput row) {
        if (row.startsAt() == null || row.endsAt() == null) {
            throw new IllegalArgumentException("Jede Zeile des Rasters braucht Beginn und Ende.");
        }
        if (!row.endsAt().isAfter(row.startsAt())) {
            throw new IllegalArgumentException("Jede Zeile des Rasters muss nach ihrem Beginn enden.");
        }
    }

    private static String cleanLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String clean = label.trim();
        if (clean.length() > 40) {
            throw new IllegalArgumentException("Die Bezeichnung darf höchstens 40 Zeichen lang sein.");
        }
        return clean;
    }
}
