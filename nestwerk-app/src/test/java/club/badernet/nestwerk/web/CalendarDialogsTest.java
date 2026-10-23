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
import club.badernet.nestwerk.todo.TodoPriority;
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
    @Autowired club.badernet.nestwerk.role.RoleService roleService;
    @Autowired FamilyMemberService memberService;
    @Autowired ChoreService chores;
    @Autowired ChoreRepository choreRepository;
    @Autowired ChoreTaskRepository tasks;
    @Autowired TodoService todos;
    @Autowired TodoRepository todoRepository;
    @Autowired CalendarEventService events;
    @Autowired CalendarEventRepository eventRepository;
    @Autowired club.badernet.nestwerk.blocker.BlockerService blockerService;
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

    private Chore onDemandChore(String title) {
        Chore chore = chores.create(new ChoreInput(title, null, null, 10, 20, 30, ChoreKind.ON_DEMAND, null, null,
                null, null, Distribution.OPEN, List.of()), admin);
        entityManager.flush();
        return chore;
    }

    @Test
    void choreOnDemandCanBePlannedOnceFromTheCalendar() throws Exception {
        Chore chore = onDemandChore("Fenster putzen");

        mockMvc.perform(post("/calendar/actions/chores/plan").with(csrf()).with(asAdmin())
                        .param("choreId", chore.getId().toString()).param("date", WEDNESDAY.toString())
                        .param("start", "17:30").param("member", lena.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("eingeplant")));

        assertThat(tasks.findAllByChoreId(chore.getId())).singleElement().satisfies(task -> {
            assertThat(task.getDueOn()).isEqualTo(WEDNESDAY);
            assertThat(task.getPlannedAt()).isEqualTo(LocalTime.of(17, 30));
            assertThat(task.getAssigneeMemberId()).isEqualTo(lena.getId());
        });
    }

    @Test
    void plannedWithoutPersonTheChoreIsOpenToTake() throws Exception {
        Chore chore = onDemandChore("Keller aufräumen");

        mockMvc.perform(post("/calendar/actions/chores/plan").with(csrf()).with(asAdmin())
                        .param("choreId", chore.getId().toString()).param("date", WEDNESDAY.toString())
                        .param("member", ""))
                .andExpect(status().isOk());

        assertThat(tasks.findAllByChoreId(chore.getId())).singleElement()
                .satisfies(task -> assertThat(task.getAssigneeMemberId()).isNull());
    }

    @Test
    void choreCanBeBookedWithoutAPlanFromTheCalendar() throws Exception {
        Chore chore = onDemandChore("Staubsaugen");

        mockMvc.perform(post("/calendar/actions/chores/book").with(csrf()).with(asAdmin())
                        .param("choreId", chore.getId().toString()).param("doer", lena.getId().toString())
                        .param("effort", "EASY").param("minutes", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("Gebucht")));

        assertThat(tasks.findAllByChoreId(chore.getId())).singleElement()
                .satisfies(task -> assertThat(task.getStatus()).isEqualTo(ChoreTaskStatus.DONE));
    }

    @Test
    void planningInThePastIsRejected() throws Exception {
        Chore chore = onDemandChore("Garage fegen");

        mockMvc.perform(post("/calendar/actions/chores/plan").with(csrf()).with(asAdmin())
                        .param("choreId", chore.getId().toString()).param("date", MONDAY.minusDays(1).toString()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void createDialogOffersEveryTypeTheViewerMayCreate() throws Exception {
        onDemandChore("Wäsche falten");

        mockMvc.perform(get("/calendar/week").param("date", WEDNESDAY.toString()).with(asAdmin()))
                .andExpect(content().string(containsString("data-open-create")))
                .andExpect(content().string(containsString("id=\"grid-quick\"")))
                .andExpect(content().string(containsString("data-create-type=\"event\"")))
                .andExpect(content().string(containsString("data-create-type=\"chore\"")))
                .andExpect(content().string(containsString("data-create-type=\"todo\"")))
                .andExpect(content().string(containsString("data-create-type=\"time\"")))
                .andExpect(content().string(containsString("/calendar/actions/todos\"")))
                .andExpect(content().string(containsString("/calendar/actions/blockers\"")))
                .andExpect(content().string(containsString("Wäsche falten")))
                .andExpect(content().string(containsString("data-chore-mode")))
                .andExpect(content().string(not(containsString("id=\"chore-quick\""))));
    }

    @Test
    void createDialogIsAvailableOnEveryView() throws Exception {
        for (String view : new String[]{"day", "days3", "week", "month", "agenda", "my"}) {
            mockMvc.perform(get("/calendar/" + view).param("date", WEDNESDAY.toString()).with(asAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("data-open-create")))
                    .andExpect(content().string(containsString("data-create-form=\"todo\"")));
        }
    }

    @Test
    void viewersWithoutAnyCreateRightDoNotSeeTheDialog() throws Exception {
        club.badernet.nestwerk.role.Role viewOnly = roleService.create("Leser", null, Set.of("CALENDAR_VIEW"));
        userService.create("leser", "passwort1", Set.of(viewOnly.getId()));
        mockMvc.perform(get("/calendar/week").param("date", WEDNESDAY.toString())
                        .with(user("leser").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-open-create"))))
                .andExpect(content().string(not(containsString("id=\"grid-quick\""))));
    }

    @Test
    void todoIsCreatedFromTheCalendar() throws Exception {
        mockMvc.perform(post("/calendar/actions/todos").with(csrf()).with(asAdmin())
                        .param("title", "Steuer machen").param("dueOn", WEDNESDAY.toString())
                        .param("priority", "HIGH").param("assignees", "member:" + lena.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("Steuer machen")));

        Todo created = todoRepository.findAll().stream().filter(t -> t.getTitle().equals("Steuer machen"))
                .findFirst().orElseThrow();
        assertThat(created.getDueOn()).isEqualTo(WEDNESDAY);
        assertThat(created.getPriority()).isEqualTo(TodoPriority.HIGH);
        assertThat(created.assigneeRefs()).extracting(AssigneeRef::key).containsExactly("member:" + lena.getId());
    }

    @Test
    void creatingATodoWithoutTitleOrRightIsRefused() throws Exception {
        mockMvc.perform(post("/calendar/actions/todos").with(csrf()).with(asAdmin()).param("title", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void timeIsCreatedFromTheCalendarForTheChosenPeople() throws Exception {
        mockMvc.perform(post("/calendar/actions/blockers").with(csrf()).with(asAdmin())
                        .param("title", "Arbeit").param("kind", "WORK").param("startTime", "08:00")
                        .param("endTime", "16:00").param("weekdays", "TUESDAY").param("validFrom", WEDNESDAY.toString())
                        .param("memberIds", lena.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("Arbeit")));

        assertThat(blockerService.forMember(lena.getId())).singleElement().satisfies(blocker -> {
            assertThat(blocker.getTitle()).isEqualTo("Arbeit");
            assertThat(blocker.getStartTime()).isEqualTo(LocalTime.of(8, 0));
        });
    }

    @Test
    void creatingATimeNeedsADayAndTimes() throws Exception {
        mockMvc.perform(post("/calendar/actions/blockers").with(csrf()).with(asAdmin())
                        .param("title", "Arbeit").param("kind", "WORK").param("startTime", "08:00")
                        .param("endTime", "16:00").param("validFrom", WEDNESDAY.toString())
                        .param("memberIds", lena.getId().toString()))
                .andExpect(status().isBadRequest());
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
    void choreCanBeResizedForItsDate() throws Exception {
        ChoreTask task = plannedChore(1);

        mockMvc.perform(post("/calendar/actions/chores/{id}/update", task.getId()).with(csrf()).with(asAdmin())
                        .param("date", MONDAY.toString()).param("start", "16:30").param("minutes", "50"))
                .andExpect(status().isOk());

        assertThat(tasks.findById(task.getId()).orElseThrow().minutes()).isEqualTo(50);
    }

    @Test
    void choreResizeToATooShortLengthIsRejected() throws Exception {
        ChoreTask task = plannedChore(1);

        mockMvc.perform(post("/calendar/actions/chores/{id}/update", task.getId()).with(csrf()).with(asAdmin())
                        .param("date", MONDAY.toString()).param("start", "16:30").param("minutes", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void movingAChoreOntoAnEventThatForbidsOverlapIsRejectedWithTheReason() throws Exception {
        ChoreTask task = plannedChore(1);
        events.create(new EventInput("Operation", null, null, WEDNESDAY, LocalTime.of(10, 0), WEDNESDAY,
                LocalTime.of(11, 0), false, null, false, Set.of(lena.getId()), false, null, true), admin);

        mockMvc.perform(post("/calendar/actions/chores/{id}/update", task.getId()).with(csrf()).with(asAdmin())
                        .param("date", WEDNESDAY.toString()).param("start", "10:30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Operation")));

        assertThat(tasks.findById(task.getId()).orElseThrow().getDueOn()).isEqualTo(MONDAY);
    }

    @Test
    void gridMarksTheTimesOfEventsAndChoresAsOverlapZones() throws Exception {
        ChoreTask task = plannedChore(1);
        CalendarEvent flagged = events.create(new EventInput("Operation", null, null, MONDAY, LocalTime.of(10, 0),
                MONDAY, LocalTime.of(11, 0), false, null, false, Set.of(lena.getId()), false, null, true), admin);

        mockMvc.perform(get("/calendar/week").param("date", MONDAY.toString()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tgrid-zone-overlap")))
                .andExpect(content().string(containsString("data-overlap-key=\"event:" + flagged.getId() + "\"")))
                .andExpect(content().string(containsString("data-overlap-key=\"chore:" + task.getId() + "\"")))
                .andExpect(content().string(containsString("data-overlap-flag=\"true\"")))
                .andExpect(content().string(containsString("data-no-overlap=\"true\"")))
                .andExpect(content().string(containsString("überschneidet sich mit dem Termin „Operation“")));
    }

    /* --- Quick actions: context menu and bar in the dialog ------------------------------------------------------ */

    @Test
    void contextMenuOffersTheChoreActionsTheViewerMayUse() throws Exception {
        ChoreTask task = plannedChore(1);

        mockMvc.perform(get("/calendar/items/chores/{id}/actions", task.getId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key").value(org.hamcrest.Matchers.contains("complete", "skip", "edit")))
                .andExpect(jsonPath("$[0].url").value("/calendar/actions/chores/" + task.getId() + "/complete"))
                .andExpect(jsonPath("$[1].label").value("Nicht nötig"));

        mockMvc.perform(post("/calendar/actions/chores/{id}/skip", task.getId()).with(csrf()).with(asAdmin()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/calendar/items/chores/{id}/actions", task.getId()).with(asAdmin()))
                .andExpect(jsonPath("$[*].key").value(org.hamcrest.Matchers.contains("undo")))
                .andExpect(jsonPath("$[0].label").value("Wieder nötig"));
    }

    @Test
    void contextMenuOfASeriesOffersToCancelOneOccurrenceOrDeleteAll() throws Exception {
        CalendarEvent series = events.create(new EventInput("Chor", null, null, WEDNESDAY, LocalTime.of(19, 0),
                WEDNESDAY, LocalTime.of(20, 0), false, "FREQ=WEEKLY", false, Set.of(), false, null), admin);
        String occurrence = series.getStartsAt().toString();

        mockMvc.perform(get("/calendar/items/events/{id}/actions", series.getId()).param("occurrence", occurrence)
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key").value(org.hamcrest.Matchers.contains("edit", "cancel-occurrence",
                        "delete")))
                .andExpect(jsonPath("$[1].params.recurrenceId").value(occurrence))
                .andExpect(jsonPath("$[2].label").value("Ganze Serie löschen"))
                .andExpect(jsonPath("$[2].danger").value(true))
                .andExpect(jsonPath("$[2].confirm").value(containsString("Chor")));
    }

    @Test
    void contextMenuOfATodoCanCheckItOff() throws Exception {
        Todo todo = dueTodo(Set.of());

        mockMvc.perform(get("/calendar/items/todos/{id}/actions", todo.getId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("complete"))
                .andExpect(jsonPath("$[0].url").value("/calendar/actions/todos/" + todo.getId() + "/status"))
                .andExpect(jsonPath("$[0].params.status").value("DONE"));
    }

    @Test
    void contextMenuRejectsUnknownEntriesAndKinds() throws Exception {
        mockMvc.perform(get("/calendar/items/chores/{id}/actions", 999_999L).with(asAdmin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/calendar/items/kitchen/{id}/actions", 1L).with(asAdmin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/calendar/items/chores/{id}/actions", 1L).with(user("niemand")))
                .andExpect(status().isForbidden());
    }

    @Test
    void dialogsStartWithTheQuickActionBar() throws Exception {
        ChoreTask task = plannedChore(1);
        Todo todo = dueTodo(Set.of());

        mockMvc.perform(get("/calendar/chores/{id}/sheet", task.getId()).with(asAdmin()))
                .andExpect(content().string(containsString("class=\"quick-actions\"")))
                .andExpect(content().string(containsString("/calendar/actions/chores/" + task.getId() + "/skip")))
                .andExpect(content().string(containsString("Nicht nötig")));
        mockMvc.perform(get("/calendar/todos/{id}/sheet", todo.getId()).with(asAdmin()))
                .andExpect(content().string(containsString("class=\"quick-actions\"")))
                .andExpect(content().string(containsString("name=\"status\" value=\"DONE\"")));
    }

    @Test
    void theEventDialogHasDeleteInItsBarAndNothingForViewersWithoutTheRight() throws Exception {
        CalendarEvent single = events.create(new EventInput("Zahnarzt", null, null, WEDNESDAY, LocalTime.of(9, 0),
                WEDNESDAY, LocalTime.of(10, 0), false, null, false, Set.of(), false, null), admin);

        mockMvc.perform(get("/calendar/events/{id}/sheet", single.getId()).with(asAdmin()))
                .andExpect(content().string(containsString("class=\"quick-actions\"")))
                .andExpect(content().string(containsString("Termin löschen")))
                .andExpect(content().string(containsString("data-confirm-tone=\"danger\"")));
        mockMvc.perform(get("/calendar/events/{id}/sheet", single.getId())
                        .with(user("anderer").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(content().string(not(containsString("class=\"quick-actions\""))));
    }

    @Test
    void theContextMenuScriptIsLoadedOnEveryPage() throws Exception {
        mockMvc.perform(get("/calendar/week").with(asAdmin()))
                .andExpect(content().string(containsString("/js/calendar-menu.js")));
        mockMvc.perform(get("/js/calendar-menu.js").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("role\", \"menu\"")))
                .andExpect(content().string(containsString("contextmenu")));
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
