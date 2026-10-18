package club.badernet.nestwerk.school;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Rechnet das Wochenraster eines Schuljahrs auf einzelne Tage um (A/B-Wochen, Ausfall, Vertretung). */
public final class Timetable {

    /** Eine Stunde an einem Tag mit ihrer Abweichung (oder {@code null}). */
    public record Entry(Lesson lesson, SchoolSubject subject, LessonChange change) {

        public boolean cancelled() {
            return change != null && change.getKind() == ChangeKind.CANCELLED;
        }

        public boolean substituted() {
            return change != null && change.getKind() == ChangeKind.SUBSTITUTE;
        }
    }

    /** Erste Stunde bis Ende der letzten Stunde eines Schultags. */
    public record Span(LocalTime start, LocalTime end) {
    }

    private Timetable() {
    }

    /** {@code A} oder {@code B} für den Tag; {@code null}, wenn der Stundenplan keine A/B-Wochen kennt. */
    public static WeekKind weekKind(LocalDate day, LocalDate weekAStart) {
        if (weekAStart == null) {
            return null;
        }
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate aMonday = weekAStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long weeks = ChronoUnit.WEEKS.between(aMonday, monday);
        return Math.floorMod(weeks, 2L) == 0 ? WeekKind.A : WeekKind.B;
    }

    /** Alle Stunden des Tages nach Beginn sortiert, auch ausfallende (mit {@link Entry#cancelled()}). */
    public static List<Entry> day(LocalDate day, SchoolYear year, List<Lesson> lessons,
                                  Map<Long, SchoolSubject> subjects, Map<Long, LessonChange> changes) {
        if (day.isBefore(year.getStartsOn()) || day.isAfter(year.getEndsOn())) {
            return List.of();
        }
        WeekKind week = weekKind(day, year.getWeekAStart());
        return lessons.stream()
                .filter(l -> l.getWeekday() == day.getDayOfWeek())
                .filter(l -> l.getWeekKind() == WeekKind.EVERY || l.getWeekKind() == week)
                .map(l -> new Entry(l, subjects.get(l.getSubjectId()), changes.get(l.getId())))
                .sorted(Comparator.comparing((Entry e) -> e.lesson().getStartsAt())
                        .thenComparing(e -> e.lesson().getEndsAt()))
                .toList();
    }

    /** Zeitraum, in dem das Kind in der Schule ist; leer, wenn alles ausfällt oder nichts angesetzt ist. */
    public static Optional<Span> span(List<Entry> entries) {
        List<Entry> held = entries.stream().filter(e -> !e.cancelled()).toList();
        if (held.isEmpty()) {
            return Optional.empty();
        }
        LocalTime start = held.stream().map(e -> e.lesson().getStartsAt()).min(Comparator.naturalOrder()).orElseThrow();
        LocalTime end = held.stream().map(e -> e.lesson().getEndsAt()).max(Comparator.naturalOrder()).orElseThrow();
        return Optional.of(new Span(start, end));
    }
}
