package club.badernet.nestwerk.school;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.DayOfWeek;
import java.time.LocalTime;

/** Eine wiederkehrende Unterrichtsstunde im Wochenraster. */
@Entity
@Table(name = "school_lesson")
public class Lesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "year_id", nullable = false)
    private Long yearId;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(nullable = false)
    private short weekday;

    @Column(name = "starts_at", nullable = false)
    private LocalTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalTime endsAt;

    @Column(length = 30)
    private String room;

    @Enumerated(EnumType.STRING)
    @Column(name = "week_kind", nullable = false, length = 5)
    private WeekKind weekKind = WeekKind.EVERY;

    protected Lesson() {
    }

    public Lesson(Long yearId, Long subjectId, DayOfWeek weekday, LocalTime startsAt, LocalTime endsAt, String room,
                  WeekKind weekKind) {
        this.yearId = yearId;
        this.subjectId = subjectId;
        this.weekday = (short) weekday.getValue();
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.room = room;
        this.weekKind = weekKind;
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

    public DayOfWeek getWeekday() {
        return DayOfWeek.of(weekday);
    }

    public LocalTime getStartsAt() {
        return startsAt;
    }

    public LocalTime getEndsAt() {
        return endsAt;
    }

    public String getRoom() {
        return room;
    }

    public WeekKind getWeekKind() {
        return weekKind;
    }
}
