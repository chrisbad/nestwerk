package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.Blocker;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerRepository;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BlockerVisibility;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
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

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/** Chores without a planned time or person show up in every calendar view, and the layers can be switched. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class CalendarChoreLayersTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired FamilyMemberRepository members;
    @Autowired BlockerRepository blockers;
    @Autowired ChoreService chores;
    @Autowired Clock clock;

    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        User admin = userService.createAdministrator("chef", "passwort1");
        FamilyMember tim = members.save(new FamilyMember("Tim", "#0000aa", null));
        tomorrow = LocalDate.now(clock).plusDays(1);
        Blocker busy = new Blocker("Arbeit", BlockerKind.WORK, LocalTime.of(5, 0), LocalTime.of(23, 0),
                EnumSet.allOf(DayOfWeek.class), LocalDate.now(clock), null, BlockerStrength.BLOCK,
                BlockerVisibility.TITLE, false, false, null);
        busy.setMemberIds(Set.of(tim.getId()));
        blockers.saveAndFlush(busy);
        // Tim has no free time, so the planner finds no time for this chore
        chores.create(new ChoreInput("Fenster putzen", null, null, 10, 25, 45, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=1", tomorrow, null, null, Distribution.FIXED, List.of(tim.getId())), admin);
    }

    private static RequestPostProcessor chef() {
        return user("chef").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"));
    }

    @Test
    void choreWithoutTimeAppearsInGridMonthAgendaAndLanes() throws Exception {
        for (String view : new String[]{"week", "days3", "day", "month"}) {
            mockMvc.perform(get("/calendar/" + view).param("date", tomorrow.toString()).with(chef()))
                    .andExpect(content().string(containsString("Fenster putzen")))
                    .andExpect(content().string(containsString("occ-chore")));
        }
        mockMvc.perform(get("/calendar/agenda").param("from", tomorrow.toString()).with(chef()))
                .andExpect(content().string(containsString("Fenster putzen")));
    }

    @Test
    void layerSwitchesAreOfferedWhereTheDataMayBeSeen() throws Exception {
        mockMvc.perform(get("/calendar/week").param("date", tomorrow.toString()).with(chef()))
                .andExpect(content().string(containsString("data-layer=\"events\"")))
                .andExpect(content().string(containsString("data-layer=\"chores\"")))
                .andExpect(content().string(containsString("data-layer=\"todos\"")));
        mockMvc.perform(get("/calendar/my").with(chef()))
                .andExpect(content().string(containsString("data-layer=\"chores\"")));
    }

    @Test
    void withoutHouseholdAccessThereIsNoChoreLayer() throws Exception {
        userService.create("nur-kalender", "passwort1", Set.of());

        mockMvc.perform(get("/calendar/week").param("date", tomorrow.toString())
                        .with(user("nur-kalender").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(content().string(not(containsString("data-layer=\"chores\""))))
                .andExpect(content().string(not(containsString("Fenster putzen"))));
    }
}
