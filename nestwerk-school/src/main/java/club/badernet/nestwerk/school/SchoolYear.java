package club.badernet.nestwerk.school;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** Ein Schuljahr eines Kindes mit Schule und Klasse; Fächer, Stundenplan und Noten hängen daran. */
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

    @Column(nullable = false, length = 150)
    private String school;

    @Column(name = "class_name", nullable = false, length = 30)
    private String className;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(nullable = false)
    private boolean active;

    /** Montag einer A-Woche; leer = der Stundenplan kennt keine A/B-Wochen. */
    @Column(name = "week_a_start")
    private LocalDate weekAStart;

    protected SchoolYear() {
    }

    public SchoolYear(Long profileId, String label, String school, String className, LocalDate startsOn,
                      LocalDate endsOn) {
        this.profileId = profileId;
        change(label, school, className, startsOn, endsOn);
    }

    public void change(String label, String school, String className, LocalDate startsOn, LocalDate endsOn) {
        this.label = label;
        this.school = school;
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

    public String getSchool() {
        return school;
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
