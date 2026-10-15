package club.badernet.nestwerk.backup.target;

import club.badernet.nestwerk.backup.Retention;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Ein Ort außerhalb des Sicherungsverzeichnisses, an den Kopien der Sicherungen gehen. */
public interface BackupTarget {

    /** Kurzer Name für Verlauf und Meldungen, z. B. „Nextcloud“. */
    String label();

    /** Legt die Datei unter dem Namen ab; eine halbe Übertragung darf nicht unter dem Endnamen sichtbar werden. */
    void upload(Path file, String name) throws IOException;

    /** Sicherungen am Ziel (nur Dateien {@code nestwerk-*}). */
    List<Retention.Item> list() throws IOException;

    void delete(String name) throws IOException;

    /** Prüft die Verbindung und liefert eine kurze Erfolgsmeldung. */
    String test() throws IOException;
}
