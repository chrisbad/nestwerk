package club.badernet.nestwerk.backup;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.attachment.Attachment;
import club.badernet.nestwerk.attachment.AttachmentOwner;
import club.badernet.nestwerk.attachment.AttachmentService;
import club.badernet.nestwerk.attachment.AttachmentStorage;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoCommentService;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sichert und stellt echt wieder her (ohne Test-Transaktion). Eigene Datenbank durch eigene Properties; am Ende
 * wird der Ausgangsstand wiederhergestellt.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw",
        "nestwerk.attachments.dir=target/backup-test/attachments",
        "nestwerk.backup.dir=target/backup-test/backups"})
class BackupRoundTripTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};

    @Autowired BackupService backups;
    @Autowired RestoreService restorer;
    @Autowired AttachmentService attachments;
    @Autowired AttachmentStorage storage;
    @Autowired TodoService todoService;
    @Autowired TodoCommentService comments;
    @Autowired TodoRepository todos;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    User admin;
    String baseline;

    @BeforeEach
    void setUp() throws IOException {
        Path dir = backups.directory();
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) {
                    Files.delete(p);
                }
            }
        }
        admin = users.findByUsername("chef").orElseGet(() -> userService.createAdministrator("chef", "passwort1"));
        baseline = backups.backupNow(BackupRun.Kind.MANUAL, admin).getFileName();
    }

    @AfterEach
    void tearDown() {
        User current = users.findByUsername("chef").orElseThrow();
        backups.restore(baseline, current);
    }

    private Map<String, Integer> rowCounts() {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_type = 'BASE TABLE' AND table_name NOT IN ('flyway_schema_history', 'backup_runs')",
                String.class).stream().collect(Collectors.toMap(t -> t,
                t -> jdbc.queryForObject("SELECT count(*) FROM " + t, Integer.class)));
    }

    private static TodoInput input(String title) {
        return new TodoInput(title, "Beschreibung mit\tTab und\nZeilenumbruch und Umlauten äöü", null, null, null,
                null, null, false, null, Set.of("Haus"), Set.of(), null);
    }

    @Test
    void restoresDataAttachmentsAndSequences() throws IOException {
        User mama = userService.create("mama", "passwort1", Set.of());
        Todo todo = todoService.create(input("Keller"), admin);
        comments.add(todo.getId(), "Erster Kommentar", admin);
        Attachment file = attachments.store(AttachmentOwner.todo(todo.getId()),
                new MockMultipartFile("file", "foto.png", "image/png", PNG), admin);
        Map<String, Integer> before = rowCounts();

        BackupRun run = backups.backupNow(BackupRun.Kind.MANUAL, admin);
        assertThat(run.getStatus()).isEqualTo(BackupRun.Status.SUCCESS);

        todoService.create(input("Danach angelegt"), admin);
        jdbc.update("UPDATE todos SET title = 'geändert' WHERE id = ?", todo.getId());
        storage.delete(file.getStorageKey());
        jdbc.update("DELETE FROM users WHERE id = ?", mama.getId());

        BackupService.RestoreResult result = backups.restore(run.getFileName(), admin);

        assertThat(result.safetyCopy()).isNotBlank();
        assertThat(rowCounts()).isEqualTo(before);
        assertThat(todos.findById(todo.getId())).get().extracting(Todo::getTitle).isEqualTo("Keller");
        assertThat(todos.findById(todo.getId()).orElseThrow().getDescription()).contains("\t", "\n", "äöü");
        assertThat(users.findByUsername("mama")).isPresent();
        try (InputStream in = storage.read(file.getStorageKey()).getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(PNG);
        }
        // Sequenzen stehen hinter den wiederhergestellten IDs
        Todo next = todoService.create(input("Neu nach Restore"), admin);
        assertThat(next.getId()).isGreaterThan(todo.getId());
        assertThat(backups.recentRuns(admin, 5)).first().extracting(BackupRun::getKind)
                .isEqualTo(BackupRun.Kind.RESTORE);
    }

    @Test
    void rejectsDamagedAndNewerBackups() throws IOException {
        BackupRun run = backups.backupNow(BackupRun.Kind.MANUAL, admin);
        Path original = backups.file(run.getFileName(), admin);
        todoService.create(input("Bleibt erhalten"), admin);

        Path damaged = rewrite(original, "nestwerk-kaputt.zip", (name, data) ->
                name.equals("db/todos.copy") ? (new String(data, StandardCharsets.UTF_8) + "x").getBytes(
                        StandardCharsets.UTF_8) : data);
        assertThatThrownBy(() -> backups.restore(damaged.getFileName().toString(), admin))
                .isInstanceOf(InvalidBackupException.class).hasMessageContaining("beschädigt");

        Path newer = rewrite(original, "nestwerk-neuer.zip", (name, data) -> name.equals(Manifest.NAME)
                ? new String(data, StandardCharsets.UTF_8).replaceFirst("\"schemaVersion\" : \"\\d+\"",
                "\"schemaVersion\" : \"999\"").getBytes(StandardCharsets.UTF_8) : data);
        assertThatThrownBy(() -> backups.restore(newer.getFileName().toString(), admin))
                .isInstanceOf(InvalidBackupException.class).hasMessageContaining("neueren");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM todos WHERE title = 'Bleibt erhalten'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void migratesOlderBackupToCurrentSchema() throws IOException {
        todoService.create(input("Aus alter Version"), admin);
        BackupRun run = backups.backupNow(BackupRun.Kind.MANUAL, admin);
        Path original = backups.file(run.getFileName(), admin);
        // Stand von Version 17 nachbauen: ohne die Tabelle und das Recht aus V18
        Path older = rewrite(original, "nestwerk-alt.zip", (name, data) -> {
            if (name.equals("db/permissions.copy")) {
                return new String(data, StandardCharsets.UTF_8).lines()
                        .filter(l -> !l.startsWith("BACKUP_MANAGE\t"))
                        .collect(Collectors.joining("\n", "", "\n")).getBytes(StandardCharsets.UTF_8);
            }
            return data;
        }, "db/backup_runs.copy", "17");

        backups.restore(older.getFileName().toString(), admin);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM todos WHERE title = 'Aus alter Version'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permissions WHERE key = 'BACKUP_MANAGE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history", Integer.class))
                .isGreaterThanOrEqualTo(18);
    }

    @Test
    void uploadAcceptsOnlyValidBackups() throws IOException {
        BackupRun run = backups.backupNow(BackupRun.Kind.MANUAL, admin);
        String name;
        try (InputStream in = Files.newInputStream(backups.file(run.getFileName(), admin))) {
            name = backups.upload(in, admin);
        }
        assertThat(name).startsWith("nestwerk-hochgeladen-");
        assertThat(backups.files(admin)).extracting(BackupService.BackupFile::name).contains(name);

        assertThatThrownBy(() -> backups.upload(InputStream.nullInputStream(), admin))
                .isInstanceOf(InvalidBackupException.class);
        User nobody = userService.create("niemand", "passwort1", Set.of());
        assertThatThrownBy(() -> backups.files(nobody)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> backups.file("../geheim.zip", admin)).isInstanceOf(InvalidBackupException.class);
    }

    private interface Transform {
        byte[] apply(String name, byte[] data);
    }

    private Path rewrite(Path source, String targetName, Transform transform) throws IOException {
        return rewrite(source, targetName, transform, null, null);
    }

    /**
     * Schreibt eine Sicherung um; Prüfsummen im Manifest werden nur angepasst, wenn {@code fixChecksums} gesetzt
     * ist (über eine neue Schemaversion), sonst bleibt die Datei absichtlich inkonsistent.
     */
    private Path rewrite(Path source, String targetName, Transform transform, String dropEntry, String version)
            throws IOException {
        Path target = source.resolveSibling(targetName);
        boolean fix = version != null;
        Map<String, byte[]> contents = new java.util.LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(source.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.getName().equals(dropEntry)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(e)) {
                    contents.put(e.getName(), transform.apply(e.getName(), in.readAllBytes()));
                }
            }
        }
        if (fix) {
            Manifest m = json.readValue(contents.get(Manifest.NAME), Manifest.class);
            List<Manifest.Entry> entries = m.entries().stream().filter(e -> contents.containsKey(e.path()))
                    .map(e -> new Manifest.Entry(e.path(), contents.get(e.path()).length, sha(contents.get(e.path()))))
                    .toList();
            List<Manifest.Table> tables = m.tables().stream()
                    .filter(t -> contents.containsKey(Manifest.tablePath(t.name()))).toList();
            contents.put(Manifest.NAME, json.writeValueAsBytes(new Manifest(m.format(), m.appVersion(), version,
                    m.createdAt(), tables, entries)));
        }
        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return target;
    }

    private static String sha(byte[] data) {
        return java.util.HexFormat.of().formatHex(BackupWriter.sha256().digest(data));
    }
}
