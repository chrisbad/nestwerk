package club.badernet.nestwerk.kitchen.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeImporterTest {

    static final String SIMPLE = """
            <html><head><title>x</title>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Recipe","name":"Linsen&shy;suppe &amp; Brot",
             "description":"Wärmt <b>gut</b>.","recipeYield":"4 Portionen","totalTime":"PT1H15M",
             "keywords":"vegetarisch, Suppe",
             "recipeIngredient":["200 g rote Linsen","1 Zwiebel","Salz"],
             "recipeInstructions":"Linsen waschen.\\nAlles kochen."}
            </script></head><body></body></html>
            """;

    @Test
    void readsSimpleRecipe() {
        ImportedRecipe r = RecipeImporter.parse(SIMPLE, "https://example.org/suppe");

        assertThat(r.title()).isEqualTo("Linsensuppe & Brot");
        assertThat(r.description()).isEqualTo("Wärmt gut.");
        assertThat(r.servings()).isEqualTo(4);
        assertThat(r.minutes()).isEqualTo(75);
        assertThat(r.tags()).isEqualTo("vegetarisch, Suppe");
        assertThat(r.ingredientLines()).containsExactly("200 g rote Linsen", "1 Zwiebel", "Salz");
        assertThat(r.steps()).isEqualTo("1. Linsen waschen.\n2. Alles kochen.");
        assertThat(r.sourceUrl()).isEqualTo("https://example.org/suppe");
    }

    @Test
    void findsRecipeInGraphWithTypeArrayAndSections() {
        String html = """
                <script type='application/ld+json'>{"@context":"https://schema.org","@graph":[
                  {"@type":"WebPage","name":"Seite"},
                  {"@type":["Recipe","NewsArticle"],"name":"Gulasch","recipeYield":["6","6 servings"],
                   "prepTime":"PT20M","cookTime":"PT1H30M","keywords":["Fleisch","Eintopf"],
                   "recipeIngredient":["1 kg Rindergulasch"],
                   "recipeInstructions":[{"@type":"HowToSection","name":"Vorbereiten","itemListElement":[
                       {"@type":"HowToStep","text":"Fleisch würfeln."}]},
                     {"@type":"HowToSection","name":"Kochen","itemListElement":[
                       {"@type":"HowToStep","text":"Anbraten."},{"@type":"HowToStep","text":"Schmoren."}]}]}
                ]}</script>
                """;

        ImportedRecipe r = RecipeImporter.parse(html, "https://example.org/gulasch");

        assertThat(r.title()).isEqualTo("Gulasch");
        assertThat(r.servings()).isEqualTo(6);
        assertThat(r.minutes()).isEqualTo(110);
        assertThat(r.tags()).isEqualTo("Fleisch, Eintopf");
        assertThat(r.steps()).isEqualTo("**Vorbereiten**\n\n1. Fleisch würfeln.\n\n**Kochen**\n\n1. Anbraten.\n2. Schmoren.");
    }

    @Test
    void readsListOfObjectsAndStepArrays() {
        String html = """
                <script type="application/ld+json">[{"@type":"Organization"},
                  {"@type":"Recipe","name":"Tee","recipeYield":2,
                   "recipeIngredient":["1 TL Tee"],
                   "recipeInstructions":[{"@type":"HowToStep","text":"Wasser kochen."},"Ziehen lassen."]}]</script>
                """;

        ImportedRecipe r = RecipeImporter.parse(html, "https://example.org/tee");

        assertThat(r.servings()).isEqualTo(2);
        assertThat(r.minutes()).isNull();
        assertThat(r.steps()).isEqualTo("1. Wasser kochen.\n2. Ziehen lassen.");
    }

    @Test
    void pageWithoutRecipeIsReported() {
        assertThatThrownBy(() -> RecipeImporter.parse("<html><script type=\"application/ld+json\">{\"@type\":\"WebPage\"}</script></html>", "https://x.org"))
                .isInstanceOf(ImportException.class).hasMessageContaining("kein Rezept");
        assertThatThrownBy(() -> RecipeImporter.parse("<script type=\"application/ld+json\">{kaputt</script>", "https://x.org"))
                .isInstanceOf(ImportException.class);
    }
}
