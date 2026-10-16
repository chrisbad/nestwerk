package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class BlockerRepositoryTest {

    @Autowired BlockerRepository blockers;
    @Autowired RestGoalRepository goals;
    @Autowired FamilyMemberRepository members;
    @Autowired EntityManager entityManager;

    @Test
    void storesBlockerWithMembersPlannersAndSkips() {
        FamilyMember lena = members.save(new FamilyMember("Lena", "#8b6cff", null));
        FamilyMember chris = members.save(new FamilyMember("Chris", "#ff9f1c", null));
        Blocker work = new Blocker("Arbeit", BlockerKind.WORK, LocalTime.of(8, 0), LocalTime.of(16, 0),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalDate.of(2026, 10, 1), null,
                BlockerStrength.WARN, BlockerVisibility.TITLE, false, true, null);
        work.setMemberIds(Set.of(lena.getId()));
        work.setPlannerIds(Set.of(chris.getId()));
        work.setSkips(Set.of(LocalDate.of(2026, 10, 23)));
        Long id = blockers.save(work).getId();
        entityManager.flush();
        entityManager.clear();

        Blocker loaded = blockers.findById(id).orElseThrow();
        assertThat(loaded.getWeekdays()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        assertThat(loaded.getMemberIds()).containsExactly(lena.getId());
        assertThat(loaded.getPlannerIds()).containsExactly(chris.getId());
        assertThat(loaded.getSkips()).containsExactly(LocalDate.of(2026, 10, 23));
        assertThat(loaded.isOvernight()).isFalse();
        assertThat(blockers.findAllByMemberId(lena.getId())).extracting(Blocker::getId).containsExactly(id);
    }

    @Test
    void sleepCrossesMidnight() {
        Blocker sleep = new Blocker("Schlaf", BlockerKind.SLEEP, LocalTime.of(22, 0), LocalTime.of(6, 30),
                EnumSet.allOf(DayOfWeek.class), LocalDate.of(2026, 10, 1), null, BlockerStrength.BLOCK,
                BlockerVisibility.BUSY, true, false, null);

        assertThat(sleep.isOvernight()).isTrue();
    }

    @Test
    void storesRestGoals() {
        FamilyMember lena = members.save(new FamilyMember("Lena", "#8b6cff", null));
        RestGoal evenings = goals.save(new RestGoal(lena.getId(), RestGoal.Kind.WINDOWS, LocalTime.of(18, 0),
                LocalTime.of(23, 59), 2, "Freie Abende"));
        entityManager.flush();
        entityManager.clear();

        assertThat(goals.findAllByMemberId(lena.getId())).singleElement()
                .satisfies(g -> {
                    assertThat(g.getId()).isEqualTo(evenings.getId());
                    assertThat(g.getTarget()).isEqualTo(2);
                    assertThat(g.getWindowStart()).isEqualTo(LocalTime.of(18, 0));
                });
    }
}
