package club.badernet.nestwerk.kitchen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static club.badernet.nestwerk.kitchen.QuantitiesTest.food;
import static org.assertj.core.api.Assertions.assertThat;

class FoodMatcherTest {

    final List<Food> foods = List.of(food("Zwiebel", 1.0, null, null), food("Rote Zwiebel", 1.0, null, null),
            food("Knoblauch", 1.0, null, null), food("Ei", 1.0, null, null), food("Olivenöl", 1.0, null, null),
            food("Öl", 1.0, null, null), food("Tomate", 1.0, null, null), food("Champignon", 1.0, null, null),
            food("Mehl", 1.0, null, null));

    String match(String name) {
        return FoodMatcher.best(name, foods).map(Food::getName).orElse(null);
    }

    @Test
    void matchesExactlyIgnoringCaseAndPlurals() {
        assertThat(match("zwiebel")).isEqualTo("Zwiebel");
        assertThat(match("Zwiebeln")).isEqualTo("Zwiebel");
        assertThat(match("Eier")).isEqualTo("Ei");
        assertThat(match("Tomaten")).isEqualTo("Tomate");
        assertThat(match("rote Zwiebeln")).isEqualTo("Rote Zwiebel");
        assertThat(match("Mehl (Type 405)")).isEqualTo("Mehl");
    }

    @Test
    void matchesWordsAndLongestContainedName() {
        assertThat(match("frische Champignons")).isEqualTo("Champignon");
        assertThat(match("Knoblauchzehen")).isEqualTo("Knoblauch");
        assertThat(match("Olivenöl extra vergine")).isEqualTo("Olivenöl");
        assertThat(match("Weißwein")).isNull();
        assertThat(match("Zauberpulver")).isNull();
    }
}
