package club.badernet.nestwerk.calendar;

/**
 * A chore or an event would overlap something that must not be overlapped. As {@link IllegalArgumentException} so
 * that forms and interfaces show the reason like other input errors.
 */
public class OverlapException extends IllegalArgumentException {

    public OverlapException(String message) {
        super(message);
    }
}
