package club.badernet.nestwerk.kitchen;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** Ein Posten im Vorrat: Menge eines Lebensmittels an einem Ort, optional mit Mindesthaltbarkeit. */
@Entity
@Table(name = "pantry_items")
public class PantryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "food_id")
    private Food food;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private double amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Unit unit;

    @Column(length = 50)
    private String location;

    @Column(name = "best_before")
    private LocalDate bestBefore;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt = Instant.now();

    protected PantryItem() {
    }

    public PantryItem(Food food, String name, double amount, Unit unit, String location, LocalDate bestBefore) {
        this.food = food;
        this.name = name;
        this.amount = amount;
        this.unit = unit;
        this.location = location;
        this.bestBefore = bestBefore;
    }

    public void change(double amount, Unit unit, String location, LocalDate bestBefore) {
        this.amount = amount;
        this.unit = unit;
        this.location = location;
        this.bestBefore = bestBefore;
    }

    public Long getId() {
        return id;
    }

    public Food getFood() {
        return food;
    }

    public String getName() {
        return name;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public Unit getUnit() {
        return unit;
    }

    public String getLocation() {
        return location;
    }

    public LocalDate getBestBefore() {
        return bestBefore;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
