package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.notification.AppSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw", "nestwerk.kitchen.seed-recipes=true"})
@Transactional
class RecipeSeederTest {

    @Autowired RecipeRepository recipes;
    @Autowired RecipeSeeder seeder;
    @Autowired AppSettings settings;

    @Test
    void loadsTheStarterRecipesOnceAndRemembersIt() throws Exception {
        long count = recipes.count();
        assertThat(count).isBetween(55L, 75L);
        assertThat(settings.get(RecipeSeeder.MARKER)).contains(String.valueOf(count));

        recipes.delete(recipes.findAllByOrderByTitleAsc().getFirst());
        seeder.run(new DefaultApplicationArguments());

        assertThat(recipes.count()).isEqualTo(count - 1);
    }

    @Test
    void starterRecipesAreComplete() {
        List<String> problems = new ArrayList<>();
        int vegetarian = 0;
        int quick = 0;
        List<Recipe> all = recipes.findAllByOrderByTitleAsc();
        for (Recipe r : all) {
            String tags = r.getTags() == null ? "" : r.getTags();
            if (tags.contains("vegetarisch") || tags.contains("vegan")) {
                vegetarian++;
            } else if (!tags.contains("Fleisch") && !tags.contains("Fisch")) {
                problems.add(r.getTitle() + ": Schlagwort vegetarisch/vegan/Fleisch/Fisch fehlt");
            }
            if (r.getMinutes() != null && r.getMinutes() <= 30) {
                quick++;
            }
            if (r.getIngredients().size() < 3 || r.getSteps() == null || r.getSteps().isBlank()) {
                problems.add(r.getTitle() + ": zu wenig Zutaten oder keine Schritte");
            }
            r.getIngredients().stream().filter(i -> i.getFood() == null)
                    .forEach(i -> problems.add(r.getTitle() + ": Zutat ohne Lebensmittel: " + i.getText()));
            RecipeNutrition nutrition = NutritionCalculator.forRecipe(r);
            if (!nutrition.missing().isEmpty()) {
                problems.add(r.getTitle() + ": Nährwerte unvollständig: " + nutrition.missing());
            }
            double kcal = nutrition.perServing().kcal();
            if (kcal < 150 || kcal > 1200) {
                problems.add(r.getTitle() + ": " + Math.round(kcal) + " kcal je Portion");
            }
        }
        assertThat(problems).isEmpty();
        assertThat(vegetarian * 100.0 / all.size()).isBetween(70.0, 80.0);
        assertThat(quick).isGreaterThanOrEqualTo(15);
    }
}
