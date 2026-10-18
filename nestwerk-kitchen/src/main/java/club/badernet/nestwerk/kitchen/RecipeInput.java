package club.badernet.nestwerk.kitchen;

import java.util.List;

/** Eingaben für ein Rezept. */
public record RecipeInput(String title, String description, Integer servings, Integer minutes, String tags,
                          String sourceUrl, String steps, List<IngredientInput> ingredients) {
}
