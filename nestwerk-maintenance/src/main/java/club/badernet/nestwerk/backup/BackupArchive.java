package club.badernet.nestwerk.backup;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Eine geöffnete Sicherung zum Lesen. {@link #verify()} prüft Größe und Prüfsumme jeder Datei. */
public final class BackupArchive implements Closeable {

    private static final Pattern ATTACHMENT = Pattern.compile("attachments/[a-z0-9]{32}");

    private final ZipFile zip;
    private final Manifest manifest;

    private BackupArchive(ZipFile zip, Manifest manifest) {
        this.zip = zip;
        this.manifest = manifest;
    }

    /** Öffnet die Datei und liest das Manifest; wirft {@link InvalidBackupException}, wenn es keine Sicherung ist. */
    public static BackupArchive open(Path file, JsonMapper json) {
        ZipFile zip;
        try {
            zip = new ZipFile(file.toFile());
        } catch (ZipException e) {
            throw new InvalidBackupException("Die Datei ist keine Nestwerk-Sicherung.");
        } catch (IOException e) {
            throw new InvalidBackupException("Die Sicherung konnte nicht geöffnet werden: " + e.getMessage());
        }
        try {
            ZipEntry entry = zip.getEntry(Manifest.NAME);
            if (entry == null) {
                throw new InvalidBackupException("Die Datei ist keine Nestwerk-Sicherung (Manifest fehlt).");
            }
            Manifest manifest;
            try (InputStream in = zip.getInputStream(entry)) {
                manifest = json.readValue(in, Manifest.class);
            } catch (JacksonException e) {
                throw new InvalidBackupException("Das Manifest der Sicherung ist beschädigt.");
            }
            if (manifest.format() != Manifest.FORMAT) {
                throw new InvalidBackupException("Unbekanntes Sicherungsformat " + manifest.format() + ".");
            }
            for (Manifest.Table table : manifest.tables()) {
                DatabaseSchema.requireName(table.name());
                table.columns().forEach(DatabaseSchema::requireName);
            }
            for (Manifest.Entry e : manifest.entries()) {
                if (!e.path().startsWith("db/") && !ATTACHMENT.matcher(e.path()).matches()) {
                    throw new InvalidBackupException("Unerwartete Datei in der Sicherung: " + e.path());
                }
            }
            return new BackupArchive(zip, manifest);
        } catch (IOException | RuntimeException e) {
            closeQuietly(zip);
            if (e instanceof InvalidBackupException invalid) {
                throw invalid;
            }
            throw new InvalidBackupException("Die Sicherung konnte nicht gelesen werden: " + e.getMessage());
        }
    }

    public Manifest manifest() {
        return manifest;
    }

    /** Prüft jede im Manifest genannte Datei auf Größe und SHA-256. */
    public void verify() {
        byte[] buffer = new byte[64 * 1024];
        for (Manifest.Entry e : manifest.entries()) {
            ZipEntry entry = zip.getEntry(e.path());
            if (entry == null) {
                throw new InvalidBackupException("In der Sicherung fehlt " + e.path() + ".");
            }
            MessageDigest digest = BackupWriter.sha256();
            long size = 0;
            try (InputStream in = zip.getInputStream(entry)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, n);
                    size += n;
                }
            } catch (IOException ex) {
                throw new InvalidBackupException("Die Sicherung ist beschädigt (" + e.path() + ").");
            }
            if (size != e.size() || !HexFormat.of().formatHex(digest.digest()).equals(e.sha256())) {
                throw new InvalidBackupException("Die Sicherung ist beschädigt (Prüfsumme von " + e.path() + ").");
            }
        }
    }

    public Optional<InputStream> table(String name) throws IOException {
        return open(Manifest.tablePath(name));
    }

    public Optional<InputStream> attachment(String key) throws IOException {
        return open(Manifest.attachmentPath(key));
    }

    private Optional<InputStream> open(String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        return entry == null ? Optional.empty() : Optional.of(zip.getInputStream(entry));
    }

    @Override
    public void close() {
        closeQuietly(zip);
    }

    private static void closeQuietly(ZipFile zip) {
        try {
            zip.close();
        } catch (IOException ignored) {
            // nichts mehr zu tun
        }
    }
}
