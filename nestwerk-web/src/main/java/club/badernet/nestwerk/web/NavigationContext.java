package club.badernet.nestwerk.web;

/**
 * Where the user came from: the validated {@code back} parameter of the request and a label for it. Pages offer a way
 * back to this origin and forms hand it on, so that "Fertig" always ends where the excursion started.
 *
 * @param back  local path of the origin, {@code null} if the page was opened directly
 * @param label German name of the origin, e.g. "Kalender"
 */
public record NavigationContext(String back, String label) {

    public boolean hasOrigin() {
        return back != null;
    }
}
