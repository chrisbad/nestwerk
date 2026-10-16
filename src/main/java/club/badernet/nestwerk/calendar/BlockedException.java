package club.badernet.nestwerk.calendar;

/**
 * Eine betroffene Person ist zu der Zeit durch einen Blocker gesperrt. Als {@link IllegalArgumentException}, damit
 * Formulare und Schnittstellen den Grund wie andere Eingabefehler anzeigen.
 */
public class BlockedException extends IllegalArgumentException {

    public BlockedException(String message) {
        super(message);
    }
}
