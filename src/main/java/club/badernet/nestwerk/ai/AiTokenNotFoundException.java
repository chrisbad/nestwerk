package club.badernet.nestwerk.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Zugangstoken existiert nicht oder gehört einem anderen Benutzer. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AiTokenNotFoundException extends RuntimeException {

    public AiTokenNotFoundException() {
        super("KI-Zugang nicht gefunden");
    }
}
