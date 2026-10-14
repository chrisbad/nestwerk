package club.badernet.nestwerk.todo;

/** Ob ein Zuständiger reicht oder jeder Zuständige selbst abhaken muss. */
public enum CompletionMode {
    ANY("Einer reicht"),
    EACH("Jeder einzeln");

    private final String label;

    CompletionMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
