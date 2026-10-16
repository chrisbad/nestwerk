package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class PantryServiceTest {

    // Die feste Uhr steht auf Montag, 12.10.2026
    static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    @Autowired PantryService pantry;
    @Autowired PantryRepository items;
    @Autowired FoodRepository foods;
    @Autowired RecipeRepository recipes;
    @Autowired MealRepository meals;
    @Autowired MealSlotRepository slots;
    @Autowired FamilyMemberRepository members;
    @Autowired UserService userService;
    @Autowired EntityManager em;

    User chef;
    Food flour;
    Food onion;
    FamilyMember lena;
    MealSlot dinner;

    @BeforeEach
    void setUp() {
        chef = userService.createAdministrator("chef", "passwort1");
        flour = foods.findByNameIgnoreCase("Mehl").orElseThrow();
        onion = foods.findByNameIgnoreCase("Zwiebel").orElseThrow();
        lena = members.save(new FamilyMember("Lena", "#00aa00", null));
        dinner = slots.findAllByOrderBySortOrderAsc().getFirst();
    }

    private Meal meal(double portions, RecipeIngredient... ingredients) {
        Recipe recipe = new Recipe("Test", 2, null);
        for (RecipeIngredient i : ingredients) {
            recipe.addIngredient(i);
        }
        recipes.saveAndFlush(recipe);
        Meal meal = new Meal(TODAY, dinner, recipe, "Test");
        meal.putEater(lena.getId(), portions);
        return meals.saveAndFlush(meal);
    }

    private PantryItem stock(Food food, double amount, Unit unit, int days) {
        return items.saveAndFlush(new PantryItem(food, food.getName(), amount, unit, "Vorrat", TODAY.plusDays(days)));
    }

    @Test
    void cookingBooksOldestBestBeforeFirstAndRemovesEmptyItems() {
        PantryItem later = stock(flour, 300, Unit.G, 5);
        PantryItem earlier = stock(flour, 500, Unit.G, 1);
        Meal meal = meal(2, new RecipeIngredient(600.0, Unit.G, flour, "Mehl", null));

        pantry.cook(meal.getId(), Map.of(), chef);
        em.flush();
        em.clear();

        assertThat(items.findById(earlier.getId())).isEmpty();
        assertThat(items.findById(later.getId()).orElseThrow().getAmount()).isEqualTo(200.0);
        assertThat(meals.findById(meal.getId()).orElseThrow().isCooked()).isTrue();
    }

    @Test
    void scalesIngredientsToThePortionsOfTheMeal() {
        PantryItem item = stock(flour, 1000, Unit.G, 30);
        Meal meal = meal(1, new RecipeIngredient(600.0, Unit.G, flour, "Mehl", null));

        pantry.cook(meal.getId(), Map.of(), chef);

        assertThat(item.getAmount()).isEqualTo(700.0);
    }

    @Test
    void convertsBetweenPieceAndGramsViaPieceWeight() {
        PantryItem pieces = stock(onion, 3, Unit.PIECE, 10);
        PantryItem grams = stock(flour, 500, Unit.G, 10);
        Meal meal = meal(2, new RecipeIngredient(160.0, Unit.G, onion, "Zwiebel", null),
                new RecipeIngredient(2.0, Unit.PIECE, flour, "Mehl", null));
        flour.setPieceGrams(100.0);

        pantry.cook(meal.getId(), Map.of(), chef);

        assertThat(pieces.getAmount()).isEqualTo(1.0);
        assertThat(grams.getAmount()).isEqualTo(300.0);
    }

    @Test
    void neverGoesBelowZeroWhenStockIsShort() {
        PantryItem item = stock(flour, 100, Unit.G, 3);
        Meal meal = meal(2, new RecipeIngredient(300.0, Unit.G, flour, "Mehl", null));

        PantryService.CookPreview preview = pantry.cook(meal.getId(), Map.of(), chef);
        em.flush();
        em.clear();

        assertThat(items.findById(item.getId())).isEmpty();
        assertThat(preview.deductions().getFirst().missing()).isEqualTo(200.0);
        assertThat(preview.deductions().getFirst().note()).isEqualTo("Vorrat reicht nicht");
        assertThat(items.findByFoodId(flour.getId())).isEmpty();
    }

    @Test
    void incompatibleUnitsAreLeftAlone() {
        // Zwiebel hat kein Packungsgewicht: Packung lässt sich nicht in Gramm umrechnen
        PantryItem pack = stock(onion, 1, Unit.PACKAGE, 3);
        Meal meal = meal(2, new RecipeIngredient(160.0, Unit.G, onion, "Zwiebel", null));

        PantryService.CookPreview preview = pantry.cook(meal.getId(), Map.of(), chef);

        assertThat(pack.getAmount()).isEqualTo(1.0);
        assertThat(preview.deductions().getFirst().hasStock()).isFalse();
        assertThat(preview.deductions().getFirst().note()).contains("Umrechnung");
    }

    @Test
    void ingredientsWithoutFoodAreNotBooked() {
        Meal meal = meal(2, new RecipeIngredient(2.0, Unit.TBSP, null, "Spezialsoße", null));

        PantryService.CookPreview preview = pantry.cook(meal.getId(), Map.of(), chef);

        assertThat(preview.deductions().getFirst().takes()).isEmpty();
        assertThat(preview.deductions().getFirst().note()).contains("ohne Lebensmittel");
    }

    @Test
    void previewChangesNothingAndAcceptsCorrectedAmounts() {
        PantryItem item = stock(flour, 1000, Unit.G, 10);
        Meal meal = meal(2, new RecipeIngredient(600.0, Unit.G, flour, "Mehl", null));
        Long ingredientId = meal.getRecipe().getIngredients().getFirst().getId();

        PantryService.CookPreview preview = pantry.preview(meal.getId(), Map.of(ingredientId, 250.0), chef);

        assertThat(preview.deductions().getFirst().takes().getFirst().amount()).isEqualTo(250.0);
        assertThat(item.getAmount()).isEqualTo(1000.0);
        assertThat(meal.isCooked()).isFalse();

        pantry.cook(meal.getId(), Map.of(ingredientId, 0.0), chef);
        assertThat(item.getAmount()).isEqualTo(1000.0);
    }

    @Test
    void sameFoodInTwoIngredientsDoesNotOverdraw() {
        PantryItem item = stock(flour, 500, Unit.G, 10);
        Meal meal = meal(2, new RecipeIngredient(300.0, Unit.G, flour, "Mehl", null),
                new RecipeIngredient(300.0, Unit.G, flour, "Mehl zum Bestäuben", null));

        PantryService.CookPreview preview = pantry.cook(meal.getId(), Map.of(), chef);

        assertThat(items.findById(item.getId())).isEmpty();
        assertThat(preview.deductions().get(1).missing()).isEqualTo(100.0);
    }

    @Test
    void cookingTwiceIsRefused() {
        Meal meal = meal(2, new RecipeIngredient(1.0, Unit.G, flour, "Mehl", null));
        pantry.cook(meal.getId(), Map.of(), chef);

        assertThatThrownBy(() -> pantry.cook(meal.getId(), Map.of(), chef))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schon");
    }

    @Test
    void takingTheRestRemovesTheItem() {
        PantryItem item = stock(flour, 100, Unit.G, 10);

        pantry.take(item.getId(), 40, chef);
        assertThat(item.getAmount()).isEqualTo(60.0);
        pantry.take(item.getId(), 80, chef);
        em.flush();
        em.clear();

        assertThat(items.findById(item.getId())).isEmpty();
    }

    @Test
    void newItemsGetBestBeforeFromShelfLife() {
        onion.setShelfDays(14);

        PantryItem item = pantry.add(onion, 2, Unit.PIECE, "Vorrat", null, chef);

        assertThat(item.getBestBefore()).isEqualTo(TODAY.plusDays(14));
    }

    @Test
    void useUpListsRecipesWithExpiringFood() {
        stock(onion, 2, Unit.PIECE, 2);
        stock(flour, 1000, Unit.G, 200);
        Recipe soup = new Recipe("Zwiebelsuppe", 2, null);
        soup.addIngredient(new RecipeIngredient(2.0, Unit.PIECE, onion, "Zwiebel", null));
        recipes.save(soup);
        Recipe bread = new Recipe("Brot", 2, null);
        bread.addIngredient(new RecipeIngredient(500.0, Unit.G, flour, "Mehl", null));
        recipes.save(bread);

        assertThat(pantry.useUp(chef)).extracting(Recipe::getTitle).containsExactly("Zwiebelsuppe");
    }

    @Test
    void shoppersCannotCookWithoutPlanRight() {
        User guest = userService.create("gast", "passwort1", java.util.Set.of());
        Meal meal = meal(2, new RecipeIngredient(1.0, Unit.G, flour, "Mehl", null));

        assertThatThrownBy(() -> pantry.cook(meal.getId(), Map.of(), guest))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
