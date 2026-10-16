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

/** Eine Zutat: Menge und Einheit eines Lebensmittels; ohne Lebensmittel zählt nur der Text. */
@Entity
@Table(name = "recipe_ingredients")
public class RecipeIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id")
    private Recipe recipe;

    @Column(nullable = false)
    private int position;

    private Double amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Unit unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_id")
    private Food food;

    @Column(nullable = false, length = 300)
    private String text;

    @Column(length = 200)
    private String note;

    protected RecipeIngredient() {
    }

    public RecipeIngredient(Double amount, Unit unit, Food food, String text, String note) {
        this.amount = amount;
        this.unit = unit;
        this.food = food;
        this.text = text;
        this.note = note;
    }

    void attach(Recipe recipe, int position) {
        this.recipe = recipe;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public Recipe getRecipe() {
        return recipe;
    }

    public int getPosition() {
        return position;
    }

    public Double getAmount() {
        return amount;
    }

    public Unit getUnit() {
        return unit;
    }

    public Food getFood() {
        return food;
    }

    public String getText() {
        return text;
    }

    public String getNote() {
        return note;
    }
}
