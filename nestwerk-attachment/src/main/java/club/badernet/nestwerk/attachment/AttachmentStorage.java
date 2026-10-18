package club.badernet.nestwerk.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Dateiablage für Anhänge; Dateinamen sind ausschließlich zufällige Schlüssel. */
@Component
public class AttachmentStorage {

    private static final Pattern KEY = Pattern.compile("[a-z0-9]{32}");

    private final Path root;

    public AttachmentStorage(@Value("${nestwerk.attachments.dir:./data/attachments}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public void write(String key, InputStream content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(root);
            Path temp = Files.createTempFile(root, "upload-", ".tmp");
            try {
                Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Anhang konnte nicht gespeichert werden", e);
        }
    }

    public Resource read(String key) {
        return new FileSystemResource(resolve(key));
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Schlüssel aller abgelegten Dateien mit Änderungszeit. */
    public List<StoredFile> list() {
        List<StoredFile> files = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (KEY.matcher(name).matches()) {
                    try {
                        files.add(new StoredFile(name, Files.getLastModifiedTime(p)));
                    } catch (IOException ignored) {
                        // Datei ist inzwischen weg
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    public record StoredFile(String key, FileTime modified) {
    }

    /** Die Datei zum Schlüssel, falls sie existiert (für die Sicherung). */
    public Optional<Path> find(String key) {
        Path path = resolve(key);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /**
     * Arbeitsverzeichnis für eine Wiederherstellung. Es liegt innerhalb der Ablage, damit das spätere Verschieben
     * im selben Dateisystem bleibt (die Ablage selbst ist meist ein Volume und lässt sich nicht umbenennen).
     */
    public Path createStaging() {
        try {
            Files.createDirectories(root);
            return Files.createTempDirectory(root, ".restore-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeStaged(Path staging, String key, InputStream content) {
        Path target = staging.resolve(resolve(key).getFileName());
        try {
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Anhang konnte nicht wiederhergestellt werden", e);
        }
    }

    /** Ersetzt alle abgelegten Dateien durch die aus dem Arbeitsverzeichnis und entfernt dieses. */
    public void replaceWith(Path staging) {
        try {
            for (StoredFile file : list()) {
                Files.deleteIfExists(resolve(file.key()));
            }
            try (Stream<Path> stream = Files.list(staging)) {
                for (Path p : stream.toList()) {
                    Files.move(p, resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.deleteIfExists(staging);
        } catch (IOException e) {
            throw new UncheckedIOException("Anhänge konnten nicht ersetzt werden", e);
        }
    }

    public void discard(Path staging) {
        try (Stream<Path> stream = Files.list(staging)) {
            for (Path p : stream.toList()) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(staging);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Ungültiger Speicherschlüssel");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Ungültiger Speicherschlüssel");
        }
        return path;
    }
}
