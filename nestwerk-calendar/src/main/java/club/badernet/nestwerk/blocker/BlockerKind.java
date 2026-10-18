package club.badernet.nestwerk.blocker;

/** Art eines Blockers; bestimmt Farbe, Symbol und Standardwerte im Formular. */
public enum BlockerKind {
    WORK("Arbeit", "arbeitet da"),
    SLEEP("Schlaf", "schläft da"),
    REST("Ruhe / Pause", "hat da Ruhezeit"),
    LEISURE("Freizeit", "hat da Freizeit"),
    SCHOOL("Schule", "ist in der Schule"),
    CUSTOM("Eigene", "ist da nicht verfügbar");

    private final String label;
    private final String verb;

    BlockerKind(String label, String verb) {
        this.label = label;
        this.verb = verb;
    }

    public String label() {
        return label;
    }

    /** Für Hinweise wie „Lena arbeitet da (…)“. */
    public String verb() {
        return verb;
    }
}
