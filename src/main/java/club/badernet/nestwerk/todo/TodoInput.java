package club.badernet.nestwerk.todo;

import java.time.LocalDate;
import java.util.Set;

/**
 * Eingabe für Anlegen/Ändern eines ToDos. {@code null} bei Priorität/Modus bedeutet Standard;
 * {@code responsibleUserId == null} heißt „Ersteller“ beim Anlegen bzw. „unverändert“ beim Ändern.
 */
public record TodoInput(String title, String description, TodoPriority priority, LocalDate availableFrom,
                        LocalDate dueOn, LocalDate followUpOn, Integer remindDaysBefore, boolean privateTodo,
                        CompletionMode completionMode, Set<String> tags, Set<AssigneeRef> assignees,
                        Long responsibleUserId) {
}
