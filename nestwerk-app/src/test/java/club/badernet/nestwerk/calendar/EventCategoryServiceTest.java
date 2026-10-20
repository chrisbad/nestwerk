package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class EventCategoryServiceTest {

    @Autowired EventCategoryService categories;
    @Autowired CalendarEventService events;
    @Autowired UserService userService;
    @Autowired RoleService roleService;

    User manager;
    User viewer;

    @BeforeEach
    void setUp() {
        Role managing = roleService.create("Kategorien", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_CATEGORY_MANAGE"));
        Role viewing = roleService.create("Nur Kalender", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE"));
        manager = userService.create("planer", "passwort1", Set.of(managing.getId()));
        viewer = userService.create("schauer", "passwort1", Set.of(viewing.getId()));
    }

    @Test
    void seedCategoriesAreListedByName() {
        assertThat(categories.list(viewer)).extracting(EventCategory::getName)
                .contains("Arzt", "Schule", "Sport", "Arbeit", "Familie", "Geburtstag").isSorted();
    }

    @Test
    void managerCreatesUpdatesAndFindsCategoriesByName() {
        EventCategory music = categories.create(new CategoryInput("  Musikschule ", CategoryColor.VIOLET), manager);

        categories.update(music.getId(), new CategoryInput("Musik", CategoryColor.TEAL), manager);

        assertThat(categories.findByName("MUSIK", viewer)).get().satisfies(c -> {
            assertThat(c.getName()).isEqualTo("Musik");
            assertThat(c.getColor()).isEqualTo(CategoryColor.TEAL);
        });
    }

    @Test
    void namesMustBeUniqueIgnoringCaseAndNotEmpty() {
        assertThatThrownBy(() -> categories.create(new CategoryInput("arzt", CategoryColor.RED), manager))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gibt es schon");
        assertThatThrownBy(() -> categories.create(new CategoryInput(" ", CategoryColor.RED), manager))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> categories.create(new CategoryInput("x".repeat(61), CategoryColor.RED), manager))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> categories.create(new CategoryInput("Ohne Farbe", null), manager))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void managingNeedsItsOwnPermission() {
        assertThatThrownBy(() -> categories.create(new CategoryInput("Garten", CategoryColor.GREEN), viewer))
                .isInstanceOf(AccessDeniedException.class);
        EventCategory first = categories.list(viewer).getFirst();
        assertThatThrownBy(() -> categories.delete(first.getId(), viewer)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deletingKeepsItsEventsWithoutCategory() {
        EventCategory trip = categories.create(new CategoryInput("Ausflug", CategoryColor.ORANGE), manager);
        CalendarEvent event = events.create(new EventInput("Zoo", null, null, LocalDate.of(2026, 10, 12),
                LocalTime.of(10, 0), LocalDate.of(2026, 10, 12), LocalTime.of(12, 0), false, null, false, Set.of(),
                false, trip.getId()), viewer);

        categories.delete(trip.getId(), manager);

        assertThat(events.get(event.getId(), viewer).getCategoryId()).isEmpty();
        assertThatThrownBy(() -> categories.get(trip.getId(), viewer)).isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void paletteNamesAreParsedCaseInsensitively() {
        assertThat(CategoryColor.parse("blue")).isEqualTo(CategoryColor.BLUE);
        assertThatThrownBy(() -> CategoryColor.parse("lila")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BLUE");
    }
}
