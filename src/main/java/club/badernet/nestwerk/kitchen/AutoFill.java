package club.badernet.nestwerk.kitchen;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wählt ein Rezept für eine leere Mahlzeit: nichts, was kürzlich oder diese Woche schon dran war; an vollen Tagen nur
 * Schnelles (≤ 30 min); Pluspunkte für Rezepte, die bald ablaufenden Vorrat verbrauchen und deren Portion nahe am
 * Kalorienziel liegt; der Zufall entscheidet bei Gleichstand.
 */
public final class AutoFill {

    public static final int QUICK_MINUTES = 30;
    private static final double EXPIRING_BONUS = 10;

    private AutoFill() {
    }

    public static Optional<Recipe> pick(List<Recipe> recipes, Set<Long> avoid, Set<Long> expiringFoodIds,
                                        boolean busy, Double targetKcal, Random random) {
        Map<Recipe, Double> scores = recipes.stream()
                .filter(r -> !avoid.contains(r.getId()))
                .filter(r -> !busy || (r.getMinutes() != null && r.getMinutes() <= QUICK_MINUTES))
                .collect(Collectors.toMap(Function.identity(), r -> score(r, expiringFoodIds, targetKcal, random)));
        return scores.entrySet().stream().max(Comparator.comparingDouble(Map.Entry::getValue)).map(Map.Entry::getKey);
    }

    private static double score(Recipe recipe, Collection<Long> expiring, Double targetKcal, Random random) {
        double score = random.nextDouble() * 0.5;
        long uses = recipe.getIngredients().stream()
                .filter(i -> i.getFood() != null && expiring.contains(i.getFood().getId())).count();
        score += uses * EXPIRING_BONUS;
        if (targetKcal != null) {
            double kcal = NutritionCalculator.forRecipe(recipe).perServing().kcal();
            if (kcal > 0) {
                score -= Math.abs(kcal - targetKcal) / 100.0;
            }
        }
        return score;
    }
}
