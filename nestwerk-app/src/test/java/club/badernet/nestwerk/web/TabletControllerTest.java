package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreCreditRepository;
import club.badernet.nestwerk.household.ChoreEffort;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskRepository;
import club.badernet.nestwerk.household.DeviceService;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.household.HouseholdDeviceRepository;
import club.badernet.nestwerk.household.MemberPins;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.RoleRepository;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class TabletControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired DeviceService devices;
    @Autowired HouseholdDeviceRepository deviceRepository;
    @Autowired MemberPins pins;
    @Autowired ChoreService chores;
    @Autowired ChoreTaskRepository tasks;
    @Autowired ChoreCreditRepository credits;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired FamilyMemberRepository members;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    User admin;
    User lenaUser;
    FamilyMember lena;
    FamilyMember baby;
    ChoreTask task;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        lenaUser = userService.create("lena", "passwort1", Set.of(benutzer));
        lena = members.save(new FamilyMember("Lena", "#00aa00", lenaUser.getId()));
        baby = members.save(new FamilyMember("Baby", "#aaaa00", null));
        LocalDate today = LocalDate.now(clock);
        Chore c = chores.create(new ChoreInput("Mülltonnen rausstellen", null, null, 5, 5, 5,
                ChoreKind.RECURRING, "FREQ=DAILY;COUNT=1", today, null, null, Distribution.FIXED, List.of(lena.getId())), admin);
        entityManager.flush();
        task = tasks.findAllByChoreId(c.getId()).getFirst();
        pins.setPin(lena.getId(), "4711", lenaUser);
    }

    private Cookie pairedCookie() throws Exception {
        DeviceService.Pairing pairing = devices.create("Küche", admin);
        MvcResult result = mockMvc.perform(post("/tablet/pair").with(csrf()).param("code", pairing.code().toLowerCase()))
                .andExpect(redirectedUrl("/tablet"))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andReturn();
        return result.getResponse().getCookie(TabletController.COOKIE);
    }

    @Test
    void withoutCookieTheTabletAsksForPairing() throws Exception {
        mockMvc.perform(get("/tablet")).andExpect(redirectedUrl("/tablet/pair"));
        mockMvc.perform(get("/tablet").cookie(new Cookie(TabletController.COOKIE, "geraten")))
                .andExpect(redirectedUrl("/tablet/pair"));
        mockMvc.perform(get("/tablet/pair")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Tablet koppeln")));
    }

    @Test
    void wrongOrUsedCodeDoesNotPair() throws Exception {
        DeviceService.Pairing pairing = devices.create("Küche", admin);
        mockMvc.perform(post("/tablet/pair").with(csrf()).param("code", "AAAAAAAA"))
                .andExpect(content().string(containsString("stimmt nicht")));
        mockMvc.perform(post("/tablet/pair").with(csrf()).param("code", pairing.code())).andExpect(redirectedUrl("/tablet"));
        mockMvc.perform(post("/tablet/pair").with(csrf()).param("code", pairing.code()))
                .andExpect(content().string(containsString("stimmt nicht")));
    }

    @Test
    void pairedTabletShowsTodaysTasksWithoutOtherModules() throws Exception {
        Cookie cookie = pairedCookie();

        mockMvc.perform(get("/tablet").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mülltonnen rausstellen")))
                .andExpect(content().string(containsString("Lena")))
                .andExpect(content().string(not(containsString("topnav"))));
        mockMvc.perform(get("/household").cookie(cookie)).andExpect(status().is3xxRedirection());
    }

    @Test
    void completingNeedsTheRightPin() throws Exception {
        Cookie cookie = pairedCookie();

        mockMvc.perform(post("/tablet/tasks/{id}/complete", task.getId()).with(csrf()).cookie(cookie)
                        .param("member", lena.getId().toString()).param("pin", "0000"))
                .andExpect(flash().attribute("error", containsString("PIN stimmt nicht")));
        mockMvc.perform(post("/tablet/tasks/{id}/complete", task.getId()).with(csrf()).cookie(cookie)
                        .param("member", baby.getId().toString()).param("pin", "4711"))
                .andExpect(flash().attribute("error", containsString("keine PIN")));
        mockMvc.perform(post("/tablet/tasks/{id}/complete", task.getId()).with(csrf()).cookie(cookie)
                        .param("member", lena.getId().toString()).param("pin", "4711"))
                .andExpect(flash().attribute("success", containsString("erledigt")));

        entityManager.flush();
        assertThat(credits.findAllByTaskId(task.getId())).extracting(c -> c.getMemberId()).containsExactly(lena.getId());
    }

    @Test
    void repeatedWrongPinsLockTheMember() throws Exception {
        Cookie cookie = pairedCookie();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/tablet/tasks/{id}/complete", task.getId()).with(csrf()).cookie(cookie)
                    .param("member", lena.getId().toString()).param("pin", "1234"));
        }

        mockMvc.perform(post("/tablet/tasks/{id}/complete", task.getId()).with(csrf()).cookie(cookie)
                        .param("member", lena.getId().toString()).param("pin", "4711"))
                .andExpect(flash().attribute("error", containsString("Zu viele")));
    }

    @Test
    void revokedTabletLosesAccessImmediately() throws Exception {
        Cookie cookie = pairedCookie();
        Long id = deviceRepository.findAll().getFirst().getId();

        devices.revoke(id, admin);

        mockMvc.perform(get("/tablet").cookie(cookie)).andExpect(redirectedUrl("/tablet/pair"));
        mockMvc.perform(post("/tablet/tasks/{id}/complete", task.getId()).with(csrf()).cookie(cookie)
                        .param("member", lena.getId().toString()).param("pin", "4711"))
                .andExpect(redirectedUrl("/tablet/pair"));
    }

    @Test
    void deviceAdministrationNeedsPermission() throws Exception {
        mockMvc.perform(get("/admin/devices").with(user("lena"))).andExpect(status().isForbidden());
        assertThatThrownBy(() -> devices.create("Flur", lenaUser)).isInstanceOf(AccessDeniedException.class);

        mockMvc.perform(post("/admin/devices").with(csrf()).with(user("chef").authorities(
                        new SimpleGrantedAuthority("HOUSEHOLD_DEVICE_MANAGE"))).param("name", "Flur"))
                .andExpect(flash().attributeExists("pairingCode"));
    }

    @Test
    void pinRulesAndOwnership() throws Exception {
        assertThatThrownBy(() -> pins.setPin(lena.getId(), "12", lenaUser)).hasMessageContaining("4 bis 8");
        assertThatThrownBy(() -> pins.setPin(baby.getId(), "1234", lenaUser)).isInstanceOf(AccessDeniedException.class);
        pins.setPin(baby.getId(), "1234", admin);

        mockMvc.perform(post("/settings/pin").with(csrf()).with(user("lena")).param("pin", ""))
                .andExpect(flash().attribute("success", containsString("entfernt")));
        entityManager.flush();
        entityManager.clear();
        assertThat(members.findById(lena.getId()).orElseThrow().hasPin()).isFalse();
        assertThat(members.findById(baby.getId()).orElseThrow().hasPin()).isTrue();
    }
}
