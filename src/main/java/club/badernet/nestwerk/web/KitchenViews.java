package club.badernet.nestwerk.web;

import club.badernet.nestwerk.kitchen.Amounts;
import club.badernet.nestwerk.kitchen.Food;
import club.badernet.nestwerk.kitchen.Nutrition;
import club.badernet.nestwerk.kitchen.NutritionCalculator;
import club.badernet.nestwerk.kitchen.Recipe;
import club.badernet.nestwerk.kitchen.RecipeIngredient;
import club.badernet.nestwerk.kitchen.RecipeNutrition;

import java.util.List;

/** Anzeigewerte für Küchen-Seiten. */
final class KitchenViews {

    private KitchenViews() {
    }

    record IngredientView(String amount, String name, String food, String kcal, boolean missing) {
    }

    record NutritionView(String kcal, String protein, String fat, String carbs) {

        static NutritionView of(Nutrition n) {
            return new NutritionView(KitchenViews.kcal(n.kcal()), grams(n.protein()), grams(n.fat()), grams(n.carbs()));
        }
    }

    record RecipeCard(Long id, String title, String kcal, Integer minutes, List<String> tags, Long photoId,
                      boolean complete) {
    }

    static String kcal(double kcal) {
        return Amounts.number(Math.round(kcal)) + " kcal";
    }

    static String grams(double grams) {
        return Amounts.number(Math.round(grams * 10) / 10.0) + " g";
    }

    static List<IngredientView> ingredients(Recipe recipe, double servings) {
        return recipe.getIngredients().stream().map(i -> ingredient(i, recipe.getServings(), servings)).toList();
    }

    private static IngredientView ingredient(RecipeIngredient i, int base, double servings) {
        Double amount = NutritionCalculator.amountFor(i, base, servings);
        Food food = i.getFood();
        String foodName = food == null || food.getName().equalsIgnoreCase(i.getText()) ? null : food.getName();
        boolean missing = i.getAmount() != null && NutritionCalculator.forIngredient(i).isEmpty();
        String kcal = NutritionCalculator.forIngredient(i).map(n -> kcal(n.kcal() * servings / base)).orElse(null);
        return new IngredientView(Amounts.format(amount, amount == null ? null : i.getUnit()), i.getText(),
                foodName, kcal, missing);
    }

    static RecipeCard card(Recipe recipe, Long photoId) {
        RecipeNutrition n = NutritionCalculator.forRecipe(recipe);
        return new RecipeCard(recipe.getId(), recipe.getTitle(), kcal(n.perServing().kcal()), recipe.getMinutes(),
                recipe.tagList(), photoId, n.complete());
    }
}
