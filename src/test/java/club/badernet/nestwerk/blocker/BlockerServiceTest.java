package club.badernet.nestwerk.blocker;

import club.badernet.nestwerk.TestcontainersConfiguration;
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
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class BlockerServiceTest {

    @Autowired BlockerService service;
    @Autowired BlockerSettings settings;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired FamilyMemberService memberService;

    User lenaUser;
    User chrisUser;
    User reader;
    User admin;
    FamilyMember lena;
    FamilyMember chris;

    @BeforeEach
    void setUp() {
        Role normal = roleService.create("Normal", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN", "CALENDAR_EVENT_DELETE_OWN"));
        Role readOnly = roleService.create("Nur lesen", null, Set.of("CALENDAR_VIEW"));
        lenaUser = userService.create("lena", "passwort1", Set.of(normal.getId()));
        chrisUser = userService.create("chris", "passwort1", Set.of(normal.getId()));
        reader = userService.create("leser", "passwort1", Set.of(readOnly.getId()));
        admin = userService.createAdministrator("chef", "passwort1");
        lena = memberService.create("Lena", "#8b6cff", lenaUser.getId());
        chris = memberService.create("Chris", "#ff9f1c", chrisUser.getId());
    }

    static BlockerInput work(Set<Long> members) {
        return new BlockerInput("Arbeit", BlockerKind.WORK, LocalTime.of(8, 0), LocalTime.of(16, 0),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalDate.of(2026, 10, 1), null,
                BlockerStrength.WARN, BlockerVisibility.TITLE, false, true, members, Set.of(), Set.of());
    }

    @Test
    void createsBlockerForOwnPerson() {
        Blocker b = service.create(work(Set.of(lena.getId())), lenaUser);

        assertThat(b.getId()).isNotNull();
        assertThat(service.forMember(lena.getId())).extracting(Blocker::getTitle).containsExactly("Arbeit");
    }

    @Test
    void creatingNeedsCalendarCreateRight() {
        assertThatThrownBy(() -> service.create(work(Set.of(lena.getId())), reader))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void editingOthersNeedsEditAll() {
        Blocker forChris = service.create(work(Set.of(chris.getId())), chrisUser);

        assertThatThrownBy(() -> service.update(forChris.getId(), work(Set.of(chris.getId())), lenaUser))
                .isInstanceOf(AccessDeniedException.class);
        service.update(forChris.getId(), work(Set.of(chris.getId())), admin);
        // für die eigene Person darf man ändern, auch wenn jemand anderes angelegt hat
        Blocker forLena = service.create(work(Set.of(lena.getId())), admin);
        service.update(forLena.getId(), work(Set.of(lena.getId())), lenaUser);
        service.delete(forLena.getId(), lenaUser);
        assertThat(service.forMember(lena.getId())).isEmpty();
    }

    @Test
    void validatesInput() {
        BlockerInput sameTimes = new BlockerInput("X", BlockerKind.WORK, LocalTime.of(8, 0), LocalTime.of(8, 0),
                EnumSet.of(DayOfWeek.MONDAY), LocalDate.of(2026, 10, 1), null, BlockerStrength.WARN,
                BlockerVisibility.TITLE, false, false, Set.of(lena.getId()), Set.of(), Set.of());
        BlockerInput noDays = new BlockerInput("X", BlockerKind.WORK, LocalTime.of(8, 0), LocalTime.of(9, 0),
                EnumSet.noneOf(DayOfWeek.class), LocalDate.of(2026, 10, 1), null, BlockerStrength.WARN,
                BlockerVisibility.TITLE, false, false, Set.of(lena.getId()), Set.of(), Set.of());
        BlockerInput noOne = work(Set.of());

        assertThatThrownBy(() -> service.create(sameTimes, admin)).hasMessageContaining("Beginn und Ende");
        assertThatThrownBy(() -> service.create(noDays, admin)).hasMessageContaining("Wochentag");
        assertThatThrownBy(() -> service.create(noOne, admin)).hasMessageContaining("Person");
    }

    @Test
    void emptyTitleFallsBackToKind() {
        BlockerInput in = new BlockerInput("  ", BlockerKind.SLEEP, LocalTime.of(22, 0), LocalTime.of(6, 30),
                EnumSet.allOf(DayOfWeek.class), LocalDate.of(2026, 10, 1), null, BlockerStrength.BLOCK,
                BlockerVisibility.BUSY, true, false, Set.of(lena.getId()), Set.of(), Set.of());

        assertThat(service.create(in, admin).getTitle()).isEqualTo("Schlaf");
    }

    @Test
    void stateSettingAcceptsOnlyKnownStates() {
        settings.setState("SN");
        assertThat(settings.state()).isEqualTo("SN");
        settings.setState("");
        assertThat(settings.state()).isNull();
        assertThatThrownBy(() -> settings.setState("XX")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void savesRestGoalsForOwnPersonOnly() {
        RestGoal goal = service.saveGoal(null, lena.getId(), RestGoal.Kind.WINDOWS, LocalTime.of(18, 0),
                LocalTime.of(23, 59), 2, "Freie Abende", lenaUser);

        assertThat(service.goals(lena.getId())).extracting(RestGoal::getId).containsExactly(goal.getId());
        assertThatThrownBy(() -> service.saveGoal(null, chris.getId(), RestGoal.Kind.HOURS, null, null, 360,
                "Ruhe", lenaUser)).isInstanceOf(AccessDeniedException.class);
    }
}
