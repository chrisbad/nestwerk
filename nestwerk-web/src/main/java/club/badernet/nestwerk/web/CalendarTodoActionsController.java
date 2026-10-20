package club.badernet.nestwerk.web;

import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.user.User;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Actions on due to-dos from the calendar's dialog and gestures: quick edit, status, move to another day or person
 * and delete. Everything goes through {@link TodoService} with its permissions.
 */
@RestController
@RequestMapping("/calendar/actions/todos")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarTodoActionsController {

    private final TodoService todos;
    private final TodoAccess access;
    private final CurrentUser currentUser;

    public CalendarTodoActionsController(TodoService todos, TodoAccess access, CurrentUser currentUser) {
        this.todos = todos;
        this.access = access;
        this.currentUser = currentUser;
    }

    /** Title, due date, priority and the assignees from the dialog; everything else stays as stored. */
    @PostMapping("/{id}/update")
    ActionResult update(@PathVariable Long id, @RequestParam int version, @RequestParam String title,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueOn,
                        @RequestParam TodoPriority priority,
                        @RequestParam(name = "assignees", required = false) List<String> assignees) {
        User actor = requireModule();
        TodoForm form = TodoForm.from(todos.get(id, actor));
        form.setTitle(title);
        form.setDueOn(dueOn);
        form.setPriority(priority);
        form.setAssignees(assignees == null ? new ArrayList<>() : new ArrayList<>(assignees));
        todos.update(id, form.toInput(), version, actor);
        return ActionResult.of("ToDo wurde gespeichert.");
    }

    @PostMapping("/{id}/status")
    ActionResult status(@PathVariable Long id, @RequestParam TodoStatus status) {
        todos.changeStatus(id, status, requireModule());
        return ActionResult.of("ToDo ist jetzt „" + status.label() + "“.");
    }

    /**
     * Dragging in the all-day row: another due date and, if {@code from} differs from {@code to}, another person
     * instead of {@code from} (an empty {@code to} only removes {@code from}, an empty {@code from} adds {@code to}).
     */
    @PostMapping("/{id}/move")
    ActionResult move(@PathVariable Long id,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueOn,
                      @RequestParam(required = false) Long from, @RequestParam(required = false) Long to) {
        User actor = requireModule();
        Todo todo = todos.get(id, actor);
        TodoForm form = TodoForm.from(todo);
        form.setDueOn(dueOn);
        form.setAssignees(new ArrayList<>(new MemberSwap(from, to).apply(form.getAssignees().stream()
                .map(AssigneeRef::parse).toList()).stream().map(AssigneeRef::key).toList()));
        todos.update(id, form.toInput(), todo.getVersion(), actor);
        return ActionResult.of("ToDo wurde verschoben.");
    }

    @PostMapping("/{id}/delete")
    ActionResult delete(@PathVariable Long id) {
        todos.delete(id, requireModule());
        return ActionResult.of("ToDo wurde gelöscht.");
    }

    private User requireModule() {
        User user = currentUser.require();
        if (!access.mayOpen(user.getUsername())) {
            throw new AccessDeniedException("Keine Berechtigung für ToDos");
        }
        return user;
    }
}
