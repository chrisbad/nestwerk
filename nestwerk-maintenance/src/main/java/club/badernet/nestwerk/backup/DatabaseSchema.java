package club.badernet.nestwerk.backup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Liest den Aufbau der Datenbank (Schema {@code public}) für Sicherung und Wiederherstellung. */
@Component
public class DatabaseSchema {

    static final String HISTORY = "flyway_schema_history";
    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]*");

    private final JdbcTemplate jdbc;

    public DatabaseSchema(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Alle Tabellen außer der Flyway-Historie, sortiert so, dass referenzierte Tabellen zuerst kommen. */
    public List<String> tablesInLoadOrder() {
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> ? "
                + "ORDER BY table_name", String.class, HISTORY);
        tables.forEach(DatabaseSchema::requireName);
        Map<String, Set<String>> parents = new HashMap<>();
        jdbc.query("SELECT c.relname AS child, p.relname AS parent FROM pg_constraint k "
                        + "JOIN pg_class c ON c.oid = k.conrelid JOIN pg_class p ON p.oid = k.confrelid "
                        + "WHERE k.contype = 'f' AND k.connamespace = 'public'::regnamespace",
                rs -> {
                    String child = rs.getString("child");
                    String parent = rs.getString("parent");
                    if (!child.equals(parent)) {
                        parents.computeIfAbsent(child, k -> new LinkedHashSet<>()).add(parent);
                    }
                });
        List<String> ordered = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String table : tables) {
            visit(table, parents, ordered, visiting, Set.copyOf(tables));
        }
        return ordered;
    }

    private static void visit(String table, Map<String, Set<String>> parents, List<String> ordered,
                              Set<String> visiting, Set<String> known) {
        if (ordered.contains(table) || !known.contains(table)) {
            return;
        }
        if (!visiting.add(table)) {
            throw new IllegalStateException("Zyklische Fremdschlüssel bei Tabelle " + table);
        }
        for (String parent : parents.getOrDefault(table, Set.of())) {
            visit(parent, parents, ordered, visiting, known);
        }
        visiting.remove(table);
        ordered.add(table);
    }

    public List<String> columns(String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position", String.class,
                requireName(table));
    }

    /** Spalten mit Sequenz (serial/identity) je Tabelle. */
    public Map<String, List<String>> sequenceColumns() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        jdbc.query("SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND (column_default LIKE 'nextval(%' OR is_identity = 'YES') ORDER BY table_name",
                rs -> {
                    result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return result;
    }

    /** Aktuelle Flyway-Version, z. B. {@code 18}. */
    public String schemaVersion() {
        List<String> versions = jdbc.queryForList("SELECT version FROM " + HISTORY
                + " WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", String.class);
        if (versions.isEmpty()) {
            throw new IllegalStateException("Keine Flyway-Version gefunden");
        }
        return versions.getFirst();
    }

    static String requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Ungültiger Tabellen- oder Spaltenname: " + name);
        }
        return name;
    }

    static String columnList(List<String> columns) {
        return String.join(", ", columns.stream().map(DatabaseSchema::requireName).toList());
    }
}
