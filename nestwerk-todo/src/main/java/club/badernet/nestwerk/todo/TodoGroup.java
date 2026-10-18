package club.badernet.nestwerk.todo;

import java.util.List;

/** ToDos einer Zeitlage, bereits sortiert. */
public record TodoGroup(TodoTiming timing, List<Todo> todos) {
}
