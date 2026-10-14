package club.badernet.nestwerk.todo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "todo_links")
public class TodoLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TodoLinkType type;

    @Column(name = "created_by")
    private Long createdBy;

    protected TodoLink() {
    }

    public TodoLink(Long sourceId, Long targetId, TodoLinkType type, Long createdBy) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.type = type;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public Long getSourceId() { return sourceId; }
    public Long getTargetId() { return targetId; }
    public TodoLinkType getType() { return type; }
    public Long getCreatedBy() { return createdBy; }
}
