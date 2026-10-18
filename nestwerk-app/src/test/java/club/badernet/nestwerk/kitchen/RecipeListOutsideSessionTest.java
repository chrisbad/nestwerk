package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Rezeptlisten werden in Controllern ohne offene Sitzung ausgewertet (open-in-view ist aus). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.kitchen.seed-recipes=true"})
class RecipeListOutsideSessionTest {

    @Autowired RecipeRepository recipes;

    @Test
    void allRecipesAndSearchResultsCarryTheirIngredientsAndFoods() {
        List<Recipe> all = recipes.findAllByOrderByTitleAsc();
        assertThat(all).hasSizeGreaterThan(50).doesNotHaveDuplicates();
        all.forEach(r -> assertThat(NutritionCalculator.forRecipe(r).perServing().kcal()).isPositive());

        List<Recipe> found = recipes.search("vegan");
        assertThat(found).isNotEmpty();
        found.forEach(r -> assertThat(NutritionCalculator.forRecipe(r).missing()).isEmpty());

        List<Long> foodIds = all.stream().flatMap(r -> r.getIngredients().stream()).map(RecipeIngredient::getFood)
                .filter(java.util.Objects::nonNull).map(Food::getId).distinct().limit(5).toList();
        List<Recipe> using = recipes.usingFoods(foodIds);
        assertThat(using).isNotEmpty().doesNotHaveDuplicates();
        using.forEach(r -> assertThat(NutritionCalculator.forRecipe(r).perServing().kcal()).isPositive());
    }
}
