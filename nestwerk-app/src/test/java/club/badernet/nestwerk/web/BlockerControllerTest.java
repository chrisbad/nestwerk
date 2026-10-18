package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.Blocker;
import club.badernet.nestwerk.blocker.BlockerConflictsTest;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerService;
import club.badernet.nestwerk.blocker.BlockerSettings;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.RestGoal;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, BlockerConflictsTest.FixedClock.class})
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class BlockerControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberService memberService;
    @Autowired BlockerService blockers;
    @Autowired BlockerSettings settings;

    FamilyMember lena;
    User lenaUser;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Normal", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN"));
        lenaUser = userService.create("lena", "passwort1", Set.of(normal.getId()));
        lena = memberService.create("Lena", "#8b6cff", lenaUser.getId());
        userService.createAdministrator("chef", "passwort1");
    }

    private static RequestPostProcessor asLena() {
        return user("lena").authorities(new SimpleGrantedAuthority("CALENDAR_VIEW"));
    }

    @Test
    void createsShowsEditsAndDeletesABlocker() throws Exception {
        mockMvc.perform(post("/times").with(csrf()).with(asLena())
                        .param("title", "Büro").param("kind", "WORK")
                        .param("startTime", "08:00").param("endTime", "16:30")
                        .param("weekdays", "MONDAY", "TUESDAY", "WEDNESDAY")
                        .param("validFrom", "2026-10-01")
                        .param("strength", "WARN").param("visibility", "BUSY")
                        .param("holidaysOff", "true")
                        .param("memberIds", lena.getId().toString())
                        .param("skips", "23.10.2026, 2026-10-30"))
                .andExpect(redirectedUrl("/times"))
                .andExpect(flash().attributeExists("success"));

        Blocker saved = blockers.forMember(lena.getId()).getFirst();
        assertThat(saved.getEndTime()).isEqualTo(LocalTime.of(16, 30));
        assertThat(saved.getWeekdays()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY);
        assertThat(saved.getSkips()).containsExactlyInAnyOrder(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 30));
        assertThat(saved.isHolidaysOff()).isTrue();

        mockMvc.perform(get("/times").with(asLena()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Büro")))
                .andExpect(content().string(containsString("08:00–16:30")))
                .andExpect(content().string(containsString("Mo, Di, Mi")));
        mockMvc.perform(get("/times/{id}", saved.getId()).with(asLena()))
                .andExpect(content().string(containsString("23.10.2026, 30.10.2026")));

        mockMvc.perform(post("/times/{id}", saved.getId()).with(csrf()).with(asLena())
                        .param("title", "Büro").param("kind", "WORK")
                        .param("startTime", "09:00").param("endTime", "17:00")
                        .param("weekdays", "FRIDAY").param("validFrom", "2026-10-01")
                        .param("strength", "BLOCK").param("visibility", "TITLE")
                        .param("memberIds", lena.getId().toString()))
                .andExpect(redirectedUrl("/times"));
        assertThat(blockers.get(saved.getId()).getStrength()).isEqualTo(BlockerStrength.BLOCK);

        mockMvc.perform(post("/times/{id}/delete", saved.getId()).with(csrf()).with(asLena()))
                .andExpect(redirectedUrl("/times"));
        assertThat(blockers.forMember(lena.getId())).isEmpty();
    }

    @Test
    void invalidInputKeepsTheForm() throws Exception {
        mockMvc.perform(post("/times").with(csrf()).with(asLena())
                        .param("title", "Kaputt").param("kind", "SLEEP")
                        .param("startTime", "22:00").param("endTime", "06:30")
                        .param("validFrom", "2026-10-01")
                        .param("strength", "BLOCK").param("visibility", "BUSY")
                        .param("memberIds", lena.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Wochentag")))
                .andExpect(content().string(containsString("Kaputt")));
    }

    @Test
    void goalsAndStateCanBeSaved() throws Exception {
        mockMvc.perform(post("/times/goals").with(csrf()).with(asLena())
                        .param("memberId", lena.getId().toString()).param("kind", "WINDOWS")
                        .param("windowStart", "18:00").param("windowEnd", "23:59").param("target", "3")
                        .param("label", "Freie Abende"))
                .andExpect(redirectedUrl("/times"));
        assertThat(blockers.goals(lena.getId())).extracting(RestGoal::getTarget).containsExactly(3);
        mockMvc.perform(get("/times").with(asLena()))
                .andExpect(content().string(containsString("Freie Abende")))
                .andExpect(content().string(containsString("7 von 3")));

        mockMvc.perform(post("/times/state").with(csrf()).with(user("chef").authorities(
                        new SimpleGrantedAuthority("CALENDAR_VIEW"))).param("state", "SN"))
                .andExpect(redirectedUrl("/times"));
        assertThat(settings.state()).isEqualTo("SN");
        mockMvc.perform(post("/times/state").with(csrf()).with(asLena()).param("state", "BY"))
                .andExpect(status().isForbidden());
    }

    @Test
    void newFormPrefillsSensibleDefaultsPerKind() throws Exception {
        mockMvc.perform(get("/times/new").param("kind", "SLEEP").with(asLena()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"22:00\"")))
                .andExpect(content().string(containsString("value=\"06:30\"")));
        assertThat(BlockerKind.values()).hasSize(6);
    }
}
