package club.badernet.nestwerk.kitchen;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Ein Rezept mit Zutaten in fester Reihenfolge, Schritten (Markdown) und Portionen. */
@Entity
@Table(name = "recipes")
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    private String description;

    @Column(nullable = false)
    private int servings;

    private Integer minutes;

    @Column(length = 500)
    private String tags;

    @Column(name = "source_url", length = 2000)
    private String sourceUrl;

    private String steps;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<RecipeIngredient> ingredients = new ArrayList<>();

    protected Recipe() {
    }

    public Recipe(String title, int servings, Long createdBy) {
        this.title = title;
        this.servings = servings;
        this.createdBy = createdBy;
    }

    public void addIngredient(RecipeIngredient ingredient) {
        ingredient.attach(this, ingredients.size());
        ingredients.add(ingredient);
    }

    public void replaceIngredients(List<RecipeIngredient> replacement) {
        ingredients.clear();
        replacement.forEach(this::addIngredient);
    }

    public List<String> tagList() {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getServings() {
        return servings;
    }

    public void setServings(int servings) {
        this.servings = servings;
    }

    public Integer getMinutes() {
        return minutes;
    }

    public void setMinutes(Integer minutes) {
        this.minutes = minutes;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getSteps() {
        return steps;
    }

    public void setSteps(String steps) {
        this.steps = steps;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<RecipeIngredient> getIngredients() {
        return ingredients;
    }
}
