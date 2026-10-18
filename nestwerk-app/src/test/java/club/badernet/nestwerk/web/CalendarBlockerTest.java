package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.blocker.BlockerInput;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerService;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BlockerVisibility;
import club.badernet.nestwerk.calendar.BlockedException;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventRepository;
import club.badernet.nestwerk.calendar.CalendarEventService;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class CalendarBlockerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberService memberService;
    @Autowired BlockerService blockers;
    @Autowired CalendarEventService events;
    @Autowired CalendarEventRepository repository;

    User editor;
    User admin;
    FamilyMember lena;
    FamilyMember kid;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Editor", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN"));
        editor = userService.create("editor", "passwort1", Set.of(normal.getId()));
        admin = userService.createAdministrator("chef", "passwort1");
        lena = memberService.create("Lena", "#8b6cff", null);
        kid = memberService.create("Lio", "#2ee6a8", null);
        blockers.create(new BlockerInput("Büro", BlockerKind.WORK, LocalTime.of(8, 0), LocalTime.of(16, 0),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalDate.of(2026, 10, 1), null,
                BlockerStrength.WARN, BlockerVisibility.TITLE, false, false, Set.of(lena.getId()), Set.of(),
                Set.of()), admin);
        blockers.create(new BlockerInput("Schule", BlockerKind.CUSTOM, LocalTime.of(8, 0), LocalTime.of(13, 0),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalDate.of(2026, 10, 1), null,
                BlockerStrength.BLOCK, BlockerVisibility.TITLE, false, false, Set.of(kid.getId()), Set.of(),
                Set.of()), admin);
    }

    private static RequestPostProcessor asEditor() {
        return user("editor").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"));
    }

    private static EventInput at(LocalDate day, int hour, Long member) {
        return new EventInput("Arzt", null, null, day, LocalTime.of(hour, 0), day, LocalTime.of(hour + 1, 0), false,
                null, false, Set.of(member));
    }

    @Test
    void serviceRefusesBlockedPeopleWhateverTheWay() {
        assertThatThrownBy(() -> events.create(at(LocalDate.of(2026, 10, 13), 10, kid.getId()), editor))
                .isInstanceOf(BlockedException.class).hasMessageContaining("Lio");
        CalendarEvent afternoon = events.create(at(LocalDate.of(2026, 10, 13), 15, kid.getId()), editor);
        assertThatThrownBy(() -> events.update(afternoon.getId(), at(LocalDate.of(2026, 10, 13), 9, kid.getId()),
                editor)).isInstanceOf(BlockedException.class);
        // als Serie: der Dienstag 9 Uhr kollidiert in den nächsten Wochen
        EventInput weekly = new EventInput("Kurs", null, null, LocalDate.of(2026, 10, 17), LocalTime.of(9, 0),
                LocalDate.of(2026, 10, 17), LocalTime.of(10, 0), false, "FREQ=DAILY", false, Set.of(kid.getId()));
        assertThatThrownBy(() -> events.create(weekly, editor)).isInstanceOf(BlockedException.class);
    }

    @Test
    void formWarnsOnceThenSavesWhenConfirmed() throws Exception {
        mockMvc.perform(post("/calendar/events").with(csrf()).with(asEditor())
                        .param("title", "Elternabend")
                        .param("startDate", "2026-10-13").param("startTime", "10:00")
                        .param("endDate", "2026-10-13").param("endTime", "11:00")
                        .param("memberIds", lena.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lena arbeitet da (Büro, 08:00–16:00).")))
                .andExpect(content().string(containsString("name=\"confirmBlockers\"")))
                .andExpect(content().string(containsString("07:00")));
        assertThat(repository.findAll()).extracting(CalendarEvent::getTitle).doesNotContain("Elternabend");

        mockMvc.perform(post("/calendar/events").with(csrf()).with(asEditor())
                        .param("title", "Elternabend")
                        .param("startDate", "2026-10-13").param("startTime", "10:00")
                        .param("endDate", "2026-10-13").param("endTime", "11:00")
                        .param("memberIds", lena.getId().toString())
                        .param("confirmBlockers", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));
        assertThat(repository.findAll()).extracting(CalendarEvent::getTitle).contains("Elternabend");
    }

    @Test
    void formShowsBlockAsError() throws Exception {
        mockMvc.perform(post("/calendar/events").with(csrf()).with(asEditor())
                        .param("title", "Zahnarzt")
                        .param("startDate", "2026-10-13").param("startTime", "10:00")
                        .param("endDate", "2026-10-13").param("endTime", "11:00")
                        .param("memberIds", kid.getId().toString())
                        .param("confirmBlockers", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lio ist da nicht verfügbar")));
    }

    @Test
    void movingWarnsButSavesAndRefusesBlocks() throws Exception {
        CalendarEvent lenaEvent = events.create(at(LocalDate.of(2026, 10, 17), 10, lena.getId()), editor);
        CalendarEvent kidEvent = events.create(at(LocalDate.of(2026, 10, 17), 10, kid.getId()), editor);

        mockMvc.perform(post("/calendar/events/{id}/move", lenaEvent.getId()).with(csrf()).with(asEditor())
                        .param("date", "2026-10-13"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lena arbeitet da")));
        mockMvc.perform(post("/calendar/events/{id}/move", kidEvent.getId()).with(csrf()).with(asEditor())
                        .param("date", "2026-10-13"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Lio")));
    }
}
