package club.badernet.nestwerk.kitchen.integration;

import java.util.List;

/** Ein von einer Webseite gelesenes Rezept; Zutaten als Originalzeilen, Schritte als Markdown. */
public record ImportedRecipe(String title, String description, Integer servings, Integer minutes, String tags,
                             String sourceUrl, String steps, List<String> ingredientLines) {
}
