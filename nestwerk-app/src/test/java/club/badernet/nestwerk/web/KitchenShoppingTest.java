package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.kitchen.Food;
import club.badernet.nestwerk.kitchen.FoodRepository;
import club.badernet.nestwerk.kitchen.Meal;
import club.badernet.nestwerk.kitchen.MealRepository;
import club.badernet.nestwerk.kitchen.MealSlotRepository;
import club.badernet.nestwerk.kitchen.PantryItem;
import club.badernet.nestwerk.kitchen.PantryRepository;
import club.badernet.nestwerk.kitchen.Recipe;
import club.badernet.nestwerk.kitchen.RecipeIngredient;
import club.badernet.nestwerk.kitchen.RecipeRepository;
import club.badernet.nestwerk.kitchen.ShoppingItem;
import club.badernet.nestwerk.kitchen.ShoppingRepository;
import club.badernet.nestwerk.kitchen.ShoppingStatus;
import club.badernet.nestwerk.kitchen.Unit;
import club.badernet.nestwerk.kitchen.integration.BringApi;
import club.badernet.nestwerk.kitchen.integration.BringException;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;

import static club.badernet.nestwerk.web.KitchenRecipesTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class KitchenShoppingTest {

    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    @MockitoBean BringApi bringApi;

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberRepository members;
    @Autowired FoodRepository foods;
    @Autowired RecipeRepository recipes;
    @Autowired MealRepository meals;
    @Autowired MealSlotRepository slots;
    @Autowired PantryRepository pantry;
    @Autowired ShoppingRepository shopping;

    static RequestPostProcessor shopper() {
        return as("einkauf", "KITCHEN_VIEW", "KITCHEN_PLAN", "KITCHEN_SHOPPING");
    }

    static RequestPostProcessor viewer() {
        return as("schau", "KITCHEN_VIEW");
    }

    static RequestPostProcessor admin() {
        return as("verwalter", "KITCHEN_VIEW", "KITCHEN_SETTINGS");
    }

    @BeforeEach
    void setUp() {
        Role role = roleService.create("Einkäufer", null, Set.of("KITCHEN_VIEW", "KITCHEN_PLAN", "KITCHEN_SHOPPING"));
        userService.create("einkauf", "passwort1", Set.of(role.getId()));
        Role view = roleService.create("Zuschauer", null, Set.of("KITCHEN_VIEW"));
        userService.create("schau", "passwort1", Set.of(view.getId()));
        Role settings = roleService.create("Verwalter", null, Set.of("KITCHEN_VIEW", "KITCHEN_SETTINGS"));
        userService.create("verwalter", "passwort1", Set.of(settings.getId()));
    }

    private Meal plannedPasta() {
        FamilyMember lena = members.save(new FamilyMember("Lena", "#00aa00", null));
        Food flour = foods.findByNameIgnoreCase("Mehl").orElseThrow();
        Recipe recipe = new Recipe("Nudelteig", 2, null);
        recipe.addIngredient(new RecipeIngredient(400.0, Unit.G, flour, "Mehl", null));
        recipes.save(recipe);
        Meal meal = new Meal(MONDAY, slots.findAllByOrderBySortOrderAsc().getFirst(), recipe, "Nudelteig");
        meal.putEater(lena.getId(), 2);
        return meals.saveAndFlush(meal);
    }

    @Test
    void pantryPageListsItemsGroupedAndFlagsExpiry() throws Exception {
        pantry.save(new PantryItem(null, "Milch", 1, Unit.L, "Kühlschrank", MONDAY.plusDays(1)));
        pantry.save(new PantryItem(null, "Joghurt", 4, Unit.PIECE, "Kühlschrank", MONDAY.minusDays(1)));
        pantry.save(new PantryItem(null, "Reis", 1, Unit.KG, "Vorrat", null));

        mockMvc.perform(get("/kitchen/pantry").with(viewer())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Kühlschrank")))
                .andExpect(content().string(containsString("läuft morgen ab")))
                .andExpect(content().string(containsString("abgelaufen")))
                .andExpect(content().string(containsString("Reis")));
    }

    @Test
    void addingToThePantryNeedsTheShoppingRight() throws Exception {
        mockMvc.perform(post("/kitchen/pantry").with(csrf()).with(viewer()).param("name", "Milch")
                .param("amount", "1").param("unit", "L")).andExpect(status().isForbidden());

        mockMvc.perform(post("/kitchen/pantry").with(csrf()).with(shopper()).param("name", "Milch")
                        .param("amount", "1,5").param("unit", "L").param("location", "Kühlschrank")
                        .param("bestBefore", "2026-10-20"))
                .andExpect(redirectedUrl("/kitchen/pantry")).andExpect(flash().attributeExists("success"));

        assertThat(pantry.findAll()).extracting(PantryItem::getName, PantryItem::getAmount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Milch", 1.5));
    }

    @Test
    void cookSheetPreviewsAndConfirmBooksFromThePantry() throws Exception {
        Meal meal = plannedPasta();
        PantryItem flour = pantry.save(new PantryItem(foods.findByNameIgnoreCase("Mehl").orElseThrow(), "Mehl", 1000,
                Unit.G, "Vorrat", MONDAY.plusDays(30)));

        mockMvc.perform(get("/kitchen/plan/meals/{id}/cook", meal.getId()).with(shopper())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Mehl")))
                .andExpect(content().string(containsString("von 400 g")));
        assertThat(flour.getAmount()).isEqualTo(1000.0);

        Long ingredientId = meal.getRecipe().getIngredients().getFirst().getId();
        mockMvc.perform(post("/kitchen/plan/meals/{id}/cook", meal.getId()).with(csrf()).with(shopper())
                        .param("amount_" + ingredientId, "500"))
                .andExpect(redirectedUrl("/kitchen/plan?week=" + MONDAY)).andExpect(flash().attributeExists("success"));

        assertThat(flour.getAmount()).isEqualTo(500.0);
        assertThat(meal.isCooked()).isTrue();
        mockMvc.perform(post("/kitchen/plan/meals/{id}/cook", meal.getId()).with(csrf()).with(shopper()))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void viewersMayLookAtButNotConfirmCooking() throws Exception {
        Meal meal = plannedPasta();

        mockMvc.perform(get("/kitchen/plan/meals/{id}/cook", meal.getId()).with(viewer())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Abbuchen und als gekocht"))));
        mockMvc.perform(post("/kitchen/plan/meals/{id}/cook", meal.getId()).with(csrf()).with(viewer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void generatingListSendingAndStowingWorkEndToEnd() throws Exception {
        plannedPasta();
        mockMvc.perform(post("/kitchen/shopping/generate").with(csrf()).with(shopper())
                        .param("from", "2026-10-12").param("to", "2026-10-18"))
                .andExpect(flash().attribute("success", containsString("1 Artikel")));
        mockMvc.perform(post("/kitchen/shopping/items").with(csrf()).with(shopper()).param("line", "Klopapier"))
                .andExpect(redirectedUrl("/kitchen/shopping"));

        mockMvc.perform(get("/kitchen/shopping").with(viewer())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Mehl")))
                .andExpect(content().string(containsString("1.000 g")))
                .andExpect(content().string(containsString("Klopapier")))
                .andExpect(content().string(containsString("Vorrat")));

        ShoppingItem flour = shopping.findAll().stream().filter(i -> i.getName().equals("Mehl")).findFirst()
                .orElseThrow();
        mockMvc.perform(post("/kitchen/shopping/items/{id}/toggle", flour.getId()).with(csrf()).with(shopper()))
                .andExpect(redirectedUrl("/kitchen/shopping"));
        assertThat(flour.getStatus()).isEqualTo(ShoppingStatus.BOUGHT);

        mockMvc.perform(get("/kitchen/shopping/stow").with(shopper())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Mehl")));
        mockMvc.perform(post("/kitchen/shopping/stow").with(csrf()).with(shopper()).param("item", flour.getId().toString())
                        .param("amount_" + flour.getId(), "400").param("unit_" + flour.getId(), "G")
                        .param("location_" + flour.getId(), "Vorrat").param("best_" + flour.getId(), "2027-01-01"))
                .andExpect(flash().attribute("success", containsString("1 Artikel eingeräumt")));

        assertThat(flour.getStatus()).isEqualTo(ShoppingStatus.STORED);
        assertThat(pantry.findAll()).extracting(PantryItem::getName).containsExactly("Mehl");
    }

    @Test
    void viewersCannotChangeTheList() throws Exception {
        mockMvc.perform(post("/kitchen/shopping/items").with(csrf()).with(viewer()).param("line", "Brot"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/kitchen/shopping/send").with(csrf()).with(viewer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/kitchen/shopping").with(viewer())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Aus Plan erzeugen"))));
    }

    @Test
    void sendingWithoutBringExplainsWhatToDo() throws Exception {
        shopping.save(ShoppingItem.manual("Brot", null, null, null));

        mockMvc.perform(post("/kitchen/shopping/send").with(csrf()).with(shopper()))
                .andExpect(redirectedUrl("/kitchen/shopping"))
                .andExpect(flash().attribute("error", containsString("noch nicht eingerichtet")));
        mockMvc.perform(post("/kitchen/shopping/sync").with(csrf()).with(shopper()))
                .andExpect(flash().attribute("error", containsString("noch nicht eingerichtet")));
    }

    @Test
    void bringSetupFailureShowsAMessageAndKeepsTheSettingsPageWorking() throws Exception {
        when(bringApi.lists(any(), any())).thenThrow(new BringException("Bring! ist gerade nicht erreichbar."));

        mockMvc.perform(post("/kitchen/settings/bring").with(csrf()).with(admin()).param("email", "a@b.de")
                        .param("password", "x"))
                .andExpect(redirectedUrl("/kitchen/settings"))
                .andExpect(flash().attribute("error", "Bring! ist gerade nicht erreichbar."));
        mockMvc.perform(get("/kitchen/settings").with(admin())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Bring!")));
        mockMvc.perform(post("/kitchen/settings/bring").with(csrf()).with(shopper()).param("email", "a@b.de")
                .param("password", "x")).andExpect(status().isForbidden());
    }

    @Test
    void connectedBringShowsListsAndSyncsTheShoppingList() throws Exception {
        when(bringApi.lists(any(), any())).thenReturn(java.util.List.of(
                new club.badernet.nestwerk.kitchen.integration.BringList("l-1", "Zuhause")));
        mockMvc.perform(post("/kitchen/settings/bring").with(csrf()).with(admin()).param("email", "a@b.de")
                        .param("password", "geheim"))
                .andExpect(flash().attribute("success", "Mit Bring! verbunden."));
        mockMvc.perform(get("/kitchen/settings").with(admin())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Zuhause")));

        shopping.save(ShoppingItem.manual("Brot", null, null, null));
        mockMvc.perform(post("/kitchen/shopping/send").with(csrf()).with(shopper()))
                .andExpect(flash().attribute("success", "1 Artikel an Bring! gesendet."));
        when(bringApi.open(any(), any(), any())).thenReturn(java.util.List.of());
        mockMvc.perform(post("/kitchen/shopping/sync").with(csrf()).with(shopper()))
                .andExpect(flash().attribute("success", "1 Artikel bei Bring! als gekauft erkannt."));
    }
}
