package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.kitchen.Amounts;
import club.badernet.nestwerk.kitchen.BringSync;
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
import club.badernet.nestwerk.kitchen.Unit;
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

    public record EaterInput(Long memberId, Double portions) {
    }

    public record AmountOverride(Long ingredientId, Double amount) {
    }

    public record CookResult(Long mealId, boolean booked, List<String> deductions) {
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
    private final BringSync bring;

    public KitchenTools(Tools tools, FoodService foods, RecipeService recipes, MealPlanService plan,
                        MealSlotRepository slots, ShoppingListService shopping, PantryService pantry,
                        FamilyMemberRepository members, BringSync bring) {
        this.tools = tools;
        this.foods = foods;
        this.recipes = recipes;
        this.plan = plan;
        this.slots = slots;
        this.shopping = shopping;
        this.pantry = pantry;
        this.members = members;
        this.bring = bring;
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
            MealSlot s = slot(Tools.required(slot, "slot"));
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

    @McpTool(name = "update_recipe", description = "Ändert ein Rezept. Nur angegebene Felder werden geändert; "
            + "ingredients ersetzt die ganze Zutatenliste (Textzeilen wie bei create_recipe).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public RecipeDetail updateRecipe(McpTransportContext context,
                                     @McpToolParam(description = "ID des Rezepts") Long recipeId,
                                     @McpToolParam(description = "Titel", required = false) String title,
                                     @McpToolParam(description = "Zutaten, eine Zeile je Zutat", required = false)
                                     List<String> ingredients,
                                     @McpToolParam(description = "Portionen", required = false) Integer servings,
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
            Long id = Tools.required(recipeId, "recipeId");
            Recipe r = recipes.get(id, user);
            List<IngredientInput> lines;
            if (ingredients == null) {
                lines = r.getIngredients().stream().map(i -> new IngredientInput(i.getAmount(), i.getUnit(),
                        i.getText(), i.getNote(), i.getFood() == null ? null : i.getFood().getId())).toList();
            } else {
                lines = RecipeService.parseLines(String.join("\n", ingredients));
                if (lines.isEmpty()) {
                    throw new Tools.ToolException("Keine Zutaten erkannt.");
                }
            }
            recipes.update(id, new RecipeInput(title == null ? r.getTitle() : title,
                    description == null ? r.getDescription() : description,
                    servings == null ? r.getServings() : servings, minutes == null ? r.getMinutes() : minutes,
                    tags == null ? r.getTags() : tags, sourceUrl == null ? r.getSourceUrl() : sourceUrl,
                    steps == null ? r.getSteps() : steps, lines), user);
            return detail(recipes.get(id, user), null);
        });
    }

    @McpTool(name = "update_food", description = "Ändert ein Lebensmittel. Nur angegebene Felder werden geändert; "
            + "Nährwerte pro 100 g. Kategorie: PRODUCE, BAKERY, CHILLED, MEAT_FISH, PANTRY, FROZEN, DRINKS, SPICES, "
            + "OTHER.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public FoodItem updateFood(McpTransportContext context,
                               @McpToolParam(description = "ID des Lebensmittels") Long foodId,
                               @McpToolParam(description = "Name", required = false) String name,
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
        return tools.write(context, user -> {
            Food f = foods.get(Tools.required(foodId, "foodId"));
            FoodCategory cat = Tools.enumValue(FoodCategory.class, category, "category");
            return food(foods.update(f.getId(), new FoodInput(name == null ? f.getName() : name,
                    cat == null ? f.getCategory() : cat, kcal == null ? f.getKcal() : kcal,
                    protein == null ? f.getProtein() : protein, fat == null ? f.getFat() : fat,
                    carbs == null ? f.getCarbs() : carbs, pieceGrams == null ? f.getPieceGrams() : pieceGrams,
                    packageGrams == null ? f.getPackageGrams() : packageGrams,
                    shelfDays == null ? f.getShelfDays() : shelfDays, f.getBarcode()), user));
        });
    }

    @McpTool(name = "move_meal", description = "Verschiebt eine geplante Mahlzeit auf einen anderen Tag und/oder "
            + "eine andere Mahlzeit (slot, z. B. Mittagessen).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public MealItem moveMeal(McpTransportContext context,
                             @McpToolParam(description = "ID der Mahlzeit (mealId)") Long mealId,
                             @McpToolParam(description = "Neuer Tag, JJJJ-MM-TT") String day,
                             @McpToolParam(description = "Neue Mahlzeit, z. B. Abendessen; ohne Angabe bleibt sie",
                                     required = false) String slot) {
        return tools.write(context, user -> {
            Meal meal = plan.get(Tools.required(mealId, "mealId"), user);
            MealSlot s = slot == null || slot.isBlank() ? meal.getSlot() : slot(slot);
            return meal(plan.move(meal.getId(), Tools.required(Tools.date(day, "day"), "day"), s.getId(), user),
                    names());
        });
    }

    @McpTool(name = "set_meal_eaters", description = "Legt fest, wer bei einer Mahlzeit mitisst und mit wie vielen "
            + "Portionen. Ersetzt die bisherigen Mitesser; eine leere Liste bedeutet: niemand. Mitglieds-IDs aus "
            + "list_members.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public MealItem setMealEaters(McpTransportContext context,
                                  @McpToolParam(description = "ID der Mahlzeit (mealId)") Long mealId,
                                  @McpToolParam(description = "Mitesser: Mitglieds-ID und Portionen (Standard 1)")
                                  List<EaterInput> eaters) {
        return tools.write(context, user -> {
            Map<Long, Double> portions = new java.util.LinkedHashMap<>();
            for (EaterInput e : Tools.required(eaters, "eaters")) {
                portions.put(Tools.required(e.memberId(), "memberId"), e.portions() == null ? 1.0 : e.portions());
            }
            return meal(plan.updateEaters(Tools.required(mealId, "mealId"), portions, user), names());
        });
    }

    @McpTool(name = "fill_meal_plan", description = "Füllt die leeren, zum Auffüllen markierten Mahlzeiten der Woche "
            + "(ab heute) mit passenden Rezepten; bald ablaufender Vorrat und Abwechslung zählen. Liefert die Anzahl.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String fillMealPlan(McpTransportContext context,
                               @McpToolParam(description = "Ein Tag der Woche, JJJJ-MM-TT; ohne Angabe diese Woche",
                                       required = false) String week) {
        return tools.write(context, user -> {
            LocalDate day = Tools.date(week, "week");
            int filled = plan.autoFill(day == null ? plan.today() : day, user);
            return filled == 0 ? "Nichts aufgefüllt – alles belegt oder keine passenden Rezepte."
                    : filled + (filled == 1 ? " Mahlzeit" : " Mahlzeiten") + " eingeplant.";
        });
    }

    @McpTool(name = "cook_meal", description = "Trägt eine Mahlzeit als gekocht ein und bucht die Zutaten vom Vorrat "
            + "ab (ältestes MHD zuerst). Mit dryRun=true wird nur gezeigt, was abgebucht würde. Mit amountOverrides "
            + "lässt sich die Menge einzelner Zutaten korrigieren (0 = nichts abbuchen).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public CookResult cookMeal(McpTransportContext context,
                               @McpToolParam(description = "ID der Mahlzeit (mealId)") Long mealId,
                               @McpToolParam(description = "Nur anzeigen, nichts ändern", required = false)
                               Boolean dryRun,
                               @McpToolParam(description = "Korrigierte Mengen je Zutat", required = false)
                               List<AmountOverride> amountOverrides) {
        return tools.write(context, user -> {
            Long id = Tools.required(mealId, "mealId");
            Map<Long, Double> overrides = new java.util.LinkedHashMap<>();
            for (AmountOverride o : amountOverrides == null ? List.<AmountOverride>of() : amountOverrides) {
                overrides.put(Tools.required(o.ingredientId(), "ingredientId"), Tools.required(o.amount(), "amount"));
            }
            boolean preview = Boolean.TRUE.equals(dryRun);
            PantryService.CookPreview result = preview ? pantry.preview(id, overrides, user)
                    : pantry.cook(id, overrides, user);
            List<String> lines = result.deductions().stream().map(d -> d.ingredient().getText() + ": "
                    + (d.takes().isEmpty() ? "nichts abgebucht" : d.takes().stream().map(t ->
                    Amounts.format(t.amount(), t.item().getUnit()) + " aus " + t.item().getName())
                    .collect(Collectors.joining(" + ")))
                    + (d.note() == null ? "" : " (" + d.note() + ")")).toList();
            return new CookResult(id, !preview, lines);
        });
    }

    @McpTool(name = "generate_shopping_list", description = "Setzt fehlende Zutaten der geplanten Mahlzeiten im "
            + "Zeitraum auf die Einkaufsliste (abzüglich Vorrat und bereits Gelistetem). Ohne Angabe diese Woche. "
            + "Liefert die Anzahl neuer Artikel.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String generateShoppingList(McpTransportContext context,
                                       @McpToolParam(description = "Erster Tag, JJJJ-MM-TT", required = false)
                                       String from,
                                       @McpToolParam(description = "Letzter Tag, JJJJ-MM-TT", required = false)
                                       String to) {
        return tools.write(context, user -> {
            int created = shopping.generate(Tools.date(from, "from"), Tools.date(to, "to"), user);
            return created == 0 ? "Der Plan braucht nichts Neues – Vorrat und Liste decken alles ab."
                    : created + " Artikel aus dem Plan auf die Liste gesetzt.";
        });
    }

    @McpTool(name = "toggle_shopping_item", description = "Hakt einen Artikel der Einkaufsliste als gekauft ab bzw. "
            + "nimmt das zurück.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ShoppingEntry toggleShoppingItem(McpTransportContext context,
                                            @McpToolParam(description = "ID des Artikels (itemId)") Long itemId) {
        return tools.write(context, user -> entry(shopping.toggle(Tools.required(itemId, "itemId"), user)));
    }

    @McpTool(name = "stow_shopping_item", description = "Räumt einen gekauften Artikel in den Vorrat ein. Menge, "
            + "Einheit, Ort und MHD sind optional (Standard: Menge des Artikels, vorgeschlagenes MHD).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public PantryEntry stowShoppingItem(McpTransportContext context,
                                        @McpToolParam(description = "ID des Artikels (itemId)") Long itemId,
                                        @McpToolParam(description = "Menge", required = false) Double amount,
                                        @McpToolParam(description = "Einheit, z. B. g, ml, Stück", required = false)
                                        String unit,
                                        @McpToolParam(description = "Ort, z. B. Kühlschrank", required = false)
                                        String location,
                                        @McpToolParam(description = "Mindestens haltbar bis, JJJJ-MM-TT",
                                                required = false) String bestBefore) {
        return tools.write(context, user -> {
            Long id = Tools.required(itemId, "itemId");
            ShoppingItem item = shopping.list(user).stream().filter(i -> i.getId().equals(id)).findFirst()
                    .orElseThrow(() -> new Tools.ToolException("Artikel nicht gefunden."));
            LocalDate until = Tools.date(bestBefore, "bestBefore");
            if (until == null) {
                until = shopping.suggestedBestBefore(item);
            }
            return pantryEntry(shopping.stow(id, amount != null ? amount
                    : item.getAmount() == null ? 1.0 : item.getAmount(), unit(unit, item.getUnit()), location, until,
                    user));
        });
    }

    @McpTool(name = "send_shopping_to_bring", description = "Sendet die offenen Artikel der Einkaufsliste an Bring!.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = true))
    public String sendShoppingToBring(McpTransportContext context) {
        return tools.write(context, user -> {
            BringSync.Result result = bring.send(user);
            if (result.failed()) {
                throw new Tools.ToolException(result.error());
            }
            return result.count() == 0 ? "Es gab nichts zu senden." : result.count() + " Artikel an Bring! gesendet.";
        });
    }

    @McpTool(name = "sync_shopping_from_bring", description = "Gleicht die Einkaufsliste mit Bring! ab: dort "
            + "abgehakte Artikel gelten als gekauft.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = true))
    public String syncShoppingFromBring(McpTransportContext context) {
        return tools.write(context, user -> {
            BringSync.Result result = bring.sync(user);
            if (result.failed()) {
                throw new Tools.ToolException(result.error());
            }
            return result.count() == 0 ? "Bring! meldet nichts Neues."
                    : result.count() + " Artikel bei Bring! als gekauft erkannt.";
        });
    }

    @McpTool(name = "add_pantry_item", description = "Legt einen Posten in den Vorrat. Ohne bestBefore gilt die "
            + "übliche Haltbarkeit des Lebensmittels, falls bekannt. Einheiten: g, kg, ml, l, Stück, EL, TL, Prise, "
            + "Packung, Bund, Dose, Scheibe.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public PantryEntry addPantryItem(McpTransportContext context,
                                     @McpToolParam(description = "Lebensmittel, z. B. Milch") String name,
                                     @McpToolParam(description = "Menge") Double amount,
                                     @McpToolParam(description = "Einheit (Standard Stück)", required = false)
                                     String unit,
                                     @McpToolParam(description = "Ort, z. B. Kühlschrank", required = false)
                                     String location,
                                     @McpToolParam(description = "Mindestens haltbar bis, JJJJ-MM-TT",
                                             required = false) String bestBefore) {
        return tools.write(context, user -> pantryEntry(pantry.add(Tools.required(name, "name"),
                Tools.required(amount, "amount"), unit(unit, null), location, Tools.date(bestBefore, "bestBefore"),
                user)));
    }

    @McpTool(name = "update_pantry_item", description = "Ändert einen Vorratsposten (Menge, Einheit, Ort, MHD). Nicht "
            + "angegebene Felder bleiben; ein leerer Ort entfernt den Ort.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public PantryEntry updatePantryItem(McpTransportContext context,
                                        @McpToolParam(description = "ID des Postens (itemId)") Long itemId,
                                        @McpToolParam(description = "Neue Menge", required = false) Double amount,
                                        @McpToolParam(description = "Einheit", required = false) String unit,
                                        @McpToolParam(description = "Ort", required = false) String location,
                                        @McpToolParam(description = "Mindestens haltbar bis, JJJJ-MM-TT",
                                                required = false) String bestBefore) {
        return tools.write(context, user -> {
            PantryItem i = pantry.get(Tools.required(itemId, "itemId"), user);
            pantry.change(i.getId(), amount == null ? i.getAmount() : amount, unit(unit, i.getUnit()),
                    location == null ? i.getLocation() : location,
                    bestBefore == null ? i.getBestBefore() : Tools.date(bestBefore, "bestBefore"), user);
            return pantryEntry(pantry.get(i.getId(), user));
        });
    }

    @McpTool(name = "take_from_pantry", description = "Entnimmt eine Menge aus einem Vorratsposten (in dessen "
            + "Einheit); ist nichts mehr da, verschwindet der Posten.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String takeFromPantry(McpTransportContext context,
                                 @McpToolParam(description = "ID des Postens (itemId)") Long itemId,
                                 @McpToolParam(description = "Zu entnehmende Menge") Double amount) {
        return tools.write(context, user -> {
            pantry.take(Tools.required(itemId, "itemId"), Tools.required(amount, "amount"), user);
            return "Entnommen.";
        });
    }

    @McpTool(name = "recipes_to_use_up", description = "Rezepte mit Zutaten, die im Vorrat bald ablaufen – die mit "
            + "den meisten Treffern zuerst.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<RecipeItem> recipesToUseUp(McpTransportContext context) {
        return tools.read(context, user -> pantry.useUp(user).stream().limit(MAX_RESULTS)
                .map(KitchenTools::recipe).toList());
    }

    @McpTool(name = "list_meal_slots", description = "Listet die Mahlzeiten des Essensplans (z. B. Frühstück, "
            + "Mittagessen, Abendessen) – ihre Namen gelten für plan_meal und move_meal.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<String> listMealSlots(McpTransportContext context) {
        return tools.read(context, user -> slots.findAllByOrderBySortOrderAsc().stream().map(MealSlot::getName)
                .toList());
    }

    // --- Hilfen ------------------------------------------------------------------------

    private MealSlot slot(String name) {
        return slots.findAllByOrderBySortOrderAsc().stream()
                .filter(x -> x.getName().equalsIgnoreCase(name.strip())).findFirst()
                .orElseThrow(() -> new Tools.ToolException("Mahlzeit unbekannt. Möglich: " + slots
                        .findAllByOrderBySortOrderAsc().stream().map(MealSlot::getName)
                        .collect(Collectors.joining(", "))));
    }

    private static Unit unit(String raw, Unit fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback == null ? Unit.PIECE : fallback;
        }
        return Unit.parse(raw).orElseThrow(() -> new Tools.ToolException("unit: unbekannte Einheit „" + raw + "“."));
    }

    // --- Umwandlung --------------------------------------------------------------------

    private Map<Long, String> names() {
        return members.findAll().stream().collect(Collectors.toMap(FamilyMember::getId, FamilyMember::getName));
    }

    static FoodItem foodItem(Food f) {
        return food(f);
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

    @McpTool(name = "delete_food", description = "Löscht ein Lebensmittel endgültig (Zutaten, die darauf zeigen, bleiben als Text). Braucht das Recht zum Bearbeiten der Rezepte. Nur nach Rückfrage benutzen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public String deleteFood(McpTransportContext context,
                           @McpToolParam(description = "ID des Lebensmittels aus search_foods") Long foodId) {
        return tools.write(context, user -> {
            Long id = Tools.required(foodId, "foodId");
            String name = foods.get(id).getName();
            foods.delete(id, user);
            return "Lebensmittel „" + name + "“ wurde gelöscht.";
        });
    }

    @McpTool(name = "delete_recipe", description = "Löscht ein Rezept endgültig samt der Einträge im Essensplan. Nur nach Rückfrage benutzen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public String deleteRecipe(McpTransportContext context,
                           @McpToolParam(description = "ID des Rezepts aus search_recipes") Long recipeId) {
        return tools.write(context, user -> {
            Long id = Tools.required(recipeId, "recipeId");
            String title = recipes.get(id, user).getTitle();
            recipes.delete(id, user);
            return "Rezept „" + title + "“ wurde gelöscht.";
        });
    }

    @McpTool(name = "delete_pantry_item", description = "Entfernt einen Posten komplett aus dem Vorrat. Teilmengen nimmt take_from_pantry heraus.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public String deletePantryItem(McpTransportContext context,
                           @McpToolParam(description = "ID des Vorratspostens aus list_pantry") Long itemId) {
        return tools.write(context, user -> {
            Long id = Tools.required(itemId, "itemId");
            pantry.delete(id, user);
            return "Vorratsposten wurde gelöscht.";
        });
    }

    @McpTool(name = "delete_meal", description = "Löscht eine geplante Mahlzeit aus dem Essensplan (das Rezept bleibt).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public String deleteMeal(McpTransportContext context,
                           @McpToolParam(description = "ID der Mahlzeit aus get_meal_plan") Long mealId) {
        return tools.write(context, user -> {
            Long id = Tools.required(mealId, "mealId");
            plan.delete(id, user);
            return "Mahlzeit wurde aus dem Plan gelöscht.";
        });
    }

    @McpTool(name = "delete_shopping_item", description = "Löscht einen Artikel von der Einkaufsliste.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public String deleteShoppingItem(McpTransportContext context,
                           @McpToolParam(description = "ID des Artikels aus shopping_list") Long itemId) {
        return tools.write(context, user -> {
            Long id = Tools.required(itemId, "itemId");
            shopping.delete(id, user);
            return "Artikel wurde von der Einkaufsliste gelöscht.";
        });
    }

    @McpTool(name = "get_food", description = "Zeigt ein Lebensmittel mit Nährwerten pro 100 g.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public FoodItem getFood(McpTransportContext context,
                            @McpToolParam(description = "ID des Lebensmittels aus search_foods") Long foodId) {
        return tools.read(context, user -> food(foods.get(Tools.required(foodId, "foodId"), user)));
    }

    @McpTool(name = "get_pantry_item", description = "Zeigt einen Vorratsposten mit Menge, Ort und Haltbarkeit.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public PantryEntry getPantryItem(McpTransportContext context,
                                     @McpToolParam(description = "ID des Postens aus list_pantry") Long itemId) {
        return tools.read(context, user -> pantryEntry(pantry.get(Tools.required(itemId, "itemId"), user)));
    }

    @McpTool(name = "update_meal", description = "Ändert, was bei einer geplanten Mahlzeit gekocht wird: ein "
            + "anderes Rezept (recipeId) oder ein freier Titel. Tag, Mahlzeit und Mitesser bleiben; verschieben mit "
            + "move_meal, Mitesser mit set_meal_eaters.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public MealItem updateMeal(McpTransportContext context,
                               @McpToolParam(description = "ID der Mahlzeit aus get_meal_plan") Long mealId,
                               @McpToolParam(description = "ID des neuen Rezepts", required = false) Long recipeId,
                               @McpToolParam(description = "Freier Titel, wenn kein Rezept", required = false)
                               String title) {
        return tools.write(context, user -> meal(plan.change(Tools.required(mealId, "mealId"), recipeId, title, user),
                names()));
    }
}
