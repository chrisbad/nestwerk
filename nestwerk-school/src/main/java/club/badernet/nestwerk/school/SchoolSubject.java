package club.badernet.nestwerk.school;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Ein Fach eines Schuljahrs. */
@Entity
@Table(name = "school_subject")
public class SchoolSubject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "year_id", nullable = false)
    private Long yearId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 7)
    private String color;

    @Column(length = 100)
    private String teacher;

    protected SchoolSubject() {
    }

    public SchoolSubject(Long yearId, String name, String color, String teacher) {
        this.yearId = yearId;
        change(name, color, teacher);
    }

    public void change(String name, String color, String teacher) {
        this.name = name;
        this.color = color;
        this.teacher = teacher;
    }

    public Long getId() {
        return id;
    }

    public Long getYearId() {
        return yearId;
    }

    public String getName() {
        return name;
    }

    public String getColor() {
        return color;
    }

    public String getTeacher() {
        return teacher;
    }
}
