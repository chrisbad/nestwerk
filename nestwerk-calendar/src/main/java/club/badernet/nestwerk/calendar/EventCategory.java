package club.badernet.nestwerk.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A kind of event (e.g. doctor, sports) that gives its events a colour. */
@Entity
@Table(name = "calendar_categories")
public class EventCategory {

    public static final int MAX_NAME_LENGTH = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategoryColor color;

    protected EventCategory() {
    }

    EventCategory(String name, CategoryColor color) {
        this.name = name;
        this.color = color;
    }

    void change(String name, CategoryColor color) {
        this.name = name;
        this.color = color;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public CategoryColor getColor() { return color; }
}
