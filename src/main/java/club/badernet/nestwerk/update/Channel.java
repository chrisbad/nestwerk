package club.badernet.nestwerk.update;

/** Woher neue Versionen kommen: getaggte Releases oder jeder Stand von main. */
public enum Channel {
    RELEASES("Releases"),
    MAIN("main (jeder neue Stand)");

    private final String label;

    Channel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
