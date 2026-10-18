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

/** Ein Artikel der Einkaufsliste. */
@Entity
@Table(name = "shopping_items")
public class ShoppingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "food_id")
    private Food food;

    private Double amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Unit unit;

    @Column(length = 200)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ShoppingOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ShoppingStatus status = ShoppingStatus.OPEN;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "bought_at")
    private Instant boughtAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ShoppingItem() {
    }

    private ShoppingItem(String name, Food food, Double amount, Unit unit, ShoppingOrigin origin) {
        this.name = name;
        this.food = food;
        this.amount = amount;
        this.unit = unit;
        this.origin = origin;
    }

    public static ShoppingItem manual(String name, Food food, Double amount, Unit unit) {
        return new ShoppingItem(name, food, amount, unit, ShoppingOrigin.MANUAL);
    }

    public static ShoppingItem fromPlan(String name, Food food, Double amount, Unit unit) {
        return new ShoppingItem(name, food, amount, unit, ShoppingOrigin.PLAN);
    }

    public void markSent(Instant at) {
        status = ShoppingStatus.SENT;
        sentAt = at;
    }

    public void markBought(Instant at) {
        status = ShoppingStatus.BOUGHT;
        boughtAt = at;
    }

    public void markOpen() {
        status = ShoppingStatus.OPEN;
        boughtAt = null;
    }

    public void markStored() {
        status = ShoppingStatus.STORED;
    }

    /** „500 g“, „2 Stück“ oder leer. */
    public String specification() {
        return Amounts.format(amount, unit);
    }

    /** Ordnet dem Artikel ein Lebensmittel zu (z. B. nach dem Scan des Produkts). */
    public void assign(Food food) {
        this.food = food;
    }

    public void change(Double amount, Unit unit) {
        this.amount = amount;
        this.unit = unit;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Food getFood() {
        return food;
    }

    public Double getAmount() {
        return amount;
    }

    public Unit getUnit() {
        return unit;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public ShoppingOrigin getOrigin() {
        return origin;
    }

    public ShoppingStatus getStatus() {
        return status;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getBoughtAt() {
        return boughtAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
