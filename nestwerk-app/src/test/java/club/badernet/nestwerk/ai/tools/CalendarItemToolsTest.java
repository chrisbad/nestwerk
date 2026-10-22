package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class CalendarItemToolsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final String DAY_TEXT = DAY.toString();

    @Autowired CalendarItemTools itemTools;
    @Autowired CalendarEventService events;
    @Autowired TodoService todos;
    @Autowired ChoreService chores;
    @Autowired ChoreTaskService choreTasks;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberService memberService;

    User admin;
    User reader;
    FamilyMember lena;
    CalendarEvent event;
    Todo todo;
    ChoreTask task;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Role viewOnly = roleService.create("Leser", null, Set.of("CALENDAR_VIEW"));
        reader = userService.create("leser", "passwort1", Set.of(viewOnly.getId()));
        lena = memberService.create("Lena", "#8b6cff", null);
        event = events.create(new EventInput("Zahnarzt", null, "Praxis", DAY, LocalTime.of(15, 0), DAY,
                LocalTime.of(16, 0), false, null, false, Set.of(lena.getId()), false, null), admin);
        todo = todos.create(new TodoInput("Steuer", null, null, null, DAY, null, null, false, null, Set.of(),
                Set.of(), null), admin);
        Chore chore = chores.create(new ChoreInput("Fenster", null, null, 20, 40, 90, ChoreKind.ON_DEMAND,
                "FREQ=DAILY", DAY, null, null, null, List.of()), admin);
        task = choreTasks.planOnce(chore.getId(), DAY, LocalTime.of(18, 30), lena.getId(), admin);
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    @Test
    void listsAllTypesOfTheRangeSortedByStart() {
        List<CalendarItemTools.Item> list = itemTools.listCalendarItems(as(admin), DAY_TEXT, DAY_TEXT, null, null);

        assertThat(list).extracting(CalendarItemTools.Item::type).containsExactly("TODO", "EVENT", "CHORE");
        assertThat(list.get(1).start()).isEqualTo(DAY + "T15:00");
        assertThat(list.get(1).details()).containsEntry("location", "Praxis");
        assertThat(list.get(2).details()).containsEntry("minutes", "40");
        assertThat(list.get(1).actions()).contains("EDIT", "DELETE");
    }

    @Test
    void filtersByTypeAndPerson() {
        assertThat(itemTools.listCalendarItems(as(admin), DAY_TEXT, DAY_TEXT, "chore", null))
                .extracting(CalendarItemTools.Item::title).containsExactly("Fenster · Lena");
        assertThat(itemTools.listCalendarItems(as(admin), DAY_TEXT, DAY_TEXT, null, lena.getId()))
                .extracting(CalendarItemTools.Item::type).containsExactly("EVENT", "CHORE");
    }

    @Test
    void callerSeesOnlyWhatTheirRightsAllow() {
        assertThat(itemTools.listCalendarItems(as(reader), DAY_TEXT, DAY_TEXT, null, null))
                .extracting(CalendarItemTools.Item::type).containsExactly("EVENT");
        assertThat(itemTools.listCalendarItems(as(reader), DAY_TEXT, DAY_TEXT, null, null).getFirst().actions())
                .isEmpty();
    }

    @Test
    void getsOneItemByTypeAndId() {
        CalendarItemTools.Item item = itemTools.getCalendarItem(as(admin), "TODO", todo.getId());

        assertThat(item.title()).isEqualTo("Steuer");
        assertThat(item.allDay()).isTrue();
        assertThat(item.details()).containsEntry("priority", "NORMAL");
        assertThat(itemTools.getCalendarItem(as(admin), "chore", task.getId()).status()).isEqualTo("OPEN");
        assertThat(itemTools.getCalendarItem(as(admin), "EVENT", event.getId()).id()).isEqualTo(event.getId());
    }

    @Test
    void getRejectsUnknownIdsHiddenItemsAndWrongInput() {
        assertThatThrownBy(() -> itemTools.getCalendarItem(as(admin), "EVENT", -1L))
                .isInstanceOf(Tools.ToolException.class).hasMessage("Eintrag nicht gefunden");
        assertThatThrownBy(() -> itemTools.getCalendarItem(as(reader), "TODO", todo.getId()))
                .isInstanceOf(Tools.ToolException.class).hasMessage("Eintrag nicht gefunden");
        assertThatThrownBy(() -> itemTools.getCalendarItem(as(admin), "NONSENSE", 1L))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("erlaubt sind");
        assertThatThrownBy(() -> itemTools.getCalendarItem(as(admin), null, 1L))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("type");
    }

    @Test
    void listRejectsBadDatesAndTooLongRanges() {
        assertThatThrownBy(() -> itemTools.listCalendarItems(as(admin), "morgen", null, null, null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("JJJJ-MM-TT");
        assertThatThrownBy(() -> itemTools.listCalendarItems(as(admin), DAY_TEXT, DAY.plusDays(200).toString(), null,
                null)).isInstanceOf(Tools.ToolException.class).hasMessageContaining("Zeitraum");
    }
}
