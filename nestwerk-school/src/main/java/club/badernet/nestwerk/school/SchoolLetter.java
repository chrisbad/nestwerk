package club.badernet.nestwerk.school;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** Ein Elternbrief mit Frist; Anhänge hängen über das Anhangsmodul daran. */
@Entity
@Table(name = "school_letter")
public class SchoolLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "year_id", nullable = false)
    private Long yearId;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(length = 500)
    private String note;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "signed_by")
    private Long signedBy;

    protected SchoolLetter() {
    }

    public SchoolLetter(Long yearId, String title, LocalDate dueOn, String note) {
        this.yearId = yearId;
        this.title = title;
        this.dueOn = dueOn;
        this.note = note;
    }

    public void sign(Long userId) {
        this.signedAt = Instant.now();
        this.signedBy = userId;
    }

    public void unsign() {
        this.signedAt = null;
        this.signedBy = null;
    }

    public boolean isSigned() {
        return signedAt != null;
    }

    public Long getId() {
        return id;
    }

    public Long getYearId() {
        return yearId;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public String getNote() {
        return note;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public Long getSignedBy() {
        return signedBy;
    }
}
