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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class ChoreTimesViewTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired FamilyMemberRepository members;
    @Autowired BlockerRepository blockers;
    @Autowired ChoreService chores;
    @Autowired Clock clock;

    User admin;
    FamilyMember lena;
    FamilyMember tim;
    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        lena = members.save(new FamilyMember("Lena", "#00aa00", null));
        tim = members.save(new FamilyMember("Tim", "#0000aa", null));
        tomorrow = LocalDate.now(clock).plusDays(1);
        Blocker busy = new Blocker("Arbeit", BlockerKind.WORK, LocalTime.of(5, 0), LocalTime.of(23, 0),
                EnumSet.allOf(DayOfWeek.class), LocalDate.now(clock), null, BlockerStrength.BLOCK,
                BlockerVisibility.TITLE, false, false, null);
        busy.setMemberIds(Set.of(tim.getId()));
        blockers.saveAndFlush(busy);
        chores.create(new ChoreInput("Bad putzen", null, null, 10, 25, 45, ChoreKind.RECURRING, "FREQ=DAILY;COUNT=1",
                tomorrow, null, null, Distribution.FIXED, List.of(lena.getId()), LocalTime.of(18, 30), null), admin);
        chores.create(new ChoreInput("Fenster", null, null, 10, 25, 45, ChoreKind.RECURRING, "FREQ=DAILY;COUNT=1",
                tomorrow, null, null, Distribution.FIXED, List.of(tim.getId())), admin);
    }

    @Test
    void weekPlanShowsPlannedTimeAndWhenNobodyIsFree() throws Exception {
        mockMvc.perform(get("/household").param("week", tomorrow.toString()).with(user("chef").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(content().string(containsString("18:30")))
                .andExpect(content().string(containsString("niemand frei")));
    }

    @Test
    void calendarShowsPlannedChoresOfPeople() throws Exception {
        mockMvc.perform(get("/calendar/week").param("date", tomorrow.toString()).with(user("chef").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(content().string(containsString("occ-chore")))
                .andExpect(content().string(containsString("Bad putzen")))
                .andExpect(content().string(containsString("18:30")));
    }
}
