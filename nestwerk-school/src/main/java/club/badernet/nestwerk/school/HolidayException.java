package club.badernet.nestwerk.school;

/** Die Ferienquelle war nicht erreichbar oder lieferte etwas Unbrauchbares. */
public class HolidayException extends RuntimeException {

    public HolidayException(String message, Throwable cause) {
        super(message, cause);
    }
}
