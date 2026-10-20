package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTaskRepository;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MCP tools for categories, rescheduling and deleting events and rescheduling chores. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class CalendarGridToolsTest {

    @Autowired CalendarTools calendarTools;
    @Autowired EventCategoryTools categoryTools;
    @Autowired HouseholdTools householdTools;
    @Autowired CalendarEventRepository repository;
    @Autowired ChoreService chores;
    @Autowired ChoreTaskRepository tasks;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberRepository members;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    User admin;
    User parent;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Role parents = roleService.create("Eltern", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN"));
        parent = userService.create("mama", "passwort1", Set.of(parents.getId()));
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    private CalendarTools.Event dentist(Long categoryId) {
        return calendarTools.createEvent(as(parent), "Zahnarzt", "2030-03-04", "09:00", null, "10:00", null, null,
                null, null, null, null, null, categoryId);
    }

    @Test
    void categoriesCanBeListedCreatedChangedAndDeleted() {
        assertThat(categoryTools.listEventCategories(as(parent))).extracting(EventCategoryTools.Category::name)
                .contains("Arzt", "Sport");

        EventCategoryTools.Category created = categoryTools.createEventCategory(as(admin), "Musikschule", "violet");
        EventCategoryTools.Category changed = categoryTools.updateEventCategory(as(admin), created.categoryId(),
                null, "TEAL");
        assertThat(changed.name()).isEqualTo("Musikschule");
        assertThat(changed.color()).isEqualTo("TEAL");
        assertThat(categoryTools.getEventCategory(as(parent), created.categoryId()).hex()).isEqualTo("#12a594");

        assertThat(categoryTools.deleteEventCategory(as(admin), created.categoryId())).contains("gelöscht");
        assertThatThrownBy(() -> categoryTools.getEventCategory(as(parent), created.categoryId()))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void managingCategoriesNeedsThePermissionAndAValidColor() {
        assertThatThrownBy(() -> categoryTools.createEventCategory(as(parent), "Garten", "GREEN"))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Berechtigung");
        assertThatThrownBy(() -> categoryTools.createEventCategory(as(admin), "Garten", "lila"))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("GREEN");
    }

    @Test
    void eventsCarryTheirCategoryAndZeroRemovesIt() {
        Long doctor = categoryTools.listEventCategories(as(parent)).stream()
                .filter(c -> c.name().equals("Arzt")).findFirst().orElseThrow().categoryId();

        CalendarTools.Event created = dentist(doctor);
        assertThat(created.category()).isEqualTo("Arzt");
        assertThat(calendarTools.listEvents(as(parent), "2030-03-04", "2030-03-04"))
                .singleElement().satisfies(e -> assertThat(e.categoryId()).isEqualTo(doctor));

        CalendarTools.Event cleared = calendarTools.updateEvent(as(parent), created.eventId(), null, null, null, null,
                null, null, null, null, null, null, null, null, 0L);
        assertThat(cleared.categoryId()).isNull();
    }

    @Test
    void rescheduleEventChangesTimesAndCanMakeItAllDay() {
        CalendarTools.Event created = dentist(null);

        CalendarTools.Event moved = calendarTools.rescheduleEvent(as(parent), created.eventId(), "2030-03-05T14:05",
                "2030-03-05T14:50", null, null, null, null);
        assertThat(moved.start()).isEqualTo("2030-03-05T14:05:00");
        assertThat(moved.end()).isEqualTo("2030-03-05T14:50:00");

        CalendarTools.Event allDay = calendarTools.rescheduleEvent(as(parent), created.eventId(), null, null, true,
                "2030-03-06", "2030-03-07", null);
        assertThat(allDay.allDay()).isTrue();
        assertThat(allDay.end()).isEqualTo("2030-03-07");
    }

    @Test
    void seriesMoveAsOccurrenceOrAsWholeSeries() {
        CalendarTools.Event series = calendarTools.createEvent(as(parent), "Chor", "2030-03-04", "18:00", null,
                "19:00", null, null, null, "FREQ=WEEKLY", null, null, null, null);
        String second = calendarTools.listEvents(as(parent), "2030-03-11", "2030-03-11").getFirst().recurrenceId();

        calendarTools.rescheduleEvent(as(parent), series.eventId(), "2030-03-12T18:00", "2030-03-12T19:00", null,
                null, null, second);
        calendarTools.rescheduleEventSeries(as(parent), series.eventId(), "17:30", "19:00");

        assertThat(calendarTools.listEvents(as(parent), "2030-03-04", "2030-03-18"))
                .extracting(CalendarTools.Event::start)
                .containsExactly("2030-03-04T17:30:00", "2030-03-12T18:00:00", "2030-03-18T17:30:00");
        assertThatThrownBy(() -> calendarTools.rescheduleEvent(as(parent), series.eventId(), "2030-03-12T18:00",
                "2030-03-12T19:00", null, null, null, null)).isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void deleteEventRemovesOwnEventsOnly() {
        CalendarTools.Event created = dentist(null);
        User other = userService.create("papa", "passwort1",
                Set.of(roleService.create("Lesen", null, Set.of("CALENDAR_VIEW")).getId()));

        assertThatThrownBy(() -> calendarTools.deleteEvent(as(other), created.eventId()))
                .isInstanceOf(Tools.ToolException.class);
        assertThat(calendarTools.deleteEvent(as(parent), created.eventId())).contains("Zahnarzt");
        assertThat(repository.findById(created.eventId())).isEmpty();
    }

    @Test
    void choreTaskCanBePlannedAtAnotherTime() {
        FamilyMember lena = members.save(new FamilyMember("Lena", "#00aa00", null));
        Chore chore = chores.create(new ChoreInput("Müll", null, null, 10, 10, 10, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=1", LocalDate.now(clock), null, null, Distribution.FIXED, List.of(lena.getId())),
                admin);
        entityManager.flush();
        Long taskId = tasks.findAllByChoreId(chore.getId()).getFirst().getId();

        HouseholdTools.ChoreTaskItem item = householdTools.rescheduleChoreTask(as(admin), taskId, "19:45");

        assertThat(item.plannedAt()).isEqualTo("19:45");
        assertThatThrownBy(() -> householdTools.rescheduleChoreTask(as(admin), taskId, "spät"))
                .isInstanceOf(Tools.ToolException.class);
    }
}
