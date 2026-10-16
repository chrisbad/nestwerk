package club.badernet.nestwerk.backup;

import club.badernet.nestwerk.attachment.AttachmentStorage;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spielt eine Sicherung ein. Bei gleicher Schemaversion geschieht das in einer einzigen Transaktion. Eine ältere
 * Sicherung wird in ein frisch bis zu ihrer Version migriertes Schema geladen und dann auf den aktuellen Stand
 * migriert. Neuere Sicherungen werden abgelehnt.
 */
@Component
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);
    private static final String CHECK_SCHEMA = "nestwerk_restore_check";

    private final DataSource dataSource;
    private final DatabaseSchema schema;
    private final AttachmentStorage attachments;
    private final Flyway flyway;

    public RestoreService(DataSource dataSource, DatabaseSchema schema, AttachmentStorage attachments,
                          Flyway flyway) {
        this.dataSource = dataSource;
        this.schema = schema;
        this.attachments = attachments;
        this.flyway = flyway;
    }

    /** Prüft, ob die Sicherung zu dieser App passt, ohne etwas zu ändern. */
    public void check(BackupArchive archive) {
        archive.verify();
        if (compareToCurrent(archive.manifest()) > 0) {
            throw new InvalidBackupException("Die Sicherung stammt aus einer neueren Nestwerk-Version (Schema "
                    + archive.manifest().schemaVersion() + ", hier " + schema.schemaVersion()
                    + "). Bitte zuerst Nestwerk aktualisieren.");
        }
    }

    public void restore(BackupArchive archive) {
        restore(archive, false);
    }

    /**
     * Spielt die Sicherung immer in ein leeres Schema ein. Für den Rückfall nach einem Update: Die neuere Version
     * hat das Schema evtl. schon verändert (Tabellen, Spalten), die Sicherung stammt aus der alten.
     */
    public void replaceAll(BackupArchive archive) {
        restore(archive, true);
    }

    private void restore(BackupArchive archive, boolean rebuild) {
        check(archive);
        Manifest manifest = archive.manifest();
        Path staging = attachments.createStaging();
        try {
            for (Manifest.Entry entry : manifest.entries()) {
                if (entry.path().startsWith("attachments/")) {
                    String key = entry.path().substring("attachments/".length());
                    try (InputStream in = archive.attachment(key).orElseThrow()) {
                        attachments.writeStaged(staging, key, in);
                    }
                }
            }
            if (rebuild || compareToCurrent(manifest) < 0) {
                requireMatchingSchema(manifest);
                log.info("Datenbank wird für die Sicherung (Schema {}) neu aufgebaut", manifest.schemaVersion());
                Flyway admin = Flyway.configure().configuration(flyway.getConfiguration()).cleanDisabled(false)
                        .target(MigrationVersion.fromVersion(manifest.schemaVersion())).load();
                admin.clean();
                admin.migrate();
                load(archive);
                flyway.migrate();
            } else {
                load(archive);
            }
        } catch (IOException e) {
            attachments.discard(staging);
            throw new UncheckedIOException("Wiederherstellen fehlgeschlagen", e);
        } catch (RuntimeException e) {
            attachments.discard(staging);
            throw e;
        }
        attachments.replaceWith(staging);
        log.info("Sicherung vom {} wiederhergestellt ({} Zeilen, {} Anhänge)", manifest.createdAt(),
                manifest.rowCount(), manifest.attachmentCount());
    }

    /**
     * Prüft vor dem Leeren der Datenbank, ob Tabellen und Spalten der Sicherung zum Schema ihrer Version passen:
     * baut dieses Schema dafür in einem Hilfsschema auf und verwirft es danach. Sonst bliebe nach einem Fehler beim
     * Laden eine leere, halb migrierte Datenbank zurück.
     */
    private void requireMatchingSchema(Manifest manifest) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS " + CHECK_SCHEMA + " CASCADE");
        try {
            Flyway.configure().configuration(flyway.getConfiguration()).schemas(CHECK_SCHEMA)
                    .defaultSchema(CHECK_SCHEMA).createSchemas(true)
                    .target(MigrationVersion.fromVersion(manifest.schemaVersion())).load().migrate();
            Map<String, Set<String>> columns = new HashMap<>();
            jdbc.query("SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = ?",
                    rs -> {
                        columns.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
                    }, CHECK_SCHEMA);
            for (Manifest.Table table : manifest.tables()) {
                Set<String> known = columns.get(table.name());
                if (known == null) {
                    throw new InvalidBackupException("Die Sicherung passt nicht zu ihrer Version "
                            + manifest.schemaVersion() + ": unbekannte Tabelle " + table.name() + ".");
                }
                if (!known.containsAll(table.columns())) {
                    throw new InvalidBackupException("Die Sicherung passt nicht zu ihrer Version "
                            + manifest.schemaVersion() + ": unbekannte Spalten in " + table.name() + ".");
                }
            }
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + CHECK_SCHEMA + " CASCADE");
        }
    }

    private int compareToCurrent(Manifest manifest) {
        return MigrationVersion.fromVersion(manifest.schemaVersion())
                .compareTo(MigrationVersion.fromVersion(schema.schemaVersion()));
    }

    /** Leert alle Tabellen und lädt die Daten der Sicherung, alles in einer Transaktion. */
    private void load(BackupArchive archive) throws IOException {
        List<String> tables = schema.tablesInLoadOrder();
        Map<String, Manifest.Table> saved = archive.manifest().tables().stream()
                .collect(Collectors.toMap(Manifest.Table::name, t -> t));
        Set<String> unknown = new HashSet<>(saved.keySet());
        tables.forEach(unknown::remove);
        if (!unknown.isEmpty()) {
            throw new InvalidBackupException("Die Sicherung enthält unbekannte Tabellen: " + unknown);
        }
        Map<String, List<String>> sequences = schema.sequenceColumns();
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                CopyManager copy = connection.unwrap(PGConnection.class).getCopyAPI();
                st.execute("TRUNCATE " + String.join(", ", tables.stream().map(t -> "public." + t).toList())
                        + " RESTART IDENTITY CASCADE");
                for (String table : tables) {
                    Manifest.Table t = saved.get(table);
                    if (t == null) {
                        continue;
                    }
                    Optional<InputStream> data = archive.table(table);
                    if (data.isEmpty()) {
                        throw new InvalidBackupException("In der Sicherung fehlen die Daten von " + table + ".");
                    }
                    try (InputStream in = data.get()) {
                        copy.copyIn("COPY public." + table + " (" + DatabaseSchema.columnList(t.columns())
                                + ") FROM STDIN", in);
                    }
                }
                for (Map.Entry<String, List<String>> e : sequences.entrySet()) {
                    String table = DatabaseSchema.requireName(e.getKey());
                    for (String column : e.getValue()) {
                        DatabaseSchema.requireName(column);
                        st.execute("SELECT setval(pg_get_serial_sequence('public." + table + "', '" + column
                                + "'), COALESCE((SELECT MAX(" + column + ") FROM public." + table
                                + "), 0) + 1, false)");
                    }
                }
                connection.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new InvalidBackupException("Die Daten passen nicht zur Datenbank: " + e.getMessage());
        }
    }
}
