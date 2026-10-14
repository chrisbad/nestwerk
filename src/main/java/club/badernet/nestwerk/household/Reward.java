package club.badernet.nestwerk.household;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Eine Belohnung, die mit gesammelten Minuten eingelöst werden kann. */
@Entity
@Table(name = "rewards")
public class Reward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(name = "cost_minutes", nullable = false)
    private int costMinutes;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Reward() {
    }

    public Reward(String title, String description, int costMinutes) {
        change(title, description, costMinutes, true);
    }

    void change(String title, String description, int costMinutes, boolean active) {
        this.title = title;
        this.description = description;
        this.costMinutes = costMinutes;
        this.active = active;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public int getCostMinutes() { return costMinutes; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
