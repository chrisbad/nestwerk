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

import java.time.Instant;

/** Gutgeschriebene Minuten eines Mitglieds für eine erledigte Aufgabe. */
@Entity
@Table(name = "chore_credits")
public class ChoreCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "task_id")
    private ChoreTask task;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private int minutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChoreEffort effort;

    @Column(name = "corrected_minutes")
    private Integer correctedMinutes;

    @Column(name = "correction_reason", length = 200)
    private String correctionReason;

    @Column(name = "corrected_by")
    private Long correctedBy;

    @Column(name = "corrected_at")
    private Instant correctedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChoreCredit() {
    }

    public ChoreCredit(ChoreTask task, Long memberId, Instant createdAt) {
        this.task = task;
        this.memberId = memberId;
        this.minutes = task.getChore().getMinutes();
        this.effort = task.getChore().getEffort();
        this.createdAt = createdAt;
    }

    void correct(int minutes, String reason, Long userId, Instant at) {
        this.correctedMinutes = minutes;
        this.correctionReason = reason;
        this.correctedBy = userId;
        this.correctedAt = at;
    }

    /** Wirksame Minuten: korrigiert, sonst Planwert. */
    public int effectiveMinutes() {
        return correctedMinutes != null ? correctedMinutes : minutes;
    }

    public boolean isCorrected() {
        return correctedMinutes != null;
    }

    public Long getId() { return id; }
    public ChoreTask getTask() { return task; }
    public Long getMemberId() { return memberId; }
    public int getMinutes() { return minutes; }
    public ChoreEffort getEffort() { return effort; }
    public Integer getCorrectedMinutes() { return correctedMinutes; }
    public String getCorrectionReason() { return correctionReason; }
    public Long getCorrectedBy() { return correctedBy; }
    public Instant getCorrectedAt() { return correctedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
