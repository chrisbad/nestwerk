package club.badernet.nestwerk.school;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalTime;

/** A lesson period or break in the grid of a school (contact); all children at that school share the grid. */
@Entity
@Table(name = "school_period")
public class SchoolPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_contact_id", nullable = false)
    private Long schoolContactId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private PeriodKind kind;

    /** Number of the period (1st, 2nd …); {@code null} for breaks. */
    @Column
    private Short number;

    @Column(length = 40)
    private String label;

    @Column(name = "starts_at", nullable = false)
    private LocalTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalTime endsAt;

    protected SchoolPeriod() {
    }

    public SchoolPeriod(Long schoolContactId, PeriodKind kind, Integer number, String label, LocalTime startsAt,
                        LocalTime endsAt) {
        this.schoolContactId = schoolContactId;
        this.kind = kind;
        this.number = number == null ? null : number.shortValue();
        this.label = label;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public Long getId() {
        return id;
    }

    public Long getSchoolContactId() {
        return schoolContactId;
    }

    public PeriodKind getKind() {
        return kind;
    }

    public boolean isBreak() {
        return kind == PeriodKind.BREAK;
    }

    public Integer getNumber() {
        return number == null ? null : number.intValue();
    }

    public String getLabel() {
        return label;
    }

    /** Display name, e.g. "3. Stunde" or "Pause". */
    public String displayName() {
        if (label != null) {
            return label;
        }
        return isBreak() ? "Pause" : number + ". Stunde";
    }

    public LocalTime getStartsAt() {
        return startsAt;
    }

    public LocalTime getEndsAt() {
        return endsAt;
    }
}
