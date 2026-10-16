package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.kitchen.Food;
import club.badernet.nestwerk.kitchen.FoodRepository;
import club.badernet.nestwerk.kitchen.Meal;
import club.badernet.nestwerk.kitchen.MealRepository;
import club.badernet.nestwerk.kitchen.MealSlot;
import club.badernet.nestwerk.kitchen.MealSlotRepository;
import club.badernet.nestwerk.kitchen.Recipe;
import club.badernet.nestwerk.kitchen.RecipeIngredient;
import club.badernet.nestwerk.kitchen.RecipeRepository;
import club.badernet.nestwerk.kitchen.Unit;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static club.badernet.nestwerk.web.KitchenRecipesTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class MealPlanTest {

    // Die feste Uhr steht auf Montag, 12.10.2026, 07:00
    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberRepository members;
    @Autowired FoodRepository foods;
    @Autowired RecipeRepository recipes;
    @Autowired MealRepository meals;
    @Autowired MealSlotRepository slots;

    FamilyMember lena;
    FamilyMember tim;
    MealSlot dinner;
    Recipe pasta;

    static RequestPostProcessor planner() {
        return as("plan", "KITCHEN_VIEW", "KITCHEN_PLAN");
    }

    @BeforeEach
    void setUp() {
        Role role = roleService.create("Planer", null, Set.of("KITCHEN_VIEW", "KITCHEN_PLAN"));
        userService.create("plan", "passwort1", Set.of(role.getId()));
        Role viewer = roleService.create("Zuschauer", null, Set.of("KITCHEN_VIEW"));
        userService.create("schau", "passwort1", Set.of(viewer.getId()));
        lena = members.save(new FamilyMember("Lena", "#00aa00", null));
        lena.setKcalGoal(2000);
        tim = members.save(new FamilyMember("Tim", "#0000aa", null));
        dinner = slots.findAllByOrderBySortOrderAsc().stream().filter(MealSlot::isAutoFill).findFirst().orElseThrow();
        Food noodles = foods.findByNameIgnoreCase("Nudeln").orElseThrow();
        // 4 Portionen à 600 kcal: 676 g Nudeln (355 kcal/100 g) = 2400 kcal
        pasta = new Recipe("Nudeln mit Soße", 4, null);
        pasta.setMinutes(20);
        pasta.addIngredient(new RecipeIngredient(2400 / 3.55, Unit.G, noodles, "Nudeln", null));
        recipes.save(pasta);
    }

    @Test
    void addingARecipeInvitesEveryoneWithSuggestedPortions() throws Exception {
        mockMvc.perform(post("/kitchen/plan/meals").with(csrf()).with(planner())
                        .param("day", MONDAY.plusDays(1).toString()).param("slotId", dinner.getId().toString())
                        .param("recipeId", pasta.getId().toString()))
                .andExpect(status().is2xxSuccessful());

        Meal meal = meals.findByDayBetweenOrderByDayAsc(MONDAY, MONDAY.plusDays(6)).getFirst();
        assertThat(meal.getTitle()).isEqualTo("Nudeln mit Soße");
        // Lena: 2000 × 30 % / 600 = 1,0; Tim ohne Ziel: 1
        assertThat(meal.getEaters()).extracting(e -> e.getMemberId(), e -> e.getPortions())
                .containsExactlyInAnyOrder(tuple(lena.getId(), 1.0), tuple(tim.getId(), 1.0));

        mockMvc.perform(get("/kitchen/plan").param("week", MONDAY.toString()).with(planner()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nudeln mit Soße")))
                .andExpect(content().string(containsString("600 / 2.000 kcal")));
    }

    @Test
    void freeTitleMoveEatersAndDelete() throws Exception {
        mockMvc.perform(post("/kitchen/plan/meals").with(csrf()).with(planner())
                        .param("day", MONDAY.toString()).param("slotId", dinner.getId().toString())
                        .param("title", "Pizza bestellen"))
                .andExpect(status().is2xxSuccessful());
        Meal meal = meals.findAll().getFirst();
        assertThat(meal.getRecipe()).isNull();

        MealSlot lunch = slots.findAllByOrderBySortOrderAsc().get(1);
        mockMvc.perform(post("/kitchen/plan/meals/{id}/move", meal.getId()).with(csrf()).with(planner())
                        .param("day", MONDAY.plusDays(2).toString()).param("slotId", lunch.getId().toString()))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(post("/kitchen/plan/meals/{id}", meal.getId()).with(csrf()).with(planner())
                        .param("eater", lena.getId().toString()).param("portions_" + lena.getId(), "1,5"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/kitchen/plan/meals/{id}", meal.getId()).with(planner()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Wer isst mit?")));
        Meal moved = meals.findById(meal.getId()).orElseThrow();
        assertThat(moved.getDay()).isEqualTo(MONDAY.plusDays(2));
        assertThat(moved.getSlot().getId()).isEqualTo(lunch.getId());
        assertThat(moved.getEaters()).extracting(e -> e.getMemberId(), e -> e.getPortions())
                .containsExactly(tuple(lena.getId(), 1.5));

        mockMvc.perform(post("/kitchen/plan/meals/{id}/delete", meal.getId()).with(csrf()).with(planner()))
                .andExpect(status().is3xxRedirection());
        assertThat(meals.findById(meal.getId())).isEmpty();
    }

    @Test
    void autoFillFillsOnlyMarkedEmptySlotsFromTodayAndVaries() throws Exception {
        Recipe other = new Recipe("Reispfanne", 2, null);
        other.setMinutes(25);
        recipes.save(other);
        meals.save(new Meal(MONDAY.plusDays(3), dinner, null, "Grillen bei Oma"));
        meals.save(new Meal(MONDAY.minusDays(1), dinner, null, "Gestern"));

        mockMvc.perform(post("/kitchen/plan/fill").with(csrf()).with(planner()).param("week", MONDAY.toString()))
                .andExpect(status().is3xxRedirection());

        List<Meal> week = meals.findByDayBetweenOrderByDayAsc(MONDAY, MONDAY.plusDays(6));
        assertThat(week).allMatch(m -> m.getSlot().getId().equals(dinner.getId()));
        assertThat(week.stream().filter(m -> m.getDay().equals(MONDAY.plusDays(3)))).hasSize(1);
        // nur zwei Rezepte, keins doppelt in der Woche → zwei Tage gefüllt, Rest bleibt frei
        assertThat(week.stream().filter(m -> m.getRecipe() != null).map(m -> m.getRecipe().getId()))
                .containsExactlyInAnyOrder(pasta.getId(), other.getId());
    }

    @Test
    void viewersMayLookButNotPlan() throws Exception {
        mockMvc.perform(get("/kitchen/plan").with(as("schau", "KITCHEN_VIEW"))).andExpect(status().isOk());
        mockMvc.perform(post("/kitchen/plan/meals").with(csrf()).with(as("schau", "KITCHEN_VIEW"))
                        .param("day", MONDAY.toString()).param("slotId", dinner.getId().toString())
                        .param("title", "X"))
                .andExpect(status().isForbidden());
    }

    @Test
    void settingsChangeGoalsAndSlots() throws Exception {
        RequestPostProcessor admin = as("chef", "KITCHEN_VIEW", "KITCHEN_SETTINGS");
        userService.createAdministrator("chef", "passwort1");
        mockMvc.perform(post("/kitchen/settings/goals").with(csrf()).with(admin)
                        .param("goal_" + tim.getId(), "2500").param("goal_" + lena.getId(), ""))
                .andExpect(status().is3xxRedirection());
        assertThat(members.findById(tim.getId()).orElseThrow().getKcalGoal()).isEqualTo(2500);
        assertThat(members.findById(lena.getId()).orElseThrow().getKcalGoal()).isNull();

        mockMvc.perform(post("/kitchen/settings/slots").with(csrf()).with(admin)
                        .param("name", "Brotzeit").param("share", "15").param("autoFill", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(slots.findAllByOrderBySortOrderAsc()).extracting(MealSlot::getName).contains("Brotzeit");

        mockMvc.perform(post("/kitchen/settings/goals").with(csrf()).with(planner()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/kitchen/settings").with(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Brotzeit")))
                .andExpect(content().string(containsString("value=\"2500\"")));
    }
}
