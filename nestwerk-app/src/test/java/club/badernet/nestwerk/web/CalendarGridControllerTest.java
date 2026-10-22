package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.blocker.BlockerInput;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerService;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BlockerVisibility;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventCategoryService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class CalendarGridControllerTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 13);

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberService memberService;
    @Autowired BlockerService blockers;
    @Autowired CalendarEventService events;
    @Autowired CalendarEventRepository repository;
    @Autowired EventCategoryService categories;

    User editor;
    User other;
    FamilyMember kid;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Editor", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN"));
        editor = userService.create("editor", "passwort1", Set.of(normal.getId()));
        other = userService.create("anderer", "passwort1", Set.of(normal.getId()));
        User admin = userService.createAdministrator("chef", "passwort1");
        kid = memberService.create("Lio", "#2ee6a8", null);
        blockers.create(new BlockerInput("Schule", BlockerKind.CUSTOM, LocalTime.of(8, 0), LocalTime.of(13, 0),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalDate.of(2026, 10, 1), null,
                BlockerStrength.BLOCK, BlockerVisibility.TITLE, false, false, Set.of(kid.getId()), Set.of(),
                Set.of()), admin);
    }

    private static RequestPostProcessor as(String name) {
        return user(name).authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"));
    }

    private CalendarEvent event(String title, int hour, Long categoryId) {
        return events.create(new EventInput(title, null, "Praxis", TUESDAY, LocalTime.of(hour, 0), TUESDAY,
                LocalTime.of(hour + 1, 0), false, null, false, Set.of(), false, categoryId), editor);
    }

    @Test
    void allThreeGridViewsShowEventsAtTheirTime() throws Exception {
        Long doctor = categories.findByName("Arzt", editor).orElseThrow().getId();
        event("Zahnarzt", 15, doctor);

        for (String view : new String[]{"days3", "week"}) {
            mockMvc.perform(get("/calendar/" + view).param("date", TUESDAY.toString()).with(as("editor")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("data-time-grid")))
                    .andExpect(content().string(containsString("Zahnarzt")))
                    .andExpect(content().string(containsString("--start: 900; --dur: 60;")))
                    .andExpect(content().string(containsString("--occ-color: #e5484d")))
                    .andExpect(content().string(containsString("data-slot-fine=\"5\"")))
                    .andExpect(content().string(containsString("data-slot-coarse=\"15\"")));
        }
    }

    @Test
    void dayViewShowsOneLanePerPersonWithTheEventInTheLaneOfItsMember() throws Exception {
        events.create(new EventInput("Zahnarzt", null, "Praxis", TUESDAY, LocalTime.of(15, 0), TUESDAY,
                LocalTime.of(16, 0), false, null, false, Set.of(kid.getId()), false, null), editor);

        String page = mockMvc.perform(get("/calendar/day").param("date", TUESDAY.toString()).with(as("editor")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-lanes")))
                .andExpect(content().string(not(containsString("data-time-grid"))))
                .andExpect(content().string(containsString("data-member=\"" + kid.getId() + "\"")))
                .andExpect(content().string(containsString("--start: 900; --dur: 60;")))
                .andExpect(content().string(containsString("data-slot-coarse=\"15\"")))
                .andReturn().getResponse().getContentAsString();

        String laneOfKid = page.substring(page.indexOf("data-member=\"" + kid.getId() + "\""));
        assertThat(laneOfKid.indexOf("Zahnarzt")).isPositive();
        assertThat(laneOfKid.indexOf("Zahnarzt")).isLessThan(laneOfKid.indexOf("Alle (ohne Person)"));
    }

    @Test
    void dayViewOffersHandlesAndTheSeriesChoiceForEditableEntries() throws Exception {
        event("Zahnarzt", 15, null);

        mockMvc.perform(get("/calendar/day").param("date", TUESDAY.toString()).with(as("editor")))
                .andExpect(content().string(containsString("data-grid-item=\"event\"")))
                .andExpect(content().string(containsString("data-editable=\"true\"")))
                .andExpect(content().string(containsString("data-start=\"" + TUESDAY + "T15:00\"")))
                .andExpect(content().string(containsString("data-end=\"" + TUESDAY + "T16:00\"")))
                .andExpect(content().string(containsString("data-handle=\"start\"")))
                .andExpect(content().string(containsString("data-handle=\"end\"")))
                .andExpect(content().string(containsString("id=\"grid-series\"")));
    }

    @Test
    void weekViewOffersHandlesAtBothEndsOfAnAllDayEvent() throws Exception {
        events.create(new EventInput("Urlaub", null, null, TUESDAY, null, TUESDAY.plusDays(1), null, true, null,
                false, Set.of(), false, null), editor);

        mockMvc.perform(get("/calendar/week").param("date", TUESDAY.toString()).with(as("editor"))
                        .cookie(new jakarta.servlet.http.Cookie(GridSplit.COOKIE, "none")))
                .andExpect(content().string(containsString("tgrid-handle-left\" data-handle=\"start\"")))
                .andExpect(content().string(containsString("tgrid-handle-right\" data-handle=\"end\"")));
    }

    @Test
    void dayViewKeepsForeignEntriesReadOnly() throws Exception {
        event("Meins", 15, null);

        mockMvc.perform(get("/calendar/day").param("date", TUESDAY.toString()).with(as("anderer")))
                .andExpect(content().string(containsString("Meins")))
                .andExpect(content().string(containsString("data-editable=\"false\"")))
                .andExpect(content().string(not(containsString("data-handle=\"start\""))));
    }

    @Test
    void dayViewIgnoresTheSplitCookieAndHidesTheSplitToggle() throws Exception {
        mockMvc.perform(get("/calendar/day").param("date", TUESDAY.toString()).with(as("editor"))
                        .cookie(new jakarta.servlet.http.Cookie(GridSplit.COOKIE, "none")))
                .andExpect(content().string(containsString("Lio")))
                .andExpect(content().string(not(containsString("data-split-toggle"))));
    }

    @Test
    void gridShowsBlockedZonesForLiveFeedback() throws Exception {
        mockMvc.perform(get("/calendar/days3").param("date", TUESDAY.toString()).with(as("editor")))
                .andExpect(content().string(containsString("tgrid-zone-block")))
                .andExpect(content().string(containsString("data-member=\"" + kid.getId() + "\"")))
                .andExpect(content().string(containsString("data-start-minute=\"480\"")));
    }

    @Test
    void gridDrawsBlockedTimesInlineAtTheirTime() throws Exception {
        mockMvc.perform(get("/calendar/days3").param("date", TUESDAY.toString()).with(as("editor")))
                .andExpect(content().string(containsString("class=\"tgrid-busy ")))
                .andExpect(content().string(containsString("--start: 480;")))
                .andExpect(content().string(containsString("tgrid-busy-label")));
    }

    @Test
    void foreignEventsAreNotEditableInTheGrid() throws Exception {
        event("Meins", 15, null);

        mockMvc.perform(get("/calendar/days3").param("date", TUESDAY.toString()).with(as("anderer")))
                .andExpect(content().string(containsString("Meins")))
                .andExpect(content().string(containsString("data-editable=\"false\"")))
                .andExpect(content().string(not(containsString("data-handle=\"start\""))));
    }

    @Test
    void sheetShowsQuickEditForOwnerAndReadOnlyForOthers() throws Exception {
        CalendarEvent own = event("Zahnarzt", 15, null);

        mockMvc.perform(get("/calendar/events/{id}/sheet", own.getId()).with(as("editor")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-grid-form=\"update\"")))
                .andExpect(content().string(containsString("value=\"15:00\"")))
                .andExpect(content().string(containsString("Termin löschen")));
        mockMvc.perform(get("/calendar/events/{id}/sheet", own.getId()).with(as("anderer")))
                .andExpect(content().string(containsString("disabled")))
                .andExpect(content().string(not(containsString("Termin löschen"))));
    }

    @Test
    void quickCreateSavesEventAndAnswersWithItsId() throws Exception {
        mockMvc.perform(post("/calendar/actions/events").with(csrf()).with(as("editor"))
                        .param("title", "Elternabend").param("startDate", TUESDAY.toString())
                        .param("startTime", "19:05").param("endDate", TUESDAY.toString()).param("endTime", "20:35"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("Elternabend")))
                .andExpect(jsonPath("$.eventId").isNumber());

        assertThat(repository.findAll()).filteredOn(e -> e.getTitle().equals("Elternabend")).singleElement()
                .satisfies(e -> assertThat(e.getStartsAt()).isEqualTo(TUESDAY.atTime(19, 5).atZone(BERLIN).toInstant()));
    }

    @Test
    void draggingMovesAndResizesSnappedTimes() throws Exception {
        CalendarEvent own = event("Zahnarzt", 15, null);

        mockMvc.perform(post("/calendar/actions/events/{id}/reschedule", own.getId()).with(csrf()).with(as("editor"))
                        .param("scope", "SINGLE").param("allDay", "false")
                        .param("start", "2026-10-14T16:15").param("end", "2026-10-14T17:45"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Termin wurde verschoben."));

        CalendarEvent moved = repository.findById(own.getId()).orElseThrow();
        assertThat(moved.getStartsAt()).isEqualTo(Instant.parse("2026-10-14T14:15:00Z"));
        assertThat(moved.getEndsAt()).isEqualTo(Instant.parse("2026-10-14T15:45:00Z"));
    }

    @Test
    void draggingIntoTheAllDayRowMakesAnAllDayEvent() throws Exception {
        CalendarEvent own = event("Ausflug", 15, null);

        mockMvc.perform(post("/calendar/actions/events/{id}/reschedule", own.getId()).with(csrf()).with(as("editor"))
                        .param("scope", "SINGLE").param("allDay", "true")
                        .param("startDate", "2026-10-15").param("endDate", "2026-10-16"))
                .andExpect(status().isOk());

        assertThat(repository.findById(own.getId()).orElseThrow().isAllDay()).isTrue();
    }

    @Test
    void blockedTimesAndForeignEventsAreRefusedWithMessage() throws Exception {
        CalendarEvent withKid = events.create(new EventInput("Arzt", null, null, TUESDAY, LocalTime.of(15, 0), TUESDAY,
                LocalTime.of(16, 0), false, null, false, Set.of(kid.getId())), editor);

        mockMvc.perform(post("/calendar/actions/events/{id}/reschedule", withKid.getId()).with(csrf()).with(as("editor"))
                        .param("scope", "SINGLE").param("start", "2026-10-13T09:00").param("end", "2026-10-13T10:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Lio")));
        mockMvc.perform(post("/calendar/actions/events/{id}/reschedule", withKid.getId()).with(csrf()).with(as("anderer"))
                        .param("scope", "SINGLE").param("start", "2026-10-13T17:00").param("end", "2026-10-13T18:00"))
                .andExpect(status().isForbidden());
    }

    @Test
    void seriesCanBeMovedAsOccurrenceOrAsWholeSeries() throws Exception {
        CalendarEvent series = events.create(new EventInput("Training", null, null, LocalDate.of(2026, 10, 6),
                LocalTime.of(18, 0), LocalDate.of(2026, 10, 6), LocalTime.of(19, 0), false, "FREQ=WEEKLY", false,
                Set.of()), editor);
        Instant second = TUESDAY.atTime(18, 0).atZone(BERLIN).toInstant();

        mockMvc.perform(post("/calendar/actions/events/{id}/reschedule", series.getId()).with(csrf()).with(as("editor"))
                        .param("scope", "OCCURRENCE").param("recurrenceId", second.toString())
                        .param("start", "2026-10-13T18:30").param("end", "2026-10-13T19:30"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/calendar/actions/events/{id}/reschedule", series.getId()).with(csrf()).with(as("editor"))
                        .param("scope", "SERIES").param("recurrenceId", second.toString())
                        .param("start", "2026-10-06T17:00").param("end", "2026-10-06T18:15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Die Serie wurde verschoben."));

        assertThat(repository.findById(series.getId()).orElseThrow().getStartsAt())
                .isEqualTo(LocalDate.of(2026, 10, 6).atTime(17, 0).atZone(BERLIN).toInstant());
    }

    @Test
    void sheetUpdateKeepsNotesAndChangesCategory() throws Exception {
        CalendarEvent own = event("Zahnarzt", 15, null);
        Long sport = categories.findByName("Sport", editor).orElseThrow().getId();

        mockMvc.perform(post("/calendar/actions/events/{id}/update", own.getId()).with(csrf()).with(as("editor"))
                        .param("scope", "SINGLE").param("title", "Zahnarzt Kontrolle").param("allDay", "false")
                        .param("startDate", TUESDAY.toString()).param("startTime", "15:30")
                        .param("endDate", TUESDAY.toString()).param("endTime", "16:00")
                        .param("categoryId", sport.toString()))
                .andExpect(status().isOk());

        CalendarEvent saved = repository.findById(own.getId()).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("Zahnarzt Kontrolle");
        assertThat(saved.getLocation()).isEqualTo("Praxis");
        assertThat(saved.getCategoryId()).contains(sport);
    }

    @Test
    void deleteActionRemovesOwnEventOnly() throws Exception {
        CalendarEvent own = event("Zahnarzt", 15, null);

        mockMvc.perform(post("/calendar/actions/events/{id}/delete", own.getId()).with(csrf()).with(as("anderer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/calendar/actions/events/{id}/delete", own.getId()).with(csrf()).with(as("editor")))
                .andExpect(status().isOk());

        assertThat(repository.findById(own.getId())).isEmpty();
    }

    @Test
    void categoryPageNeedsItsPermission() throws Exception {
        mockMvc.perform(get("/calendar/categories").with(as("editor"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/calendar/categories").with(user("chef")
                        .authorities(new SimpleGrantedAuthority("CALENDAR_CATEGORY_MANAGE"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Geburtstag")));
        mockMvc.perform(post("/calendar/categories").with(csrf()).with(user("chef")
                        .authorities(new SimpleGrantedAuthority("CALENDAR_CATEGORY_MANAGE")))
                        .param("name", "Musikschule").param("color", "VIOLET"))
                .andExpect(status().is3xxRedirection());

        assertThat(categories.findByName("Musikschule", editor)).isPresent();
    }

    @Test
    void slotLengthIsAChoiceOfSeveralLengthsNotJustFineAndCoarse() throws Exception {
        mockMvc.perform(get("/calendar/week").param("date", TUESDAY.toString()).with(as("editor")))
                .andExpect(content().string(containsString("data-slot-select")))
                .andExpect(content().string(containsString("<option value=\"10\">10 Min</option>")))
                .andExpect(content().string(containsString("<option value=\"30\">30 Min</option>")))
                .andExpect(content().string(containsString("<option value=\"60\">60 Min</option>")))
                .andExpect(content().string(not(containsString("data-slot-choice"))))
                .andExpect(content().string(containsString("data-slot-coarse=\"15\"")));
    }

    @Test
    void calendarUsesTheFullWidthExceptForListViews() throws Exception {
        String css = mockMvc.perform(get("/css/nestwerk.css").with(as("editor"))).andReturn().getResponse()
                .getContentAsString();

        assertThat(css).contains(".cal-main { max-width: none; }")
                .contains(".cal-main-grid { max-width: none; }")
                .contains(".cal-main-lanes { max-width: none; }")
                .doesNotContain(".cal-main { max-width: 78rem; }");
        mockMvc.perform(get("/calendar/agenda").with(as("editor")))
                .andExpect(content().string(containsString("cal-main cal-main-list")));
    }
}
