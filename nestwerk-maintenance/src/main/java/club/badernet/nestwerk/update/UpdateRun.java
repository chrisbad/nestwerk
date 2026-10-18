package club.badernet.nestwerk.update;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Ein Update-Lauf: Auftrag an den Updater und, sobald er fertig ist, sein Ergebnis mit Protokoll. */
@Entity
@Table(name = "update_runs")
public class UpdateRun {

    public enum Status {
        QUEUED, RUNNING, DONE, ROLLED_BACK, FAILED;

        public boolean isFinished() {
            return this == DONE || this == ROLLED_BACK || this == FAILED;
        }
    }

    private static final int MAX_MESSAGE = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, length = 40, unique = true)
    private String jobId;

    @Column(name = "from_version", nullable = false, length = 60)
    private String fromVersion;

    @Column(name = "to_version", nullable = false, length = 60)
    private String toVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 20)
    private String step;

    @Column(length = MAX_MESSAGE)
    private String message;

    @Column(columnDefinition = "TEXT")
    private String log;

    @Column(name = "backup_file", length = 200)
    private String backupFile;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected UpdateRun() {
    }

    UpdateRun(String jobId, String fromVersion, String toVersion, Channel channel, Long requestedBy,
              Instant requestedAt) {
        this.jobId = jobId;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.channel = channel;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.status = Status.QUEUED;
    }

    /** Übernimmt den Stand aus dem Auftragsverzeichnis; liefert, ob der Lauf damit gerade fertig geworden ist. */
    boolean apply(JobStatus job, String log, String backupFile, Instant now) {
        boolean wasFinished = status.isFinished();
        this.status = job.status();
        this.step = job.step();
        this.message = job.message() == null ? null
                : job.message().substring(0, Math.min(job.message().length(), MAX_MESSAGE));
        if (log != null) {
            this.log = log;
        }
        if (backupFile != null) {
            this.backupFile = backupFile;
        }
        if (!wasFinished && status.isFinished()) {
            this.finishedAt = job.updatedAt() != null ? job.updatedAt() : now;
            return true;
        }
        return false;
    }

    public Long getId() { return id; }
    public String getJobId() { return jobId; }
    public String getFromVersion() { return fromVersion; }
    public String getToVersion() { return toVersion; }
    public Channel getChannel() { return channel; }
    public Status getStatus() { return status; }
    public String getStep() { return step; }
    public String getMessage() { return message; }
    public String getLog() { return log; }
    public String getBackupFile() { return backupFile; }
    public Long getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
