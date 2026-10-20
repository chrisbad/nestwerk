package club.badernet.nestwerk.school;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** Eine Note in einem Fach und Halbjahr; {@code tenths} in Zehnteln (17 = 2+), {@code weight} in Prozent. */
@Entity
@Table(name = "school_grade")
public class SchoolGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "year_id", nullable = false)
    private Long yearId;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(nullable = false)
    private short term;

    @Column(nullable = false)
    private short tenths;

    @Column(nullable = false)
    private short weight = 100;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private GradeKind kind;

    @Column(nullable = false)
    private LocalDate day;

    @Column(length = 200)
    private String note;

    protected SchoolGrade() {
    }

    public SchoolGrade(Long yearId, Long subjectId, int term, int tenths, int weight, GradeKind kind, LocalDate day,
                       String note) {
        this.yearId = yearId;
        change(subjectId, term, tenths, weight, kind, day, note);
    }

    public void change(Long subjectId, int term, int tenths, int weight, GradeKind kind, LocalDate day, String note) {
        this.subjectId = subjectId;
        this.term = (short) term;
        this.tenths = (short) tenths;
        this.weight = (short) weight;
        this.kind = kind;
        this.day = day;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public Long getYearId() {
        return yearId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public int getTerm() {
        return term;
    }

    public int getTenths() {
        return tenths;
    }

    /** Gewicht in Prozent einer normalen Note (100 = einfach, 200 = doppelt). */
    public int getWeight() {
        return weight;
    }

    public GradeKind getKind() {
        return kind;
    }

    public LocalDate getDay() {
        return day;
    }

    public String getNote() {
        return note;
    }

    public String getLabel() {
        return Grade.label(tenths);
    }
}
