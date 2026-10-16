package club.badernet.nestwerk.kitchen;

/** Eingaben für ein Lebensmittel; Nährwerte pro 100 g, Gewichte in g. */
public record FoodInput(String name, FoodCategory category, Double kcal, Double protein, Double fat, Double carbs,
                        Double pieceGrams, Double packageGrams, Integer shelfDays, String barcode) {
}
