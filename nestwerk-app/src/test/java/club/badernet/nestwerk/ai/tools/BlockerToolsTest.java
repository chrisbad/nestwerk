package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class BlockerToolsTest {

    @Autowired BlockerTools blockerTools;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;

    User admin;
    User mama;
    User papa;
    FamilyMember mamaM;
    FamilyMember papaM;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Role eltern = roleService.create("Eltern", null, Set.of("CALENDAR_VIEW", "CALENDAR_EVENT_CREATE",
                "CALENDAR_EVENT_EDIT_OWN"));
        acl.applyPreset(admin, eltern.getId(), "ELTERNTEIL");
        mama = userService.create("mama", "passwort1", Set.of(eltern.getId()));
        papa = userService.create("papa", "passwort1", Set.of(eltern.getId()));
        mamaM = members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        papaM = members.save(new FamilyMember("Papa", "#00aa00", papa.getId()));
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    @Test
    void createsUpdatesAndMasksBlockers() {
        BlockerTools.BlockerItem work = blockerTools.createBlocker(as(mama), "Büro", "work", "08:00", "16:30",
                List.of("MONDAY", "Di", "mittwoch"), List.of(mamaM.getId()), null, null, "block", "busy", null,
                true, null, null);

        assertThat(work.weekdays()).containsExactly("MONDAY", "TUESDAY", "WEDNESDAY");
        assertThat(work.strength()).isEqualTo("BLOCK");
        assertThat(work.holidaysOff()).isTrue();

        BlockerTools.BlockerItem changed = blockerTools.updateBlocker(as(mama), work.blockerId(), null, null, "07:30",
                null, List.of("MONDAY"), null, null, "", null, null, null, null, null, List.of("2030-05-01"));
        assertThat(changed.start()).isEqualTo("07:30");
        assertThat(changed.weekdays()).containsExactly("MONDAY");
        assertThat(changed.skips()).hasSize(1);

        assertThat(blockerTools.listBlockers(as(papa), mamaM.getId())).extracting(BlockerTools.BlockerItem::title)
                .containsExactly("belegt");
        blockerTools.updateBlocker(as(mama), work.blockerId(), null, null, null, null, null, null, null, null, null,
                "hidden", null, null, null, null);
        assertThat(blockerTools.listBlockers(as(papa), null)).isEmpty();
        assertThatThrownBy(() -> blockerTools.updateBlocker(as(papa), work.blockerId(), "Frei", null, null, null,
                null, null, null, null, null, null, null, null, null, null)).isInstanceOf(Tools.ToolException.class);
        assertThatThrownBy(() -> blockerTools.createBlocker(as(mama), "x", "work", "08:00", "08:00",
                List.of("MONDAY"), List.of(mamaM.getId()), null, null, null, null, null, null, null, null))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void savesRestGoalsAndHolidayState() {
        blockerTools.saveRestGoal(as(mama), mamaM.getId(), "windows", 3, null, "18:00", "22:00", "Freie Abende");
        blockerTools.saveRestGoal(as(mama), mamaM.getId(), "hours", 10, null, null, null, null);

        List<BlockerTools.GoalItem> goals = blockerTools.listRestGoals(as(mama), mamaM.getId());
        assertThat(goals).extracting(BlockerTools.GoalItem::label).containsExactlyInAnyOrder("Freie Abende",
                "Ruhezeit");
        assertThat(goals).filteredOn(g -> g.kind().equals("HOURS")).extracting(BlockerTools.GoalItem::target)
                .containsExactly(10);
        assertThatThrownBy(() -> blockerTools.saveRestGoal(as(papa), mamaM.getId(), "hours", 10, null, null, null,
                null)).isInstanceOf(Tools.ToolException.class);

        assertThat(blockerTools.setHolidayState(as(admin), "bw")).contains("BW");
        assertThatThrownBy(() -> blockerTools.setHolidayState(as(papa), "BY")).isInstanceOf(Tools.ToolException.class);
    }
}
