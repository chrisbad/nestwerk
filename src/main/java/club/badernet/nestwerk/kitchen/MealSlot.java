package club.badernet.nestwerk.kitchen;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Eine Mahlzeit des Tages (Frühstück, Abendessen …) mit ihrem Anteil am Tagesbedarf in Prozent. */
@Entity
@Table(name = "meal_slots")
public class MealSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private int share;

    @Column(name = "auto_fill", nullable = false)
    private boolean autoFill;

    protected MealSlot() {
    }

    public MealSlot(String name, int sortOrder, int share, boolean autoFill) {
        this.name = name;
        this.sortOrder = sortOrder;
        this.share = share;
        this.autoFill = autoFill;
    }

    public void change(String name, int sortOrder, int share, boolean autoFill) {
        this.name = name;
        this.sortOrder = sortOrder;
        this.share = share;
        this.autoFill = autoFill;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public int getShare() {
        return share;
    }

    public boolean isAutoFill() {
        return autoFill;
    }
}
