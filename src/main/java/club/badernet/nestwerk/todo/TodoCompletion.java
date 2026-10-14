package club.badernet.nestwerk.todo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** Eine Stelle, die bei „jeder einzeln“ abhaken muss: ein Mitglied oder ein Kontakt. */
@Entity
@Table(name = "todo_completions")
public class TodoCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "todo_id")
    private Todo todo;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "contact_id")
    private Long contactId;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private Long completedBy;

    protected TodoCompletion() {
    }

    TodoCompletion(Todo todo, Long memberId, Long contactId) {
        this.todo = todo;
        this.memberId = memberId;
        this.contactId = contactId;
    }

    public Long getMemberId() { return memberId; }
    public Long getContactId() { return contactId; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getCompletedBy() { return completedBy; }

    public boolean isDone() {
        return completedAt != null;
    }

    void complete(Long userId) {
        completedAt = Instant.now();
        completedBy = userId;
    }

    void reopen() {
        completedAt = null;
        completedBy = null;
    }
}
