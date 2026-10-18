package club.badernet.nestwerk.web;

import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.CompletionMode;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoHistoryEntry;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Macht Verlaufseinträge lesbar; Namen und Titel nur, wenn der Betrachter sie sehen darf. */
@Component
public class TodoHistoryFormatter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMANY);

    private final TodoRepository todos;
    private final TodoAccess access;

    public TodoHistoryFormatter(TodoRepository todos, TodoAccess access) {
        this.todos = todos;
        this.access = access;
    }

    public String text(TodoHistoryEntry e, TodoViews.Labels labels, User viewer) {
        String o = e.getOldValue();
        String n = e.getNewValue();
        return switch (e.getField()) {
            case "CREATED" -> "hat das ToDo angelegt";
            case "CREATED_FROM_SERIES" -> "Nächstes Exemplar einer Wiederholung (aus " + todo(o, viewer) + ")";
            case "SUCCESSOR" -> "Nächstes Exemplar angelegt: " + todo(n, viewer);
            case "TITLE" -> "Titel: „" + o + "“ → „" + n + "“";
            case "DESCRIPTION" -> "hat die Beschreibung geändert";
            case "STATUS" -> "Status: " + status(o) + " → " + status(n);
            case "PRIORITY" -> "Priorität: " + priority(o) + " → " + priority(n);
            case "AVAILABLE_FROM" -> "Frühestens ab: " + date(o) + " → " + date(n);
            case "DUE_ON" -> "Spätestens bis: " + date(o) + " → " + date(n);
            case "FOLLOW_UP_ON" -> "Wiedervorlage: " + date(o) + " → " + date(n);
            case "REMIND" -> "Erinnerung: " + days(o) + " → " + days(n);
            case "PRIVATE" -> "true".equals(n) ? "hat das ToDo privat gemacht" : "hat das ToDo für alle sichtbar gemacht";
            case "TAGS" -> "Schlagworte: " + dash(o) + " → " + dash(n);
            case "ASSIGNEES" -> "Zuständig: " + assignees(o, labels) + " → " + assignees(n, labels);
            case "MODE" -> "Abhaken: " + mode(o) + " → " + mode(n);
            case "RESPONSIBLE" -> "Verantwortlich: " + user(o, labels) + " → " + user(n, labels);
            case "RRULE" -> "Wiederholung: " + rule(o) + " → " + rule(n);
            case "CHECKED" -> "hat abgehakt";
            case "UNCHECKED" -> "hat das Abhaken zurückgenommen";
            case "CHILD_ADDED" -> "Unteraufgabe hinzugefügt: " + todo(n, viewer);
            case "PARENT" -> n == null ? "auf die oberste Ebene verschoben" : "untergeordnet unter " + todo(n, viewer);
            case "LINK_ADDED" -> "Verknüpfung: " + link(n, viewer);
            case "LINK_REMOVED" -> "Verknüpfung gelöst: " + link(o, viewer);
            case "WATCHER_ADDED" -> "Beobachter hinzugefügt: " + user(n, labels);
            case "WATCHER_REMOVED" -> "Beobachter entfernt: " + user(o, labels);
            default -> e.getField();
        };
    }

    private String todo(String id, User viewer) {
        if (id == null) {
            return "—";
        }
        try {
            return todos.findById(Long.valueOf(id)).filter(t -> access.can(viewer, "VIEW", t))
                    .map(Todo::getTitle).map(t -> "„" + t + "“").orElse("ein ToDo, das du nicht sehen kannst");
        } catch (NumberFormatException ex) {
            return "—";
        }
    }

    private String link(String value, User viewer) {
        if (value == null || !value.contains(":")) {
            return "—";
        }
        String[] parts = value.split(":", 2);
        String label = switch (parts[0]) {
            case "BLOCKS" -> "blockiert";
            case "BLOCKED_BY" -> "wird blockiert von";
            case "RELATES" -> "verwandt mit";
            case "DUPLICATES" -> "ist Duplikat von";
            case "DUPLICATED_BY" -> "hat Duplikat";
            default -> parts[0];
        };
        return label + " " + todo(parts[1], viewer);
    }

    private static String assignees(String keys, TodoViews.Labels labels) {
        if (keys == null || keys.isBlank()) {
            return "niemand";
        }
        List<String> names = new ArrayList<>();
        for (String key : keys.split(",")) {
            try {
                names.add(labels.assignee(AssigneeRef.parse(key)).label());
            } catch (IllegalArgumentException ex) {
                names.add("?");
            }
        }
        return String.join(", ", names);
    }

    private static String user(String id, TodoViews.Labels labels) {
        try {
            return id == null ? "—" : labels.user(Long.valueOf(id));
        } catch (NumberFormatException ex) {
            return "—";
        }
    }

    private static String status(String name) {
        try {
            return name == null ? "—" : TodoStatus.valueOf(name).label();
        } catch (IllegalArgumentException ex) {
            return name;
        }
    }

    private static String priority(String name) {
        try {
            return name == null ? "—" : TodoPriority.valueOf(name).label();
        } catch (IllegalArgumentException ex) {
            return name;
        }
    }

    private static String mode(String name) {
        try {
            return name == null ? "—" : CompletionMode.valueOf(name).label();
        } catch (IllegalArgumentException ex) {
            return name;
        }
    }

    private static String date(String iso) {
        try {
            return iso == null ? "—" : DATE.format(LocalDate.parse(iso));
        } catch (RuntimeException ex) {
            return iso;
        }
    }

    private static String days(String value) {
        return value == null ? "—" : value + " Tage vorher";
    }

    private static String rule(String rrule) {
        return rrule == null ? "keine" : TodoViews.repeatLabel(rrule);
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
