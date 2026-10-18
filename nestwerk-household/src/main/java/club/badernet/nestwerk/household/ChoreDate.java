package club.badernet.nestwerk.household;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalTime;

/** Ein fester Termin einer Hausarbeit der Art {@link ChoreKind#DATES}. */
@Entity
@Table(name = "chore_dates")
public class ChoreDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chore_id", nullable = false)
    private Long choreId;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "due_time")
    private LocalTime dueTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChoreDateSource source;

    @Column(name = "external_uid")
    private String externalUid;

    protected ChoreDate() {
    }

    public ChoreDate(Long choreId, LocalDate dueOn, LocalTime dueTime, ChoreDateSource source, String externalUid) {
        this.choreId = choreId;
        this.dueOn = dueOn;
        this.dueTime = dueTime;
        this.source = source;
        this.externalUid = externalUid;
    }

    void move(LocalDate dueOn, LocalTime dueTime) {
        this.dueOn = dueOn;
        this.dueTime = dueTime;
    }

    public String slotKey() {
        return ChoreTask.slotKey(dueOn, dueTime);
    }

    public Long getId() { return id; }
    public Long getChoreId() { return choreId; }
    public LocalDate getDueOn() { return dueOn; }
    public LocalTime getDueTime() { return dueTime; }
    public ChoreDateSource getSource() { return source; }
    public String getExternalUid() { return externalUid; }
}
