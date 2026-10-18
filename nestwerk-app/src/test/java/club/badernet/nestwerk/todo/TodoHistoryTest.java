package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class TodoHistoryTest {

    @Autowired TodoService service;
    @Autowired TodoStructureService structure;
    @Autowired TodoHistoryRepository history;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired FamilyMemberRepository members;

    User mama;

    @BeforeEach
    void setUp() {
        mama = userService.create("mama", "passwort1",
                Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
    }

    private static TodoInput input(String title, LocalDate due, Set<AssigneeRef> refs) {
        return new TodoInput(title, null, null, null, due, null, null, false, null, Set.of(), refs, null);
    }

    private List<TodoHistoryEntry> entries(Todo todo) {
        return history.findAllByTodoIdOrderByAtAscIdAsc(todo.getId());
    }

    @Test
    void creationIsRecorded() {
        Todo t = service.create(input("Fenster", null, Set.of()), mama);

        assertThat(entries(t)).extracting(TodoHistoryEntry::getField, TodoHistoryEntry::getActorId)
                .containsExactly(tuple("CREATED", mama.getId()));
    }

    @Test
    void eachChangedFieldIsRecordedWithRawValues() {
        FamilyMember lena = members.save(new FamilyMember("Lena", "#aa3366", null));
        Todo t = service.create(input("Fenster", null, Set.of()), mama);

        service.update(t.getId(), input("Fenster putzen", LocalDate.of(2026, 11, 2),
                Set.of(AssigneeRef.member(lena.getId()))), t.getVersion(), mama);

        assertThat(entries(t)).extracting(TodoHistoryEntry::getField, TodoHistoryEntry::getOldValue,
                        TodoHistoryEntry::getNewValue)
                .contains(tuple("TITLE", "Fenster", "Fenster putzen"),
                        tuple("DUE_ON", null, "2026-11-02"),
                        tuple("ASSIGNEES", null, "member:" + lena.getId()));
    }

    @Test
    void statusChangesAreRecorded() {
        Todo t = service.create(input("Müll", null, Set.of()), mama);

        service.take(t.getId(), mama);
        service.check(t.getId(), mama);

        assertThat(entries(t)).extracting(TodoHistoryEntry::getField, TodoHistoryEntry::getOldValue,
                        TodoHistoryEntry::getNewValue)
                .contains(tuple("STATUS", "OPEN", "IN_PROGRESS"), tuple("STATUS", "IN_PROGRESS", "DONE"));
    }

    @Test
    void structureChangesAreRecordedOnBothSides() {
        Todo parent = service.create(input("Keller", null, Set.of()), mama);
        Todo other = service.create(input("Regal kaufen", null, Set.of()), mama);

        Todo child = structure.createChild(parent.getId(), input("Regal abbauen", null, Set.of()), mama);
        structure.link(child.getId(), other.getId(), TodoLinkType.BLOCKS, mama);

        assertThat(entries(parent)).extracting(TodoHistoryEntry::getField, TodoHistoryEntry::getNewValue)
                .contains(tuple("CHILD_ADDED", String.valueOf(child.getId())));
        assertThat(entries(child)).extracting(TodoHistoryEntry::getField, TodoHistoryEntry::getNewValue)
                .contains(tuple("LINK_ADDED", "BLOCKS:" + other.getId()));
        assertThat(entries(other)).extracting(TodoHistoryEntry::getField, TodoHistoryEntry::getNewValue)
                .contains(tuple("LINK_ADDED", "BLOCKED_BY:" + child.getId()));
    }

    @Test
    void unchangedUpdateRecordsNothing() {
        Todo t = service.create(input("Gleich", null, Set.of()), mama);
        int before = entries(t).size();

        service.update(t.getId(), input("Gleich", null, Set.of()), t.getVersion(), mama);

        assertThat(entries(t)).hasSize(before);
    }
}
