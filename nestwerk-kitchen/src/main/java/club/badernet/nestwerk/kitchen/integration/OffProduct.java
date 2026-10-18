package club.badernet.nestwerk.kitchen.integration;

/** Ein Produkt aus Open Food Facts; Nährwerte pro 100 g. */
public record OffProduct(String barcode, String name, String brand, Double kcal, Double protein, Double fat,
                         Double carbs, Double packageGrams) {
}
