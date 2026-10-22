package club.badernet.nestwerk.demo;

import club.badernet.nestwerk.kitchen.IngredientInput;
import club.badernet.nestwerk.kitchen.MealPlanService;
import club.badernet.nestwerk.kitchen.MealSlot;
import club.badernet.nestwerk.kitchen.PantryService;
import club.badernet.nestwerk.kitchen.Recipe;
import club.badernet.nestwerk.kitchen.RecipeInput;
import club.badernet.nestwerk.kitchen.RecipeService;
import club.badernet.nestwerk.kitchen.ShoppingListService;
import club.badernet.nestwerk.kitchen.Unit;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
class DemoKitchenArea implements DemoArea {

    private static final int WEEK_DAYS = 7;

    private final RecipeService recipes;
    private final MealPlanService mealPlan;
    private final PantryService pantry;
    private final ShoppingListService shopping;

    DemoKitchenArea(RecipeService recipes, MealPlanService mealPlan, PantryService pantry,
                    ShoppingListService shopping) {
        this.recipes = recipes;
        this.mealPlan = mealPlan;
        this.pantry = pantry;
        this.shopping = shopping;
    }

    @Override
    public String name() {
        return "kitchen";
    }

    @Override
    public void seed(DemoContext context) {
        createFamilyRecipes(context);
        planWeek(context);
        stockPantry(context);
        fillShoppingList(context);
    }

    private void createFamilyRecipes(DemoContext context) {
        recipes.create(new RecipeInput("Omas Kartoffelsuppe", "Deftige Suppe für kalte Tage.", 4, 45,
                "Suppe, Familienrezept", null,
                "Kartoffeln und Zwiebel würfeln und in Butter anschwitzen.\nMit Brühe aufgießen und 25 Minuten kochen.\n"
                        + "Würstchen in Scheiben dazugeben und mit Majoran abschmecken.",
                List.of(ingredient(800.0, Unit.G, "Kartoffeln"), ingredient(1.0, Unit.PIECE, "Zwiebel"),
                        ingredient(1.0, Unit.L, "Gemüsebrühe"), ingredient(4.0, Unit.PIECE, "Wiener Würstchen"),
                        ingredient(1.0, Unit.TBSP, "Butter"))), context.actor());
        recipes.create(new RecipeInput("Pfannkuchen mit Apfelmus", "Sonntagsklassiker der Kinder.", 4, 30,
                "Süß, Kinder", null,
                "Mehl, Milch und Eier zu einem glatten Teig verrühren.\nPortionsweise in der Pfanne ausbacken.\n"
                        + "Mit Apfelmus servieren.",
                List.of(ingredient(250.0, Unit.G, "Mehl"), ingredient(500.0, Unit.ML, "Milch"),
                        ingredient(3.0, Unit.PIECE, "Eier"), ingredient(1.0, Unit.PINCH, "Salz"),
                        ingredient(1.0, Unit.PACKAGE, "Apfelmus"))), context.actor());
    }

    private void planWeek(DemoContext context) {
        List<MealSlot> slots = mealPlan.week(context.today(), context.actor()).slots();
        if (slots.isEmpty()) {
            return;
        }
        MealSlot dinner = slots.get(Math.min(2, slots.size() - 1));
        List<Recipe> candidates = recipes.search("", context.actor());
        LocalDate monday = context.monday();
        for (int day = 0; day < Math.min(WEEK_DAYS, candidates.size()); day++) {
            Recipe recipe = candidates.get(day);
            mealPlan.add(monday.plusDays(day), dinner.getId(), recipe.getId(), recipe.getTitle(), context.actor());
        }
    }

    private void stockPantry(DemoContext context) {
        LocalDate today = context.today();
        pantry.add("Nudeln", 1.5, Unit.KG, "Vorratsschrank", today.plusMonths(8), context.actor());
        pantry.add("Reis", 1.0, Unit.KG, "Vorratsschrank", today.plusMonths(10), context.actor());
        pantry.add("Passierte Tomaten", 4.0, Unit.PACKAGE, "Vorratsschrank", today.plusMonths(6), context.actor());
        pantry.add("Milch", 2.0, Unit.L, "Kühlschrank", today.plusDays(6), context.actor());
        pantry.add("Joghurt", 3.0, Unit.PIECE, "Kühlschrank", today.plusDays(1), context.actor());
        pantry.add("Tiefkühlerbsen", 750.0, Unit.G, "Tiefkühler", today.plusMonths(7), context.actor());
        pantry.add("Äpfel", 6.0, Unit.PIECE, "Obstkorb", today.plusDays(10), context.actor());
    }

    private void fillShoppingList(DemoContext context) {
        List.of("2 Packungen Butter", "1 Bund Petersilie", "500 g Hackfleisch", "Klopapier", "6 Eier",
                "1 Brot", "2 Dosen Tomaten").forEach(line -> shopping.addManual(line, context.actor()));
    }

    private static IngredientInput ingredient(Double amount, Unit unit, String name) {
        return new IngredientInput(amount, unit, name, null, null);
    }
}
