package club.badernet.nestwerk.kitchen;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Ein Lebensmittel mit Nährwerten pro 100 g sowie Stückgewicht und Packungsgröße für die Umrechnung. */
@Entity
@Table(name = "foods")
public class Food {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FoodCategory category;

    private Double kcal;
    private Double protein;
    private Double fat;
    private Double carbs;

    @Column(name = "piece_grams")
    private Double pieceGrams;

    @Column(name = "package_grams")
    private Double packageGrams;

    @Column(name = "shelf_days")
    private Integer shelfDays;

    @Column(length = 32, unique = true)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FoodSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Food() {
    }

    public Food(String name, FoodCategory category, FoodSource source) {
        this.name = name;
        this.category = category;
        this.source = source;
    }

    public void setNutrition(Double kcal, Double protein, Double fat, Double carbs) {
        this.kcal = kcal;
        this.protein = protein;
        this.fat = fat;
        this.carbs = carbs;
    }

    public boolean hasNutrition() {
        return kcal != null;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public FoodCategory getCategory() {
        return category;
    }

    public void setCategory(FoodCategory category) {
        this.category = category;
    }

    public Double getKcal() {
        return kcal;
    }

    public Double getProtein() {
        return protein;
    }

    public Double getFat() {
        return fat;
    }

    public Double getCarbs() {
        return carbs;
    }

    public Double getPieceGrams() {
        return pieceGrams;
    }

    public void setPieceGrams(Double pieceGrams) {
        this.pieceGrams = pieceGrams;
    }

    public Double getPackageGrams() {
        return packageGrams;
    }

    public void setPackageGrams(Double packageGrams) {
        this.packageGrams = packageGrams;
    }

    public Integer getShelfDays() {
        return shelfDays;
    }

    public void setShelfDays(Integer shelfDays) {
        this.shelfDays = shelfDays;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public FoodSource getSource() {
        return source;
    }

    public void setSource(FoodSource source) {
        this.source = source;
    }
}
