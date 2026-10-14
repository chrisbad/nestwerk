package club.badernet.nestwerk.todo;

public enum TodoPriority {
    LOW("Niedrig"),
    NORMAL("Normal"),
    HIGH("Hoch"),
    URGENT("Dringend");

    private final String label;

    TodoPriority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
