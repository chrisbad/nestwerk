package club.badernet.nestwerk.kitchen;

/** Eine Zutat aus Formular, Import oder MCP; ohne {@code foodId} wird über den Namen zugeordnet. */
public record IngredientInput(Double amount, Unit unit, String name, String note, Long foodId) {

    public static IngredientInput of(IngredientLine line) {
        return new IngredientInput(line.amount(), line.unit(), line.name(), line.note(), null);
    }
}
