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
import java.util.UUID;

/**
 * Ein konkretes Vorkommen einer Hausarbeit mit Fälligkeit und Zuständigem. Beim Buchen wandert die Aufgabe auf den
 * Zeitpunkt der Durchführung; der {@code slotKey} behält den geplanten Termin.
 */
@Entity
@Table(name = "chore_tasks")
public class ChoreTask {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final String AD_HOC = "adhoc-";

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

    /** Vom Planer gewählte Uhrzeit in einer freien Lücke der zuständigen Person (leer = keine gefunden). */
    @Column(name = "planned_at")
    private LocalTime plannedAt;

    /** The time was set by hand (e.g. moved in the calendar); automatic re-planning keeps it. */
    @Column(name = "planned_manually", nullable = false)
    private boolean plannedManually;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChoreTaskStatus status = ChoreTaskStatus.OPEN;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by_user")
    private Long completedByUser;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ChoreEffort effort;

    @Column(name = "booked_at")
    private Instant bookedAt;

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

    /** Ungeplante Aufgabe, die direkt gebucht wird (z. B. bei Hausarbeiten „bei Bedarf“). */
    static ChoreTask adHoc(Chore chore, LocalDate day, LocalTime time) {
        ChoreTask task = new ChoreTask(chore, day, time, null);
        task.slotKey = AD_HOC + UUID.randomUUID().toString().replace("-", "");
        return task;
    }

    /**
     * Einmalige, von Hand eingeplante Aufgabe (z. B. aus dem Kalender), unabhängig vom Rhythmus der Hausarbeit. Sie
     * zählt wie eine ungeplant gebuchte: die Planung verteilt sie nicht um, und nach „Rückgängig“ verschwindet sie.
     */
    static ChoreTask scheduledOnce(Chore chore, LocalDate day, LocalTime time, Long assigneeMemberId) {
        ChoreTask task = new ChoreTask(chore, day, time, assigneeMemberId);
        task.slotKey = AD_HOC + UUID.randomUUID().toString().replace("-", "");
        task.planManually(time);
        return task;
    }

    /** Eindeutiger Schlüssel des Termins innerhalb der Hausarbeit (macht die Planung idempotent). */
    public static String slotKey(LocalDate dueOn, LocalTime dueTime) {
        return dueTime == null ? dueOn.toString() : dueOn + "T" + dueTime.format(TIME);
    }

    void assign(Long memberId) {
        this.assigneeMemberId = memberId;
    }

    void plan(Long memberId, LocalTime at) {
        this.assigneeMemberId = memberId;
        this.plannedAt = at;
        this.plannedManually = false;
    }

    void planManually(LocalTime at) {
        this.plannedAt = at;
        this.plannedManually = true;
    }

    /** Moves the open task to another day and time by hand; the slot key keeps the date the plan gave it. */
    void moveManually(LocalDate day, LocalTime at) {
        this.dueOn = day;
        planManually(at);
    }

    /** Drops the open task for its date; planning keeps it as a placeholder and does not create it again. */
    void cancel() {
        this.status = ChoreTaskStatus.CANCELLED;
    }

    /** Marks the open task as not necessary for its date; who and when are kept so that it can be undone. */
    void skip(Instant at, Long userId) {
        this.status = ChoreTaskStatus.SKIPPED;
        this.completedAt = at;
        this.completedByUser = userId;
    }

    /** Bucht die Aufgabe und verschiebt sie auf den Zeitpunkt der Durchführung. */
    void complete(Instant performedAt, LocalDate day, LocalTime time, ChoreEffort effort, Instant bookedAt,
                  Long userId) {
        this.status = ChoreTaskStatus.DONE;
        this.completedAt = performedAt;
        this.dueOn = day;
        this.dueTime = time;
        this.effort = effort;
        this.bookedAt = bookedAt;
        this.completedByUser = userId;
    }

    /** Wieder offen, zurück auf dem geplanten Termin. */
    void reopen() {
        this.status = ChoreTaskStatus.OPEN;
        this.completedAt = null;
        this.completedByUser = null;
        this.effort = null;
        this.bookedAt = null;
        if (!isAdHoc()) {
            int t = slotKey.indexOf('T');
            this.dueOn = LocalDate.parse(t < 0 ? slotKey : slotKey.substring(0, t));
            this.dueTime = t < 0 ? null : LocalTime.parse(slotKey.substring(t + 1));
        }
    }

    public boolean isAdHoc() {
        return slotKey.startsWith(AD_HOC);
    }

    void markNotifiedDue() {
        this.notifiedDue = true;
    }

    public boolean isOpen() {
        return status == ChoreTaskStatus.OPEN;
    }

    public boolean isSkipped() {
        return status == ChoreTaskStatus.SKIPPED;
    }

    public boolean isDone() {
        return status == ChoreTaskStatus.DONE;
    }

    public boolean isCancelled() {
        return status == ChoreTaskStatus.CANCELLED;
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
    public LocalTime getPlannedAt() { return plannedAt; }
    public boolean isPlannedManually() { return plannedManually; }
    public ChoreTaskStatus getStatus() { return status; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getCompletedByUser() { return completedByUser; }
    public ChoreEffort getEffort() { return effort; }
    public Instant getBookedAt() { return bookedAt; }
    public boolean isNotifiedDue() { return notifiedDue; }
    public long getVersion() { return version; }
}
