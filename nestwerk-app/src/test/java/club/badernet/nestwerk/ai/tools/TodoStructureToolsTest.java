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

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class TodoStructureToolsTest {

    @Autowired TodoTools todoTools;
    @Autowired TodoStructureTools structureTools;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired AclService acl;
    @Autowired FamilyMemberRepository members;

    User admin;
    User mama;
    User papa;

    @BeforeEach
    void setUp() {
        admin = userService.createAdministrator("chef", "passwort1");
        Role eltern = roleService.create("Eltern", null, Set.of());
        acl.applyPreset(admin, eltern.getId(), "ELTERNTEIL");
        mama = userService.create("mama", "passwort1", Set.of(eltern.getId()));
        papa = userService.create("papa", "passwort1", Set.of(eltern.getId()));
        members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        members.save(new FamilyMember("Papa", "#00aa00", papa.getId()));
    }

    private static McpTransportContext as(User user) {
        return McpTransportContext.create(Map.of(McpCaller.USER_ID, user.getId()));
    }

    private Long todo(String title) {
        return todoTools.createTodo(as(mama), title, null, null, null, null, null, null, null, null, null, null,
                null, null).todo().id();
    }

    @Test
    void buildsSubtasksAndLinks() {
        Long parent = todo("Umzug");
        TodoStructureTools.TodoRef child = structureTools.createSubtodo(as(mama), parent, "Kartons packen", null,
                null, null);
        Long other = todo("Transporter mieten");

        TodoStructureTools.Structure linked = structureTools.linkTodos(as(mama), other, child.id(), "blocks");
        assertThat(linked.links()).extracting(l -> l.other().id()).containsExactly(child.id());

        TodoStructureTools.Structure s = structureTools.getTodoStructure(as(mama), parent);
        assertThat(s.children()).extracting(TodoStructureTools.TodoRef::title).containsExactly("Kartons packen");
        assertThat(s.progress()).isEqualTo("0/1");

        Long linkId = structureTools.getTodoStructure(as(mama), child.id()).links().get(0).linkId();
        assertThat(structureTools.unlinkTodos(as(mama), child.id(), linkId).links()).isEmpty();

        TodoStructureTools.Structure moved = structureTools.moveTodo(as(mama), child.id(), null);
        assertThat(moved.parent()).isNull();
        assertThatThrownBy(() -> structureTools.moveTodo(as(mama), parent, parent))
                .isInstanceOf(Tools.ToolException.class);
    }

    @Test
    void takesWatchesAndFollowsUp() {
        Long id = todo("Garage aufräumen");

        assertThat(structureTools.takeTodo(as(papa), id).status()).isEqualTo("IN_PROGRESS");

        assertThat(structureTools.watchTodo(as(papa), id, true, null).watching()).isTrue();
        assertThat(structureTools.watchTodo(as(mama), id, true, papa.getId()).watchers()).contains("papa");
        assertThat(structureTools.watchTodo(as(papa), id, false, null).watching()).isFalse();

        assertThat(structureTools.followUpTodo(as(mama), id, "done").status()).isEqualTo("DONE");
        assertThat(structureTools.todoHistory(as(mama), id)).extracting(TodoStructureTools.HistoryItem::change)
                .anyMatch(c -> c.startsWith("Status"));
    }

    @Test
    void editsOwnCommentAndSetsNotificationOverride() {
        Long id = todo("Fenster putzen");
        Long commentId = todoTools.addTodoComment(as(mama), id, "alt").id();

        structureTools.editTodoComment(as(mama), commentId, "neu");

        assertThat(todoTools.getTodo(as(mama), id).comments()).extracting(TodoTools.Comment::body)
                .containsExactly("neu");
        assertThat(structureTools.setTodoNotifications(as(admin), id, "off", null)).contains("OFF");
    }
}
