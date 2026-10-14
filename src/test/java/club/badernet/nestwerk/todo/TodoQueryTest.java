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

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class TodoQueryTest {

    @Autowired TodoQuery query;
    @Autowired TodoService service;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired FamilyMemberRepository members;
    @Autowired Clock clock;

    User mama;
    User papa;
    LocalDate today;

    @BeforeEach
    void setUp() {
        Long benutzer = roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId();
        mama = userService.create("mama", "passwort1", Set.of(benutzer));
        papa = userService.create("papa", "passwort1", Set.of(benutzer));
        today = LocalDate.now(clock);
    }

    private Todo todo(String title, User owner, LocalDate from, LocalDate due, TodoPriority prio, String... tags) {
        return service.create(new TodoInput(title, null, prio, from, due, null, null, false, null, Set.of(tags),
                Set.of(), null), owner);
    }

    private static List<String> titles(List<TodoGroup> groups, TodoTiming timing) {
        return groups.stream().filter(g -> g.timing() == timing).flatMap(g -> g.todos().stream())
                .map(Todo::getTitle).toList();
    }

    @Test
    void timingBoundaries() {
        assertThat(TodoTiming.of(todo("a", mama, null, today.minusDays(1), null), today)).isEqualTo(TodoTiming.OVERDUE);
        assertThat(TodoTiming.of(todo("b", mama, null, today, null), today)).isEqualTo(TodoTiming.DUE_SOON);
        assertThat(TodoTiming.of(todo("c", mama, null, today.plusDays(7), null), today)).isEqualTo(TodoTiming.DUE_SOON);
        assertThat(TodoTiming.of(todo("d", mama, null, today.plusDays(8), null), today)).isEqualTo(TodoTiming.CURRENT);
        assertThat(TodoTiming.of(todo("e", mama, today.plusDays(1), null, null), today)).isEqualTo(TodoTiming.DORMANT);
        assertThat(TodoTiming.of(todo("f", mama, today, null, null), today)).isEqualTo(TodoTiming.CURRENT);
        assertThat(TodoTiming.of(todo("g", mama, null, null, null), today)).isEqualTo(TodoTiming.NO_DATE);
    }

    @Test
    void groupsInFixedOrderAndSortsByPriorityThenDue() {
        todo("Später", mama, null, today.plusDays(30), TodoPriority.NORMAL);
        todo("Dringend später", mama, null, today.plusDays(40), TodoPriority.URGENT);
        todo("Überfällig", mama, null, today.minusDays(2), TodoPriority.LOW);
        todo("Irgendwann", mama, null, null, null);

        List<TodoGroup> groups = query.list(mama, TodoFilter.defaults());

        assertThat(groups).extracting(TodoGroup::timing)
                .containsSubsequence(TodoTiming.OVERDUE, TodoTiming.CURRENT, TodoTiming.NO_DATE);
        assertThat(titles(groups, TodoTiming.CURRENT)).containsExactly("Dringend später", "Später");
    }

    @Test
    void closedTodosAreHiddenUnlessRequested() {
        Todo done = todo("Erledigt", mama, null, null, null);
        service.check(done.getId(), mama);

        assertThat(titles(query.list(mama, TodoFilter.defaults()), TodoTiming.CLOSED)).isEmpty();
        TodoFilter withClosed = TodoFilter.defaults().withIncludeClosed(true);
        assertThat(titles(query.list(mama, withClosed), TodoTiming.CLOSED)).containsExactly("Erledigt");
    }

    @Test
    void filtersByInvolvementTagPriorityTextAndAssignee() {
        FamilyMember papaMember = members.save(new FamilyMember("Papa", "#00aa00", papa.getId()));
        todo("Mamas Steuer", mama, null, null, TodoPriority.HIGH, "papierkram");
        Todo forPapa = service.create(new TodoInput("Reifen wechseln", "Winterreifen aus dem Keller", null, null,
                null, null, null, false, null, Set.of("auto"), Set.of(AssigneeRef.member(papaMember.getId())), null),
                mama);

        assertThat(all(query.list(papa, TodoFilter.defaults().withInvolvement(TodoFilter.Involvement.MINE))))
                .containsExactly("Reifen wechseln");
        assertThat(all(query.list(papa, TodoFilter.defaults().withTag("papierkram")))).containsExactly("Mamas Steuer");
        assertThat(all(query.list(papa, TodoFilter.defaults().withPriority(TodoPriority.HIGH))))
                .containsExactly("Mamas Steuer");
        assertThat(all(query.list(papa, TodoFilter.defaults().withText("winterreifen"))))
                .containsExactly("Reifen wechseln");
        assertThat(all(query.list(papa, TodoFilter.defaults().withAssignee(AssigneeRef.member(papaMember.getId())))))
                .containsExactly(forPapa.getTitle());
        assertThat(query.tags(papa)).contains("auto", "papierkram");
    }

    @Test
    void privateTodoIsInvisibleEverywhere() {
        service.create(new TodoInput("Geschenk für Papa", null, null, null, today, null, null, true, null, Set.of(),
                Set.of(), null), mama);

        assertThat(all(query.list(papa, TodoFilter.defaults()))).doesNotContain("Geschenk für Papa");
        assertThat(query.mine(papa, 5)).extracting(Todo::getTitle).doesNotContain("Geschenk für Papa");
        assertThat(query.mine(mama, 5)).extracting(Todo::getTitle).contains("Geschenk für Papa");
    }

    @Test
    void mineShowsOnlyUrgentInvolvedTodos() {
        todo("Heute", mama, null, today, null);
        todo("Ruhend", mama, today.plusDays(3), null, null);
        todo("Papas", papa, null, today, null);

        assertThat(query.mine(mama, 5)).extracting(Todo::getTitle).containsExactly("Heute");
    }

    private static List<String> all(List<TodoGroup> groups) {
        return groups.stream().flatMap(g -> g.todos().stream()).map(Todo::getTitle).toList();
    }
}
