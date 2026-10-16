package club.badernet.nestwerk.blocker;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalTime;

/**
 * Ruhe-Ziel einer Person je Woche: {@link Kind#WINDOWS} zählt Tage, an denen das Fenster frei von Terminen bleibt
 * (z. B. „2 freie Abende“), {@link Kind#HOURS} die Minuten in Ruhe-Blockern ohne Termine (z. B. „6 Stunden“).
 */
@Entity
@Table(name = "rest_goals")
public class RestGoal {

    public enum Kind { WINDOWS, HOURS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(name = "window_start")
    private LocalTime windowStart;

    @Column(name = "window_end")
    private LocalTime windowEnd;

    /** Anzahl freier Fenster bzw. Minuten je Woche. */
    @Column(nullable = false)
    private int target;

    @Column(nullable = false, length = 100)
    private String label;

    protected RestGoal() {
    }

    public RestGoal(Long memberId, Kind kind, LocalTime windowStart, LocalTime windowEnd, int target, String label) {
        this.memberId = memberId;
        update(kind, windowStart, windowEnd, target, label);
    }

    public void update(Kind kind, LocalTime windowStart, LocalTime windowEnd, int target, String label) {
        this.kind = kind;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.target = target;
        this.label = label;
    }

    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public Kind getKind() { return kind; }
    public LocalTime getWindowStart() { return windowStart; }
    public LocalTime getWindowEnd() { return windowEnd; }
    public int getTarget() { return target; }
    public String getLabel() { return label; }
}
