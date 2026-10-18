package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class ShoppingListServiceTest {

    // Die feste Uhr steht auf Montag, 12.10.2026
    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    @Autowired ShoppingListService service;
    @Autowired ShoppingRepository shopping;
    @Autowired PantryRepository pantry;
    @Autowired FoodRepository foods;
    @Autowired RecipeRepository recipes;
    @Autowired MealRepository meals;
    @Autowired MealSlotRepository slots;
    @Autowired FamilyMemberRepository members;
    @Autowired UserService userService;

    User chef;
    FamilyMember lena;
    MealSlot dinner;
    Food flour;
    Food onion;

    @BeforeEach
    void setUp() {
        chef = userService.createAdministrator("chef", "passwort1");
        lena = members.save(new FamilyMember("Lena", "#00aa00", null));
        dinner = slots.findAllByOrderBySortOrderAsc().getFirst();
        flour = foods.findByNameIgnoreCase("Mehl").orElseThrow();
        flour.setPackageGrams(1000.0);
        onion = foods.findByNameIgnoreCase("Zwiebel").orElseThrow();
    }

    private Meal plan(LocalDate day, double portions, RecipeIngredient... ingredients) {
        Recipe recipe = new Recipe("Gericht " + System.nanoTime(), 2, null);
        for (RecipeIngredient i : ingredients) {
            recipe.addIngredient(i);
        }
        recipes.saveAndFlush(recipe);
        Meal meal = new Meal(day, dinner, recipe, recipe.getTitle());
        meal.putEater(lena.getId(), portions);
        return meals.saveAndFlush(meal);
    }

    private List<ShoppingItem> open() {
        return shopping.findByStatusAndOrigin(ShoppingStatus.OPEN, ShoppingOrigin.PLAN);
    }

    @Test
    void needIsPlanMinusPantryRoundedToPackages() {
        plan(MONDAY, 2, new RecipeIngredient(600.0, Unit.G, flour, "Mehl", null));
        plan(MONDAY.plusDays(1), 2, new RecipeIngredient(500.0, Unit.G, flour, "Mehl", null));
        pantry.save(new PantryItem(flour, "Mehl", 300, Unit.G, "Vorrat", null));

        service.generate(MONDAY, MONDAY.plusDays(6), chef);

        // 1100 g Bedarf - 300 g Vorrat = 800 g -> eine 1000-g-Packung
        assertThat(open()).extracting(ShoppingItem::getName, ShoppingItem::getAmount, ShoppingItem::getUnit)
                .containsExactly(tuple("Mehl", 1000.0, Unit.G));
    }

    @Test
    void piecesStayPiecesAndPantryInGramsIsSubtracted() {
        plan(MONDAY, 2, new RecipeIngredient(3.0, Unit.PIECE, onion, "Zwiebel", null));
        pantry.save(new PantryItem(onion, "Zwiebel", 80, Unit.G, "Vorrat", null));

        service.generate(MONDAY, MONDAY, chef);

        // 240 g - 80 g = 160 g = 2 Stück
        assertThat(open()).extracting(ShoppingItem::getAmount, ShoppingItem::getUnit)
                .containsExactly(tuple(2.0, Unit.PIECE));
    }

    @Test
    void coveredNeedCreatesNothing() {
        plan(MONDAY, 2, new RecipeIngredient(200.0, Unit.G, flour, "Mehl", null));
        pantry.save(new PantryItem(flour, "Mehl", 500, Unit.G, "Vorrat", null));

        assertThat(service.generate(MONDAY, MONDAY, chef)).isZero();
        assertThat(open()).isEmpty();
    }

    @Test
    void ingredientsWithoutFoodAreSummedByTextWithoutPantryDeduction() {
        plan(MONDAY, 2, new RecipeIngredient(1.0, Unit.CAN, null, "Kokosmilch", null));
        plan(MONDAY.plusDays(1), 2, new RecipeIngredient(1.0, Unit.CAN, null, "kokosmilch", null));

        service.generate(MONDAY, MONDAY.plusDays(6), chef);

        assertThat(open()).extracting(ShoppingItem::getName, ShoppingItem::getAmount, ShoppingItem::getUnit)
                .containsExactly(tuple("Kokosmilch", 2.0, Unit.CAN));
    }

    @Test
    void cookedMealsAndMealsOutsideTheRangeAreIgnored() {
        Meal cooked = plan(MONDAY, 2, new RecipeIngredient(500.0, Unit.G, flour, "Mehl", null));
        cooked.setCookedAt(java.time.Instant.now());
        plan(MONDAY.plusDays(10), 2, new RecipeIngredient(500.0, Unit.G, flour, "Mehl", null));

        assertThat(service.generate(MONDAY, MONDAY.plusDays(6), chef)).isZero();
    }

    @Test
    void generatingAgainReplacesOnlyOpenPlanItems() {
        plan(MONDAY, 2, new RecipeIngredient(500.0, Unit.G, flour, "Mehl", null));
        service.generate(MONDAY, MONDAY, chef);
        ShoppingItem manual = service.addManual("Klopapier", chef);
        ShoppingItem kept = shopping.save(ShoppingItem.fromPlan("Butter", null, 250.0, Unit.G));
        kept.markSent(java.time.Instant.now());

        service.generate(MONDAY, MONDAY, chef);

        assertThat(shopping.findAll()).extracting(ShoppingItem::getName)
                .containsExactlyInAnyOrder("Mehl", "Klopapier", "Butter");
        assertThat(shopping.findById(manual.getId())).isPresent();
        assertThat(shopping.findById(kept.getId())).isPresent();
    }

    @Test
    void whatIsAlreadyAtBringIsNotCreatedTwice() {
        plan(MONDAY, 2, new RecipeIngredient(500.0, Unit.G, flour, "Mehl", null));
        service.generate(MONDAY, MONDAY, chef);
        open().forEach(i -> i.markSent(java.time.Instant.now()));

        assertThat(service.generate(MONDAY, MONDAY, chef)).isZero();
        assertThat(shopping.findAll()).hasSize(1);
    }

    @Test
    void manualItemIsParsedAndMatchedToFood() {
        ShoppingItem item = service.addManual("2 Zwiebeln", chef);

        assertThat(item.getFood()).isEqualTo(onion);
        assertThat(item.getAmount()).isEqualTo(2.0);
        assertThat(item.getOrigin()).isEqualTo(ShoppingOrigin.MANUAL);
        assertThatThrownBy(() -> service.addManual("  ", chef)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkingOffMarksBoughtAndAgainOpensIt() {
        ShoppingItem item = service.addManual("Klopapier", chef);

        service.toggle(item.getId(), chef);
        assertThat(item.getStatus()).isEqualTo(ShoppingStatus.BOUGHT);
        service.toggle(item.getId(), chef);
        assertThat(item.getStatus()).isEqualTo(ShoppingStatus.OPEN);
    }

    @Test
    void stowingMovesBoughtItemsIntoThePantryWithSuggestedBestBefore() {
        onion.setShelfDays(14);
        ShoppingItem item = service.addManual("2 Zwiebeln", chef);
        assertThatThrownBy(() -> service.stow(item.getId(), 2, Unit.PIECE, "Vorrat", null, chef))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("noch nicht");
        service.toggle(item.getId(), chef);

        assertThat(service.suggestedBestBefore(item)).isEqualTo(MONDAY.plusDays(14));
        PantryItem stored = service.stow(item.getId(), 2, Unit.PIECE, "Vorrat", MONDAY.plusDays(14), chef);

        assertThat(stored.getFood()).isEqualTo(onion);
        assertThat(stored.getBestBefore()).isEqualTo(MONDAY.plusDays(14));
        assertThat(item.getStatus()).isEqualTo(ShoppingStatus.STORED);
        assertThat(service.list(chef)).doesNotContain(item);
        assertThat(service.toStow(chef)).isEmpty();
    }
}
