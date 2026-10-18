package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.kitchen.Food;
import club.badernet.nestwerk.kitchen.FoodService;
import club.badernet.nestwerk.kitchen.FoodSource;
import club.badernet.nestwerk.kitchen.integration.ImportedRecipe;
import club.badernet.nestwerk.kitchen.integration.OffProduct;
import club.badernet.nestwerk.kitchen.integration.OpenFoodFacts;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static club.badernet.nestwerk.web.KitchenRecipesTest.cook;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class KitchenImportTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FoodService foodService;
    @MockitoBean OpenFoodFacts off;

    User cookUser;

    @BeforeEach
    void setUp() {
        Role cook = roleService.create("Koch", null, Set.of("KITCHEN_VIEW", "KITCHEN_RECIPES_EDIT"));
        cookUser = userService.create("koch", "passwort1", Set.of(cook.getId()));
    }

    @Test
    void importedRecipeOpensPrefilledForm() {
        RecipeForm form = RecipeForm.from(new ImportedRecipe("Suppe", "lecker", 4, 30, "warm",
                "https://example.org/s", "1. Kochen.", List.of("200 g rote Linsen", "Salz")));
        assertThat(form.getTitle()).isEqualTo("Suppe");
        assertThat(form.getRows()).extracting(RecipeForm.Row::getName).containsExactly("rote Linsen", "Salz");
        assertThat(form.getRows().getFirst().getUnit()).isEqualTo("G");
    }

    @Test
    void importFromInternalAddressShowsMessage() throws Exception {
        mockMvc.perform(post("/kitchen/recipes/import").with(csrf()).with(cook()).param("url", "http://127.0.0.1/x"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Interne Adressen")));
    }

    @Test
    void barcodeFindsKnownFoodWithoutAskingOpenFoodFacts() throws Exception {
        Food food = foodService.create(new club.badernet.nestwerk.kitchen.FoodInput("Hafermilch", null, 46.0, null,
                null, null, null, null, null, "4006040052388"), FoodSource.MANUAL, cookUser);
        mockMvc.perform(post("/kitchen/foods/barcode").with(csrf()).with(cook()).param("code", "4006040052388"))
                .andExpect(redirectedUrl("/kitchen/foods/" + food.getId()));
        verifyNoInteractions(off);
    }

    @Test
    void barcodeCreatesFoodFromOpenFoodFacts() throws Exception {
        when(off.product("4000417025005")).thenReturn(Optional.of(new OffProduct("4000417025005",
                "Vollmilch-Schokolade", "Ritter Sport", 537.0, 6.3, 31.0, 56.0, 100.0)));
        mockMvc.perform(post("/kitchen/foods/barcode").with(csrf()).with(cook()).param("code", "4000417025005"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", containsString("Open Food Facts")));
        Food created = foodService.byBarcode("4000417025005").orElseThrow();
        assertThat(created.getName()).isEqualTo("Vollmilch-Schokolade");
        assertThat(created.getSource()).isEqualTo(FoodSource.OFF);
        assertThat(created.getPackageGrams()).isEqualTo(100.0);
    }

    @Test
    void unknownBarcodeLeadsToManualForm() throws Exception {
        when(off.product("123456789")).thenReturn(Optional.empty());
        mockMvc.perform(post("/kitchen/foods/barcode").with(csrf()).with(cook()).param("code", "123456789"))
                .andExpect(redirectedUrl("/kitchen/foods/new?barcode=123456789"));
    }

    @Test
    void openFoodFactsOutageShowsMessage() throws Exception {
        when(off.product("123456789")).thenThrow(new club.badernet.nestwerk.kitchen.integration.ExternalServiceException(
                "Open Food Facts ist nicht erreichbar.", null));
        mockMvc.perform(post("/kitchen/foods/barcode").with(csrf()).with(cook()).param("code", "123456789"))
                .andExpect(redirectedUrl("/kitchen/foods"))
                .andExpect(flash().attribute("error", containsString("nicht erreichbar")));
    }
}
