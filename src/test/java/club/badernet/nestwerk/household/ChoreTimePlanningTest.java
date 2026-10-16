package club.badernet.nestwerk.household;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.Blocker;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerRepository;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BlockerVisibility;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Planer legt Hausarbeiten in freie Lücken der zuständigen Person, außerhalb ihrer Blocker. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class ChoreTimePlanningTest {

    @Autowired ChoreService chores;
    @Autowired ChorePlanner planner;
    @Autowired ChoreTaskService taskService;
    @Autowired ChoreTaskRepository tasks;
    @Autowired BlockerRepository blockers;
    @Autowired UserService userService;
    @Autowired FamilyMemberRepository members;
    @Autowired EntityManager entityManager;
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
    }

    private void block(FamilyMember who, LocalTime from, LocalTime to) {
        Blocker b = new Blocker("Arbeit", BlockerKind.WORK, from, to, EnumSet.allOf(DayOfWeek.class),
                LocalDate.now(clock), null, BlockerStrength.BLOCK, BlockerVisibility.TITLE, false, false, null);
        b.setMemberIds(Set.of(who.getId()));
        blockers.saveAndFlush(b);
    }

    private Chore chore(Distribution distribution, LocalTime due, LocalTime windowStart, Long... ids) {
        return chores.create(new ChoreInput("Bad putzen", null, null, 10, 30, 45, ChoreKind.RECURRING,
                "FREQ=DAILY;COUNT=1", tomorrow, due, null, distribution, List.of(ids), windowStart, null), admin);
    }

    private ChoreTask only(Chore c) {
        entityManager.flush();
        List<ChoreTask> all = tasks.findAllByChoreId(c.getId());
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    @Test
    void placesTaskInFirstGapOutsideBlockers() {
        block(lena, LocalTime.of(6, 0), LocalTime.of(16, 0));

        ChoreTask task = only(chore(Distribution.FIXED, LocalTime.of(20, 0), null, lena.getId()));

        assertThat(task.getAssigneeMemberId()).isEqualTo(lena.getId());
        assertThat(task.getPlannedAt()).isEqualTo(LocalTime.of(16, 0));
    }

    @Test
    void windowStartIsRespected() {
        ChoreTask task = only(chore(Distribution.FIXED, LocalTime.of(21, 0), LocalTime.of(18, 30), lena.getId()));

        assertThat(task.getPlannedAt()).isEqualTo(LocalTime.of(18, 30));
    }

    @Test
    void earlyDueTimePlansRightBeforeIt() {
        ChoreTask task = only(chore(Distribution.FIXED, LocalTime.of(7, 0), null, lena.getId()));

        assertThat(task.getPlannedAt()).isEqualTo(LocalTime.of(6, 30));
    }

    @Test
    void rotationSkipsPersonWithoutGap() {
        block(lena, LocalTime.of(5, 0), LocalTime.of(23, 0));

        ChoreTask task = only(chore(Distribution.ROTATION, null, null, lena.getId(), tim.getId()));

        assertThat(task.getAssigneeMemberId()).isEqualTo(tim.getId());
        assertThat(task.getPlannedAt()).isEqualTo(LocalTime.of(7, 0));
    }

    @Test
    void nobodyFreeLeavesTaskOpenWithoutTime() {
        block(lena, LocalTime.of(5, 0), LocalTime.of(23, 0));

        ChoreTask task = only(chore(Distribution.FIXED, null, null, lena.getId()));

        assertThat(task.getAssigneeMemberId()).isNull();
        assertThat(task.getPlannedAt()).isNull();
    }

    @Test
    void twoChoresOfOnePersonDoNotOverlap() {
        ChoreTask first = only(chore(Distribution.FIXED, null, null, lena.getId()));
        ChoreTask second = only(chore(Distribution.FIXED, null, null, lena.getId()));

        assertThat(first.getPlannedAt()).isEqualTo(LocalTime.of(7, 0));
        assertThat(second.getPlannedAt()).isEqualTo(LocalTime.of(7, 30));
    }

    @Test
    void reassigningPlansTheTimeForTheNewPerson() {
        block(tim, LocalTime.of(5, 0), LocalTime.of(12, 0));
        ChoreTask task = only(chore(Distribution.FIXED, null, null, lena.getId()));

        taskService.assign(task.getId(), tim.getId(), admin);
        entityManager.flush();

        assertThat(tasks.findById(task.getId()).orElseThrow().getPlannedAt()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    void newBlockerMovesPlannedTime() {
        ChoreTask task = only(chore(Distribution.FIXED, null, null, lena.getId()));
        assertThat(task.getPlannedAt()).isEqualTo(LocalTime.of(7, 0));

        block(lena, LocalTime.of(6, 0), LocalTime.of(13, 0));
        planner.planAll(LocalDate.now(clock));
        entityManager.flush();

        assertThat(tasks.findById(task.getId()).orElseThrow().getPlannedAt()).isEqualTo(LocalTime.of(13, 0));
    }
}
