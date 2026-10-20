package club.badernet.nestwerk.school;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Schulprofil eines Familienmitglieds; Schule und Klasse stehen im jeweiligen Schuljahr. */
@Entity
@Table(name = "school_profile")
public class SchoolProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(length = 2)
    private String state;

    protected SchoolProfile() {
    }

    public SchoolProfile(Long memberId, String state) {
        this.memberId = memberId;
        this.state = state;
    }

    public void changeState(String state) {
        this.state = state;
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    /** Kürzel des Bundeslands (siehe {@code GermanHolidays.STATES}) oder {@code null}. */
    public String getState() {
        return state;
    }
}
