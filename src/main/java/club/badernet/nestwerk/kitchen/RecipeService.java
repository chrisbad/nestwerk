package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Rezepte anlegen, ändern, suchen; Zutaten werden Lebensmitteln zugeordnet. */
@Service
public class RecipeService {

    private final RecipeRepository recipes;
    private final FoodRepository foods;
    private final KitchenAccess access;

    public RecipeService(RecipeRepository recipes, FoodRepository foods, KitchenAccess access) {
        this.recipes = recipes;
        this.foods = foods;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<Recipe> search(String q, User viewer) {
        access.require(viewer, KitchenPermission.KITCHEN_VIEW);
        return q == null || q.isBlank() ? recipes.findAllByOrderByTitleAsc() : recipes.search(q.strip());
    }

    @Transactional(readOnly = true)
    public Recipe get(Long id, User viewer) {
        access.require(viewer, KitchenPermission.KITCHEN_VIEW);
        Recipe recipe = recipes.findById(id).orElseThrow(() -> new NoSuchElementException("Rezept nicht gefunden"));
        recipe.getIngredients().forEach(i -> {
            if (i.getFood() != null) {
                i.getFood().getName();
            }
        });
        return recipe;
    }

    @Transactional
    public Recipe create(RecipeInput input, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_RECIPES_EDIT);
        Recipe recipe = new Recipe(title(input), servings(input), actor.getId());
        apply(recipe, input);
        return recipes.save(recipe);
    }

    @Transactional
    public Recipe update(Long id, RecipeInput input, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_RECIPES_EDIT);
        Recipe recipe = get(id, actor);
        recipe.setTitle(title(input));
        recipe.setServings(servings(input));
        apply(recipe, input);
        recipe.touch();
        return recipe;
    }

    @Transactional
    public void delete(Long id, User actor) {
        access.require(actor, KitchenPermission.KITCHEN_RECIPES_EDIT);
        recipes.delete(get(id, actor));
    }

    /** Zerlegt Textzeilen in Zutaten. */
    public static List<IngredientInput> parseLines(String text) {
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(text.split("\\R")).map(IngredientParser::parse).filter(Objects::nonNull)
                .map(IngredientInput::of).toList();
    }

    /** Ordnet eine Zutat zu: gewähltes Lebensmittel, sonst gleicher Name, sonst bester Treffer. */
    @Transactional(readOnly = true)
    public RecipeIngredient resolve(IngredientInput input, List<Food> candidates) {
        Food food = null;
        if (input.foodId() != null) {
            food = foods.findById(input.foodId()).orElse(null);
        }
        if (food == null) {
            food = foods.findByNameIgnoreCase(input.name().strip())
                    .orElseGet(() -> FoodMatcher.best(input.name(), candidates).orElse(null));
        }
        String note = input.note() == null || input.note().isBlank() ? null : truncate(input.note().strip(), 200);
        return new RecipeIngredient(input.amount(), input.amount() == null ? input.unit() : unitOrPiece(input),
                food, truncate(input.name().strip(), 300), note);
    }

    private void apply(Recipe recipe, RecipeInput input) {
        recipe.setDescription(blankToNull(input.description()));
        if (input.minutes() != null && (input.minutes() < 1 || input.minutes() > 2880)) {
            throw new IllegalArgumentException("Die Dauer liegt zwischen 1 und 2880 Minuten.");
        }
        recipe.setMinutes(input.minutes());
        recipe.setTags(blankToNull(input.tags()) == null ? null : truncate(input.tags().strip(), 500));
        String url = blankToNull(input.sourceUrl());
        if (url != null && !url.matches("(?i)https?://\\S+")) {
            throw new IllegalArgumentException("Die Quelle muss ein Link (http/https) sein.");
        }
        recipe.setSourceUrl(url == null ? null : truncate(url, 2000));
        recipe.setSteps(blankToNull(input.steps()));
        List<Food> candidates = foods.findAll();
        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (IngredientInput i : input.ingredients() == null ? List.<IngredientInput>of() : input.ingredients()) {
            if (i.name() == null || i.name().isBlank()) {
                continue;
            }
            if (i.amount() != null && (i.amount() <= 0 || i.amount() > 100_000)) {
                throw new IllegalArgumentException("Die Menge von „" + i.name().strip() + "“ ist ungültig.");
            }
            ingredients.add(resolve(i, candidates));
        }
        recipe.replaceIngredients(ingredients);
    }

    private static Unit unitOrPiece(IngredientInput input) {
        return input.unit() == null ? Unit.PIECE : input.unit();
    }

    private static String title(RecipeInput input) {
        String title = input.title() == null ? "" : input.title().strip();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("Bitte einen Titel angeben.");
        }
        return truncate(title, 200);
    }

    private static int servings(RecipeInput input) {
        int servings = input.servings() == null ? 2 : input.servings();
        if (servings < 1 || servings > 100) {
            throw new IllegalArgumentException("Portionen zwischen 1 und 100.");
        }
        return servings;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
