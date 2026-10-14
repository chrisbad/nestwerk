package club.badernet.nestwerk.todo;

import java.time.LocalDate;

/** Zeitlage eines ToDos; abgeleitet aus Datum und Status, nie gespeichert. Reihenfolge = Listenreihenfolge. */
public enum TodoTiming {
    OVERDUE("Überfällig"),
    DUE_SOON("Bald fällig"),
    CURRENT("Aktuell"),
    DORMANT("Ruhend"),
    NO_DATE("Ohne Datum"),
    CLOSED("Abgeschlossen");

    /** „Bald fällig“ heißt: spätestens in so vielen Tagen. */
    public static final int SOON_DAYS = 7;

    private final String label;

    TodoTiming(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static TodoTiming of(Todo todo, LocalDate today) {
        if (todo.isClosed()) {
            return CLOSED;
        }
        if (todo.getAvailableFrom() != null && todo.getAvailableFrom().isAfter(today)) {
            return DORMANT;
        }
        if (todo.getDueOn() != null) {
            if (todo.getDueOn().isBefore(today)) {
                return OVERDUE;
            }
            if (!todo.getDueOn().isAfter(today.plusDays(SOON_DAYS))) {
                return DUE_SOON;
            }
            return CURRENT;
        }
        return todo.getAvailableFrom() != null ? CURRENT : NO_DATE;
    }
}
