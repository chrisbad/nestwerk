package club.badernet.nestwerk.school;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** A school year of a child with school and class; subjects, timetable and grades belong to it. */
@Entity
@Table(name = "school_year")
public class SchoolYear {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Column(nullable = false, length = 30)
    private String label;

    /** Contact of the school (office); {@code null} while no school is assigned. */
    @Column(name = "school_contact_id")
    private Long schoolContactId;

    @Column(name = "class_name", nullable = false, length = 30)
    private String className;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(nullable = false)
    private boolean active;

    /** Monday of an A week; {@code null} = the timetable has no A/B weeks. */
    @Column(name = "week_a_start")
    private LocalDate weekAStart;

    protected SchoolYear() {
    }

    public SchoolYear(Long profileId, String label, Long schoolContactId, String className, LocalDate startsOn,
                      LocalDate endsOn) {
        this.profileId = profileId;
        change(label, schoolContactId, className, startsOn, endsOn);
    }

    public void change(String label, Long schoolContactId, String className, LocalDate startsOn, LocalDate endsOn) {
        this.label = label;
        this.schoolContactId = schoolContactId;
        this.className = className;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }

    public void changeWeekAStart(LocalDate weekAStart) {
        this.weekAStart = weekAStart;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public Long getProfileId() {
        return profileId;
    }

    public String getLabel() {
        return label;
    }

    public Long getSchoolContactId() {
        return schoolContactId;
    }

    public String getClassName() {
        return className;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public LocalDate getWeekAStart() {
        return weekAStart;
    }

    public boolean isActive() {
        return active;
    }
}
