package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.kitchen.BringSync;
import club.badernet.nestwerk.kitchen.FoodService;
import club.badernet.nestwerk.kitchen.IngredientInput;
import club.badernet.nestwerk.kitchen.KitchenAccess;
import club.badernet.nestwerk.kitchen.KitchenPermission;
import club.badernet.nestwerk.kitchen.KitchenSettings;
import club.badernet.nestwerk.kitchen.KitchenSettingsService;
import club.badernet.nestwerk.kitchen.MealSlot;
import club.badernet.nestwerk.kitchen.MealSlotRepository;
import club.badernet.nestwerk.kitchen.Recipe;
import club.badernet.nestwerk.kitchen.RecipeInput;
import club.badernet.nestwerk.kitchen.RecipeService;
import club.badernet.nestwerk.kitchen.integration.ImportedRecipe;
import club.badernet.nestwerk.kitchen.integration.RecipeImporter;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Küche: Einstellungen (Mahlzeiten, Kalorienziele, Bring!-Liste), Rezept-Import von Webseiten und Barcode-Suche.
 * Die Bring!-Anmeldung mit Passwort läuft bewusst nur über die Weboberfläche.
 */
@Component
public class KitchenSettingsTools {

    public record SlotItem(Long slotId, String name, int sharePercent, boolean autoFill) {
    }

    public record GoalItem(Long memberId, String member, Integer kcalGoal) {
    }

    public record BringListItem(String uuid, String name, boolean chosen) {
    }

    public record Settings(List<SlotItem> slots, List<GoalItem> calorieGoals, boolean bringConnected,
                           String bringEmail, String bringList) {
    }

    public record RecipePreview(String title, String description, Integer servings, Integer minutes, String tags,
                                String sourceUrl, String steps, List<String> ingredients, Long savedRecipeId) {
    }

    public record BarcodeResult(KitchenTools.FoodItem food, boolean createdFromOpenFoodFacts) {
    }

    private final Tools tools;
    private final KitchenSettingsService settingsService;
    private final KitchenSettings kitchenSettings;
    private final MealSlotRepository slots;
    private final FamilyMemberRepository members;
    private final BringSync bring;
    private final RecipeImporter importer;
    private final RecipeService recipes;
    private final FoodService foods;
    private final KitchenAccess access;

    public KitchenSettingsTools(Tools tools, KitchenSettingsService settingsService, KitchenSettings kitchenSettings,
                                MealSlotRepository slots, FamilyMemberRepository members, BringSync bring,
                                RecipeImporter importer, RecipeService recipes, FoodService foods,
                                KitchenAccess access) {
        this.tools = tools;
        this.settingsService = settingsService;
        this.kitchenSettings = kitchenSettings;
        this.slots = slots;
        this.members = members;
        this.bring = bring;
        this.importer = importer;
        this.recipes = recipes;
        this.foods = foods;
        this.access = access;
    }

    @McpTool(name = "kitchen_settings", description = "Zeigt die Küchen-Einstellungen: Mahlzeiten mit ID, Kalorien-"
            + "Anteil und Auto-Befüllung, Kalorienziele je Mitglied und den Stand der Bring!-Anbindung.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Settings kitchenSettings(McpTransportContext context) {
        return tools.read(context, user -> settings());
    }

    @McpTool(name = "set_calorie_goal", description = "Setzt das Tages-Kalorienziel eines Mitglieds (500–10000 kcal); "
            + "ohne kcal wird das Ziel gelöscht. Braucht das Recht für Küchen-Einstellungen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public Settings setCalorieGoal(McpTransportContext context,
                                   @McpToolParam(description = "Mitglieds-ID aus list_members") Long memberId,
                                   @McpToolParam(description = "Kalorienziel pro Tag", required = false) Integer kcal) {
        return tools.write(context, user -> {
            Map<Long, Integer> goals = new HashMap<>();
            goals.put(Tools.required(memberId, "memberId"), kcal);
            settingsService.setGoals(goals, user);
            return settings();
        });
    }

    @McpTool(name = "create_meal_slot", description = "Legt eine Mahlzeit des Essensplans an (z. B. Zwischenmahlzeit). "
            + "sharePercent: Anteil am Tagesbedarf (0–100), autoFill: wird beim Befüllen des Plans berücksichtigt.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public SlotItem createMealSlot(McpTransportContext context,
                                   @McpToolParam(description = "Name, z. B. Brotzeit") String name,
                                   @McpToolParam(description = "Anteil am Tagesbedarf in Prozent", required = false)
                                   Integer sharePercent,
                                   @McpToolParam(description = "Beim automatischen Befüllen berücksichtigen",
                                           required = false) Boolean autoFill) {
        return tools.write(context, user -> slot(settingsService.createSlot(name,
                sharePercent == null ? 0 : sharePercent, Boolean.TRUE.equals(autoFill), user)));
    }

    @McpTool(name = "update_meal_slot", description = "Ändert eine Mahlzeit des Essensplans (slotId aus "
            + "kitchen_settings). Nicht angegebene Felder bleiben.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public SlotItem updateMealSlot(McpTransportContext context,
                                   @McpToolParam(description = "slotId aus kitchen_settings") Long slotId,
                                   @McpToolParam(description = "Name", required = false) String name,
                                   @McpToolParam(description = "Anteil am Tagesbedarf in Prozent", required = false)
                                   Integer sharePercent,
                                   @McpToolParam(description = "Beim automatischen Befüllen berücksichtigen",
                                           required = false) Boolean autoFill) {
        return tools.write(context, user -> {
            MealSlot current = slots.findById(Tools.required(slotId, "slotId"))
                    .orElseThrow(() -> new Tools.ToolException("Mahlzeit nicht gefunden"));
            settingsService.updateSlot(slotId, name != null ? name : current.getName(),
                    sharePercent != null ? sharePercent : current.getShare(),
                    autoFill != null ? autoFill : current.isAutoFill(), user);
            return slot(slots.findById(slotId).orElseThrow());
        });
    }

    @McpTool(name = "list_bring_lists", description = "Zeigt die Einkaufslisten des verbundenen Bring!-Kontos und "
            + "welche davon gewählt ist. Die Anmeldung bei Bring! geschieht in der Weboberfläche.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = true))
    public List<BringListItem> listBringLists(McpTransportContext context) {
        return tools.read(context, user -> {
            String chosen = kitchenSettings.bring().listUuid();
            return bring.lists(user).stream()
                    .map(l -> new BringListItem(l.uuid(), l.name(), l.uuid().equals(chosen))).toList();
        });
    }

    @McpTool(name = "choose_bring_list", description = "Wählt die Bring!-Liste, mit der die Einkaufsliste "
            + "abgeglichen wird (uuid aus list_bring_lists).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = true,
                    idempotentHint = true))
    public String chooseBringList(McpTransportContext context,
                                  @McpToolParam(description = "uuid aus list_bring_lists") String uuid) {
        return tools.write(context, user -> {
            bring.chooseList(Tools.required(uuid, "uuid"), user);
            return "Liste gewählt: " + kitchenSettings.bring().listName();
        });
    }

    @McpTool(name = "import_recipe_from_url", description = "Liest ein Rezept von einer Webseite (schema.org-Rezept). "
            + "Mit save=false (Standard) kommt nur eine Vorschau zurück; mit save=true wird das Rezept angelegt. "
            + "Zutaten werden wie bei create_recipe Lebensmitteln zugeordnet.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = true))
    public RecipePreview importRecipeFromUrl(McpTransportContext context,
                                             @McpToolParam(description = "Adresse der Rezeptseite") String url,
                                             @McpToolParam(description = "true = Rezept direkt anlegen",
                                                     required = false) Boolean save) {
        return tools.write(context, user -> {
            access.require(user, KitchenPermission.KITCHEN_RECIPES_EDIT);
            ImportedRecipe r = importer.importFrom(Tools.required(url, "url"));
            Long savedId = null;
            if (Boolean.TRUE.equals(save)) {
                List<IngredientInput> lines = RecipeService.parseLines(String.join("\n", r.ingredientLines()));
                if (lines.isEmpty()) {
                    throw new Tools.ToolException("Keine Zutaten erkannt.");
                }
                Recipe recipe = recipes.create(new RecipeInput(r.title(), r.description(),
                        r.servings() == null ? 2 : r.servings(), r.minutes(), r.tags(), r.sourceUrl(), r.steps(),
                        lines), user);
                savedId = recipe.getId();
            }
            return new RecipePreview(r.title(), r.description(), r.servings(), r.minutes(), r.tags(), r.sourceUrl(),
                    r.steps(), r.ingredientLines(), savedId);
        });
    }

    @McpTool(name = "find_food_by_barcode", description = "Sucht ein Lebensmittel per Barcode (6–14 Ziffern): erst in "
            + "Nestwerk, sonst bei Open Food Facts – dann wird es angelegt (Abteilung danach prüfen). Die foodId "
            + "gilt für add_pantry_item und add_shopping_item.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = true))
    public BarcodeResult findFoodByBarcode(McpTransportContext context,
                                           @McpToolParam(description = "Barcode, nur Ziffern") String barcode) {
        return tools.write(context, user -> foods.fromBarcode(Tools.required(barcode, "barcode"), user)
                .map(m -> new BarcodeResult(KitchenTools.foodItem(m.food()), m.created()))
                .orElseThrow(() -> new Tools.ToolException("Produkt unbekannt – bitte mit create_food anlegen.")));
    }

    private Settings settings() {
        KitchenSettings.BringAccount account = kitchenSettings.bring();
        return new Settings(slots.findAllByOrderBySortOrderAsc().stream().map(KitchenSettingsTools::slot).toList(),
                members.findAllByOrderByNameAsc().stream().map(KitchenSettingsTools::goal).toList(),
                account.configured(), account.email(), account.listName());
    }

    private static SlotItem slot(MealSlot s) {
        return new SlotItem(s.getId(), s.getName(), s.getShare(), s.isAutoFill());
    }

    private static GoalItem goal(FamilyMember m) {
        return new GoalItem(m.getId(), m.getName(), m.getKcalGoal());
    }
}
