package club.badernet.nestwerk.backup.target;

import club.badernet.nestwerk.backup.Retention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ein eingebundenes Verzeichnis, z. B. eine externe Platte. Geschrieben wird nur, wenn dort die Markierungsdatei
 * liegt: Ist die Platte nicht eingehängt, landet die Kopie so nicht unbemerkt auf der Systemplatte.
 */
public class DirectoryTarget implements BackupTarget {

    public static final String MARKER = ".nestwerk-backup-target";
    static final Pattern NAME = Pattern.compile("nestwerk-[a-z0-9-]{1,80}\\.zip(\\.enc)?");

    private final Path dir;

    public DirectoryTarget(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    @Override
    public String label() {
        return "Externe Platte";
    }

    public Path directory() {
        return dir;
    }

    public boolean isPrepared() {
        return Files.isRegularFile(dir.resolve(MARKER));
    }

    /** Legt die Markierung an; das Verzeichnis muss existieren und beschreibbar sein. */
    public void prepare() throws IOException {
        if (!Files.isDirectory(dir) || !Files.isWritable(dir)) {
            throw new IOException("Das Verzeichnis " + dir + " ist nicht vorhanden oder nicht beschreibbar.");
        }
        Files.writeString(dir.resolve(MARKER), "Nestwerk legt hier Sicherungen ab.\n");
    }

    private void requirePrepared() throws IOException {
        if (!isPrepared()) {
            throw new IOException("Die externe Platte ist nicht eingehängt oder nicht eingerichtet (" + dir + ").");
        }
    }

    @Override
    public void upload(Path file, String name) throws IOException {
        requirePrepared();
        requireName(name);
        Path temp = dir.resolve("." + name + ".part");
        Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(temp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public List<Retention.Item> list() throws IOException {
        requirePrepared();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> NAME.matcher(p.getFileName().toString()).matches())
                    .map(p -> {
                        try {
                            return new Retention.Item(p.getFileName().toString(),
                                    Files.getLastModifiedTime(p).toInstant());
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).toList();
        }
    }

    @Override
    public void delete(String name) throws IOException {
        requirePrepared();
        Files.deleteIfExists(dir.resolve(requireName(name)));
    }

    @Override
    public String test() throws IOException {
        requirePrepared();
        Path probe = Files.createTempFile(dir, ".nestwerk-test-", ".tmp");
        Files.delete(probe);
        long free = Files.getFileStore(dir).getUsableSpace();
        return "Beschreibbar, " + (free / (1024 * 1024 * 1024)) + " GB frei.";
    }

    static String requireName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Ungültiger Dateiname: " + name);
        }
        return name;
    }
}
