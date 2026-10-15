package club.badernet.nestwerk.backup;

import club.badernet.nestwerk.security.AdminPermission;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sicherungen anlegen, auflisten, hochladen und wiederherstellen. Es läuft immer nur ein Vorgang zur selben Zeit;
 * die Sicherungsdateien liegen im Sicherungsverzeichnis und sind die maßgebliche Liste.
 */
@Service
public class BackupService {

    /** Eine Sicherungsdatei im Verzeichnis. */
    public record BackupFile(String name, long size, Instant modified) {
    }

    /** Ergebnis einer Wiederherstellung. */
    public record RestoreResult(Manifest manifest, String safetyCopy) {
    }

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final Pattern FILE = Pattern.compile("nestwerk-[a-z0-9-]{1,80}\\.zip");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    static final long MAX_UPLOAD = 20L * 1024 * 1024 * 1024;

    private final BackupWriter writer;
    private final RestoreService restorer;
    private final BackupRunRepository runs;
    private final UserRepository users;
    private final PermissionCatalog permissions;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final Path dir;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nestwerk-backup");
        t.setDaemon(true);
        return t;
    });

    public BackupService(BackupWriter writer, RestoreService restorer, BackupRunRepository runs,
                         UserRepository users, PermissionCatalog permissions, TransactionTemplate tx,
                         JsonMapper json, Clock clock, @Value("${nestwerk.backup.dir:./data/backups}") String dir) {
        this.writer = writer;
        this.restorer = restorer;
        this.runs = runs;
        this.users = users;
        this.permissions = permissions;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public boolean canManage(User user) {
        return user != null && permissions.effective(user).contains(AdminPermission.BACKUP_MANAGE.name());
    }

    public Path directory() {
        return dir;
    }

    public boolean isBusy() {
        return busy.get();
    }

    private void acquire() {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("Es läuft bereits eine Sicherung oder Wiederherstellung.");
        }
    }

    // --- Lesen ---------------------------------------------------------------------

    public List<BackupFile> files(User actor) {
        requireManage(actor);
        return listFiles();
    }

    List<BackupFile> listFiles() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> FILE.matcher(p.getFileName().toString()).matches() && Files.isRegularFile(p))
                    .map(p -> {
                        try {
                            return new BackupFile(p.getFileName().toString(), Files.size(p),
                                    Files.getLastModifiedTime(p).toInstant());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .sorted(Comparator.comparing(BackupFile::modified).reversed()
                            .thenComparing(BackupFile::name, Comparator.reverseOrder()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<BackupRun> recentRuns(User actor, int limit) {
        requireManage(actor);
        return runs.findAllByOrderByStartedAtDescIdDesc(PageRequest.of(0, limit));
    }

    /** Pfad einer Sicherungsdatei; nur Namen aus dem Verzeichnis sind erlaubt. */
    public Path file(String name, User actor) {
        requireManage(actor);
        return existing(name);
    }

    public Manifest manifest(String name, User actor) {
        try (BackupArchive archive = BackupArchive.open(file(name, actor), json)) {
            return archive.manifest();
        }
    }

    // --- Sichern -------------------------------------------------------------------

    /** Startet eine Sicherung im Hintergrund. */
    public void startBackup(User actor) {
        requireManage(actor);
        acquire();
        try {
            BackupRun run = newRun(BackupRun.Kind.MANUAL, actor);
            executor.submit(() -> {
                try {
                    execute(run);
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            busy.set(false);
            throw e;
        }
    }

    /** Sichert sofort im aufrufenden Thread (Zeitplan, vor Wiederherstellung oder Update). */
    public BackupRun backupNow(BackupRun.Kind kind, User actor) {
        if (actor != null) {
            requireManage(actor);
        }
        acquire();
        try {
            return execute(newRun(kind, actor));
        } finally {
            busy.set(false);
        }
    }

    private BackupRun newRun(BackupRun.Kind kind, User actor) {
        return tx.execute(s -> runs.save(new BackupRun(kind, clock.instant(), actor == null ? null : actor.getId())));
    }

    private BackupRun execute(BackupRun run) {
        String name = uniqueName("nestwerk-" + LocalDateTime.now(clock).format(STAMP));
        Path temp = null;
        try {
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, ".nestwerk-", ".tmp");
            BackupWriter.Result result;
            try (OutputStream out = Files.newOutputStream(temp)) {
                result = writer.write(out);
            }
            Path target = dir.resolve(name);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            long size = Files.size(target);
            Manifest m = result.manifest();
            String message = m.tables().size() + " Tabellen, " + m.rowCount() + " Zeilen, " + m.attachmentCount()
                    + " Anhänge" + (result.warnings().isEmpty() ? "" : ". " + String.join("; ", result.warnings()));
            BackupRun.Status status = result.warnings().isEmpty() ? BackupRun.Status.SUCCESS
                    : BackupRun.Status.WARNING;
            log.info("Sicherung {} angelegt: {}", name, message);
            return finish(run, status, name, size, message);
        } catch (IOException | RuntimeException e) {
            log.error("Sicherung fehlgeschlagen", e);
            deleteQuietly(temp);
            return finish(run, BackupRun.Status.FAILED, null, null, describe(e));
        }
    }

    private BackupRun finish(BackupRun run, BackupRun.Status status, String file, Long size, String message) {
        return tx.execute(s -> {
            BackupRun current = runs.findById(run.getId()).orElse(run);
            current.finish(status, clock.instant(), file, size, message);
            return runs.save(current);
        });
    }

    private String uniqueName(String base) {
        String name = base + ".zip";
        for (int i = 2; Files.exists(dir.resolve(name)); i++) {
            name = base + "-" + i + ".zip";
        }
        return name;
    }

    // --- Hochladen, Löschen ----------------------------------------------------------

    /** Übernimmt eine hochgeladene Sicherung ins Verzeichnis, nachdem sie vollständig geprüft wurde. */
    public String upload(InputStream content, User actor) {
        requireManage(actor);
        Path temp = null;
        try {
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, ".upload-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = content.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_UPLOAD) {
                        throw new InvalidBackupException("Die Datei ist zu groß.");
                    }
                    out.write(buffer, 0, n);
                }
            }
            try (BackupArchive archive = BackupArchive.open(temp, json)) {
                archive.verify();
            }
            String name = uniqueName("nestwerk-hochgeladen-" + LocalDateTime.now(clock).format(STAMP));
            Files.move(temp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException("Hochladen fehlgeschlagen", e);
        } finally {
            deleteQuietly(temp);
        }
    }

    public void delete(String name, User actor) {
        Path path = file(name, actor);
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- Wiederherstellen ------------------------------------------------------------

    /**
     * Stellt die genannte Sicherung wieder her. Vorher wird der aktuelle Stand gesichert; danach steht ein Eintrag
     * im (wiederhergestellten) Verlauf.
     */
    public RestoreResult restore(String name, User actor) {
        requireManage(actor);
        Path path = existing(name);
        acquire();
        Instant started = clock.instant();
        try (BackupArchive archive = BackupArchive.open(path, json)) {
            restorer.check(archive);
            BackupRun safety = execute(newRun(BackupRun.Kind.PRE_RESTORE, actor));
            if (safety.getStatus() == BackupRun.Status.FAILED) {
                throw new IllegalStateException("Die Sicherheitskopie vor dem Wiederherstellen ist fehlgeschlagen: "
                        + safety.getMessage());
            }
            restorer.restore(archive);
            Manifest m = archive.manifest();
            record(actor, started, BackupRun.Status.SUCCESS, name, "Wiederhergestellt: Stand vom " + m.createdAt()
                    + ", " + m.rowCount() + " Zeilen, " + m.attachmentCount() + " Anhänge. Sicherheitskopie: "
                    + safety.getFileName());
            return new RestoreResult(m, safety.getFileName());
        } catch (RuntimeException e) {
            log.error("Wiederherstellen fehlgeschlagen", e);
            record(actor, started, BackupRun.Status.FAILED, name, describe(e));
            throw e;
        } finally {
            busy.set(false);
        }
    }

    /** Verlaufseintrag nach dem Wiederherstellen; der Benutzer existiert im neuen Stand evtl. nicht mehr. */
    private void record(User actor, Instant started, BackupRun.Status status, String file, String message) {
        tx.executeWithoutResult(s -> {
            Long userId = actor == null ? null : users.findById(actor.getId()).map(User::getId).orElse(null);
            BackupRun run = new BackupRun(BackupRun.Kind.RESTORE, started, userId);
            run.finish(status, clock.instant(), file, null, message);
            runs.save(run);
        });
    }

    // --- Hilfen ------------------------------------------------------------------------

    private Path existing(String name) {
        if (name == null || !FILE.matcher(name).matches()) {
            throw new InvalidBackupException("Ungültiger Dateiname.");
        }
        Path path = dir.resolve(name).normalize();
        if (!path.startsWith(dir) || !Files.isRegularFile(path)) {
            throw new InvalidBackupException("Die Sicherung " + name + " gibt es nicht.");
        }
        return path;
    }

    private void requireManage(User actor) {
        if (!canManage(actor)) {
            throw new AccessDeniedException("Keine Berechtigung, Sicherungen zu verwalten");
        }
    }

    static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Aufräumen ist bestmöglich
            }
        }
    }

    Optional<BackupRun> lastSuccessfulBackup() {
        return runs.findFirstByKindInAndStatusInOrderByStartedAtDesc(
                List.of(BackupRun.Kind.MANUAL, BackupRun.Kind.SCHEDULED, BackupRun.Kind.PRE_RESTORE,
                        BackupRun.Kind.PRE_UPDATE),
                List.of(BackupRun.Status.SUCCESS, BackupRun.Status.WARNING));
    }
}
