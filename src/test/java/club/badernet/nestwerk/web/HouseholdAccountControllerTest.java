package club.badernet.nestwerk.web;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreCredit;
import club.badernet.nestwerk.household.ChoreCreditRepository;
import club.badernet.nestwerk.household.ChoreEffort;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskRepository;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.household.Redemption;
import club.badernet.nestwerk.household.RedemptionRepository;
import club.badernet.nestwerk.household.RedemptionStatus;
import club.badernet.nestwerk.household.Reward;
import club.badernet.nestwerk.household.RewardRepository;
import club.badernet.nestwerk.household.RewardService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@AutoConfigureMockMvc
@Transactional
class HouseholdAccountControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;
    @Autowired ChoreService chores;
    @Autowired ChoreTaskService taskService;
    @Autowired ChoreTaskRepository tasks;
    @Autowired ChoreCreditRepository credits;
    @Autowired RewardService rewardService;
    @Autowired RewardRepository rewardRepository;
    @Autowired RedemptionRepository redemptions;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    User admin;
    User mama;
    User kid;
    FamilyMember kidM;
    LocalDate today;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Role eltern = roleService.create("Eltern", null, Set.of());
        acl.applyPreset(admin, eltern.getId(), "ELTERNTEIL");
        Role kinder = roleService.create("Kinder", null, Set.of());
        acl.applyPreset(admin, kinder.getId(), "KIND");
        mama = userService.create("mama", "passwort1", Set.of(eltern.getId()));
        kid = userService.create("kind", "passwort1", Set.of(kinder.getId()));
        members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        kidM = members.save(new FamilyMember("Lena", "#00aa00", kid.getId()));
        today = LocalDate.now(clock);
    }

    private ChoreCredit kidDid(String title, int minutes) {
        Chore c = chores.create(new ChoreInput(title, null, null, minutes, ChoreEffort.MEDIUM, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=1", today, null, null, Distribution.FIXED, List.of(kidM.getId())), admin);
        entityManager.flush();
        ChoreTask t = tasks.findAllByChoreId(c.getId()).getFirst();
        taskService.complete(t.getId(), kid, null, Set.of());
        entityManager.flush();
        return credits.findAllByTaskId(t.getId()).getFirst();
    }

    @Test
    void statsPageShowsMinutesAndCorrectionOnlyForParents() throws Exception {
        kidDid("Staubsaugen", 25);

        mockMvc.perform(get("/household/stats").with(user("mama")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Staubsaugen")))
                .andExpect(content().string(containsString("25")))
                .andExpect(content().string(containsString("Korrigieren")));
        mockMvc.perform(get("/household/stats").param("period", "month").with(user("kind")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("deine eigenen Werte")))
                .andExpect(content().string(not(containsString("Korrigieren"))));
    }

    @Test
    void correctingACreditStoresMinutesAndReason() throws Exception {
        ChoreCredit credit = kidDid("Staubsaugen", 25);

        mockMvc.perform(post("/household/credits/{id}/correct", credit.getId()).with(csrf()).with(user("mama"))
                        .param("minutes", "10").param("reason", "nur das Wohnzimmer"))
                .andExpect(flash().attributeExists("success"));
        mockMvc.perform(post("/household/credits/{id}/correct", credit.getId()).with(csrf()).with(user("kind"))
                        .param("minutes", "100").param("reason", "mehr"))
                .andExpect(status().isForbidden());

        entityManager.flush();
        entityManager.clear();
        ChoreCredit stored = credits.findById(credit.getId()).orElseThrow();
        assertThat(stored.effectiveMinutes()).isEqualTo(10);
        assertThat(stored.getCorrectionReason()).isEqualTo("nur das Wohnzimmer");
    }

    @Test
    void childRedeemsAndParentApproves() throws Exception {
        kidDid("Staubsaugen", 50);
        Reward eis = rewardService.create("Eis", null, 30, mama);

        mockMvc.perform(get("/household/rewards").with(user("kind")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Eis")))
                .andExpect(content().string(containsString("50")))
                .andExpect(content().string(not(containsString("Neue Belohnung"))));

        mockMvc.perform(post("/household/rewards/{id}/redeem", eis.getId()).with(csrf()).with(user("kind")))
                .andExpect(flash().attributeExists("success"));
        Redemption r = redemptions.findAll().getFirst();

        mockMvc.perform(post("/household/redemptions/{id}/decide", r.getId()).with(csrf()).with(user("kind"))
                        .param("approve", "true"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/household/rewards").with(user("mama")))
                .andExpect(content().string(containsString("Warten auf Bestätigung")))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*Warten auf Bestätigung.*class=\"member-chip\"[^>]*>Lena<.*Eis.*")));
        mockMvc.perform(post("/household/redemptions/{id}/decide", r.getId()).with(csrf()).with(user("mama"))
                        .param("approve", "true"))
                .andExpect(flash().attributeExists("success"));

        entityManager.flush();
        entityManager.clear();
        assertThat(redemptions.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(RedemptionStatus.APPROVED);
    }

    @Test
    void notEnoughMinutesShowsMessage() throws Exception {
        Reward kino = rewardService.create("Kino", null, 300, mama);

        mockMvc.perform(post("/household/rewards/{id}/redeem", kino.getId()).with(csrf()).with(user("kind")))
                .andExpect(flash().attribute("error", containsString("Nicht genug Minuten")));
    }

    @Test
    void parentsManageRewards() throws Exception {
        mockMvc.perform(post("/household/rewards").with(csrf()).with(user("mama"))
                        .param("title", "Ausflug").param("cost", "500"))
                .andExpect(flash().attributeExists("success"));
        mockMvc.perform(post("/household/rewards").with(csrf()).with(user("kind"))
                        .param("title", "Alles").param("cost", "1"))
                .andExpect(status().isForbidden());

        assertThat(rewardRepository.findAll()).extracting(Reward::getTitle).containsExactly("Ausflug");
    }
}
