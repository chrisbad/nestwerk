package club.badernet.nestwerk.web;

/** Snapping steps of the time grid: fine (5 minutes) and coarse (15 minutes). */
public enum SlotSize {
    FINE(5),
    COARSE(15);

    private final int minutes;

    SlotSize(int minutes) {
        this.minutes = minutes;
    }

    public int minutes() {
        return minutes;
    }
}
