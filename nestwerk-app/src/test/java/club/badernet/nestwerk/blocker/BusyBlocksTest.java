package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class BusyBlocksTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 13);

    @Autowired BusyBlocks busyBlocks;
    @Autowired BlockerService blockers;
    @Autowired CalendarSettings calendar;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberService memberService;

    User lenaUser;
    User otherUser;
    User admin;
    FamilyMember lena;
    ZoneId zone;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Normal", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE"));
        lenaUser = userService.create("lena", "passwort1", Set.of(normal.getId()));
        otherUser = userService.create("chris", "passwort1", Set.of(normal.getId()));
        admin = userService.createAdministrator("chef", "passwort1");
        lena = memberService.create("Lena", "#8b6cff", lenaUser.getId());
        zone = calendar.zone();
    }

    private void blocker(String title, LocalTime from, LocalTime to, DayOfWeek day, BlockerStrength strength,
                         BlockerVisibility visibility) {
        blockers.create(new BlockerInput(title, BlockerKind.WORK, from, to, EnumSet.of(day), LocalDate.of(2026, 10, 1),
                null, strength, visibility, false, false, Set.of(lena.getId()), Set.of(), Set.of()), admin);
    }

    @Test
    void deliversExactStartAndEndOfTheDay() {
        blocker("Büro", LocalTime.of(8, 0), LocalTime.of(16, 0), DayOfWeek.TUESDAY, BlockerStrength.WARN,
                BlockerVisibility.TITLE);

        List<BusyBlock> blocks = busyBlocks.between(TUESDAY, TUESDAY, lenaUser);

        assertThat(blocks).singleElement().satisfies(block -> {
            assertThat(block.memberId()).isEqualTo(lena.getId());
            assertThat(block.start()).isEqualTo(TUESDAY.atTime(8, 0).atZone(zone).toInstant());
            assertThat(block.end()).isEqualTo(TUESDAY.atTime(16, 0).atZone(zone).toInstant());
            assertThat(block.kind()).isEqualTo("work");
        });
    }

    @Test
    void deliversEveryStrength() {
        for (BlockerStrength strength : BlockerStrength.values()) {
            blocker(strength.name(), LocalTime.of(8, 0), LocalTime.of(9, 0), DayOfWeek.TUESDAY, strength,
                    BlockerVisibility.TITLE);
        }

        assertThat(busyBlocks.between(TUESDAY, TUESDAY, admin)).extracting(BusyBlock::strength)
                .containsExactlyInAnyOrder(BlockerStrength.values());
    }

    @Test
    void affectedMemberAndAdminSeeTheTitleOthersSeeBusyOrNothing() {
        blocker("Büro", LocalTime.of(8, 0), LocalTime.of(9, 0), DayOfWeek.TUESDAY, BlockerStrength.WARN,
                BlockerVisibility.BUSY);
        blocker("Therapie", LocalTime.of(10, 0), LocalTime.of(11, 0), DayOfWeek.TUESDAY, BlockerStrength.WARN,
                BlockerVisibility.HIDDEN);

        assertThat(busyBlocks.between(TUESDAY, TUESDAY, lenaUser)).extracting(BusyBlock::label)
                .containsExactly("Büro", "Therapie");
        assertThat(busyBlocks.between(TUESDAY, TUESDAY, admin)).extracting(BusyBlock::label)
                .containsExactly("Büro", "Therapie");
        assertThat(busyBlocks.between(TUESDAY, TUESDAY, otherUser)).extracting(BusyBlock::label)
                .containsExactly("belegt");
    }

    @Test
    void titleVisibilityShowsTheTitleToOthers() {
        blocker("Büro", LocalTime.of(8, 0), LocalTime.of(9, 0), DayOfWeek.TUESDAY, BlockerStrength.SHOW,
                BlockerVisibility.TITLE);

        assertThat(busyBlocks.between(TUESDAY, TUESDAY, otherUser)).extracting(BusyBlock::label)
                .containsExactly("Büro");
    }

    @Test
    void overnightBlockerIsCutPerDay() {
        blocker("Schlaf", LocalTime.of(22, 0), LocalTime.of(6, 0), DayOfWeek.TUESDAY, BlockerStrength.WARN,
                BlockerVisibility.TITLE);
        LocalDate wednesday = TUESDAY.plusDays(1);

        List<BusyBlock> blocks = busyBlocks.between(TUESDAY, wednesday, admin);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).start()).isEqualTo(TUESDAY.atTime(22, 0).atZone(zone).toInstant());
        assertThat(blocks.get(0).end()).isEqualTo(wednesday.atStartOfDay(zone).toInstant());
        assertThat(blocks.get(1).start()).isEqualTo(wednesday.atStartOfDay(zone).toInstant());
        assertThat(blocks.get(1).end()).isEqualTo(wednesday.atTime(6, 0).atZone(zone).toInstant());
    }

    @Test
    void overnightBlockerOfTheDayBeforeReachesIntoTheRange() {
        blocker("Schlaf", LocalTime.of(22, 0), LocalTime.of(6, 0), DayOfWeek.TUESDAY, BlockerStrength.WARN,
                BlockerVisibility.TITLE);
        LocalDate wednesday = TUESDAY.plusDays(1);

        assertThat(busyBlocks.between(wednesday, wednesday, admin)).singleElement().satisfies(block -> {
            assertThat(block.start()).isEqualTo(wednesday.atStartOfDay(zone).toInstant());
            assertThat(block.end()).isEqualTo(wednesday.atTime(6, 0).atZone(zone).toInstant());
        });
    }

    @Test
    void daysWithoutBlockerStayEmpty() {
        blocker("Büro", LocalTime.of(8, 0), LocalTime.of(9, 0), DayOfWeek.TUESDAY, BlockerStrength.WARN,
                BlockerVisibility.TITLE);

        assertThat(busyBlocks.between(TUESDAY.plusDays(1), TUESDAY.plusDays(1), admin)).isEmpty();
    }
}
