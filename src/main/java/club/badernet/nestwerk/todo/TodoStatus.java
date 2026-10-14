package club.badernet.nestwerk.todo;

public enum TodoStatus {
    OPEN("Offen"),
    IN_PROGRESS("In Arbeit"),
    DONE("Erledigt"),
    DISCARDED("Verworfen");

    private final String label;

    TodoStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isClosed() {
        return this == DONE || this == DISCARDED;
    }
}
