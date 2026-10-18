package club.badernet.nestwerk.kitchen;

import org.junit.jupiter.api.Test;

import static club.badernet.nestwerk.kitchen.QuantitiesTest.food;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NutritionCalculatorTest {

    @Test
    void sumsIngredientsAndDividesByServings() {
        Recipe recipe = new Recipe("Test", 2, null);
        recipe.addIngredient(new RecipeIngredient(200.0, Unit.G, food("Mehl", 340.0, null, null), "200 g Mehl", null));
        recipe.addIngredient(new RecipeIngredient(2.0, Unit.TBSP, food("Öl", 884.0, null, null), "2 EL Öl", null));
        recipe.addIngredient(new RecipeIngredient(null, null, food("Salz", 0.0, null, null), "Salz", null));

        RecipeNutrition n = NutritionCalculator.forRecipe(recipe);

        assertThat(n.total().kcal()).isCloseTo(680 + 265.2, within(0.01));
        assertThat(n.total().protein()).isCloseTo(2.3, within(0.01));
        assertThat(n.perServing().kcal()).isCloseTo((680 + 265.2) / 2, within(0.01));
        assertThat(n.missing()).isEmpty();
        assertThat(n.complete()).isTrue();
    }

    @Test
    void unconvertibleOrUnknownIngredientsAreReportedNotCountedAsZero() {
        Recipe recipe = new Recipe("Test", 1, null);
        recipe.addIngredient(new RecipeIngredient(1.0, Unit.PIECE, food("Kürbis", 30.0, null, null), "1 Kürbis", null));
        recipe.addIngredient(new RecipeIngredient(100.0, Unit.G, null, "100 g Zauberpulver", null));
        recipe.addIngredient(new RecipeIngredient(100.0, Unit.G, food("Neu", null, null, null), "100 g Neu", null));
        recipe.addIngredient(new RecipeIngredient(100.0, Unit.G, food("Mehl", 340.0, null, null), "100 g Mehl", null));

        RecipeNutrition n = NutritionCalculator.forRecipe(recipe);

        assertThat(n.total().kcal()).isCloseTo(340, within(0.01));
        assertThat(n.missing()).containsExactly("1 Kürbis", "100 g Zauberpulver", "100 g Neu");
        assertThat(n.complete()).isFalse();
    }

    @Test
    void scalesToOtherServings() {
        Recipe recipe = new Recipe("Test", 4, null);
        recipe.addIngredient(new RecipeIngredient(400.0, Unit.G, food("Mehl", 100.0, null, null), "400 g Mehl", null));
        assertThat(NutritionCalculator.forRecipe(recipe).perServing().kcal()).isCloseTo(100, within(0.01));
        assertThat(NutritionCalculator.amountFor(recipe.getIngredients().getFirst(), 4, 6)).isEqualTo(600.0);
    }
}
