package club.badernet.nestwerk.school;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Verknüpfung eines ToDos (bei Serien: des ersten) mit Schuljahr und Fach. */
@Entity
@Table(name = "school_homework")
public class Homework {

    @Id
    @Column(name = "todo_id")
    private Long todoId;

    @Column(name = "year_id", nullable = false)
    private Long yearId;

    @Column(name = "subject_id")
    private Long subjectId;

    protected Homework() {
    }

    public Homework(Long todoId, Long yearId, Long subjectId) {
        this.todoId = todoId;
        this.yearId = yearId;
        this.subjectId = subjectId;
    }

    public Long getTodoId() {
        return todoId;
    }

    public Long getYearId() {
        return yearId;
    }

    public Long getSubjectId() {
        return subjectId;
    }
}
