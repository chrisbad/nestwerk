package club.badernet.nestwerk.todo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Ein Eintrag im Verlauf eines ToDos; wird nur angelegt, nie geändert. */
@Entity
@Table(name = "todo_history")
public class TodoHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "todo_id", nullable = false, updatable = false)
    private Long todoId;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(nullable = false, updatable = false)
    private Instant at = Instant.now();

    @Column(nullable = false, length = 50, updatable = false)
    private String field;

    @Column(name = "old_value", columnDefinition = "TEXT", updatable = false)
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT", updatable = false)
    private String newValue;

    protected TodoHistoryEntry() {
    }

    public TodoHistoryEntry(Long todoId, Long actorId, String field, String oldValue, String newValue) {
        this.todoId = todoId;
        this.actorId = actorId;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public Long getId() { return id; }
    public Long getTodoId() { return todoId; }
    public Long getActorId() { return actorId; }
    public Instant getAt() { return at; }
    public String getField() { return field; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
}
