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

/** A recurring lesson in the weekly timetable. */
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

    /** First period in the school's grid; {@code null} for free times (e.g. clubs). */
    @Column(name = "period_from")
    private Short periodFrom;

    @Column(name = "period_to")
    private Short periodTo;

    protected Lesson() {
    }

    public Lesson(Long yearId, Long subjectId, DayOfWeek weekday, LocalTime startsAt, LocalTime endsAt, String room,
                  WeekKind weekKind) {
        this.yearId = yearId;
        change(subjectId, weekday, startsAt, endsAt, room, weekKind, null, null);
    }

    public void change(Long subjectId, DayOfWeek weekday, LocalTime startsAt, LocalTime endsAt, String room,
                       WeekKind weekKind, Integer periodFrom, Integer periodTo) {
        this.subjectId = subjectId;
        this.weekday = (short) weekday.getValue();
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.room = room;
        this.weekKind = weekKind;
        this.periodFrom = periodFrom == null ? null : periodFrom.shortValue();
        this.periodTo = periodTo == null ? null : periodTo.shortValue();
    }

    /** Places the lesson in the grid and takes its times. */
    public void placeInPeriods(int from, int to, LocalTime startsAt, LocalTime endsAt) {
        this.periodFrom = (short) from;
        this.periodTo = (short) to;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
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

    public Integer getPeriodFrom() {
        return periodFrom == null ? null : periodFrom.intValue();
    }

    public Integer getPeriodTo() {
        return periodTo == null ? null : periodTo.intValue();
    }

    public boolean inPeriods() {
        return periodFrom != null;
    }
}
