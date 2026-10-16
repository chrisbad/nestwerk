package club.badernet.nestwerk.blocker;

/** Art eines Blockers; bestimmt Farbe, Symbol und Standardwerte im Formular. */
public enum BlockerKind {
    WORK("Arbeit", "arbeitet"),
    SLEEP("Schlaf", "schläft"),
    REST("Ruhe / Pause", "hat Ruhezeit"),
    LEISURE("Freizeit", "hat Freizeit"),
    CUSTOM("Eigene", "ist nicht verfügbar");

    private final String label;
    private final String verb;

    BlockerKind(String label, String verb) {
        this.label = label;
        this.verb = verb;
    }

    public String label() {
        return label;
    }

    /** Für Hinweise wie „Lena arbeitet da“. */
    public String verb() {
        return verb;
    }
}
