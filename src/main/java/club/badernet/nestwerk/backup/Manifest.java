package club.badernet.nestwerk.backup;

import java.util.List;

/**
 * Inhaltsverzeichnis einer Nestwerk-Sicherung ({@code manifest.json} im Archiv).
 *
 * @param format        Version des Archivformats
 * @param appVersion    Version der App, die gesichert hat (nur zur Information)
 * @param schemaVersion Flyway-Version der Datenbank zum Zeitpunkt der Sicherung
 * @param createdAt     Zeitpunkt (ISO-8601, UTC)
 * @param tables        gesicherte Tabellen in Ladereihenfolge
 * @param entries       alle Dateien im Archiv außer dem Manifest, mit Größe und SHA-256
 */
public record Manifest(int format, String appVersion, String schemaVersion, String createdAt, List<Table> tables,
                       List<Entry> entries) {

    public static final int FORMAT = 1;
    public static final String NAME = "manifest.json";

    public record Table(String name, List<String> columns, long rows) {
    }

    public record Entry(String path, long size, String sha256) {
    }

    public static String tablePath(String table) {
        return "db/" + table + ".copy";
    }

    public static String attachmentPath(String key) {
        return "attachments/" + key;
    }

    public long attachmentCount() {
        return entries.stream().filter(e -> e.path().startsWith("attachments/")).count();
    }

    public long rowCount() {
        return tables.stream().mapToLong(Table::rows).sum();
    }
}
