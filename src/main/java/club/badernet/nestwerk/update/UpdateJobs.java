package club.badernet.nestwerk.update;

import club.badernet.nestwerk.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aufträge an den Updater-Container über ein gemeinsames Verzeichnis: die App schreibt
 * {@code jobs/<id>/request.json}, die Dateien der Zielversion und zuletzt {@code ready}; der Updater antwortet mit
 * {@code status.json}, {@code log.txt} und {@code backup}. Er meldet sich über {@code updater.alive}.
 */
@Component
public class UpdateJobs {

    private static final Logger log = LoggerFactory.getLogger(UpdateJobs.class);
    private static final Duration ALIVE = Duration.ofMinutes(2);
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final Path dir;
    private final ReleaseSource source;
    private final UpdateRunRepository runs;
    private final UpdateNotifier notifier;
    private final UpdateCheck check;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final SecureRandom random = new SecureRandom();

    public UpdateJobs(@Value("${nestwerk.update.dir:/data/updater}") Path dir, ReleaseSource source,
                      UpdateRunRepository runs, UpdateNotifier notifier, UpdateCheck check, Clock clock) {
        this.dir = dir.toAbsolutePath().normalize();
        this.source = source;
        this.runs = runs;
        this.notifier = notifier;
        this.check = check;
        this.clock = clock;
    }

    public boolean updaterInstalled() {
        try {
            Instant beat = Files.getLastModifiedTime(dir.resolve("updater.alive")).toInstant();
            return beat.isAfter(clock.instant().minus(ALIVE));
        } catch (IOException e) {
            return false;
        }
    }

    public Optional<UpdateRun> open() {
        return runs.findByStatusIn(EnumSet.of(UpdateRun.Status.QUEUED, UpdateRun.Status.RUNNING)).stream()
                .findFirst();
    }

    public List<UpdateRun> recent(int limit) {
        return runs.findAllByOrderByRequestedAtDescIdDesc(PageRequest.of(0, limit));
    }

    @Transactional
    public UpdateRun start(AvailableUpdate update, Channel channel, User actor) {
        if (open().isPresent()) {
            throw new IllegalStateException("Es läuft bereits ein Update.");
        }
        Map<String, byte[]> files = source.files(update.ref());
        Instant now = clock.instant();
        String id = ID.format(now) + "-" + suffix();
        Path job = dir.resolve("jobs").resolve(id);
        try {
            Files.createDirectories(job.resolve("files"));
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Files.write(job.resolve("files").resolve(file.getKey()), file.getValue());
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("id", id);
            request.put("fromVersion", check.current().raw());
            request.put("toVersion", update.version());
            request.put("imageTag", update.imageTag());
            request.put("channel", channel.name());
            request.put("requestedBy", actor.getUsername());
            request.put("requestedAt", now.toString());
            Files.writeString(job.resolve("request.json"), json.writeValueAsString(request), StandardCharsets.UTF_8);
            Files.writeString(job.resolve("ready"), "");
        } catch (IOException e) {
            throw new UncheckedIOException("Der Auftrag für den Updater ließ sich nicht anlegen", e);
        }
        return runs.save(new UpdateRun(id, check.current().raw(), update.version(), channel, actor.getId(), now));
    }

    /** Stand des Auftrags; fehlt {@code status.json} oder ist sie unvollständig, ist er unbekannt. */
    public Optional<JobStatus> status(String jobId) {
        Path file = jobDir(jobId).resolve("status.json");
        try {
            JsonNode node = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
            UpdateRun.Status status = UpdateRun.Status.valueOf(node.path("status").asString());
            String updated = node.path("updatedAt").asString("");
            return Optional.of(new JobStatus(status, text(node, "step"), text(node, "message"),
                    updated.isEmpty() ? null : Instant.parse(updated)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            log.debug("Status von {} nicht lesbar: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Übernimmt den Stand offener Aufträge in den Verlauf und meldet fertige Läufe. */
    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT10S")
    @Transactional
    public void sync() {
        for (UpdateRun run : runs.findByStatusIn(EnumSet.of(UpdateRun.Status.QUEUED, UpdateRun.Status.RUNNING))) {
            Optional<JobStatus> status = status(run.getJobId());
            if (status.isEmpty()) {
                continue;
            }
            Path job = jobDir(run.getJobId());
            boolean finished = run.apply(status.get(), read(job.resolve("log.txt")), read(job.resolve("backup")),
                    clock.instant());
            if (finished) {
                notifier.finished(run);
            }
        }
    }

    public Optional<String> log(String jobId) {
        return Optional.ofNullable(read(jobDir(jobId).resolve("log.txt")));
    }

    private Path jobDir(String jobId) {
        if (jobId == null || !jobId.matches("\\d{8}-\\d{6}-[a-z0-9]{4}")) {
            throw new IllegalArgumentException("Ungültiger Auftrag.");
        }
        return dir.resolve("jobs").resolve(jobId);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asString("");
        return value.isEmpty() ? null : value;
    }

    private String suffix() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            s.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return s.toString();
    }
}
