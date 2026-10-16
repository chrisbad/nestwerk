package club.badernet.nestwerk.kitchen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeSeedParserTest {

    @Test
    void readsTitleMetaIngredientsAndSteps() {
        List<RecipeInput> recipes = RecipeSeedParser.parse("""
                # Pfannkuchen
                portionen: 4 | minuten: 25 | tags: vegetarisch, Frühstück
                ## Zutaten
                250 g Mehl
                2 EL Butter
                Salz
                ## Schritte
                1. Teig rühren.
                2. Backen.

                # Obstsalat
                portionen: 2 | minuten: 10 | tags: vegan
                ## Zutaten
                2 Äpfel
                ## Schritte
                1. Schneiden.
                """);

        assertThat(recipes).hasSize(2);
        RecipeInput first = recipes.getFirst();
        assertThat(first.title()).isEqualTo("Pfannkuchen");
        assertThat(first.servings()).isEqualTo(4);
        assertThat(first.minutes()).isEqualTo(25);
        assertThat(first.tags()).isEqualTo("vegetarisch, Frühstück");
        assertThat(first.ingredients()).extracting(IngredientInput::name).containsExactly("Mehl", "Butter", "Salz");
        assertThat(first.ingredients().getFirst().amount()).isEqualTo(250.0);
        assertThat(first.ingredients().getFirst().unit()).isEqualTo(Unit.G);
        assertThat(first.steps()).isEqualTo("1. Teig rühren.\n2. Backen.");
        assertThat(recipes.get(1).title()).isEqualTo("Obstsalat");
    }

    @Test
    void unknownMetaKeyIsAnError() {
        assertThatThrownBy(() -> RecipeSeedParser.parse("# X\nportion: 2\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("portion");
    }

    @Test
    void emptyTextGivesNoRecipes() {
        assertThat(RecipeSeedParser.parse("")).isEmpty();
        assertThat(RecipeSeedParser.parse(null)).isEmpty();
    }
}
