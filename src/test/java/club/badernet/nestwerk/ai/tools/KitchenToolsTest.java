package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.kitchen.PantryItem;
import club.badernet.nestwerk.kitchen.PantryRepository;
import club.badernet.nestwerk.kitchen.Unit;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class KitchenToolsTest {

    // Die feste Uhr steht auf Montag, 12.10.2026
    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    @Autowired KitchenTools kitchen;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberRepository members;
    @Autowired PantryRepository pantry;

    User chef;
    User guest;
    User outsider;

    @BeforeEach
    void setUp() {
        Role cooks = roleService.create("Köche", null, Set.of("KITCHEN_VIEW", "KITCHEN_RECIPES_EDIT", "KITCHEN_PLAN",
                "KITCHEN_SHOPPING"));
        chef = userService.create("koch", "passwort1", Set.of(cooks.getId()));
        Role viewers = roleService.create("Gäste", null, Set.of("KITCHEN_VIEW"));
        guest = userService.create("gast", "passwort1", Set.of(viewers.getId()));
        outsider = userService.create("fremd", "passwort1", Set.of());
        FamilyMember lena = members.save(new FamilyMember("Lena", "#00aa00", chef.getId()));
        lena.setKcalGoal(2000);
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    private KitchenTools.RecipeDetail pancakes() {
        return kitchen.createRecipe(as(chef), "Pfannkuchen", List.of("200 g Mehl", "2 Eier", "300 ml Milch",
                "1 Prise Salz", "3 EL Spezialaroma"), 2, 20, null, "schnell, süß", "Alles verrühren.", null);
    }

    @Test
    void createRecipeParsesLinesAndNamesIngredientsWithoutFood() {
        KitchenTools.RecipeDetail recipe = pancakes();

        assertThat(recipe.recipe().title()).isEqualTo("Pfannkuchen");
        assertThat(recipe.ingredients()).extracting(KitchenTools.IngredientItem::food)
                .containsExactly("Mehl", "Ei", "Milch", "Salz", null);
        assertThat(recipe.ingredientsWithoutFood()).containsExactly("Spezialaroma");
        assertThat(recipe.recipe().kcalPerServing()).isGreaterThan(300);
        assertThat(recipe.recipe().tags()).containsExactly("schnell", "süß");
    }

    @Test
    void getRecipeScalesAmounts() {
        Long id = pancakes().recipe().id();

        KitchenTools.RecipeDetail scaled = kitchen.getRecipe(as(chef), id, 4.0);

        assertThat(scaled.ingredients().getFirst().amount()).isEqualTo(400.0);
        assertThat(kitchen.searchRecipes(as(guest), "pfann")).extracting(KitchenTools.RecipeItem::id)
                .containsExactly(id);
    }

    @Test
    void searchAndCreateFoods() {
        assertThat(kitchen.searchFoods(as(guest), "zwiebel")).extracting(KitchenTools.FoodItem::name)
                .contains("Zwiebel");

        KitchenTools.FoodItem created = kitchen.createFood(as(chef), "Kichererbsenmehl", "pantry", 340.0, 12.0, 2.0, 70.0,
                null, 1000.0, 365);

        assertThat(created.category()).isEqualTo("PANTRY");
        assertThat(kitchen.searchFoods(as(chef), "kichererbsenm")).hasSize(1);
        assertThatThrownBy(() -> kitchen.createFood(as(guest), "Verboten", null, null, null, null, null, null, null,
                null)).isInstanceOf(Tools.ToolException.class).hasMessageContaining("Keine Berechtigung");
    }

    @Test
    void plansMealsAndShowsThePlan() {
        Long id = pancakes().recipe().id();

        KitchenTools.MealItem meal = kitchen.planMeal(as(chef), MONDAY.plusDays(1).toString(), "abendessen", id, null);
        kitchen.planMeal(as(chef), MONDAY.toString(), "Mittagessen", null, "Reste");

        assertThat(meal.slot()).isEqualTo("Abendessen");
        assertThat(meal.eaters()).hasSize(1).first().asString().startsWith("Lena ×");
        assertThat(meal.kcalPerServing()).isNotNull();
        assertThat(kitchen.getMealPlan(as(guest), null, null)).extracting(KitchenTools.MealItem::title)
                .containsExactly("Reste", "Pfannkuchen");
        assertThatThrownBy(() -> kitchen.planMeal(as(chef), MONDAY.toString(), "Mitternachtssnack", id, null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Möglich");
        assertThatThrownBy(() -> kitchen.planMeal(as(guest), MONDAY.toString(), "Abendessen", id, null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Keine Berechtigung");
    }

    @Test
    void shoppingListAndPantry() {
        kitchen.addShoppingItem(as(chef), "2 Dosen Tomaten");
        pantry.save(new PantryItem(null, "Milch", 1, Unit.L, "Kühlschrank", MONDAY.plusDays(1)));

        assertThat(kitchen.shoppingList(as(guest))).extracting(KitchenTools.ShoppingEntry::name,
                KitchenTools.ShoppingEntry::amount).containsExactly(org.assertj.core.groups.Tuple.tuple("Tomaten",
                "2 Dose"));
        assertThat(kitchen.listPantry(as(guest))).extracting(KitchenTools.PantryEntry::name).containsExactly("Milch");
        assertThatThrownBy(() -> kitchen.addShoppingItem(as(guest), "Brot")).isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void editsRecipesFoodsAndMeals() {
        Long id = pancakes().recipe().id();

        KitchenTools.RecipeDetail changed = kitchen.updateRecipe(as(chef), id, "Dicke Pfannkuchen", null, null, 25,
                null, null, null, null);
        assertThat(changed.recipe().title()).isEqualTo("Dicke Pfannkuchen");
        assertThat(changed.recipe().minutes()).isEqualTo(25);
        assertThat(changed.ingredients()).hasSize(5);
        assertThat(kitchen.updateRecipe(as(chef), id, null, List.of("100 g Mehl"), 1, null, null, null, null, null)
                .ingredients()).hasSize(1);
        assertThatThrownBy(() -> kitchen.updateRecipe(as(guest), id, "Nein", null, null, null, null, null, null, null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Keine Berechtigung");

        KitchenTools.FoodItem food = kitchen.createFood(as(chef), "Testmehl", null, 300.0, null, null, null, null, null,
                null);
        KitchenTools.FoodItem updated = kitchen.updateFood(as(chef), food.id(), null, "pantry", 350.0, null, null, null,
                null, null, null);
        assertThat(updated.kcalPer100g()).isEqualTo(350.0);
        assertThat(updated.category()).isEqualTo("PANTRY");
        assertThat(updated.name()).isEqualTo("Testmehl");

        KitchenTools.MealItem meal = kitchen.planMeal(as(chef), MONDAY.toString(), "Abendessen", id, null);
        KitchenTools.MealItem moved = kitchen.moveMeal(as(chef), meal.id(), MONDAY.plusDays(2).toString(),
                "Mittagessen");
        assertThat(moved.day()).isEqualTo(MONDAY.plusDays(2));
        assertThat(moved.slot()).isEqualTo("Mittagessen");
        Long lena = members.findAll().getFirst().getId();
        assertThat(kitchen.setMealEaters(as(chef), meal.id(), List.of(new KitchenTools.EaterInput(lena, 1.5)))
                .eaters()).containsExactly("Lena ×1.5");
        assertThat(kitchen.setMealEaters(as(chef), meal.id(), List.of()).eaters()).isEmpty();
        assertThat(kitchen.listMealSlots(as(guest))).contains("Abendessen", "Mittagessen");
    }

    @Test
    void shoppingPantryAndCooking() {
        Long id = pancakes().recipe().id();
        KitchenTools.MealItem meal = kitchen.planMeal(as(chef), MONDAY.plusDays(1).toString(), "Abendessen", id, null);

        assertThat(kitchen.generateShoppingList(as(chef), MONDAY.toString(), MONDAY.plusDays(6).toString()))
                .contains("Artikel");
        List<KitchenTools.ShoppingEntry> list = kitchen.shoppingList(as(chef));
        assertThat(list).isNotEmpty();
        Long itemId = list.getFirst().id();
        assertThat(kitchen.toggleShoppingItem(as(chef), itemId).status()).isEqualTo("BOUGHT");
        KitchenTools.PantryEntry stowed = kitchen.stowShoppingItem(as(chef), itemId, 500.0, "g", "Vorrat", null);
        assertThat(stowed.location()).isEqualTo("Vorrat");

        KitchenTools.PantryEntry milk = kitchen.addPantryItem(as(chef), "Milch", 1.0, "l", "Kühlschrank",
                MONDAY.plusDays(3).toString());
        assertThat(kitchen.updatePantryItem(as(chef), milk.id(), 2.0, null, null, null).amount()).startsWith("2");
        assertThat(kitchen.takeFromPantry(as(chef), milk.id(), 0.5)).isEqualTo("Entnommen.");
        assertThatThrownBy(() -> kitchen.addPantryItem(as(chef), "Käse", 1.0, "Eimerweise", null, null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Einheit");
        assertThat(kitchen.recipesToUseUp(as(guest))).isNotNull();

        KitchenTools.CookResult preview = kitchen.cookMeal(as(chef), meal.id(), true, null);
        assertThat(preview.booked()).isFalse();
        assertThat(kitchen.getMealPlan(as(chef), MONDAY.toString(), null)).noneMatch(KitchenTools.MealItem::cooked);
        KitchenTools.CookResult cooked = kitchen.cookMeal(as(chef), meal.id(), null, null);
        assertThat(cooked.booked()).isTrue();
        assertThat(kitchen.getMealPlan(as(chef), MONDAY.toString(), null)).anyMatch(KitchenTools.MealItem::cooked);
        assertThatThrownBy(() -> kitchen.cookMeal(as(chef), meal.id(), null, null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("schon");
        assertThat(kitchen.fillMealPlan(as(chef), MONDAY.toString())).isNotBlank();
    }

    @Test
    void usersWithoutKitchenRightsSeeNothing() {
        assertThatThrownBy(() -> kitchen.searchRecipes(as(outsider), null)).isInstanceOf(Tools.ToolException.class)
                .hasMessageContaining("Keine Berechtigung");
        assertThatThrownBy(() -> kitchen.listPantry(as(outsider))).isInstanceOf(Tools.ToolException.class);
        assertThatThrownBy(() -> kitchen.getRecipe(as(chef), 999_999L, null)).isInstanceOf(Tools.ToolException.class)
                .hasMessageContaining("nicht gefunden");
    }
}
