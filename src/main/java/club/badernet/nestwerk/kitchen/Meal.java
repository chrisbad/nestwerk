package club.badernet.nestwerk.kitchen;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Ein geplantes Essen an einem Tag in einer Mahlzeit: ein Rezept oder nur ein Titel („Reste“, „Pizza bestellen“). */
@Entity
@Table(name = "meals")
public class Meal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate day;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "slot_id")
    private MealSlot slot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id")
    private Recipe recipe;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "cooked_at")
    private Instant cookedAt;

    @Version
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "meal_eaters", joinColumns = @JoinColumn(name = "meal_id"))
    private List<MealEater> eaters = new ArrayList<>();

    protected Meal() {
    }

    public Meal(LocalDate day, MealSlot slot, Recipe recipe, String title) {
        this.day = day;
        this.slot = slot;
        this.recipe = recipe;
        this.title = title;
    }

    public void putEater(Long memberId, double portions) {
        eaters.removeIf(e -> e.getMemberId().equals(memberId));
        eaters.add(new MealEater(memberId, portions));
    }

    public void removeEater(Long memberId) {
        eaters.removeIf(e -> e.getMemberId().equals(memberId));
    }

    public double totalPortions() {
        return eaters.stream().mapToDouble(MealEater::getPortions).sum();
    }

    public void moveTo(LocalDate day, MealSlot slot) {
        this.day = day;
        this.slot = slot;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getDay() {
        return day;
    }

    public MealSlot getSlot() {
        return slot;
    }

    public Recipe getRecipe() {
        return recipe;
    }

    public void setRecipe(Recipe recipe) {
        this.recipe = recipe;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getCookedAt() {
        return cookedAt;
    }

    public void setCookedAt(Instant cookedAt) {
        this.cookedAt = cookedAt;
    }

    public boolean isCooked() {
        return cookedAt != null;
    }

    public List<MealEater> getEaters() {
        return eaters;
    }
}
