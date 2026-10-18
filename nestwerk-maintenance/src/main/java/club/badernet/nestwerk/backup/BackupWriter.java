package club.badernet.nestwerk.backup;

import club.badernet.nestwerk.attachment.AttachmentStorage;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Schreibt eine vollständige Sicherung: alle Tabellen per {@code COPY} aus einem einheitlichen Datenbankstand
 * (REPEATABLE READ) und genau die Anhänge, auf die dieser Stand verweist.
 */
@Component
public class BackupWriter {

    /** Ergebnis: Manifest und Hinweise (z. B. fehlende Anhangsdateien). */
    public record Result(Manifest manifest, List<String> warnings) {
    }

    private static final Logger log = LoggerFactory.getLogger(BackupWriter.class);

    private final DataSource dataSource;
    private final DatabaseSchema schema;
    private final AttachmentStorage attachments;
    private final JsonMapper json;
    private final Clock clock;
    private final String appVersion;

    public BackupWriter(DataSource dataSource, DatabaseSchema schema, AttachmentStorage attachments, JsonMapper json,
                        Clock clock, ObjectProvider<BuildProperties> build) {
        this.dataSource = dataSource;
        this.schema = schema;
        this.attachments = attachments;
        this.json = json;
        this.clock = clock;
        this.appVersion = Optional.ofNullable(build.getIfAvailable()).map(BuildProperties::getVersion).orElse("dev");
    }

    public Result write(OutputStream target) throws IOException {
        List<Manifest.Table> tables = new ArrayList<>();
        List<Manifest.Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String version;
        ZipOutputStream zip = new ZipOutputStream(target);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try {
                CopyManager copy = connection.unwrap(PGConnection.class).getCopyAPI();
                // Die Abfragen über JdbcTemplate liefen auf einer anderen Verbindung; Schema-Infos sind stabil
                version = schema.schemaVersion();
                for (String table : schema.tablesInLoadOrder()) {
                    List<String> columns = schema.columns(table);
                    long[] rows = new long[1];
                    entries.add(entry(zip, Manifest.tablePath(table), out -> {
                        try {
                            rows[0] = copy.copyOut("COPY public." + table + " (" + DatabaseSchema.columnList(columns)
                                    + ") TO STDOUT", out);
                        } catch (SQLException e) {
                            throw new IOException("Tabelle " + table + " konnte nicht gesichert werden", e);
                        }
                    }));
                    tables.add(new Manifest.Table(table, columns, rows[0]));
                }
                for (String key : storageKeys(connection)) {
                    Optional<Path> file = attachments.find(key);
                    if (file.isEmpty()) {
                        warnings.add("Anhangsdatei fehlt: " + key);
                        continue;
                    }
                    entries.add(entry(zip, Manifest.attachmentPath(key), out -> Files.copy(file.get(), out)));
                }
            } finally {
                connection.rollback();
                connection.setReadOnly(false);
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IOException("Datenbank konnte nicht gelesen werden", e);
        }
        Manifest manifest = new Manifest(Manifest.FORMAT, appVersion, version, clock.instant().toString(), tables,
                entries);
        zip.putNextEntry(new ZipEntry(Manifest.NAME));
        zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        zip.closeEntry();
        zip.finish();
        warnings.forEach(w -> log.warn("Sicherung: {}", w));
        return new Result(manifest, warnings);
    }

    private static List<String> storageKeys(Connection connection) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (PreparedStatement st = connection.prepareStatement("SELECT storage_key FROM attachments ORDER BY id");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
        }
        return keys;
    }

    private interface EntryWriter {
        void write(OutputStream out) throws IOException;
    }

    private static Manifest.Entry entry(ZipOutputStream zip, String path, EntryWriter writer) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        MessageDigest digest = sha256();
        CountingStream counter = new CountingStream(zip);
        writer.write(new DigestOutputStream(counter, digest));
        zip.closeEntry();
        return new Manifest.Entry(path, counter.count, HexFormat.of().formatHex(digest.digest()));
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Zählt Bytes und schließt den Zip-Strom nicht. */
    private static final class CountingStream extends FilterOutputStream {
        long count;

        CountingStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
