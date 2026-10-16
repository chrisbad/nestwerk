package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.kitchen.Food;
import club.badernet.nestwerk.kitchen.FoodRepository;
import club.badernet.nestwerk.kitchen.Recipe;
import club.badernet.nestwerk.kitchen.RecipeRepository;
import club.badernet.nestwerk.kitchen.Unit;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class KitchenRecipesTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RecipeRepository recipes;
    @Autowired FoodRepository foods;

    @BeforeEach
    void setUp() {
        Role cook = roleService.create("Koch", null, Set.of("KITCHEN_VIEW", "KITCHEN_RECIPES_EDIT"));
        userService.create("koch", "passwort1", Set.of(cook.getId()));
        Role guest = roleService.create("Gast", null, Set.of("KITCHEN_VIEW"));
        userService.create("gast", "passwort1", Set.of(guest.getId()));
        userService.create("fremd", "passwort1", Set.of());
    }

    static RequestPostProcessor as(String name, String... authorities) {
        return user(name).authorities(java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    static RequestPostProcessor cook() {
        return as("koch", "KITCHEN_VIEW", "KITCHEN_RECIPES_EDIT");
    }

    private Long createPancakes() throws Exception {
        String location = mockMvc.perform(post("/kitchen/recipes").with(csrf()).with(cook())
                        .param("title", "Pfannkuchen").param("servings", "2").param("minutes", "20")
                        .param("tags", "süß, schnell").param("steps", "Alles **verrühren**.")
                        .param("rows[0].amount", "200").param("rows[0].unit", "G").param("rows[0].name", "Mehl")
                        .param("rows[1].amount", "2").param("rows[1].unit", "PIECE").param("rows[1].name", "Eier")
                        .param("rows[2].amount", "").param("rows[2].unit", "").param("rows[2].name", "")
                        .param("paste", "300 ml Milch\nSalz"))
                .andExpect(redirectedUrlPattern("/kitchen/recipes/*"))
                .andReturn().getResponse().getRedirectedUrl();
        return Long.valueOf(location.substring(location.lastIndexOf('/') + 1));
    }

    @Test
    void createsRecipeFromRowsAndPastedLinesAndShowsNutrition() throws Exception {
        Long id = createPancakes();

        Recipe saved = recipes.findById(id).orElseThrow();
        assertThat(saved.getIngredients()).extracting(i -> i.getFood() == null ? null : i.getFood().getName())
                .containsExactly("Mehl", "Ei", "Milch", "Salz");
        assertThat(saved.getIngredients().get(2).getUnit()).isEqualTo(Unit.ML);

        // 200 g Mehl 680 + 2 Eier 120 g 186 + 300 ml Milch 192 = 1058 kcal; 2 Portionen → 529
        mockMvc.perform(get("/kitchen/recipes/{id}", id).with(as("gast", "KITCHEN_VIEW")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Pfannkuchen")))
                .andExpect(content().string(containsString("529 kcal")))
                .andExpect(content().string(containsString("<strong>verrühren</strong>")))
                .andExpect(content().string(not(containsString("Nährwerte unvollständig"))));
    }

    @Test
    void scalesAmountsToChosenServings() throws Exception {
        Long id = createPancakes();
        mockMvc.perform(get("/kitchen/recipes/{id}", id).param("servings", "4").with(cook()))
                .andExpect(content().string(containsString("400 g")))
                .andExpect(content().string(containsString("600 ml")));
    }

    @Test
    void marksIncompleteNutrition() throws Exception {
        mockMvc.perform(post("/kitchen/recipes").with(csrf()).with(cook())
                        .param("title", "Zauber").param("servings", "1")
                        .param("paste", "100 g Zauberpulver"))
                .andExpect(status().is3xxRedirection());
        Long id = recipes.findAllByOrderByTitleAsc().stream().filter(r -> r.getTitle().equals("Zauber"))
                .findFirst().orElseThrow().getId();
        mockMvc.perform(get("/kitchen/recipes/{id}", id).with(cook()))
                .andExpect(content().string(containsString("Nährwerte unvollständig")))
                .andExpect(content().string(containsString("Zauberpulver")));
    }

    @Test
    void editsSearchesAndDeletes() throws Exception {
        Long id = createPancakes();
        mockMvc.perform(get("/kitchen/recipes/{id}/edit", id).with(cook()))
                .andExpect(content().string(containsString("value=\"Mehl\"")));
        mockMvc.perform(post("/kitchen/recipes/{id}", id).with(csrf()).with(cook())
                        .param("title", "Dicke Pfannkuchen").param("servings", "3")
                        .param("rows[0].amount", "250").param("rows[0].unit", "G").param("rows[0].name", "Mehl"))
                .andExpect(redirectedUrlPattern("/kitchen/recipes/*"));
        assertThat(recipes.findById(id).orElseThrow().getIngredients()).hasSize(1);

        mockMvc.perform(get("/kitchen/recipes").param("q", "dicke").with(cook()))
                .andExpect(content().string(containsString("Dicke Pfannkuchen")));
        mockMvc.perform(get("/kitchen/recipes").param("q", "lasagne").with(cook()))
                .andExpect(content().string(not(containsString("Dicke Pfannkuchen"))));

        mockMvc.perform(post("/kitchen/recipes/{id}/delete", id).with(csrf()).with(cook()))
                .andExpect(status().is3xxRedirection());
        assertThat(recipes.findById(id)).isEmpty();
    }

    @Test
    void viewersMayNotEditAndOthersMayNotLook() throws Exception {
        mockMvc.perform(post("/kitchen/recipes").with(csrf()).with(as("gast", "KITCHEN_VIEW"))
                        .param("title", "X").param("servings", "1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/kitchen/recipes").with(as("fremd")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/kitchen/recipes").with(as("gast", "KITCHEN_VIEW")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Neues Rezept"))));
    }

    @Test
    void uploadsPhoto() throws Exception {
        Long id = createPancakes();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0};
        mockMvc.perform(multipart("/kitchen/recipes/{id}/photo", id)
                        .file(new MockMultipartFile("file", "bild.png", "image/png", png)).with(csrf()).with(cook()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/kitchen/recipes/{id}", id).with(cook()))
                .andExpect(content().string(containsString("recipe-photo")));
    }

    @Test
    void managesFoods() throws Exception {
        mockMvc.perform(post("/kitchen/foods").with(csrf()).with(cook())
                        .param("name", "Hafermilch").param("category", "CHILLED").param("kcal", "46")
                        .param("protein", "1").param("fat", "1,5").param("carbs", "6,7").param("packageGrams", "1000"))
                .andExpect(status().is3xxRedirection());
        Food oat = foods.findByNameIgnoreCase("hafermilch").orElseThrow();
        assertThat(oat.getFat()).isEqualTo(1.5);

        mockMvc.perform(post("/kitchen/foods").with(csrf()).with(cook())
                        .param("name", "hafermilch").param("category", "CHILLED"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gibt es schon")));

        mockMvc.perform(get("/kitchen/foods").param("q", "hafer").with(cook()))
                .andExpect(content().string(containsString("Hafermilch")))
                .andExpect(content().string(containsString("Haferflocken")));
    }
}
