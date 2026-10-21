package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreRepository;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskRepository;
import club.badernet.nestwerk.household.ChoreTaskStatus;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The calendar's dialogs and their actions for chores and to-dos, the focus day across views, the grid split by
 * person and dragging entries between people.
 */
@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class CalendarDialogsTest {

    /** Today of the fixed clock. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    private static final LocalDate WEDNESDAY = MONDAY.plusDays(2);

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired FamilyMemberService memberService;
    @Autowired ChoreService chores;
    @Autowired ChoreRepository choreRepository;
    @Autowired ChoreTaskRepository tasks;
    @Autowired TodoService todos;
    @Autowired TodoRepository todoRepository;
    @Autowired CalendarEventService events;
    @Autowired CalendarEventRepository eventRepository;
    @Autowired EntityManager entityManager;

    User admin;
    FamilyMember lena;
    FamilyMember tim;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        lena = memberService.create("Lena", "#00aa00", null);
        tim = memberService.create("Tim", "#0000aa", null);
    }

    private static RequestPostProcessor asAdmin() {
        return user("chef").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"));
    }

    private ChoreTask plannedChore(int days) {
        Chore chore = chores.create(new ChoreInput("Bad putzen", null, null, 10, 20, 30, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=" + days, MONDAY, LocalTime.of(18, 0), null, Distribution.FIXED,
                List.of(lena.getId())), admin);
        entityManager.flush();
        return tasks.findAllByChoreId(chore.getId()).stream()
                .min((a, b) -> a.getDueOn().compareTo(b.getDueOn())).orElseThrow();
    }

    private Todo dueTodo(Set<AssigneeRef> assignees) {
        return todos.create(new TodoInput("Steuer", null, null, null, WEDNESDAY, null, null, false, null, Set.of(),
                assignees, null), admin);
    }

    /* --- Focus day ----------------------------------------------------------------------------------------------- */

    @Test
    void weekAndMonthKeepTheFocusDayForTheOtherViews() throws Exception {
        mockMvc.perform(get("/calendar/week").param("date", WEDNESDAY.toString()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/calendar/day?date=" + WEDNESDAY)))
                .andExpect(content().string(containsString("/calendar/week?date=" + WEDNESDAY.plusWeeks(1))));
        mockMvc.perform(get("/calendar/month").param("date", WEDNESDAY.toString()).with(asAdmin()))
                .andExpect(content().string(containsString("/calendar/days3?date=" + WEDNESDAY)))
                .andExpect(content().string(containsString("is-focus")));
    }

    @Test
    void toolbarListsViewsFromDayToAgenda() throws Exception {
        String page = mockMvc.perform(get("/calendar/day").param("date", WEDNESDAY.toString()).with(asAdmin()))
                .andReturn().getResponse().getContentAsString();

        assertThat(page.indexOf(">Tag</a>")).isLessThan(page.indexOf(">3 Tage</a>"));
        assertThat(page.indexOf(">3 Tage</a>")).isLessThan(page.indexOf(">Woche</a>"));
        assertThat(page.indexOf(">Woche</a>")).isLessThan(page.indexOf(">Monat</a>"));
        assertThat(page.indexOf(">Monat</a>")).isLessThan(page.indexOf(">Agenda</a>"));
    }

    @Test
    void savingTheFullEventFormReturnsToTheCalendarViewItCameFrom() throws Exception {
        mockMvc.perform(post("/calendar/events").with(csrf()).with(user("chef").authorities(
                                new SimpleGrantedAuthority("CALENDAR_VIEW"), new SimpleGrantedAuthority("CALENDAR_EVENT_CREATE")))
                        .param("title", "Elternabend").param("startDate", WEDNESDAY.toString()).param("startTime", "19:00")
                        .param("endDate", WEDNESDAY.toString()).param("endTime", "20:00")
                        .param("back", "/calendar/days3?date=" + WEDNESDAY))
                .andExpect(redirectedUrl("/calendar/days3?date=" + WEDNESDAY));
    }

    /* --- Chore dialog -------------------------------------------------------------------------------------------- */

    @Test
    void choreDialogOffersThisDateAndTheWholeChore() throws Exception {
        ChoreTask task = plannedChore(2);

        mockMvc.perform(get("/calendar/chores/{id}/sheet", task.getId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Abhaken und buchen")))
                .andExpect(content().string(containsString("Diesen Termin löschen")))
                .andExpect(content().string(containsString("data-tab=\"chore\"")))
                .andExpect(content().string(containsString("Ganze Hausarbeit löschen")));
    }

    @Test
    void choreCanBeBookedFromTheCalendar() throws Exception {
        ChoreTask task = plannedChore(1);

        mockMvc.perform(post("/calendar/actions/chores/{id}/complete", task.getId()).with(csrf()).with(asAdmin())
                        .param("doer", lena.getId().toString()).param("effort", "EASY").param("minutes", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("Erledigt")));

        assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(ChoreTaskStatus.DONE);
    }

    @Test
    void choreCanBeMovedToAnotherDayAndPerson() throws Exception {
        ChoreTask task = plannedChore(1);

        mockMvc.perform(post("/calendar/actions/chores/{id}/update", task.getId()).with(csrf()).with(asAdmin())
                        .param("date", WEDNESDAY.toString()).param("start", "16:30")
                        .param("member", tim.getId().toString()))
                .andExpect(status().isOk());

        ChoreTask moved = tasks.findById(task.getId()).orElseThrow();
        assertThat(moved.getDueOn()).isEqualTo(WEDNESDAY);
        assertThat(moved.getPlannedAt()).isEqualTo(LocalTime.of(16, 30));
        assertThat(moved.getAssigneeMemberId()).isEqualTo(tim.getId());
    }

    @Test
    void cancellingADateKeepsTheChoreAndItsOtherDates() throws Exception {
        ChoreTask task = plannedChore(2);

        mockMvc.perform(post("/calendar/actions/chores/{id}/cancel", task.getId()).with(csrf()).with(asAdmin()))
                .andExpect(status().isOk());

        assertThat(tasks.findById(task.getId()).orElseThrow().isCancelled()).isTrue();
        assertThat(tasks.findAllByChoreId(task.getChore().getId())).filteredOn(ChoreTask::isOpen).hasSize(1);
        mockMvc.perform(get("/calendar/day").param("date", MONDAY.toString()).with(asAdmin()))
                .andExpect(content().string(not(containsString("data-chore-id=\"" + task.getId() + "\""))));
    }

    @Test
    void wholeChoreCanBeChangedAndDeletedFromTheDialog() throws Exception {
        ChoreTask task = plannedChore(2);
        Long choreId = task.getChore().getId();

        mockMvc.perform(post("/calendar/actions/chores/templates/{id}/update", choreId).with(csrf()).with(asAdmin())
                        .param("title", "Bad gründlich putzen").param("minutesEasy", "10").param("minutesMedium", "20")
                        .param("minutesHard", "30").param("kind", "RECURRING").param("rrule", "FREQ=DAILY;COUNT=2")
                        .param("startsOn", MONDAY.toString()).param("distribution", "FIXED")
                        .param("memberIds", lena.getId().toString()))
                .andExpect(status().isOk());
        assertThat(choreRepository.findById(choreId).orElseThrow().getTitle()).isEqualTo("Bad gründlich putzen");

        mockMvc.perform(post("/calendar/actions/chores/templates/{id}/delete", choreId).with(csrf()).with(asAdmin()))
                .andExpect(status().isOk());
        assertThat(choreRepository.findById(choreId)).isEmpty();
    }

    @Test
    void choreActionsAnswerWithMessageWhenRefused() throws Exception {
        ChoreTask task = plannedChore(1);

        mockMvc.perform(post("/calendar/actions/chores/{id}/update", task.getId()).with(csrf()).with(asAdmin())
                        .param("date", MONDAY.minusDays(1).toString()).param("start", "09:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Vergangenheit")));
        mockMvc.perform(post("/calendar/actions/chores/{id}/cancel", 999_999L).with(csrf()).with(asAdmin()))
                .andExpect(status().isNotFound());
    }

    /* --- To-do dialog -------------------------------------------------------------------------------------------- */

    @Test
    void todoDialogEditsTitleDueDatePriorityAndPeople() throws Exception {
        Todo todo = dueTodo(Set.of(AssigneeRef.member(lena.getId())));

        mockMvc.perform(get("/calendar/todos/{id}/sheet", todo.getId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ToDo löschen")));
        mockMvc.perform(post("/calendar/actions/todos/{id}/update", todo.getId()).with(csrf()).with(asAdmin())
                        .param("version", String.valueOf(todo.getVersion())).param("title", "Steuererklärung")
                        .param("dueOn", MONDAY.plusDays(4).toString()).param("priority", "HIGH")
                        .param("assignees", "member:" + tim.getId()))
                .andExpect(status().isOk());

        Todo saved = todoRepository.findById(todo.getId()).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("Steuererklärung");
        assertThat(saved.getDueOn()).isEqualTo(MONDAY.plusDays(4));
        assertThat(saved.assigneeRefs()).containsExactly(AssigneeRef.member(tim.getId()));
    }

    @Test
    void todoStatusAndDeleteWorkFromTheDialog() throws Exception {
        Todo todo = dueTodo(Set.of());

        mockMvc.perform(post("/calendar/actions/todos/{id}/status", todo.getId()).with(csrf()).with(asAdmin())
                        .param("status", "DONE"))
                .andExpect(status().isOk());
        assertThat(todoRepository.findById(todo.getId()).orElseThrow().getStatus()).isEqualTo(TodoStatus.DONE);

        mockMvc.perform(post("/calendar/actions/todos/{id}/delete", todo.getId()).with(csrf()).with(asAdmin()))
                .andExpect(status().isOk());
        assertThat(todoRepository.findById(todo.getId())).isEmpty();
    }

    @Test
    void draggingATodoChangesItsDayAndHandsItToAnotherPerson() throws Exception {
        Todo todo = dueTodo(Set.of(AssigneeRef.member(lena.getId())));

        mockMvc.perform(post("/calendar/actions/todos/{id}/move", todo.getId()).with(csrf()).with(asAdmin())
                        .param("dueOn", MONDAY.plusDays(3).toString())
                        .param("from", lena.getId().toString()).param("to", tim.getId().toString()))
                .andExpect(status().isOk());

        Todo moved = todoRepository.findById(todo.getId()).orElseThrow();
        assertThat(moved.getDueOn()).isEqualTo(MONDAY.plusDays(3));
        assertThat(moved.assigneeRefs()).containsExactly(AssigneeRef.member(tim.getId()));
    }

    /* --- Split by person ----------------------------------------------------------------------------------------- */

    @Test
    void splitGridShowsAColumnPerPersonAndEntriesInTheirPeoplesColumns() throws Exception {
        events.create(new EventInput("Zahnarzt", null, null, WEDNESDAY, LocalTime.of(15, 0), WEDNESDAY,
                LocalTime.of(16, 0), false, null, false, Set.of(lena.getId(), tim.getId())), admin);

        String page = mockMvc.perform(get("/calendar/days3").param("date", WEDNESDAY.toString()).with(asAdmin())
                        .cookie(new Cookie(GridSplit.COOKIE, "person")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tgrid-split")))
                .andExpect(content().string(containsString("data-member-name=\"Lena\"")))
                .andExpect(content().string(containsString(">Alle</span>")))
                .andReturn().getResponse().getContentAsString();

        assertThat(page.split(">Zahnarzt</span>", -1)).hasSize(3);
        mockMvc.perform(get("/calendar/days3").param("date", WEDNESDAY.toString()).with(asAdmin()))
                .andExpect(content().string(not(containsString("tgrid-split"))));
    }

    @Test
    void draggingAnEventIntoAnotherPersonsColumnReplacesThatPerson() throws Exception {
        CalendarEvent event = events.create(new EventInput("Training", null, null, WEDNESDAY, LocalTime.of(17, 0),
                WEDNESDAY, LocalTime.of(18, 0), false, null, false, Set.of(lena.getId())), admin);

        mockMvc.perform(post("/calendar/actions/events/{id}/reschedule", event.getId()).with(csrf()).with(asAdmin())
                        .param("scope", "SINGLE").param("allDay", "false")
                        .param("start", WEDNESDAY + "T18:00").param("end", WEDNESDAY + "T19:00")
                        .param("fromMember", lena.getId().toString()).param("toMember", tim.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("Person")));

        CalendarEvent moved = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(moved.getMemberIds()).containsExactly(tim.getId());
        assertThat(moved.getStartsAt()).isEqualTo(WEDNESDAY.atTime(18, 0).atZone(java.time.ZoneId.of("Europe/Berlin"))
                .toInstant());
    }

    @Test
    void draggingSidewaysOnlyChangesThePerson() throws Exception {
        CalendarEvent event = events.create(new EventInput("Training", null, null, WEDNESDAY, LocalTime.of(17, 0),
                WEDNESDAY, LocalTime.of(18, 0), false, null, false, Set.of(lena.getId())), admin);

        mockMvc.perform(post("/calendar/actions/events/{id}/reschedule", event.getId()).with(csrf()).with(asAdmin())
                        .param("scope", "SINGLE").param("fromMember", lena.getId().toString()).param("toMember", ""))
                .andExpect(status().isOk());

        CalendarEvent moved = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(moved.getMemberIds()).isEmpty();
        assertThat(moved.getStartsAt()).isEqualTo(event.getStartsAt());
    }

    /* --- Confirmation dialog ------------------------------------------------------------------------------------- */

    @Test
    void confirmationDialogIsNeutralUnlessADeletionAsks() throws Exception {
        mockMvc.perform(get("/calendar/month").with(asAdmin()))
                .andExpect(content().string(containsString("data-confirm-ok>Bestätigen</button>")))
                .andExpect(content().string(not(containsString("button-danger\" data-confirm-ok"))));
    }
}
