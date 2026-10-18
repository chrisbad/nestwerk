package club.badernet.nestwerk.kitchen;

/** Eine zerlegte Zutatenzeile; {@code text} ist die Originalzeile. */
public record IngredientLine(Double amount, Unit unit, String name, String note, String text) {
}
