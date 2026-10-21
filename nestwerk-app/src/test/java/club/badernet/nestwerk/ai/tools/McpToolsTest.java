package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerInput;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerService;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BlockerVisibility;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreCredit;
import club.badernet.nestwerk.household.ChoreCreditRepository;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskRepository;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclService;
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
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class McpToolsTest {

    private static final String CALENDAR_RIGHTS = "CALENDAR_VIEW,CALENDAR_EVENT_CREATE,CALENDAR_EVENT_EDIT_OWN,"
            + "CALENDAR_PRIVATE_CREATE";

    @Autowired MemberTools memberTools;
    @Autowired CalendarTools calendarTools;
    @Autowired TodoTools todoTools;
    @Autowired HouseholdTools householdTools;
    @Autowired ContactTools contactTools;
    @Autowired OverviewTools overviewTools;
    @Autowired CalendarEventService events;
    @Autowired ChoreService chores;
    @Autowired ChoreTaskRepository tasks;
    @Autowired ChoreCreditRepository credits;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;
    @Autowired BlockerService blockerService;

    User admin;
    User mama;
    User papa;
    User kid;
    FamilyMember mamaM;
    FamilyMember kidM;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Role eltern = roleService.create("Eltern", null, Set.of(CALENDAR_RIGHTS.split(",")));
        acl.applyPreset(admin, eltern.getId(), "ELTERNTEIL");
        Role kinder = roleService.create("Kinder", null, Set.of());
        acl.applyPreset(admin, kinder.getId(), "KIND");
        mama = userService.create("mama", "passwort1", Set.of(eltern.getId()));
        papa = userService.create("papa", "passwort1", Set.of(eltern.getId()));
        kid = userService.create("kind", "passwort1", Set.of(kinder.getId()));
        mamaM = members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        members.save(new FamilyMember("Papa", "#00aa00", papa.getId()));
        kidM = members.save(new FamilyMember("Kind", "#0000aa", kid.getId()));
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    @Test
    void whoamiReportsTheCallingUser() {
        MemberTools.Me me = memberTools.whoami(as(mama));

        assertThat(me.username()).isEqualTo("mama");
        assertThat(me.memberName()).isEqualTo("Mama");
        assertThat(me.permissions()).contains("CALENDAR_VIEW");
    }

    @Test
    void createsListsAndUpdatesTodos() {
        TodoTools.TodoDetail created = todoTools.createTodo(as(mama), "Keller entrümpeln", "Alles raus", "HIGH",
                "2030-01-31", null, null, null, List.of("Haus"), List.of("member:" + kidM.getId()), null, null,
                null, null);

        assertThat(created.todo().priority()).isEqualTo("HIGH");
        assertThat(created.todo().assignees()).containsExactly("member:" + kidM.getId() + " (Kind)");
        assertThat(todoTools.listTodos(as(mama), null, null, null, null, null, "keller", null))
                .extracting(TodoTools.TodoItem::title).containsExactly("Keller entrümpeln");

        TodoTools.TodoDetail updated = todoTools.updateTodo(as(mama), created.todo().id(), created.todo().version(),
                null, null, null, "", null, null, null, null, null, null, null, null, null);

        assertThat(updated.todo().dueOn()).isNull();
        assertThat(updated.todo().title()).isEqualTo("Keller entrümpeln");
        assertThat(updated.todo().priority()).isEqualTo("HIGH");
        assertThat(updated.description()).isEqualTo("Alles raus");

        todoTools.addTodoComment(as(papa), created.todo().id(), "Mache ich am Samstag");
        todoTools.checkTodo(as(papa), created.todo().id());
        TodoTools.TodoDetail detail = todoTools.getTodo(as(mama), created.todo().id());
        assertThat(detail.todo().status()).isEqualTo("DONE");
        assertThat(detail.comments()).extracting(TodoTools.Comment::author).containsExactly("papa");
    }

    @Test
    void managesSingleOccurrencesAndMovesEvents() {
        CalendarTools.Event series = calendarTools.createEvent(as(mama), "Chor", "2030-03-04", "18:00", null,
                "19:00", null, null, null, "FREQ=WEEKLY", null, null, null, null);
        List<CalendarTools.Event> listed = calendarTools.listEvents(as(mama), "2030-03-04", "2030-03-18");
        assertThat(listed).hasSize(3).allMatch(e -> e.recurrenceId() != null);

        calendarTools.cancelEventOccurrence(as(mama), series.eventId(), listed.get(1).recurrenceId());
        calendarTools.changeEventOccurrence(as(mama), series.eventId(), listed.get(2).recurrenceId(), "Chor extra",
                null, null, "2030-03-18T17:00", "2030-03-18T18:30");
        assertThat(calendarTools.listEvents(as(mama), "2030-03-04", "2030-03-18"))
                .extracting(CalendarTools.Event::title, CalendarTools.Event::start)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Chor", "2030-03-04T18:00:00"),
                        org.assertj.core.api.Assertions.tuple("Chor extra", "2030-03-18T17:00:00"));

        CalendarTools.Event single = calendarTools.createEvent(as(mama), "Arzt", "2030-04-01", "09:00", null,
                "10:00", null, null, null, null, null, null, null, null);
        assertThat(calendarTools.moveEvent(as(mama), single.eventId(), "2030-04-03").start())
                .isEqualTo("2030-04-03T09:00:00");
        assertThatThrownBy(() -> calendarTools.moveEvent(as(mama), series.eventId(), "2030-04-03"))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void createsAndUpdatesContacts() {
        ContactTools.ContactItem created = contactTools.createContact(as(mama), "Dr. Weber", "Arzt", null,
                "0711 123", null, null, null, "Stuttgart", null, null);
        ContactTools.ContactItem updated = contactTools.updateContact(as(mama), created.id(), null, null, "Praxis Weber",
                "", null, null, null, null, null, "Mo geschlossen");

        assertThat(updated.name()).isEqualTo("Dr. Weber");
        assertThat(updated.company()).isEqualTo("Praxis Weber");
        assertThat(updated.phone()).isNull();
        assertThat(updated.city()).isEqualTo("Stuttgart");
        assertThat(contactTools.searchContacts(as(mama), "weber")).hasSize(1);
    }

    @Test
    void staleVersionIsReportedAsToolError() {
        TodoTools.TodoDetail created = todoTools.createTodo(as(mama), "Fenster putzen", null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> todoTools.updateTodo(as(papa), created.todo().id(), created.todo().version() + 5,
                "Fenster", null, null, null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("geändert");
    }

    @Test
    void createsEventAndHidesForeignPrivateEvents() {
        CalendarTools.Event event = calendarTools.createEvent(as(mama), "Zahnarzt", "2030-03-04", "09:30", null,
                "10:15", null, "Praxis", null, null, null, null, List.of(mamaM.getId()), null);
        events.create(new EventInput("Geheim", null, null, LocalDate.of(2030, 3, 4), LocalTime.of(12, 0),
                LocalDate.of(2030, 3, 4), LocalTime.of(13, 0), false, null, true, Set.of()), papa);

        assertThat(event.start()).isEqualTo("2030-03-04T09:30:00");
        assertThat(event.end()).isEqualTo("2030-03-04T10:15:00");
        assertThat(event.participants()).containsExactly("Mama");
        assertThat(calendarTools.listEvents(as(mama), "2030-03-04", "2030-03-04"))
                .extracting(CalendarTools.Event::title).containsExactly("Zahnarzt");

        CalendarTools.Event moved = calendarTools.updateEvent(as(mama), event.eventId(), null, null, "11:00", null,
                "12:00", null, null, null, null, null, null, null, null);
        assertThat(moved.start()).isEqualTo("2030-03-04T11:00:00");
        assertThat(moved.location()).isEqualTo("Praxis");
    }

    @Test
    void blockersWarnInTheAnswerAndBlocksAreReadableErrors() {
        blockerService.create(new BlockerInput("Arbeit", BlockerKind.WORK, LocalTime.of(8, 0), LocalTime.of(16, 0),
                java.util.EnumSet.of(java.time.DayOfWeek.MONDAY), LocalDate.of(2030, 1, 1), null,
                BlockerStrength.WARN, BlockerVisibility.TITLE, false, false, Set.of(mamaM.getId()), Set.of(),
                Set.of()), admin);
        blockerService.create(new BlockerInput("Schule", BlockerKind.CUSTOM, LocalTime.of(8, 0), LocalTime.of(13, 0),
                java.util.EnumSet.of(java.time.DayOfWeek.MONDAY), LocalDate.of(2030, 1, 1), null,
                BlockerStrength.BLOCK, BlockerVisibility.TITLE, false, false, Set.of(kidM.getId()), Set.of(),
                Set.of()), admin);

        // 4.3.2030 ist ein Montag
        CalendarTools.Event event = calendarTools.createEvent(as(papa), "Elterngespräch", "2030-03-04", "10:00", null,
                "11:00", null, null, null, null, null, null, List.of(mamaM.getId()), null);
        assertThat(event.eventId()).isNotNull();
        assertThat(event.warnings()).singleElement().asString().contains("Mama arbeitet da");

        assertThatThrownBy(() -> calendarTools.createEvent(as(papa), "Zahnarzt", "2030-03-04", "10:00", null,
                "11:00", null, null, null, null, null, null, List.of(kidM.getId()), null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("Kind ist da nicht verfügbar");
    }

    @Test
    void allDayEventsShowInclusiveEnd() {
        CalendarTools.Event event = calendarTools.createEvent(as(mama), "Urlaub", "2030-07-01", null, "2030-07-03",
                null, true, null, null, null, null, true, List.of(mamaM.getId()), null);

        assertThat(event.start()).isEqualTo("2030-07-01");
        assertThat(event.end()).isEqualTo("2030-07-03");
        assertThat(event.absence()).isTrue();
        assertThat(calendarTools.listEvents(as(mama), "2030-07-01", "2030-07-10"))
                .extracting(CalendarTools.Event::end).containsExactly("2030-07-03");
    }

    @Test
    void missingRightsAndBadInputGiveReadableErrors() {
        assertThatThrownBy(() -> calendarTools.listEvents(as(kid), null, null))
                .isInstanceOf(Tools.ToolException.class).hasMessageStartingWith("Keine Berechtigung");
        assertThatThrownBy(() -> calendarTools.listEvents(as(mama), "4.3.2030", null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("JJJJ-MM-TT");
        assertThatThrownBy(() -> todoTools.listTodos(as(mama), null, "FERTIG", null, null, null, null, null))
                .isInstanceOf(Tools.ToolException.class).hasMessageContaining("OPEN");
        assertThatThrownBy(() -> todoTools.getTodo(as(mama), 999_999L))
                .isInstanceOf(Tools.ToolException.class).hasMessage("ToDo nicht gefunden");
        assertThatThrownBy(() -> contactTools.getContact(as(mama), 999_999L))
                .isInstanceOf(Tools.ToolException.class).hasMessage("Kontakt nicht gefunden");
    }

    @Test
    void completingChoreTaskWritesCredits() {
        LocalDate today = LocalDate.now(clock);
        Chore chore = chores.create(new ChoreInput("Küche aufräumen", null, null, 20, 20, 20, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=2", today, null, null, Distribution.FIXED, List.of(kidM.getId())), admin);
        entityManager.flush();
        ChoreTask task = tasks.findAllByChoreId(chore.getId()).stream()
                .min(Comparator.comparing(ChoreTask::getDueOn)).orElseThrow();

        assertThat(householdTools.myChoreTasks(as(kid))).extracting(HouseholdTools.ChoreTaskItem::taskId)
                .contains(task.getId());
        HouseholdTools.ChoreTaskItem done = householdTools.completeChoreTask(as(kid), task.getId(), null,
                List.of(mamaM.getId()), null, null);

        assertThat(done.status()).isNotEqualTo("OPEN");
        entityManager.flush();
        assertThat(credits.findAllByTaskId(task.getId())).extracting(ChoreCredit::getMemberId)
                .containsExactlyInAnyOrder(kidM.getId(), mamaM.getId());
        assertThatThrownBy(() -> householdTools.completeChoreTask(as(kid), task.getId(), null, null, null, null))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void fillingTheHouseholdPlanViaTools() {
        LocalDate today = LocalDate.now(clock);
        HouseholdTools.ChoreItem created = householdTools.createChore(as(admin), "Bad putzen", "Bad", null, null,
                "FREQ=DAILY;COUNT=3", today.toString(), null, null, "FIXED", List.of(kidM.getId()), null, null, null,
                null, null);
        entityManager.flush();

        assertThat(created.kind()).isEqualTo("RECURRING");
        assertThat(created.members()).hasSize(1);
        assertThat(householdTools.listChores(as(admin))).extracting(HouseholdTools.ChoreItem::title)
                .contains("Bad putzen");
        List<HouseholdTools.ChoreTaskItem> planned = householdTools.listChoreTasks(as(admin), today.toString(),
                today.plusDays(2).toString());
        assertThat(planned).hasSize(3);

        assertThat(householdTools.updateChore(as(admin), created.choreId(), null, null, null, null, null, null, null,
                null, null, null, 5, 8, 12, null, null).minutesMedium()).isEqualTo(8);
        // Ändern plant die offenen Aufgaben neu, die IDs sind danach andere
        Long taskId = householdTools.listChoreTasks(as(admin), today.toString(), today.plusDays(2).toString())
                .getFirst().taskId();
        assertThat(householdTools.assignChoreTask(as(admin), taskId, mamaM.getId()).assignee())
                .isEqualTo("Mama");

        HouseholdTools.ChoreItem dated = householdTools.createChore(as(admin), "Sperrmüll", null, null, "DATES", null,
                null, null, null, "OPEN", null, null, null, null, null, null);
        assertThat(householdTools.addChoreDate(as(admin), dated.choreId(), today.plusDays(5).toString(), "10:00")
                .dates()).containsExactly(today.plusDays(5) + " 10:00");

        HouseholdTools.ChoreItem onDemand = householdTools.createChore(as(admin), "Staubsaugen", null, null,
                "ON_DEMAND", null, null, null, null, "OPEN", null, null, null, null, null, null);
        assertThat(householdTools.bookChore(as(admin), onDemand.choreId(), kidM.getId(), null, "EASY", null).status())
                .isNotEqualTo("OPEN");

        HouseholdTools.ChoreTaskItem once = householdTools.planChoreTask(as(admin), onDemand.choreId(),
                today.plusDays(3).toString(), "18:15", kidM.getId());
        assertThat(once.status()).isEqualTo("OPEN");
        assertThat(once.dueOn()).isEqualTo(today.plusDays(3));
        assertThat(once.plannedAt()).isEqualTo("18:15");
        assertThat(once.assignee()).isEqualTo("Kind");
        assertThatThrownBy(() -> householdTools.planChoreTask(as(admin), onDemand.choreId(),
                today.minusDays(1).toString(), null, null)).isInstanceOf(Tools.ToolException.class);

        assertThat(householdTools.setChoreActive(as(admin), created.choreId(), false).active()).isFalse();
        assertThat(householdTools.setChoreActive(as(admin), created.choreId(), true).active()).isTrue();
        assertThatThrownBy(() -> householdTools.createChore(as(kid), "Verboten", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null)).isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void overviewSkipsSectionsWithoutRights() {
        todoTools.createTodo(as(mama), "Heute erledigen", null, null, LocalDate.now(clock).toString(), null, null,
                null, null, List.of("member:" + kidM.getId()), null, null, null, null);

        OverviewTools.Overview overview = overviewTools.todayOverview(as(kid));

        assertThat(overview.user()).isEqualTo("kind");
        assertThat(overview.eventsToday()).isEmpty();
        assertThat(overview.notes()).anyMatch(n -> n.startsWith("Kalender"));
        assertThat(overview.myUrgentTodos()).extracting(TodoTools.TodoItem::title).contains("Heute erledigen");
    }
}
