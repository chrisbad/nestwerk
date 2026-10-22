package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.blocker.BlockerInput;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerService;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BlockerVisibility;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class CalendarBlockerBandsTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberService memberService;
    @Autowired BlockerService blockers;

    FamilyMember lena;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Leser", null, Set.of("CALENDAR_VIEW"));
        User lenaUser = userService.create("lena", "passwort1", Set.of(normal.getId()));
        userService.create("chris", "passwort1", Set.of(normal.getId()));
        User admin = userService.createAdministrator("chef", "passwort1");
        lena = memberService.create("Lena", "#8b6cff", lenaUser.getId());
        create("Büro", BlockerVisibility.BUSY, admin);
        create("Therapie", BlockerVisibility.HIDDEN, admin);
    }

    private void create(String title, BlockerVisibility visibility, User admin) {
        blockers.create(new BlockerInput(title, BlockerKind.WORK, LocalTime.of(8, 0), LocalTime.of(16, 0),
                EnumSet.of(DayOfWeek.TUESDAY), LocalDate.of(2026, 10, 1), null, BlockerStrength.WARN, visibility,
                false, false, Set.of(lena.getId()), Set.of(), Set.of()), admin);
    }

    @Test
    void affectedPersonSeesTitles() throws Exception {
        mockMvc.perform(get("/calendar/week").param("date", "2026-10-13")
                        .with(user("lena").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(content().string(containsString("tgrid-busy-label")))
                .andExpect(content().string(containsString("Büro")))
                .andExpect(content().string(containsString("Therapie")))
                .andExpect(content().string(containsString("08:00–16:00")));
        mockMvc.perform(get("/calendar/month").param("date", "2026-10-13")
                        .with(user("lena").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(content().string(containsString("class=\"band")))
                .andExpect(content().string(containsString("08:00–16:00")));
    }

    @Test
    void weekHeaderNoLongerCarriesTheBlockerBands() throws Exception {
        mockMvc.perform(get("/calendar/week").param("date", "2026-10-13")
                        .with(user("lena").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(content().string(not(containsString("class=\"bands"))));
    }

    @Test
    void othersSeeBusyOrNothing() throws Exception {
        mockMvc.perform(get("/calendar/week").param("date", "2026-10-13")
                        .with(user("chris").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(content().string(containsString("Lena · belegt")))
                .andExpect(content().string(not(containsString("Büro"))))
                .andExpect(content().string(not(containsString("Therapie"))));
        mockMvc.perform(get("/calendar/month").param("date", "2026-10-13")
                        .with(user("chris").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"))))
                .andExpect(content().string(containsString("Lena · belegt")))
                .andExpect(content().string(not(containsString("Therapie"))));
    }
}
