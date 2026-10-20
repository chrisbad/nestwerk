package club.badernet.nestwerk.school;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Zeugnisnote eines Fachs im Halbjahr, getrennt vom errechneten Durchschnitt. */
@Entity
@Table(name = "school_report_grade")
public class ReportGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(nullable = false)
    private short term;

    @Column(nullable = false)
    private short tenths;

    protected ReportGrade() {
    }

    public ReportGrade(Long subjectId, int term, int tenths) {
        this.subjectId = subjectId;
        this.term = (short) term;
        change(tenths);
    }

    public void change(int tenths) {
        this.tenths = (short) tenths;
    }

    public Long getId() {
        return id;
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

    public String getLabel() {
        return Grade.label(tenths);
    }
}
