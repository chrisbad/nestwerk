package club.badernet.nestwerk.todo;

/**
 * Filter für Liste, Board und Baum. {@code null} heißt „egal“. Abgeschlossene ToDos sind ausgeblendet,
 * solange weder {@code includeClosed} gesetzt noch nach einem abgeschlossenen Status gefiltert wird.
 */
public record TodoFilter(Involvement involvement, AssigneeRef assignee, TodoStatus status, TodoTiming timing,
                         TodoPriority priority, String tag, String text, boolean includeClosed) {

    public enum Involvement {
        ALL("Alle ToDos"),
        MINE("Meine ToDos"),
        RESPONSIBLE("Von mir verantwortet"),
        WATCHING("Von mir beobachtet");

        private final String label;

        Involvement(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static TodoFilter defaults() {
        return new TodoFilter(Involvement.ALL, null, null, null, null, null, null, false);
    }

    public TodoFilter withInvolvement(Involvement value) {
        return new TodoFilter(value, assignee, status, timing, priority, tag, text, includeClosed);
    }

    public TodoFilter withAssignee(AssigneeRef value) {
        return new TodoFilter(involvement, value, status, timing, priority, tag, text, includeClosed);
    }

    public TodoFilter withStatus(TodoStatus value) {
        return new TodoFilter(involvement, assignee, value, timing, priority, tag, text, includeClosed);
    }

    public TodoFilter withTiming(TodoTiming value) {
        return new TodoFilter(involvement, assignee, status, value, priority, tag, text, includeClosed);
    }

    public TodoFilter withPriority(TodoPriority value) {
        return new TodoFilter(involvement, assignee, status, timing, value, tag, text, includeClosed);
    }

    public TodoFilter withTag(String value) {
        return new TodoFilter(involvement, assignee, status, timing, priority, value, text, includeClosed);
    }

    public TodoFilter withText(String value) {
        return new TodoFilter(involvement, assignee, status, timing, priority, tag, value, includeClosed);
    }

    public TodoFilter withIncludeClosed(boolean value) {
        return new TodoFilter(involvement, assignee, status, timing, priority, tag, text, value);
    }

    public boolean showsClosed() {
        return includeClosed || (status != null && status.isClosed()) || timing == TodoTiming.CLOSED;
    }

    /** Ist irgendein Filter außer den Standardwerten gesetzt? */
    public boolean isActive() {
        return !equals(defaults());
    }
}
