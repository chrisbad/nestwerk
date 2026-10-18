package club.badernet.nestwerk.kitchen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static club.badernet.nestwerk.kitchen.QuantitiesTest.food;
import static org.assertj.core.api.Assertions.assertThat;

class AutoFillTest {

    static Recipe recipe(long id, String title, Integer minutes, Food food, double grams) {
        Recipe r = new Recipe(title, 1, null);
        org.springframework.test.util.ReflectionTestUtils.setField(r, "id", id);
        r.setMinutes(minutes);
        r.addIngredient(new RecipeIngredient(grams, Unit.G, food, food.getName(), null));
        return r;
    }

    static Food withId(Food f, long id) {
        org.springframework.test.util.ReflectionTestUtils.setField(f, "id", id);
        return f;
    }

    final Food pasta = withId(food("Nudeln", 350.0, null, null), 1);
    final Food spinach = withId(food("Spinat", 25.0, null, null), 2);
    final Recipe slowPasta = recipe(10, "Lasagne", 90, pasta, 200);       // 700 kcal
    final Recipe quickPasta = recipe(11, "Aglio e Olio", 20, pasta, 150);  // 525 kcal
    final Recipe spinachDish = recipe(12, "Spinatpfanne", 25, spinach, 400); // 100 kcal

    @Test
    void avoidsRecentRecipes() {
        assertThat(AutoFill.pick(List.of(slowPasta, quickPasta), Set.of(10L), Set.of(), false, null, new Random(1)))
                .contains(quickPasta);
        assertThat(AutoFill.pick(List.of(slowPasta), Set.of(10L), Set.of(), false, null, new Random(1))).isEmpty();
    }

    @Test
    void prefersRecipesUsingExpiringFood() {
        for (int seed = 0; seed < 20; seed++) {
            assertThat(AutoFill.pick(List.of(slowPasta, quickPasta, spinachDish), Set.of(), Set.of(2L), false, null,
                    new Random(seed))).contains(spinachDish);
        }
    }

    @Test
    void busyDaysOnlyGetQuickRecipes() {
        for (int seed = 0; seed < 20; seed++) {
            assertThat(AutoFill.pick(List.of(slowPasta, quickPasta), Set.of(), Set.of(), true, null, new Random(seed)))
                    .contains(quickPasta);
        }
        assertThat(AutoFill.pick(List.of(slowPasta), Set.of(), Set.of(), true, null, new Random(1))).isEmpty();
    }

    @Test
    void prefersPortionsNearTheGoal() {
        for (int seed = 0; seed < 20; seed++) {
            assertThat(AutoFill.pick(List.of(slowPasta, spinachDish), Set.of(), Set.of(), false, 650.0,
                    new Random(seed))).contains(slowPasta);
        }
    }
}
