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

/** Klassenarbeit, Test, Elternabend oder Ausflug; als ganztägiger Kalendertermin eingetragen. */
@Entity
@Table(name = "school_exam")
public class SchoolExam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "year_id", nullable = false)
    private Long yearId;

    @Column(name = "subject_id")
    private Long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private ExamKind kind;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false)
    private LocalDate day;

    @Column(name = "study_days", nullable = false)
    private int studyDays;

    @Column(name = "event_id")
    private Long eventId;

    protected SchoolExam() {
    }

    public SchoolExam(Long yearId, Long subjectId, ExamKind kind, String title, LocalDate day, int studyDays) {
        this.yearId = yearId;
        change(subjectId, kind, title, day, studyDays);
    }

    public void change(Long subjectId, ExamKind kind, String title, LocalDate day, int studyDays) {
        this.subjectId = subjectId;
        this.kind = kind;
        this.title = title;
        this.day = day;
        this.studyDays = studyDays;
    }

    public void linkEvent(Long eventId) {
        this.eventId = eventId;
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

    public ExamKind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getDay() {
        return day;
    }

    /** Wie viele Tage vor dem Termin zusätzlich ans Lernen erinnert wird (0 = nur am Vortag). */
    public int getStudyDays() {
        return studyDays;
    }

    public Long getEventId() {
        return eventId;
    }
}
