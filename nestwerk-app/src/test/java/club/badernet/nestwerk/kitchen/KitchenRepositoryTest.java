package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.security.PermissionDefinition;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class KitchenRepositoryTest {

    @Autowired FoodRepository foods;
    @Autowired RecipeRepository recipes;
    @Autowired MealSlotRepository slots;
    @Autowired MealRepository meals;
    @Autowired PantryRepository pantry;
    @Autowired ShoppingRepository shopping;
    @Autowired FamilyMemberRepository members;
    @Autowired PermissionCatalog catalog;
    @Autowired EntityManager entityManager;

    @Test
    void seedContainsCommonFoodsWithPieceWeight() {
        Food onion = foods.findByNameIgnoreCase("zwiebel").orElseThrow();
        assertThat(onion.getPieceGrams()).isEqualTo(80.0);
        assertThat(onion.getKcal()).isEqualTo(40.0);
        assertThat(onion.getCategory()).isEqualTo(FoodCategory.PRODUCE);
        assertThat(onion.getSource()).isEqualTo(FoodSource.SEED);
        assertThat(foods.count()).isGreaterThan(150);
    }

    @Test
    void storesRecipeWithIngredientsInOrder() {
        Food flour = foods.findByNameIgnoreCase("Mehl").orElseThrow();
        Recipe recipe = new Recipe("Pfannkuchen", 4, null);
        recipe.setMinutes(25);
        recipe.setTags("schnell, süß");
        recipe.setSteps("Alles verrühren.");
        recipe.addIngredient(new RecipeIngredient(250.0, Unit.G, flour, "250 g Mehl", null));
        recipe.addIngredient(new RecipeIngredient(null, null, null, "Prise Salz", "nach Geschmack"));
        Long id = recipes.saveAndFlush(recipe).getId();
        entityManager.clear();

        Recipe loaded = recipes.findById(id).orElseThrow();
        assertThat(loaded.getIngredients()).extracting(RecipeIngredient::getText)
                .containsExactly("250 g Mehl", "Prise Salz");
        assertThat(loaded.getIngredients().getFirst().getFood().getName()).isEqualTo("Mehl");
        assertThat(loaded.getIngredients().getFirst().getUnit()).isEqualTo(Unit.G);
        assertThat(loaded.tagList()).containsExactly("schnell", "süß");
    }

    @Test
    void storesMealWithEatersAndPantryAndShoppingItems() {
        FamilyMember lena = members.save(new FamilyMember("Lena", "#00aa00", null));
        lena.setKcalGoal(2000);
        MealSlot dinner = slots.findAllByOrderBySortOrderAsc().stream()
                .filter(s -> s.getName().equals("Abendessen")).findFirst().orElseThrow();
        assertThat(dinner.isAutoFill()).isTrue();
        assertThat(dinner.getShare()).isEqualTo(30);

        Meal meal = new Meal(LocalDate.of(2026, 10, 20), dinner, null, "Brotzeit");
        meal.putEater(lena.getId(), 1.5);
        Long id = meals.saveAndFlush(meal).getId();
        Food milk = foods.findByNameIgnoreCase("Milch").orElseThrow();
        pantry.saveAndFlush(new PantryItem(milk, "Milch", 1000, Unit.ML, "Kühlschrank", LocalDate.of(2026, 10, 25)));
        shopping.saveAndFlush(ShoppingItem.manual("Brot", null, null, null));
        entityManager.clear();

        Meal loaded = meals.findById(id).orElseThrow();
        assertThat(loaded.getEaters()).extracting(MealEater::getMemberId, MealEater::getPortions)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(lena.getId(), 1.5));
        assertThat(members.findById(lena.getId()).orElseThrow().getKcalGoal()).isEqualTo(2000);
        assertThat(meals.findByDayBetweenOrderByDayAsc(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)))
                .hasSize(1);
        assertThat(pantry.findAll()).singleElement().extracting(PantryItem::getUnit).isEqualTo(Unit.ML);
        assertThat(shopping.findAll()).singleElement()
                .satisfies(i -> {
                    assertThat(i.getStatus()).isEqualTo(ShoppingStatus.OPEN);
                    assertThat(i.getOrigin()).isEqualTo(ShoppingOrigin.MANUAL);
                });
    }

    @Test
    void kitchenPermissionsAreInTheCatalog() {
        List<PermissionDefinition> kitchen = catalog.byModule().get("Küche");
        assertThat(kitchen).extracting(PermissionDefinition::key).containsExactly("KITCHEN_VIEW",
                "KITCHEN_RECIPES_EDIT", "KITCHEN_PLAN", "KITCHEN_SHOPPING", "KITCHEN_SETTINGS");
        assertThat(catalog.codeKeys()).contains("KITCHEN_SETTINGS");
    }
}
