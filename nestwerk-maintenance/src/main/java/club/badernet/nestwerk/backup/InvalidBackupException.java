package club.badernet.nestwerk.backup;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Die Datei ist keine gültige oder eine beschädigte bzw. unpassende Sicherung. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidBackupException extends RuntimeException {

    public InvalidBackupException(String message) {
        super(message);
    }
}
