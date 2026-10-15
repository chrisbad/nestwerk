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

/** Ein Antrag, eine Belohnung für ein Mitglied einzulösen. Der Preis wird beim Antrag festgehalten. */
@Entity
@Table(name = "reward_redemptions")
public class Redemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "reward_id")
    private Reward reward;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "cost_minutes", nullable = false)
    private int costMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RedemptionStatus status = RedemptionStatus.REQUESTED;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    private long version;

    protected Redemption() {
    }

    public Redemption(Reward reward, Long memberId, Long requestedBy, Instant requestedAt) {
        this.reward = reward;
        this.memberId = memberId;
        this.costMinutes = reward.getCostMinutes();
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
    }

    void decide(boolean approve, Long userId, Instant at) {
        this.status = approve ? RedemptionStatus.APPROVED : RedemptionStatus.REJECTED;
        this.decidedBy = userId;
        this.decidedAt = at;
    }

    public boolean isPending() {
        return status == RedemptionStatus.REQUESTED;
    }

    public Long getId() { return id; }
    public Reward getReward() { return reward; }
    public Long getMemberId() { return memberId; }
    public int getCostMinutes() { return costMinutes; }
    public RedemptionStatus getStatus() { return status; }
    public Long getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public Long getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
}
