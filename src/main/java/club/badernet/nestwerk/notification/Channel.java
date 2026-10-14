package club.badernet.nestwerk.notification;

/** Zusätzliche Zustellwege; In-App ist immer an. */
public enum Channel {
    EMAIL("E-Mail"),
    PUSH("Push");

    private final String label;

    Channel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
