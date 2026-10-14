package club.badernet.nestwerk.household;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Die Aufgabe wurde inzwischen von jemand anderem geändert (z. B. schon abgehakt). */
@ResponseStatus(HttpStatus.CONFLICT)
public class ChoreConflictException extends RuntimeException {

    public ChoreConflictException(String message) {
        super(message);
    }
}
