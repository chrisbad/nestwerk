package club.badernet.nestwerk.household;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.notification.NotificationRepository;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Auswertung, Korrektur und Belohnungen. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class HouseholdAccountTest {

    @Autowired ChoreService chores;
    @Autowired ChoreTaskService taskService;
    @Autowired ChoreTaskRepository tasks;
    @Autowired ChoreCreditRepository credits;
    @Autowired ChoreStats stats;
    @Autowired RewardService rewards;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;
    @Autowired NotificationRepository notifications;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    User admin;
    User mama;
    User kid;
    FamilyMember mamaM;
    FamilyMember kidM;
    FamilyMember tim;
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
        mamaM = members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        kidM = members.save(new FamilyMember("Lena", "#00aa00", kid.getId()));
        tim = members.save(new FamilyMember("Tim", "#0000aa", null));
        today = LocalDate.now(clock);
    }

    private ChoreTask done(String title, int minutes, ChoreEffort effort, FamilyMember doer, User actor, Long... helpers) {
        Chore c = chores.create(new ChoreInput(title, null, null, minutes, effort, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=1", today, null, null, Distribution.FIXED, List.of(doer.getId())), admin);
        entityManager.flush();
        ChoreTask t = tasks.findAllByChoreId(c.getId()).getFirst();
        taskService.complete(t.getId(), actor, doer.getId(), Set.of(helpers));
        entityManager.flush();
        return t;
    }

    @Test
    void statsSumMinutesAndCountEffortsSeparately() {
        done("Bad", 30, ChoreEffort.HARD, kidM, kid);
        done("Blumen", 5, ChoreEffort.EASY, kidM, kid, mamaM.getId());
        Chore open = chores.create(new ChoreInput("Fenster", null, null, 20, ChoreEffort.MEDIUM, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=1", today, null, null, Distribution.FIXED, List.of(kidM.getId())), admin);
        entityManager.flush();
        jdbc.update("UPDATE chore_tasks SET due_on = ? WHERE chore_id = ?", today.minusDays(2), open.getId());

        List<ChoreStats.MemberStats> result = stats.forPeriod(mama, today.minusDays(6), today);

        ChoreStats.MemberStats lena = result.stream().filter(s -> s.member().getId().equals(kidM.getId())).findFirst().orElseThrow();
        assertThat(lena.minutes()).isEqualTo(35);
        assertThat(lena.easy()).isEqualTo(1);
        assertThat(lena.medium()).isZero();
        assertThat(lena.hard()).isEqualTo(1);
        assertThat(lena.overdue()).isEqualTo(1);
        ChoreStats.MemberStats m = result.stream().filter(s -> s.member().getId().equals(mamaM.getId())).findFirst().orElseThrow();
        assertThat(m.minutes()).isEqualTo(5);
    }

    @Test
    void childSeesOnlyOwnStats() {
        done("Bad", 30, ChoreEffort.HARD, kidM, kid, mamaM.getId());

        assertThat(stats.forPeriod(kid, today, today)).extracting(s -> s.member().getId()).containsExactly(kidM.getId());
        assertThat(stats.credits(kid, today, today)).extracting(ChoreCredit::getMemberId).containsExactly(kidM.getId());
        assertThat(stats.forPeriod(mama, today, today)).hasSize(3);
    }

    @Test
    void correctionChangesEffectiveMinutesAndNeedsRightAndReason() {
        ChoreTask t = done("Bad", 30, ChoreEffort.HARD, kidM, kid);
        ChoreCredit credit = credits.findAllByTaskId(t.getId()).getFirst();

        assertThatThrownBy(() -> stats.correct(credit.getId(), 10, "schlampig", kid))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> stats.correct(credit.getId(), 10, " ", mama))
                .hasMessageContaining("Grund");
        assertThatThrownBy(() -> stats.correct(credit.getId(), 601, "zu viel", mama))
                .hasMessageContaining("Minuten");
        stats.correct(credit.getId(), 10, "nur halb gemacht", mama);
        entityManager.flush();

        assertThat(stats.forPeriod(mama, today, today)).filteredOn(s -> s.member().getId().equals(kidM.getId()))
                .singleElement().satisfies(s -> assertThat(s.minutes()).isEqualTo(10));
        assertThat(rewards.balance(kidM.getId())).isEqualTo(10);
    }

    @Test
    void balanceCountsPendingAndApprovedButNotRejected() {
        done("Bad", 30, ChoreEffort.HARD, kidM, kid);
        done("Küche", 30, ChoreEffort.MEDIUM, kidM, kid);
        Reward kino = rewards.create("Kinoabend", null, 40, mama);
        Reward eis = rewards.create("Eis", "eine Kugel", 15, mama);

        Redemption r1 = rewards.request(kino.getId(), null, kid);
        assertThat(rewards.balance(kidM.getId())).isEqualTo(20);
        assertThatThrownBy(() -> rewards.request(kino.getId(), null, kid)).hasMessageContaining("genug");

        rewards.decide(r1.getId(), false, mama);
        assertThat(rewards.balance(kidM.getId())).isEqualTo(60);
        Redemption r2 = rewards.request(eis.getId(), null, kid);
        rewards.decide(r2.getId(), true, mama);
        assertThat(rewards.balance(kidM.getId())).isEqualTo(45);
        assertThatThrownBy(() -> rewards.decide(r2.getId(), false, mama)).isInstanceOf(ChoreConflictException.class);
    }

    @Test
    void rewardRightsAreChecked() {
        assertThatThrownBy(() -> rewards.create("Kino", null, 40, kid)).isInstanceOf(AccessDeniedException.class);
        Reward kino = rewards.create("Kino", null, 1, mama);
        done("Bad", 30, ChoreEffort.HARD, tim, mama);

        assertThatThrownBy(() -> rewards.request(kino.getId(), tim.getId(), kid)).isInstanceOf(AccessDeniedException.class);
        Redemption r = rewards.request(kino.getId(), tim.getId(), mama);
        assertThatThrownBy(() -> rewards.decide(r.getId(), true, kid)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> rewards.create(" ", null, 40, mama)).hasMessageContaining("Titel");
        assertThatThrownBy(() -> rewards.create("Eis", null, 0, mama)).hasMessageContaining("Minuten");
    }

    @Test
    void requestNotifiesApproversAndDecisionNotifiesRequester() {
        done("Bad", 30, ChoreEffort.HARD, kidM, kid);
        Reward eis = rewards.create("Eis", null, 15, mama);

        Redemption r = rewards.request(eis.getId(), null, kid);
        entityManager.flush();
        assertThat(notifications.findAll()).filteredOn(n -> n.getType() == NotificationType.REWARD_REQUESTED)
                .extracting(n -> n.getUserId()).contains(mama.getId()).doesNotContain(kid.getId());

        rewards.decide(r.getId(), true, mama);
        entityManager.flush();
        assertThat(notifications.findAll()).filteredOn(n -> n.getType() == NotificationType.REWARD_DECIDED)
                .extracting(n -> n.getUserId()).containsExactly(kid.getId());
    }

    @Test
    void inactiveRewardCannotBeRequested() {
        done("Bad", 30, ChoreEffort.HARD, kidM, kid);
        Reward eis = rewards.create("Eis", null, 15, mama);
        rewards.update(eis.getId(), "Eis", null, 15, false, mama);

        assertThatThrownBy(() -> rewards.request(eis.getId(), null, kid)).hasMessageContaining("nicht mehr");
    }
}
