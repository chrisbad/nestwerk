package club.badernet.nestwerk.item;

import club.badernet.nestwerk.TestcontainersConfiguration;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class CalendarItemsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);

    @Autowired CalendarItems items;
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

    private List<CalendarItem> day(User viewer, Set<ItemType> types, Long member) {
        return items.between(DAY, DAY, viewer, types, member);
    }

    @Test
    void collectsEveryTypeSortedByStart() {
        assertThat(day(admin, Set.of(), null)).extracting(CalendarItem::type, CalendarItem::title)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ItemType.TODO, "Steuer"),
                        org.assertj.core.groups.Tuple.tuple(ItemType.EVENT, "Zahnarzt"),
                        org.assertj.core.groups.Tuple.tuple(ItemType.CHORE, "Fenster · Lena"));
    }

    @Test
    void eventCarriesTimesMembersAndActions() {
        CalendarItem.Event item = (CalendarItem.Event) day(admin, Set.of(ItemType.EVENT), null).getFirst();

        assertThat(item.id()).isEqualTo(event.getId());
        assertThat(item.start()).isEqualTo(LocalDateTime.of(DAY, LocalTime.of(15, 0)));
        assertThat(item.end()).isEqualTo(LocalDateTime.of(DAY, LocalTime.of(16, 0)));
        assertThat(item.allDay()).isFalse();
        assertThat(item.memberIds()).containsExactly(lena.getId());
        assertThat(item.location()).isEqualTo("Praxis");
        assertThat(item.actions()).contains(ItemAction.EDIT, ItemAction.DELETE, ItemAction.MOVE);
    }

    @Test
    void plannedChoreHasItsTimeAndTheOpenActions() {
        CalendarItem.Chore item = (CalendarItem.Chore) day(admin, Set.of(ItemType.CHORE), null).getFirst();

        assertThat(item.id()).isEqualTo(task.getId());
        assertThat(item.start()).isEqualTo(LocalDateTime.of(DAY, LocalTime.of(18, 30)));
        assertThat(item.end()).isEqualTo(LocalDateTime.of(DAY, LocalTime.of(19, 10)));
        assertThat(item.allDay()).isFalse();
        assertThat(item.memberIds()).containsExactly(lena.getId());
        assertThat(item.status()).isEqualTo("OPEN");
        assertThat(item.seriesId()).isEqualTo(task.getChore().getId());
        assertThat(item.actions()).contains(ItemAction.COMPLETE, ItemAction.MOVE, ItemAction.EDIT);
    }

    @Test
    void todoIsAnAllDayItemOnItsDueDay() {
        CalendarItem.Todo item = (CalendarItem.Todo) day(admin, Set.of(ItemType.TODO), null).getFirst();

        assertThat(item.id()).isEqualTo(todo.getId());
        assertThat(item.allDay()).isTrue();
        assertThat(item.start()).isEqualTo(DAY.atStartOfDay());
        assertThat(item.end()).isEqualTo(DAY.plusDays(1).atStartOfDay());
        assertThat(item.actions()).contains(ItemAction.COMPLETE, ItemAction.MOVE);
    }

    @Test
    void memberFilterKeepsOnlyItemsOfThatPerson() {
        assertThat(day(admin, Set.of(), lena.getId())).extracting(CalendarItem::type)
                .containsExactly(ItemType.EVENT, ItemType.CHORE);
    }

    @Test
    void viewerWithoutHouseholdAndTodoRightsOnlySeesEvents() {
        assertThat(day(reader, Set.of(), null)).extracting(CalendarItem::type).containsExactly(ItemType.EVENT);
    }

    @Test
    void viewerWithoutEditRightGetsNoActionsOnForeignEvents() {
        assertThat(((CalendarItem.Event) day(reader, Set.of(), null).getFirst()).actions()).isEmpty();
    }

    @Test
    void findsAnItemByTypeAndId() {
        assertThat(items.find(ItemType.EVENT, event.getId(), admin)).get().extracting(CalendarItem::title)
                .isEqualTo("Zahnarzt");
        assertThat(items.find(ItemType.TODO, todo.getId(), admin)).get().extracting(CalendarItem::title)
                .isEqualTo("Steuer");
        assertThat(items.find(ItemType.CHORE, task.getId(), admin)).get().extracting(CalendarItem::title)
                .isEqualTo("Fenster · Lena");
    }

    @Test
    void findIsEmptyForUnknownIdsAndForItemsTheViewerMayNotSee() {
        assertThat(items.find(ItemType.EVENT, -1L, admin)).isEmpty();
        assertThat(items.find(ItemType.TODO, -1L, admin)).isEmpty();
        assertThat(items.find(ItemType.CHORE, -1L, admin)).isEmpty();
        assertThat(items.find(ItemType.TODO, todo.getId(), reader)).isEqualTo(Optional.empty());
        assertThat(items.find(ItemType.CHORE, task.getId(), reader)).isEmpty();
    }
}
