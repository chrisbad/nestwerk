package club.badernet.nestwerk.web;

import java.util.List;

/**
 * Snapping steps of the time grid. {@link #FINE} and {@link #COARSE} are the defaults the page starts with; the
 * viewer can choose any of {@link #OPTIONS} in the display menu, remembered per browser.
 */
public enum SlotSize {
    FINE(5),
    COARSE(15);

    /** Slot lengths in minutes the display menu offers. */
    public static final List<Integer> OPTIONS = List.of(5, 10, 15, 20, 30, 60);

    private final int minutes;

    SlotSize(int minutes) {
        this.minutes = minutes;
    }

    public int minutes() {
        return minutes;
    }
}
