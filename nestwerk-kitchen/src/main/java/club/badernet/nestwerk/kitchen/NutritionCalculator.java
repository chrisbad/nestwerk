package club.badernet.nestwerk.kitchen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Nährwerte aus den Zutaten. Zutaten ohne Menge („Salz nach Geschmack“) zählen nicht und fehlen auch nicht; Zutaten mit
 * Menge, die sich nicht in Gramm umrechnen lassen oder kein Lebensmittel mit Nährwerten haben, werden gemeldet.
 */
public final class NutritionCalculator {

    private NutritionCalculator() {
    }

    public static RecipeNutrition forRecipe(Recipe recipe) {
        Nutrition total = Nutrition.ZERO;
        List<String> missing = new ArrayList<>();
        for (RecipeIngredient i : recipe.getIngredients()) {
            if (i.getAmount() == null) {
                continue;
            }
            Optional<Nutrition> n = forIngredient(i);
            if (n.isPresent()) {
                total = total.plus(n.get());
            } else {
                missing.add(i.getText());
            }
        }
        return new RecipeNutrition(total, total.times(1.0 / Math.max(1, recipe.getServings())), List.copyOf(missing));
    }

    public static Optional<Nutrition> forIngredient(RecipeIngredient i) {
        Food food = i.getFood();
        if (i.getAmount() == null || food == null || !food.hasNutrition()) {
            return Optional.empty();
        }
        return Quantities.grams(i.getAmount(), i.getUnit(), food).map(g -> Nutrition.of(food, g));
    }

    /** Menge einer Zutat für eine andere Portionenzahl. */
    public static Double amountFor(RecipeIngredient i, int servings, double wantedServings) {
        if (i.getAmount() == null) {
            return null;
        }
        return i.getAmount() * wantedServings / Math.max(1, servings);
    }
}
