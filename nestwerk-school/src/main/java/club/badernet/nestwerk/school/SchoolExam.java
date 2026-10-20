package club.badernet.nestwerk.school;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Exam, test or school appointment (parents' evening, trip …); appears as a calendar event for its participants,
 * all-day or with a time.
 */
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

    @Column(name = "starts_at")
    private LocalTime startsAt;

    @Column(name = "ends_at")
    private LocalTime endsAt;

    @Column(length = 150)
    private String location;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "school_exam_member", joinColumns = @JoinColumn(name = "exam_id"))
    @Column(name = "member_id", nullable = false)
    private Set<Long> memberIds = new HashSet<>();

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

    /** Time and location; without a start the appointment is all-day. */
    public void schedule(LocalTime startsAt, LocalTime endsAt, String location) {
        this.startsAt = startsAt;
        this.endsAt = startsAt == null ? null : endsAt;
        this.location = location;
    }

    public void setMemberIds(Set<Long> memberIds) {
        this.memberIds.clear();
        this.memberIds.addAll(memberIds);
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

    /** How many days ahead an extra study reminder is sent (0 = only the day before). */
    public int getStudyDays() {
        return studyDays;
    }

    public Long getEventId() {
        return eventId;
    }

    public LocalTime getStartsAt() {
        return startsAt;
    }

    public LocalTime getEndsAt() {
        return endsAt;
    }

    public String getLocation() {
        return location;
    }

    public boolean isAllDay() {
        return startsAt == null;
    }

    public Set<Long> getMemberIds() {
        return Set.copyOf(memberIds);
    }
}
