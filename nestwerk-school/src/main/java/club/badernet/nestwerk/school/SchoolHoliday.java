package club.badernet.nestwerk.school;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** Schulferien oder beweglicher Ferientag eines Bundeslands (aus der Online-Quelle oder von Hand). */
@Entity
@Table(name = "school_holiday")
public class SchoolHoliday {

    public static final String ONLINE = "ONLINE";
    public static final String MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2)
    private String state;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(name = "external_id", length = 64)
    private String externalId;

    protected SchoolHoliday() {
    }

    public SchoolHoliday(String state, String name, LocalDate startsOn, LocalDate endsOn, String source,
                         String externalId) {
        this.state = state;
        this.name = name;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.source = source;
        this.externalId = externalId;
    }

    public boolean covers(LocalDate day) {
        return !day.isBefore(startsOn) && !day.isAfter(endsOn);
    }

    public Long getId() {
        return id;
    }

    public String getState() {
        return state;
    }

    public String getName() {
        return name;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public String getSource() {
        return source;
    }

    public String getExternalId() {
        return externalId;
    }
}
