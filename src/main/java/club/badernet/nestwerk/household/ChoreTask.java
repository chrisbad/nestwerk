package club.badernet.nestwerk.household;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Ein konkretes Vorkommen einer Hausarbeit mit Fälligkeit und Zuständigem. */
@Entity
@Table(name = "chore_tasks")
public class ChoreTask {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "chore_id")
    private Chore chore;

    @Column(name = "slot_key", nullable = false, length = 40)
    private String slotKey;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "due_time")
    private LocalTime dueTime;

    @Column(name = "assignee_member_id")
    private Long assigneeMemberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChoreTaskStatus status = ChoreTaskStatus.OPEN;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by_user")
    private Long completedByUser;

    @Column(name = "notified_due", nullable = false)
    private boolean notifiedDue;

    @Version
    private long version;

    protected ChoreTask() {
    }

    public ChoreTask(Chore chore, LocalDate dueOn, LocalTime dueTime, Long assigneeMemberId) {
        this.chore = chore;
        this.dueOn = dueOn;
        this.dueTime = dueTime;
        this.slotKey = slotKey(dueOn, dueTime);
        this.assigneeMemberId = assigneeMemberId;
    }

    /** Eindeutiger Schlüssel des Termins innerhalb der Hausarbeit (macht die Planung idempotent). */
    public static String slotKey(LocalDate dueOn, LocalTime dueTime) {
        return dueTime == null ? dueOn.toString() : dueOn + "T" + dueTime.format(TIME);
    }

    void assign(Long memberId) {
        this.assigneeMemberId = memberId;
    }

    void complete(Instant at, Long userId) {
        this.status = ChoreTaskStatus.DONE;
        this.completedAt = at;
        this.completedByUser = userId;
    }

    void reopen() {
        this.status = ChoreTaskStatus.OPEN;
        this.completedAt = null;
        this.completedByUser = null;
    }

    void markNotifiedDue() {
        this.notifiedDue = true;
    }

    public boolean isOpen() {
        return status == ChoreTaskStatus.OPEN;
    }

    public boolean isOverdue(LocalDate today) {
        return isOpen() && dueOn.isBefore(today);
    }

    public String timeLabel() {
        return dueTime == null ? null : dueTime.format(TIME);
    }

    public Long getId() { return id; }
    public Chore getChore() { return chore; }
    public String getSlotKey() { return slotKey; }
    public LocalDate getDueOn() { return dueOn; }
    public LocalTime getDueTime() { return dueTime; }
    public Long getAssigneeMemberId() { return assigneeMemberId; }
    public ChoreTaskStatus getStatus() { return status; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getCompletedByUser() { return completedByUser; }
    public boolean isNotifiedDue() { return notifiedDue; }
    public long getVersion() { return version; }
}
