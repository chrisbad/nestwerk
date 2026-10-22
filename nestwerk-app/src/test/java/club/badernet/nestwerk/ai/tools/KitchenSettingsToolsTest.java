package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.kitchen.FoodCategory;
import club.badernet.nestwerk.kitchen.FoodInput;
import club.badernet.nestwerk.kitchen.FoodService;
import club.badernet.nestwerk.kitchen.FoodSource;
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

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class KitchenSettingsToolsTest {

    @Autowired KitchenSettingsTools settingsTools;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberRepository members;
    @Autowired FoodService foods;

    User chef;
    User guest;
    FamilyMember lena;

    @BeforeEach
    void setUp() {
        Role admins = roleService.create("Küchenchefs", null, Set.of("KITCHEN_VIEW", "KITCHEN_RECIPES_EDIT",
                "KITCHEN_PLAN", "KITCHEN_SHOPPING", "KITCHEN_SETTINGS"));
        chef = userService.create("koch", "passwort1", Set.of(admins.getId()));
        Role viewers = roleService.create("Gäste", null, Set.of("KITCHEN_VIEW"));
        guest = userService.create("gast", "passwort1", Set.of(viewers.getId()));
        lena = members.save(new FamilyMember("Lena", "#00aa00", chef.getId()));
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    @Test
    void managesSlotsAndCalorieGoals() {
        KitchenSettingsTools.SlotItem created = settingsTools.createMealSlot(as(chef), "Brotzeit", 10, true);
        KitchenSettingsTools.SlotItem changed = settingsTools.updateMealSlot(as(chef), created.slotId(), null, 15, null);

        assertThat(changed.name()).isEqualTo("Brotzeit");
        assertThat(changed.sharePercent()).isEqualTo(15);
        assertThat(changed.autoFill()).isTrue();

        KitchenSettingsTools.Settings settings = settingsTools.setCalorieGoal(as(chef), lena.getId(), 2200);
        assertThat(settings.calorieGoals()).extracting(KitchenSettingsTools.GoalItem::kcalGoal).contains(2200);
        assertThat(settings.slots()).extracting(KitchenSettingsTools.SlotItem::name).contains("Brotzeit");
        assertThat(settings.bringConnected()).isFalse();

        assertThatThrownBy(() -> settingsTools.setCalorieGoal(as(chef), lena.getId(), 100))
                .isInstanceOf(Tools.ToolException.class);
        assertThatThrownBy(() -> settingsTools.createMealSlot(as(guest), "Nachtisch", 5, false))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void recipeImportNeedsEditRightBeforeAnyFetch() {
        assertThatThrownBy(() -> settingsTools.importRecipeFromUrl(as(guest), "https://example.org/rezept", false))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Berechtigung");
    }

    @Test
    void findsKnownFoodByBarcode() {
        foods.create(new FoodInput("Testflocken Spezial", FoodCategory.PANTRY, 370.0, 13.0, 7.0, 59.0, null, 500.0, null,
                "4001234567890"), FoodSource.MANUAL, chef);

        KitchenSettingsTools.BarcodeResult result = settingsTools.findFoodByBarcode(as(chef), "4001234567890");

        assertThat(result.food().name()).isEqualTo("Testflocken Spezial");
        assertThat(result.createdFromOpenFoodFacts()).isFalse();
        assertThatThrownBy(() -> settingsTools.findFoodByBarcode(as(chef), "12"))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Ziffern");
    }

    @Test
    void deletesMealSlotsButKeepsTheLastOne() {
        KitchenSettingsTools.SlotItem extra = settingsTools.createMealSlot(as(chef), "Brotzeit", 10, false);

        assertThatThrownBy(() -> settingsTools.deleteMealSlot(as(guest), extra.slotId()))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Keine Berechtigung");
        assertThat(settingsTools.deleteMealSlot(as(chef), extra.slotId())).contains("gelöscht");
        assertThat(settingsTools.kitchenSettings(as(chef)).slots()).extracting(KitchenSettingsTools.SlotItem::name)
                .doesNotContain("Brotzeit");
        assertThatThrownBy(() -> settingsTools.deleteMealSlot(as(chef), extra.slotId()))
                .isInstanceOf(Tools.ToolException.class).hasMessage("Die Mahlzeit gibt es nicht.");

        java.util.List<KitchenSettingsTools.SlotItem> all = settingsTools.kitchenSettings(as(chef)).slots();
        for (int i = 0; i < all.size() - 1; i++) {
            settingsTools.deleteMealSlot(as(chef), all.get(i).slotId());
        }
        assertThatThrownBy(() -> settingsTools.deleteMealSlot(as(chef), all.getLast().slotId()))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Mindestens eine Mahlzeit");
    }

    @Test
    void readsOneMealSlot() {
        KitchenSettingsTools.SlotItem created = settingsTools.createMealSlot(as(chef), "Brotzeit", 10, true);

        assertThat(settingsTools.getMealSlot(as(guest), created.slotId()).name()).isEqualTo("Brotzeit");
        assertThatThrownBy(() -> settingsTools.getMealSlot(as(guest), 999_999L))
                .isInstanceOf(Tools.ToolException.class).hasMessage("Die Mahlzeit gibt es nicht.");
    }
}
