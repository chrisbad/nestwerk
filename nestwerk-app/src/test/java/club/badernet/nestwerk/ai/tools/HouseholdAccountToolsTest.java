package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskRepository;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class HouseholdAccountToolsTest {

    @Autowired HouseholdAccountTools accountTools;
    @Autowired HouseholdTools householdTools;
    @Autowired ChoreService chores;
    @Autowired ChoreTaskRepository tasks;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    User admin;
    User kid;
    FamilyMember kidM;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Role kinder = roleService.create("Kinder", null, Set.of());
        acl.applyPreset(admin, kinder.getId(), "KIND");
        kid = userService.create("kind", "passwort1", Set.of(kinder.getId()));
        kidM = members.save(new FamilyMember("Kind", "#0000aa", kid.getId()));
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    @Test
    void statsCorrectionAndRewardsFlow() {
        LocalDate today = LocalDate.now(clock);
        Chore chore = chores.create(new ChoreInput("Küche aufräumen", null, null, 30, 30, 30, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=1", today, null, null, Distribution.FIXED, List.of(kidM.getId())), admin);
        entityManager.flush();
        ChoreTask task = tasks.findAllByChoreId(chore.getId()).stream()
                .min(Comparator.comparing(ChoreTask::getDueOn)).orElseThrow();
        householdTools.completeChoreTask(as(kid), task.getId(), null, null, null, null);
        entityManager.flush();

        HouseholdAccountTools.Stats stats = accountTools.choreStats(as(admin), "month", null);
        assertThat(stats.members()).filteredOn(m -> m.memberId().equals(kidM.getId()))
                .extracting(HouseholdAccountTools.MemberStat::minutes).containsExactly(30);

        List<HouseholdAccountTools.CreditItem> credits = accountTools.listChoreCredits(as(admin), "day", null);
        assertThat(credits).hasSize(1);
        accountTools.correctChoreCredit(as(admin), credits.get(0).creditId(), 20, "Nur halb fertig");
        assertThat(accountTools.listChoreCredits(as(admin), "day", null).get(0))
                .satisfies(c -> assertThat(c.minutes()).isEqualTo(20))
                .satisfies(c -> assertThat(c.corrected()).isTrue());
        assertThatThrownBy(() -> accountTools.correctChoreCredit(as(admin), credits.get(0).creditId(), 20, ""))
                .isInstanceOf(Tools.ToolException.class);

        HouseholdAccountTools.RewardItem reward = accountTools.createReward(as(admin), "Eis essen", 15, null);
        assertThat(accountTools.updateReward(as(admin), reward.rewardId(), null, 10, null, null).costMinutes())
                .isEqualTo(10);

        HouseholdAccountTools.RedemptionItem request = accountTools.redeemReward(as(kid), reward.rewardId(), null);
        assertThat(request.status()).isEqualTo("REQUESTED");
        assertThat(accountTools.listRewards(as(admin)).pending()).hasSize(1);
        accountTools.decideRedemption(as(admin), request.redemptionId(), true);
        assertThat(accountTools.listRewards(as(admin)).pending()).isEmpty();
        assertThatThrownBy(() -> accountTools.decideRedemption(as(admin), request.redemptionId(), true))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void removesFixedChoreDates() {
        LocalDate day = LocalDate.now(clock).plusDays(3);
        HouseholdTools.ChoreItem chore = householdTools.createChore(as(admin), "Biotonne", null, null, "DATES", null,
                null, null, null, "FIXED", List.of(kidM.getId()), null, null, null, null, null);
        householdTools.addChoreDate(as(admin), chore.choreId(), day.toString(), null);

        accountTools.removeChoreDate(as(admin), chore.choreId(), day.toString(), null);

        assertThat(householdTools.listChores(as(admin))).filteredOn(c -> c.choreId().equals(chore.choreId()))
                .flatExtracting(HouseholdTools.ChoreItem::dates).isEmpty();
        assertThatThrownBy(() -> accountTools.removeChoreDate(as(admin), chore.choreId(), day.toString(), null))
                .isInstanceOf(Tools.ToolException.class);
    }
}
