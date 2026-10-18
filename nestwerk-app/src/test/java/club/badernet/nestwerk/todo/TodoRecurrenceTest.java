package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.TestcontainersConfiguration;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class TodoRecurrenceTest {

    @Autowired TodoService service;
    @Autowired TodoStructureService structure;
    @Autowired TodoRepository todos;
    @Autowired TodoRecurrence recurrence;
    @Autowired UserService userService;
    @Autowired RoleRepository roles;
    @Autowired Clock clock;

    User mama;
    LocalDate today;

    @BeforeEach
    void setUp() {
        mama = userService.create("mama", "passwort1",
                Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        today = LocalDate.now(clock);
    }

    private Todo recurring(String title, LocalDate from, LocalDate due, LocalDate followUp, String rule) {
        return service.create(new TodoInput(title, null, TodoPriority.HIGH, from, due, followUp, 3, false, null,
                Set.of("haus"), Set.of(), null, rule), mama);
    }

    private Todo successorOf(Todo todo) {
        Long id = todos.findById(todo.getId()).orElseThrow().getSuccessorId();
        assertThat(id).as("Nachfolger").isNotNull();
        return todos.findById(id).orElseThrow();
    }

    @Test
    void nextDateFollowsRule() {
        LocalDate seed = LocalDate.of(2026, 1, 15);

        assertThat(recurrence.next("FREQ=MONTHLY", seed, LocalDate.of(2026, 3, 15))).contains(LocalDate.of(2026, 4, 15));
        assertThat(recurrence.next("FREQ=YEARLY", seed, LocalDate.of(2026, 1, 15))).contains(LocalDate.of(2027, 1, 15));
        assertThat(recurrence.next("FREQ=MONTHLY;COUNT=2", seed, LocalDate.of(2026, 2, 15))).isEmpty();
    }

    @Test
    void invalidRuleIsRejected() {
        assertThatThrownBy(() -> recurring("Kaputt", null, today, null, "FREQ=IRGENDWANN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wiederholung");
    }

    @Test
    void closingCreatesSuccessorWithShiftedWindow() {
        LocalDate due = LocalDate.of(2026, 10, 30);
        Todo t = recurring("Rauchmelder prüfen", due.minusDays(10), due, due.minusDays(5), "FREQ=MONTHLY");

        service.check(t.getId(), mama);

        Todo next = successorOf(t);
        assertThat(next.getDueOn()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(next.getAvailableFrom()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(next.getFollowUpOn()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(next.getStatus()).isEqualTo(TodoStatus.OPEN);
        assertThat(next.getTitle()).isEqualTo("Rauchmelder prüfen");
        assertThat(next.getPriority()).isEqualTo(TodoPriority.HIGH);
        assertThat(next.getTags()).containsExactly("haus");
        assertThat(next.getRrule()).isEqualTo("FREQ=MONTHLY");
        assertThat(next.getSeriesId()).isEqualTo(t.getId());
    }

    @Test
    void withoutDatesTheCompletionDayIsTheAnchor() {
        Todo t = recurring("Filter wechseln", null, null, null, "FREQ=YEARLY");

        service.changeStatus(t.getId(), TodoStatus.DONE, mama);

        assertThat(successorOf(t).getDueOn()).isAfter(today).isBeforeOrEqualTo(today.plusYears(1));
    }

    @Test
    void reclosingDoesNotDuplicateSuccessor() {
        Todo t = recurring("Müll", null, today, null, "FREQ=WEEKLY");

        service.check(t.getId(), mama);
        Long first = todos.findById(t.getId()).orElseThrow().getSuccessorId();
        service.uncheck(t.getId(), mama);
        service.check(t.getId(), mama);

        assertThat(todos.findById(t.getId()).orElseThrow().getSuccessorId()).isEqualTo(first);
        assertThat(todos.findAll().stream().filter(x -> x.getTitle().equals("Müll")).count()).isEqualTo(2);
    }

    @Test
    void exhaustedRuleCreatesNoSuccessor() {
        Todo t = recurring("Einmal", null, today, null, "FREQ=DAILY;COUNT=1");

        service.check(t.getId(), mama);

        assertThat(todos.findById(t.getId()).orElseThrow().getSuccessorId()).isNull();
    }

    @Test
    void subtasksAreCopiedOpen() {
        Todo parent = recurring("Frühjahrsputz", null, LocalDate.of(2027, 3, 20), null, "FREQ=YEARLY");
        Todo child = structure.createChild(parent.getId(), new TodoInput("Fenster", null, null, null, null, null,
                null, false, null, Set.of(), Set.of(), null, null), mama);
        service.check(child.getId(), mama);

        service.changeStatus(parent.getId(), TodoStatus.DONE, mama);

        Todo next = successorOf(parent);
        List<Todo> copied = todos.findAllByParentId(next.getId());
        assertThat(copied).extracting(Todo::getTitle).containsExactly("Fenster");
        assertThat(copied.getFirst().getStatus()).isEqualTo(TodoStatus.OPEN);
        assertThat(copied.getFirst().getRrule()).isNull();
    }
}
