package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.kitchen.Food;
import club.badernet.nestwerk.kitchen.FoodCategory;
import club.badernet.nestwerk.kitchen.FoodInput;
import club.badernet.nestwerk.kitchen.FoodService;
import club.badernet.nestwerk.kitchen.FoodSource;
import club.badernet.nestwerk.kitchen.IngredientInput;
import club.badernet.nestwerk.kitchen.Meal;
import club.badernet.nestwerk.kitchen.MealEater;
import club.badernet.nestwerk.kitchen.MealPlanService;
import club.badernet.nestwerk.kitchen.MealSlot;
import club.badernet.nestwerk.kitchen.MealSlotRepository;
import club.badernet.nestwerk.kitchen.Nutrition;
import club.badernet.nestwerk.kitchen.NutritionCalculator;
import club.badernet.nestwerk.kitchen.PantryItem;
import club.badernet.nestwerk.kitchen.PantryService;
import club.badernet.nestwerk.kitchen.Recipe;
import club.badernet.nestwerk.kitchen.RecipeIngredient;
import club.badernet.nestwerk.kitchen.RecipeInput;
import club.badernet.nestwerk.kitchen.RecipeNutrition;
import club.badernet.nestwerk.kitchen.RecipeService;
import club.badernet.nestwerk.kitchen.ShoppingItem;
import club.badernet.nestwerk.kitchen.ShoppingListService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Küche: Lebensmittel, Rezepte, Essensplan, Einkaufsliste und Vorrat. */
@Component
public class KitchenTools {

    public record FoodItem(Long id, String name, String category, Double kcalPer100g, Double proteinPer100g,
                           Double fatPer100g, Double carbsPer100g, Double pieceGrams, Double packageGrams,
                           String barcode) {
    }

    public record RecipeItem(Long id, String title, int servings, Integer minutes, List<String> tags,
                             double kcalPerServing, boolean nutritionComplete) {
    }

    public record IngredientItem(String text, Double amount, String unit, String food) {
    }

    public record RecipeDetail(RecipeItem recipe, String description, String sourceUrl, String steps,
                               List<IngredientItem> ingredients, double proteinPerServing, double fatPerServing,
                               double carbsPerServing, List<String> ingredientsWithoutNutrition,
                               List<String> ingredientsWithoutFood) {
    }

    public record MealItem(Long id, LocalDate day, String slot, String title, Long recipeId, boolean cooked,
                           List<String> eaters, Double kcalPerServing) {
    }

    public record ShoppingEntry(Long id, String name, String amount, String status, String origin) {
    }

    public record PantryEntry(Long id, String name, String amount, String location, LocalDate bestBefore) {
    }

    private static final int MAX_RESULTS = 100;
    private static final int MAX_DAYS = 31;

    private final Tools tools;
    private final FoodService foods;
    private final RecipeService recipes;
    private final MealPlanService plan;
    private final MealSlotRepository slots;
    private final ShoppingListService shopping;
    private final PantryService pantry;
    private final FamilyMemberRepository members;

    public KitchenTools(Tools tools, FoodService foods, RecipeService recipes, MealPlanService plan,
                        MealSlotRepository slots, ShoppingListService shopping, PantryService pantry,
                        FamilyMemberRepository members) {
        this.tools = tools;
        this.foods = foods;
        this.recipes = recipes;
        this.plan = plan;
        this.slots = slots;
        this.shopping = shopping;
        this.pantry = pantry;
        this.members = members;
    }

    @McpTool(name = "search_foods", description = "Sucht Lebensmittel mit Nährwerten pro 100 g (Name enthält den "
            + "Suchtext; ohne Suchtext die ersten 100).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<FoodItem> searchFoods(McpTransportContext context,
                                      @McpToolParam(description = "Suchtext", required = false) String query) {
        return tools.read(context, user -> foods.search(query, user).stream().limit(MAX_RESULTS)
                .map(KitchenTools::food).toList());
    }

    @McpTool(name = "create_food", description = "Legt ein Lebensmittel an. Nährwerte pro 100 g (bzw. 100 ml); "
            + "pieceGrams ist das Gewicht eines Stücks, packageGrams die übliche Packungsgröße. Kategorie: PRODUCE, "
            + "BAKERY, CHILLED, MEAT_FISH, PANTRY, FROZEN, DRINKS, SPICES, OTHER.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public FoodItem createFood(McpTransportContext context,
                               @McpToolParam(description = "Name") String name,
                               @McpToolParam(description = "Kategorie", required = false) String category,
                               @McpToolParam(description = "kcal pro 100 g", required = false) Double kcal,
                               @McpToolParam(description = "Eiweiß g pro 100 g", required = false) Double protein,
                               @McpToolParam(description = "Fett g pro 100 g", required = false) Double fat,
                               @McpToolParam(description = "Kohlenhydrate g pro 100 g", required = false) Double carbs,
                               @McpToolParam(description = "Gewicht pro Stück in g", required = false)
                               Double pieceGrams,
                               @McpToolParam(description = "Packungsgröße in g", required = false)
                               Double packageGrams,
                               @McpToolParam(description = "Haltbarkeit in Tagen", required = false)
                               Integer shelfDays) {
        return tools.write(context, user -> food(foods.create(new FoodInput(Tools.required(name, "name"),
                Tools.enumValue(FoodCategory.class, category, "category"), kcal, protein, fat, carbs, pieceGrams,
                packageGrams, shelfDays, null), FoodSource.MANUAL, user)));
    }

    @McpTool(name = "search_recipes", description = "Sucht Rezepte nach Titel oder Schlagwort mit Kalorien pro "
            + "Portion. Ohne Suchtext alle (höchstens 100).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<RecipeItem> searchRecipes(McpTransportContext context,
                                          @McpToolParam(description = "Suchtext", required = false) String query) {
        return tools.read(context, user -> recipes.search(query, user).stream().limit(MAX_RESULTS)
                .map(KitchenTools::recipe).toList());
    }

    @McpTool(name = "get_recipe", description = "Zeigt ein Rezept mit Zutaten, Schritten und Nährwerten pro "
            + "Portion; die Mengen gelten für die Portionenzahl des Rezepts oder für servings.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public RecipeDetail getRecipe(McpTransportContext context,
                                  @McpToolParam(description = "ID des Rezepts (recipeId)") Long recipeId,
                                  @McpToolParam(description = "Mengen auf diese Portionenzahl umrechnen",
                                          required = false) Double servings) {
        return tools.read(context, user -> detail(recipes.get(Tools.required(recipeId, "recipeId"), user), servings));
    }

    @McpTool(name = "create_recipe", description = "Legt ein Rezept an. Zutaten als Textzeilen wie „200 g Mehl“, "
            + "„2 EL Olivenöl“, „1 ½ Zwiebeln“ oder „Salz“; sie werden zerlegt und Lebensmitteln zugeordnet. Die "
            + "Antwort nennt Zutaten ohne Lebensmittel (kein Nährwert) – dafür vorher create_food nutzen und das "
            + "Rezept neu anlegen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public RecipeDetail createRecipe(McpTransportContext context,
                                     @McpToolParam(description = "Titel") String title,
                                     @McpToolParam(description = "Zutaten, eine Zeile je Zutat") List<String> ingredients,
                                     @McpToolParam(description = "Portionen (Standard 4)", required = false)
                                     Integer servings,
                                     @McpToolParam(description = "Dauer in Minuten", required = false) Integer minutes,
                                     @McpToolParam(description = "Kurzbeschreibung", required = false)
                                     String description,
                                     @McpToolParam(description = "Schlagworte, kommagetrennt", required = false)
                                     String tags,
                                     @McpToolParam(description = "Zubereitung (Markdown)", required = false)
                                     String steps,
                                     @McpToolParam(description = "Quelle als Link", required = false)
                                     String sourceUrl) {
        return tools.write(context, user -> {
            List<IngredientInput> lines = RecipeService.parseLines(String.join("\n",
                    Tools.required(ingredients, "ingredients")));
            if (lines.isEmpty()) {
                throw new Tools.ToolException("Keine Zutaten erkannt.");
            }
            Recipe recipe = recipes.create(new RecipeInput(Tools.required(title, "title"), description,
                    servings == null ? 4 : servings, minutes, tags, sourceUrl, steps, lines), user);
            return detail(recipes.get(recipe.getId(), user), null);
        });
    }

    @McpTool(name = "plan_meal", description = "Plant eine Mahlzeit im Essensplan. Mahlzeit (slot) per Name wie "
            + "„Abendessen“; entweder ein Rezept (recipeId) oder ein freier Titel. Mitesser sind alle Personen mit "
            + "vorgeschlagenen Portionen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public MealItem planMeal(McpTransportContext context,
                             @McpToolParam(description = "Tag, JJJJ-MM-TT") String day,
                             @McpToolParam(description = "Mahlzeit, z. B. Abendessen") String slot,
                             @McpToolParam(description = "ID des Rezepts", required = false) Long recipeId,
                             @McpToolParam(description = "Freier Titel, wenn kein Rezept", required = false)
                             String title) {
        return tools.write(context, user -> {
            LocalDate date = Tools.required(Tools.date(day, "day"), "day");
            MealSlot s = slots.findAllByOrderBySortOrderAsc().stream()
                    .filter(x -> x.getName().equalsIgnoreCase(Tools.required(slot, "slot").strip())).findFirst()
                    .orElseThrow(() -> new Tools.ToolException("Mahlzeit unbekannt. Möglich: " + slots
                            .findAllByOrderBySortOrderAsc().stream().map(MealSlot::getName)
                            .collect(Collectors.joining(", "))));
            Meal meal = plan.add(date, s.getId(), recipeId, title, user);
            return meal(meal, names());
        });
    }

    @McpTool(name = "get_meal_plan", description = "Zeigt den Essensplan im Zeitraum (einschließlich), ohne Angabe "
            + "die laufende Woche; höchstens 31 Tage.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<MealItem> getMealPlan(McpTransportContext context,
                                      @McpToolParam(description = "Erster Tag, JJJJ-MM-TT", required = false)
                                      String from,
                                      @McpToolParam(description = "Letzter Tag, JJJJ-MM-TT", required = false)
                                      String to) {
        return tools.read(context, user -> {
            LocalDate first = Tools.date(from, "from");
            if (first == null) {
                first = MealPlanService.monday(plan.today());
            }
            LocalDate last = Tools.date(to, "to");
            if (last == null) {
                last = first.plusDays(6);
            }
            if (last.isBefore(first) || last.isAfter(first.plusDays(MAX_DAYS))) {
                throw new Tools.ToolException("Der Zeitraum muss 0–" + MAX_DAYS + " Tage umfassen.");
            }
            Map<Long, String> names = names();
            List<MealItem> result = new java.util.ArrayList<>();
            for (LocalDate monday = MealPlanService.monday(first); !monday.isAfter(last); monday = monday.plusWeeks(1)) {
                for (Meal m : plan.week(monday, user).meals()) {
                    if (!m.getDay().isBefore(first) && !m.getDay().isAfter(last)) {
                        result.add(meal(m, names));
                    }
                }
            }
            result.sort(java.util.Comparator.comparing(MealItem::day));
            return result;
        });
    }

    @McpTool(name = "shopping_list", description = "Zeigt die Einkaufsliste (offen, an Bring! gesendet, gekauft).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<ShoppingEntry> shoppingList(McpTransportContext context) {
        return tools.read(context, user -> shopping.list(user).stream().map(KitchenTools::entry).toList());
    }

    @McpTool(name = "add_shopping_item", description = "Setzt einen Artikel auf die Einkaufsliste, z. B. „2 Dosen "
            + "Tomaten“ oder „Klopapier“.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ShoppingEntry addShoppingItem(McpTransportContext context,
                                         @McpToolParam(description = "Artikel mit optionaler Menge") String item) {
        return tools.write(context, user -> entry(shopping.addManual(Tools.required(item, "item"), user)));
    }

    @McpTool(name = "list_pantry", description = "Zeigt den Vorrat nach Haltbarkeit sortiert (bald ablaufende "
            + "zuerst).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<PantryEntry> listPantry(McpTransportContext context) {
        return tools.read(context, user -> pantry.list(user).stream().map(KitchenTools::pantryEntry).toList());
    }

    // --- Umwandlung --------------------------------------------------------------------

    private Map<Long, String> names() {
        return members.findAll().stream().collect(Collectors.toMap(FamilyMember::getId, FamilyMember::getName));
    }

    private static FoodItem food(Food f) {
        return new FoodItem(f.getId(), f.getName(), f.getCategory().name(), f.getKcal(), f.getProtein(), f.getFat(),
                f.getCarbs(), f.getPieceGrams(), f.getPackageGrams(), f.getBarcode());
    }

    private static RecipeItem recipe(Recipe r) {
        RecipeNutrition n = NutritionCalculator.forRecipe(r);
        return new RecipeItem(r.getId(), r.getTitle(), r.getServings(), r.getMinutes(), r.tagList(),
                Math.round(n.perServing().kcal()), n.complete());
    }

    private static RecipeDetail detail(Recipe r, Double servings) {
        double wanted = servings == null ? r.getServings() : servings;
        if (wanted <= 0 || wanted > 1000) {
            throw new Tools.ToolException("servings: zwischen 0 und 1000 erwartet.");
        }
        RecipeNutrition n = NutritionCalculator.forRecipe(r);
        Nutrition per = n.perServing();
        List<IngredientItem> ingredients = r.getIngredients().stream().map(i -> {
            Double amount = NutritionCalculator.amountFor(i, r.getServings(), wanted);
            return new IngredientItem(i.getText(), amount == null ? null : Math.round(amount * 100) / 100.0,
                    i.getUnit() == null ? null : i.getUnit().getLabel(), i.getFood() == null ? null
                    : i.getFood().getName());
        }).toList();
        List<String> withoutFood = r.getIngredients().stream().filter(i -> i.getAmount() != null && i.getFood() == null)
                .map(RecipeIngredient::getText).filter(Objects::nonNull).toList();
        return new RecipeDetail(recipe(r), r.getDescription(), r.getSourceUrl(), r.getSteps(), ingredients,
                round(per.protein()), round(per.fat()), round(per.carbs()), n.missing(), withoutFood);
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static MealItem meal(Meal m, Map<Long, String> names) {
        List<String> eaters = m.getEaters().stream().map((MealEater e) -> names.getOrDefault(e.getMemberId(), "?")
                + " ×" + e.getPortions()).sorted().toList();
        Double kcal = MealPlanService.kcalPerServing(m.getRecipe());
        return new MealItem(m.getId(), m.getDay(), m.getSlot().getName(), m.getTitle(),
                m.getRecipe() == null ? null : m.getRecipe().getId(), m.isCooked(), eaters,
                kcal == null ? null : (double) Math.round(kcal));
    }

    private static ShoppingEntry entry(ShoppingItem i) {
        return new ShoppingEntry(i.getId(), i.getName(), i.specification(), i.getStatus().name(),
                i.getOrigin().name());
    }

    private static PantryEntry pantryEntry(PantryItem i) {
        return new PantryEntry(i.getId(), i.getName(), club.badernet.nestwerk.kitchen.Amounts.format(i.getAmount(),
                i.getUnit()), i.getLocation(), i.getBestBefore());
    }
}
