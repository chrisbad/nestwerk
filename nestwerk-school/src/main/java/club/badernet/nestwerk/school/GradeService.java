package club.badernet.nestwerk.school;

import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Noten und Zeugnisnoten. Sehen darf sie, wer {@code SCHOOL_GRADES} hat oder mit {@code SCHOOL_GRADES_OWN} das eigene
 * Kind-Profil betrachtet; pflegen darf nur {@code SCHOOL_GRADES}.
 */
@Service
public class GradeService {

    /** Ein Fach mit seinen Noten im Halbjahr, dem gewichteten Durchschnitt und der Zeugnisnote. */
    public record SubjectGrades(SchoolSubject subject, List<SchoolGrade> grades, Double average, ReportGrade report,
                                List<Double> trend) {

        public String averageLabel() {
            return average == null ? null : Grade.format(average);
        }
    }

    /** Alle Fächer eines Halbjahrs mit dem Gesamtdurchschnitt über die Fachdurchschnitte. */
    public record Overview(List<SubjectGrades> subjects, Double overallAverage) {

        public String overallLabel() {
            return overallAverage == null ? null : Grade.format(overallAverage);
        }
    }

    private final SchoolGradeRepository grades;
    private final ReportGradeRepository reports;
    private final SchoolSubjectRepository subjects;
    private final SchoolYearRepository years;
    private final SchoolProfileRepository profiles;
    private final SchoolAccess access;

    public GradeService(SchoolGradeRepository grades, ReportGradeRepository reports, SchoolSubjectRepository subjects,
                        SchoolYearRepository years, SchoolProfileRepository profiles, SchoolAccess access) {
        this.grades = grades;
        this.reports = reports;
        this.subjects = subjects;
        this.years = years;
        this.profiles = profiles;
        this.access = access;
    }

    /** Darf der Benutzer die Noten dieses Schuljahrs sehen? */
    @Transactional(readOnly = true)
    public boolean mayView(Long yearId, User user) {
        return years.findById(yearId).flatMap(y -> profiles.findById(y.getProfileId()))
                .map(p -> access.mayViewGrades(user, p.getMemberId())).orElse(false);
    }

    @Transactional(readOnly = true)
    public Overview overview(Long yearId, int term, User viewer) {
        requireView(yearId, viewer);
        requireTerm(term);
        Map<Long, List<SchoolGrade>> byLesson = grades.findAllByYearIdAndTermOrderByDayAscIdAsc(yearId, term).stream()
                .collect(Collectors.groupingBy(SchoolGrade::getSubjectId));
        List<SchoolSubject> all = subjects.findAllByYearIdOrderByNameAsc(yearId);
        Map<Long, ReportGrade> report = reports
                .findAllBySubjectIdInAndTerm(all.stream().map(SchoolSubject::getId).toList(), term).stream()
                .collect(Collectors.toMap(ReportGrade::getSubjectId, Function.identity()));
        List<SubjectGrades> rows = new ArrayList<>();
        List<Double> averages = new ArrayList<>();
        for (SchoolSubject subject : all) {
            List<SchoolGrade> list = byLesson.getOrDefault(subject.getId(), List.of());
            Double average = average(list);
            if (!list.isEmpty()) {
                averages.add(rawAverage(list));
            }
            rows.add(new SubjectGrades(subject, list, average, report.get(subject.getId()), trend(list)));
        }
        Double overall = averages.isEmpty() ? null
                : round(averages.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        return new Overview(rows, overall);
    }

    @Transactional
    public SchoolGrade add(Long yearId, Long subjectId, int term, String grade, int weight, GradeKind kind,
                           LocalDate day, String note, User actor) {
        requireEdit(actor);
        requireYear(yearId);
        SchoolGrade created = new SchoolGrade(yearId, requireSubject(yearId, subjectId).getId(), requireTerm(term),
                Grade.parse(grade), requireWeight(weight), requireKind(kind), requireDay(day), cleanNote(note));
        return grades.save(created);
    }

    @Transactional
    public void update(Long id, Long subjectId, int term, String grade, int weight, GradeKind kind, LocalDate day,
                       String note, User actor) {
        requireEdit(actor);
        SchoolGrade existing = grades.findById(id).orElseThrow(() -> new IllegalArgumentException("Note nicht gefunden"));
        existing.change(requireSubject(existing.getYearId(), subjectId).getId(), requireTerm(term), Grade.parse(grade),
                requireWeight(weight), requireKind(kind), requireDay(day), cleanNote(note));
    }

    @Transactional
    public void delete(Long id, User actor) {
        requireEdit(actor);
        grades.delete(grades.findById(id).orElseThrow(() -> new IllegalArgumentException("Note nicht gefunden")));
    }

    /** Setzt die Zeugnisnote eines Fachs im Halbjahr; eine leere Eingabe entfernt sie. */
    @Transactional
    public void setReportGrade(Long yearId, Long subjectId, int term, String grade, User actor) {
        requireEdit(actor);
        requireTerm(term);
        SchoolSubject subject = requireSubject(yearId, subjectId);
        Optional<ReportGrade> existing = reports.findBySubjectIdAndTerm(subject.getId(), term);
        if (grade == null || grade.isBlank()) {
            existing.ifPresent(reports::delete);
            return;
        }
        int tenths = Grade.parse(grade);
        if (existing.isPresent()) {
            existing.get().change(tenths);
        } else {
            reports.save(new ReportGrade(subject.getId(), term, tenths));
        }
    }

    /** Gewichteter Durchschnitt in Zehnteln auf zwei Stellen gerundet; {@code null} ohne Noten. */
    public static Double average(List<SchoolGrade> list) {
        return list.isEmpty() ? null : round(rawAverage(list));
    }

    private static double rawAverage(List<SchoolGrade> list) {
        long weights = list.stream().mapToLong(SchoolGrade::getWeight).sum();
        double sum = list.stream().mapToDouble(g -> g.getTenths() / 10.0 * g.getWeight()).sum();
        return sum / weights;
    }

    /** Durchschnitt nach jeder Note, in zeitlicher Reihenfolge. */
    public static List<Double> trend(List<SchoolGrade> chronological) {
        List<Double> result = new ArrayList<>();
        for (int i = 1; i <= chronological.size(); i++) {
            result.add(average(chronological.subList(0, i)));
        }
        return result;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void requireView(Long yearId, User viewer) {
        if (!mayView(yearId, viewer)) {
            throw new AccessDeniedException("Keine Berechtigung, die Noten zu sehen");
        }
    }

    private void requireEdit(User actor) {
        access.require(actor, SchoolPermission.SCHOOL_GRADES);
    }

    private SchoolYear requireYear(Long yearId) {
        return years.findById(yearId).orElseThrow(() -> new IllegalArgumentException("Schuljahr nicht gefunden"));
    }

    private SchoolSubject requireSubject(Long yearId, Long subjectId) {
        return subjects.findById(subjectId == null ? -1L : subjectId).filter(s -> s.getYearId().equals(yearId))
                .orElseThrow(() -> new IllegalArgumentException("Bitte ein Fach wählen."));
    }

    private static int requireTerm(int term) {
        if (term != 1 && term != 2) {
            throw new IllegalArgumentException("Das Halbjahr ist 1 oder 2.");
        }
        return term;
    }

    private static int requireWeight(int weight) {
        if (weight < 25 || weight > 400) {
            throw new IllegalArgumentException("Das Gewicht liegt zwischen 25 % und 400 %.");
        }
        return weight;
    }

    private static GradeKind requireKind(GradeKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("Bitte die Art der Note wählen.");
        }
        return kind;
    }

    private static LocalDate requireDay(LocalDate day) {
        if (day == null) {
            throw new IllegalArgumentException("Bitte das Datum angeben.");
        }
        return day;
    }

    private static String cleanNote(String note) {
        String clean = note == null ? "" : note.trim();
        if (clean.length() > 200) {
            throw new IllegalArgumentException("Die Notiz darf höchstens 200 Zeichen lang sein.");
        }
        return clean.isEmpty() ? null : clean;
    }
}
