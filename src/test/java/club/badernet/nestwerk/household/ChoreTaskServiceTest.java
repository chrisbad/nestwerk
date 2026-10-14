package club.badernet.nestwerk.household;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class ChoreTaskServiceTest {

    @Autowired ChoreService chores;
    @Autowired ChoreTaskService service;
    @Autowired ChoreTaskRepository tasks;
    @Autowired ChoreCreditRepository credits;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired RoleRepository roles;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    User admin;
    User mama;
    User papa;
    User kid;
    User stranger;
    FamilyMember mamaM;
    FamilyMember papaM;
    FamilyMember kidM;
    FamilyMember baby;
    LocalDate today;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        Role eltern = roleService.create("Eltern", null, Set.of());
        acl.applyPreset(admin, eltern.getId(), "ELTERNTEIL");
        Role kinder = roleService.create("Kinder", null, Set.of());
        acl.applyPreset(admin, kinder.getId(), "KIND");
        Role leer = roleService.create("Leer", null, Set.of());
        mama = userService.create("mama", "passwort1", Set.of(eltern.getId()));
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
        kid = userService.create("kind", "passwort1", Set.of(kinder.getId()));
        stranger = userService.create("fremd", "passwort1", Set.of(leer.getId()));
        mamaM = members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        papaM = members.save(new FamilyMember("Papa", "#00aa00", papa.getId()));
        kidM = members.save(new FamilyMember("Kind", "#0000aa", kid.getId()));
        baby = members.save(new FamilyMember("Baby", "#aaaa00", null));
        today = LocalDate.now(clock);
    }

    private ChoreTask firstTask(ChoreKind kind, Integer interval, Distribution distribution, Long... ids) {
        Chore c = chores.create(new ChoreInput("Küche aufräumen", null, null, 20, ChoreEffort.MEDIUM, kind,
                kind == ChoreKind.RECURRING ? "FREQ=DAILY;COUNT=2" : null, today, null, interval, distribution,
                List.of(ids)), admin);
        entityManager.flush();
        return tasks.findAllByChoreId(c.getId()).stream().min(Comparator.comparing(ChoreTask::getDueOn)).orElseThrow();
    }

    private ChoreTask kidsTask() {
        return firstTask(ChoreKind.RECURRING, null, Distribution.FIXED, kidM.getId());
    }

    @Test
    void completingCreditsDoerAndEveryHelperWithFullMinutes() {
        ChoreTask t = kidsTask();

        service.complete(t.getId(), kid, null, Set.of(papaM.getId(), kidM.getId()));

        entityManager.flush();
        assertThat(credits.findAllByTaskId(t.getId()))
                .extracting(ChoreCredit::getMemberId, ChoreCredit::getMinutes, ChoreCredit::getEffort)
                .containsExactlyInAnyOrder(tuple(kidM.getId(), 20, ChoreEffort.MEDIUM),
                        tuple(papaM.getId(), 20, ChoreEffort.MEDIUM));
        ChoreTask done = tasks.findById(t.getId()).orElseThrow();
        assertThat(done.isOpen()).isFalse();
        assertThat(done.getCompletedByUser()).isEqualTo(kid.getId());
    }

    @Test
    void completingTwiceIsAConflict() {
        ChoreTask t = kidsTask();
        service.complete(t.getId(), kid, null, Set.of());

        assertThatThrownBy(() -> service.complete(t.getId(), papa, null, Set.of()))
                .isInstanceOf(ChoreConflictException.class);
    }

    @Test
    void childCannotCompleteSomebodyElsesTask() {
        ChoreTask t = firstTask(ChoreKind.RECURRING, null, Distribution.FIXED, papaM.getId());

        assertThatThrownBy(() -> service.complete(t.getId(), kid, null, Set.of()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void completingForAnotherMemberNeedsTheAnyRight() {
        ChoreTask t = kidsTask();

        assertThatThrownBy(() -> service.complete(t.getId(), kid, papaM.getId(), Set.of()))
                .isInstanceOf(AccessDeniedException.class);

        service.complete(t.getId(), mama, kidM.getId(), Set.of());
        entityManager.flush();
        assertThat(credits.findAllByTaskId(t.getId())).extracting(ChoreCredit::getMemberId)
                .containsExactly(kidM.getId());
    }

    @Test
    void takingOverCreditsTheOneWhoDidIt() {
        ChoreTask t = kidsTask();

        service.complete(t.getId(), papa, null, Set.of());

        entityManager.flush();
        assertThat(credits.findAllByTaskId(t.getId())).extracting(ChoreCredit::getMemberId)
                .containsExactly(papaM.getId());
    }

    @Test
    void userWithoutMemberMustChooseWhoDidIt() {
        ChoreTask t = kidsTask();

        assertThatThrownBy(() -> service.complete(t.getId(), admin, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mitglied");
    }

    @Test
    void undoRemovesCreditsForCompleterOrCorrector() {
        ChoreTask t = kidsTask();
        service.complete(t.getId(), kid, null, Set.of(papaM.getId()));

        assertThatThrownBy(() -> service.undo(t.getId(), papa)).isInstanceOf(AccessDeniedException.class);
        service.undo(t.getId(), kid);

        entityManager.flush();
        assertThat(credits.findAllByTaskId(t.getId())).isEmpty();
        assertThat(tasks.findById(t.getId()).orElseThrow().isOpen()).isTrue();

        service.complete(t.getId(), kid, null, Set.of());
        service.undo(t.getId(), mama);
        entityManager.flush();
        assertThat(tasks.findById(t.getId()).orElseThrow().isOpen()).isTrue();
    }

    @Test
    void intervalCompletionPlansSuccessorAndUndoRemovesIt() {
        ChoreTask t = firstTask(ChoreKind.INTERVAL, 7, Distribution.FIXED, kidM.getId());

        service.complete(t.getId(), kid, null, Set.of());
        entityManager.flush();
        List<ChoreTask> open = tasks.findAllByChoreIdAndStatus(t.getChore().getId(), ChoreTaskStatus.OPEN);
        assertThat(open).singleElement().satisfies(n -> assertThat(n.getDueOn()).isEqualTo(today.plusDays(7)));

        service.undo(t.getId(), kid);
        entityManager.flush();
        assertThat(tasks.findAllByChoreIdAndStatus(t.getChore().getId(), ChoreTaskStatus.OPEN))
                .singleElement().satisfies(n -> assertThat(n.getId()).isEqualTo(t.getId()));
    }

    @Test
    void assignedMemberMayHandOverOnlyToCandidates() {
        ChoreTask t = firstTask(ChoreKind.RECURRING, null, Distribution.ROTATION, kidM.getId(), papaM.getId());
        assertThat(t.getAssigneeMemberId()).isEqualTo(kidM.getId());

        assertThatThrownBy(() -> service.assign(t.getId(), baby.getId(), kid))
                .isInstanceOf(AccessDeniedException.class);
        service.assign(t.getId(), papaM.getId(), kid);
        assertThat(tasks.findById(t.getId()).orElseThrow().getAssigneeMemberId()).isEqualTo(papaM.getId());

        service.assign(t.getId(), baby.getId(), mama);
        assertThat(tasks.findById(t.getId()).orElseThrow().getAssigneeMemberId()).isEqualTo(baby.getId());
    }

    @Test
    void invisibleForUsersWithoutViewRule() {
        ChoreTask t = kidsTask();

        assertThatThrownBy(() -> service.get(t.getId(), stranger)).isInstanceOf(ChoreNotFoundException.class);
        assertThat(service.get(t.getId(), kid).getId()).isEqualTo(t.getId());
    }

    @Test
    void mineListsOwnDueTasksAndOpenOnesToTake() {
        ChoreTask own = kidsTask();
        ChoreTask open = firstTask(ChoreKind.RECURRING, null, Distribution.OPEN);
        firstTask(ChoreKind.RECURRING, null, Distribution.FIXED, papaM.getId());

        assertThat(service.mine(kid, today)).extracting(ChoreTask::getId)
                .containsExactlyInAnyOrder(own.getId(), open.getId());
    }
}
