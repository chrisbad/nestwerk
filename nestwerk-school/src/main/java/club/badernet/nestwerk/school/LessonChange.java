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

/** Ausfall oder Vertretung einer Stunde an einem bestimmten Tag. */
@Entity
@Table(name = "school_lesson_change")
public class LessonChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    @Column(nullable = false)
    private LocalDate day;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ChangeKind kind;

    @Column(length = 200)
    private String note;

    protected LessonChange() {
    }

    public LessonChange(Long lessonId, LocalDate day, ChangeKind kind, String note) {
        this.lessonId = lessonId;
        this.day = day;
        change(kind, note);
    }

    public void change(ChangeKind kind, String note) {
        this.kind = kind;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public Long getLessonId() {
        return lessonId;
    }

    public LocalDate getDay() {
        return day;
    }

    public ChangeKind getKind() {
        return kind;
    }

    public String getNote() {
        return note;
    }
}
