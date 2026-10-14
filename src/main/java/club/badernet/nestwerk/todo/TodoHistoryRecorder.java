package club.badernet.nestwerk.todo;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Schreibt den Verlauf. Gespeichert werden Rohwerte (Status-Namen, ISO-Daten, IDs, Schlüssel wie
 * {@code member:5}); lesbar gemacht wird erst bei der Anzeige, damit Namen nur sieht, wer sie sehen darf.
 */
@Component
public class TodoHistoryRecorder {

    private final TodoHistoryRepository history;

    public TodoHistoryRecorder(TodoHistoryRepository history) {
        this.history = history;
    }

    public void record(Long todoId, Long actorId, String field, String oldValue, String newValue) {
        history.save(new TodoHistoryEntry(todoId, actorId, field, oldValue, newValue));
    }

    /** Vergleichbarer Stand der änderbaren Felder. */
    public static Map<String, String> snapshot(Todo t) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("TITLE", t.getTitle());
        s.put("DESCRIPTION", t.getDescription() == null ? null : Integer.toHexString(t.getDescription().hashCode()));
        s.put("PRIORITY", t.getPriority().name());
        s.put("AVAILABLE_FROM", str(t.getAvailableFrom()));
        s.put("DUE_ON", str(t.getDueOn()));
        s.put("FOLLOW_UP_ON", str(t.getFollowUpOn()));
        s.put("REMIND", str(t.getRemindDaysBefore()));
        s.put("PRIVATE", String.valueOf(t.isPrivateTodo()));
        s.put("TAGS", t.getTags().isEmpty() ? null : t.getTags().stream().sorted().collect(Collectors.joining(", ")));
        s.put("ASSIGNEES", t.assigneeRefs().isEmpty() ? null
                : t.assigneeRefs().stream().map(AssigneeRef::key).sorted().collect(Collectors.joining(",")));
        s.put("MODE", t.getCompletionMode().name());
        s.put("RESPONSIBLE", str(t.getResponsibleUserId()));
        s.put("RRULE", t.getRrule());
        return s;
    }

    /** Schreibt einen Eintrag je geändertem Feld; die Beschreibung ohne Werte („geändert“). */
    public void recordChanges(Todo todo, Long actorId, Map<String, String> before) {
        Map<String, String> after = snapshot(todo);
        for (Map.Entry<String, String> e : before.entrySet()) {
            String now = after.get(e.getKey());
            if (!Objects.equals(e.getValue(), now)) {
                boolean description = e.getKey().equals("DESCRIPTION");
                record(todo.getId(), actorId, e.getKey(), description ? null : e.getValue(), description ? null : now);
            }
        }
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
