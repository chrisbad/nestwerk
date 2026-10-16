package club.badernet.nestwerk.update;

import java.util.Map;
import java.util.Optional;

/** Woher Nestwerk von neuen Versionen erfährt und ihre Installationsdateien holt. */
public interface ReleaseSource {

    /** Die neueste Version im Kanal, falls sie neuer ist als {@code current}; wirft {@link UpdateSourceException}. */
    Optional<AvailableUpdate> latest(Channel channel, AppVersion current);

    /** Compose-Dateien und {@code update.sh} der Version {@code ref}, nach Dateiname. */
    Map<String, byte[]> files(String ref);
}
