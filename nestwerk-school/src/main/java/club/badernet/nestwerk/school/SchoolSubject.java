package club.badernet.nestwerk.school;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.LinkedHashSet;
import java.util.Set;

/** A subject of a school year; its teachers are contacts. */
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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "school_subject_teacher", joinColumns = @JoinColumn(name = "subject_id"))
    @Column(name = "contact_id", nullable = false)
    private Set<Long> teacherIds = new LinkedHashSet<>();

    protected SchoolSubject() {
    }

    public SchoolSubject(Long yearId, String name, String color) {
        this.yearId = yearId;
        change(name, color);
    }

    public void change(String name, String color) {
        this.name = name;
        this.color = color;
    }

    public void setTeacherIds(Set<Long> teacherIds) {
        this.teacherIds.clear();
        this.teacherIds.addAll(teacherIds);
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

    /** Contact ids of the teachers. */
    public Set<Long> getTeacherIds() {
        return Set.copyOf(teacherIds);
    }
}
