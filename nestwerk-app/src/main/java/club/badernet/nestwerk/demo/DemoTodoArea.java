package club.badernet.nestwerk.demo;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.CompletionMode;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class DemoTodoArea implements DemoArea {

    private final TodoService todos;

    DemoTodoArea(TodoService todos) {
        this.todos = todos;
    }

    @Override
    public String name() {
        return "todos";
    }

    @Override
    public void seed(DemoContext context) {
        LocalDate today = context.today();
        DemoFamily family = context.family();

        create(context, "Geburtstagsgeschenk für Oma besorgen", "Etwas Selbstgemachtes oder ein Gutschein.",
                TodoPriority.NORMAL, today.plusDays(10), Set.of("Familie"), family.parent());
        create(context, "Steuererklärung abgeben", "Belege liegen im Ordner im Arbeitszimmer.", TodoPriority.HIGH,
                today.plusDays(25), Set.of("Papierkram"), family.parent());
        create(context, "Fahrrad von Max reparieren", "Hinterreifen hat einen Platten.", TodoPriority.NORMAL,
                today.plusDays(3), Set.of("Haus"), family.parent(), family.son());
        create(context, "Wasserfilter wechseln", null, TodoPriority.LOW, today.plusDays(14), Set.of("Haus"),
                family.partner());
        create(context, "Rechnung Stromanbieter prüfen", "Abschlag wurde erhöht.", TodoPriority.URGENT,
                today.minusDays(2), Set.of("Papierkram"), family.partner());
        create(context, "Zimmer aufräumen", "Wäsche in den Schrank, Spielzeug sortieren.", TodoPriority.NORMAL,
                today.plusDays(1), Set.of("Kinder"), family.daughter());
        create(context, "Sportbeutel packen", null, TodoPriority.LOW, today, Set.of("Kinder"), family.son());
        create(context, "Winterreifen aufziehen lassen", "Termin in der Werkstatt vereinbaren.", TodoPriority.HIGH,
                today.plusDays(7), Set.of("Auto"), family.parent());

        Todo inProgress = create(context, "Gartenhaus streichen", "Farbe ist im Keller.", TodoPriority.NORMAL,
                today.plusDays(20), Set.of("Haus"), family.parent(), family.partner());
        todos.changeStatus(inProgress.getId(), TodoStatus.IN_PROGRESS, context.actor());
        Todo done = create(context, "Impfpass der Kinder suchen", null, TodoPriority.NORMAL, today.minusDays(5),
                Set.of("Papierkram"), family.partner());
        todos.changeStatus(done.getId(), TodoStatus.DONE, context.actor());
    }

    private Todo create(DemoContext context, String title, String description, TodoPriority priority,
                        LocalDate dueOn, Set<String> tags, FamilyMember... assignees) {
        Set<AssigneeRef> refs = Arrays.stream(assignees)
                .map(member -> AssigneeRef.member(member.getId()))
                .collect(Collectors.toSet());
        return todos.create(new TodoInput(title, description, priority, null, dueOn, null, null, false,
                CompletionMode.ANY, tags, refs, null), context.actor());
    }
}
