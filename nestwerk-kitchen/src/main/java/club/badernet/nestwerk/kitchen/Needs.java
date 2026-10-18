package club.badernet.nestwerk.kitchen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Rechnet den Einkaufsbedarf aus: Zutaten aller Mahlzeiten je Lebensmittel in Gramm (Stück, wenn nur in Stück
 * gebraucht), minus Vorrat, auf sinnvolle Schritte aufgerundet. Zutaten ohne Lebensmittel werden je Text
 * zusammengefasst und nicht gegen den Vorrat gerechnet.
 */
public final class Needs {

    static final double EPSILON = 1e-6;
    static final double STEP_GRAMS = 50;

    /** Ein Posten, der gekauft werden soll. */
    public record Need(String name, Food food, Double amount, Unit unit) {
    }

    private static final class Acc {
        double grams;
        boolean pieceOnly = true;
        final Map<Unit, Double> other = new LinkedHashMap<>();
    }

    private Needs() {
    }

    public static List<Need> compute(List<Meal> meals, Function<Food, List<PantryItem>> stock) {
        Map<Food, Acc> byFood = new LinkedHashMap<>();
        Map<String, Need> free = new LinkedHashMap<>();
        for (Meal meal : meals) {
            Recipe recipe = meal.getRecipe();
            if (recipe == null || meal.isCooked() || meal.totalPortions() <= 0) {
                continue;
            }
            double factor = meal.totalPortions() / Math.max(1, recipe.getServings());
            for (RecipeIngredient i : recipe.getIngredients()) {
                if (i.getAmount() == null) {
                    continue;
                }
                double amount = i.getAmount() * factor;
                Unit unit = i.getUnit() == null ? Unit.PIECE : i.getUnit();
                if (i.getFood() == null) {
                    free.merge(i.getText().toLowerCase(Locale.GERMAN) + "|" + unit,
                            new Need(i.getText(), null, amount, unit),
                            (a, b) -> new Need(a.name(), null, a.amount() + b.amount(), a.unit()));
                    continue;
                }
                Acc acc = byFood.computeIfAbsent(i.getFood(), f -> new Acc());
                var grams = Quantities.grams(amount, unit, i.getFood());
                if (grams.isPresent()) {
                    acc.grams += grams.get();
                    acc.pieceOnly &= unit == Unit.PIECE;
                } else {
                    acc.other.merge(unit, amount, Double::sum);
                }
            }
        }
        List<Need> result = new ArrayList<>();
        byFood.forEach((food, acc) -> need(food, acc, stock.apply(food), result));
        for (Need n : free.values()) {
            result.add(new Need(n.name(), null, Math.ceil(n.amount() * 10 - EPSILON) / 10, n.unit()));
        }
        return result;
    }

    private static void need(Food food, Acc acc, List<PantryItem> stock, List<Need> out) {
        double grams = acc.grams;
        Map<Unit, Double> other = new LinkedHashMap<>(acc.other);
        for (PantryItem item : stock) {
            var inGrams = Quantities.grams(item.getAmount(), item.getUnit(), food);
            if (inGrams.isPresent()) {
                grams -= inGrams.get();
            } else if (other.containsKey(item.getUnit())) {
                other.merge(item.getUnit(), -item.getAmount(), Double::sum);
            }
        }
        if (grams > EPSILON) {
            if (acc.pieceOnly && food.getPieceGrams() != null) {
                out.add(new Need(food.getName(), food, Math.ceil(grams / food.getPieceGrams() - EPSILON), Unit.PIECE));
            } else {
                double step = food.getPackageGrams() != null ? food.getPackageGrams() : STEP_GRAMS;
                out.add(new Need(food.getName(), food, Math.ceil(grams / step - EPSILON) * step, Unit.G));
            }
        }
        other.forEach((unit, amount) -> {
            if (amount > EPSILON) {
                out.add(new Need(food.getName(), food, roundUp(amount), unit));
            }
        });
    }

    private static double roundUp(double amount) {
        return Math.ceil(amount - EPSILON);
    }
}
