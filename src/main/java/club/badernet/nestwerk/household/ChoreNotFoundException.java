package club.badernet.nestwerk.household;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Hausarbeit oder Aufgabe existiert nicht oder ist für den Betrachter unsichtbar. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ChoreNotFoundException extends RuntimeException {

    public ChoreNotFoundException() {
        super("Hausarbeit nicht gefunden");
    }
}
