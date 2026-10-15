package club.badernet.nestwerk.backup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Ein Sicherungs- oder Wiederherstellungslauf für den Verlauf. */
@Entity
@Table(name = "backup_runs")
public class BackupRun {

    public enum Kind {
        MANUAL("von Hand"), SCHEDULED("geplant"), PRE_RESTORE("vor dem Wiederherstellen"),
        PRE_UPDATE("vor einem Update"), RESTORE("Wiederherstellung");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Status { RUNNING, SUCCESS, WARNING, FAILED }

    private static final int MAX_MESSAGE = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "file_name", length = 200)
    private String fileName;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = MAX_MESSAGE)
    private String message;

    @Column(name = "started_by")
    private Long startedBy;

    protected BackupRun() {
    }

    BackupRun(Kind kind, Instant startedAt, Long startedBy) {
        this.kind = kind;
        this.status = Status.RUNNING;
        this.startedAt = startedAt;
        this.startedBy = startedBy;
    }

    void finish(Status status, Instant at, String fileName, Long sizeBytes, String message) {
        this.status = status;
        this.finishedAt = at;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.message = message == null || message.length() <= MAX_MESSAGE ? message
                : message.substring(0, MAX_MESSAGE - 1) + "…";
    }

    public Long getId() { return id; }
    public Kind getKind() { return kind; }
    public Status getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getFileName() { return fileName; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getMessage() { return message; }
    public Long getStartedBy() { return startedBy; }
}
