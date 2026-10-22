package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.item.CalendarItem;
import club.badernet.nestwerk.item.CalendarItemSource;
import club.badernet.nestwerk.item.ItemAction;
import club.badernet.nestwerk.item.ItemType;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Open to-dos with a due date as all-day calendar items on that day. */
@Component
public class TodoItems implements CalendarItemSource {

    private final TodoQuery query;
    private final TodoService todos;
    private final TodoAccess access;
    private final FamilyMemberRepository members;

    public TodoItems(TodoQuery query, TodoService todos, TodoAccess access, FamilyMemberRepository members) {
        this.query = query;
        this.todos = todos;
        this.access = access;
        this.members = members;
    }

    @Override
    public ItemType type() {
        return ItemType.TODO;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CalendarItem> items(LocalDate from, LocalDate to, User viewer) {
        if (!access.mayOpen(viewer.getUsername())) {
            return List.of();
        }
        Map<Long, FamilyMember> people = people();
        return query.dueBetween(viewer, from, to).values().stream().flatMap(List::stream)
                .map(todo -> item(todo, people, viewer)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CalendarItem> find(Long id, User viewer) {
        if (!access.mayOpen(viewer.getUsername())) {
            return Optional.empty();
        }
        Todo todo;
        try {
            todo = todos.get(id, viewer);
        } catch (TodoNotFoundException e) {
            return Optional.empty();
        }
        return todo.getDueOn() == null ? Optional.empty() : Optional.of(item(todo, people(), viewer));
    }

    private Map<Long, FamilyMember> people() {
        return members.findAll().stream().collect(Collectors.toMap(FamilyMember::getId, Function.identity()));
    }

    private CalendarItem item(Todo todo, Map<Long, FamilyMember> people, User viewer) {
        Set<Long> memberIds = todo.assigneeRefs().stream().filter(ref -> ref.type() == AssigneeRef.Type.MEMBER)
                .map(AssigneeRef::id).collect(Collectors.toUnmodifiableSet());
        String color = memberIds.stream().map(people::get).filter(Objects::nonNull).findFirst()
                .map(FamilyMember::getColor).orElse(null);
        Set<ItemAction> actions = EnumSet.noneOf(ItemAction.class);
        if (!todo.isClosed()) {
            if (access.can(viewer, "CHANGE_STATUS", todo)) {
                actions.add(ItemAction.COMPLETE);
            }
            if (access.can(viewer, "EDIT", todo)) {
                actions.add(ItemAction.MOVE);
                actions.add(ItemAction.EDIT);
            }
        }
        return new CalendarItem.Todo(todo.getId(), todo.getTitle(), todo.getDueOn().atStartOfDay(),
                todo.getDueOn().plusDays(1).atStartOfDay(), true, memberIds, color, todo.getStatus().name(),
                todo.getSeriesId(), Set.copyOf(actions), todo.getPriority().name());
    }
}
