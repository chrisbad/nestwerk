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

@Entity
@Table(name = "todo_assignees")
public class TodoAssignee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "todo_id")
    private Todo todo;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "role_id")
    private Long roleId;

    @Column(name = "contact_id")
    private Long contactId;

    protected TodoAssignee() {
    }

    TodoAssignee(Todo todo, AssigneeRef ref) {
        this.todo = todo;
        switch (ref.type()) {
            case MEMBER -> memberId = ref.id();
            case ROLE -> roleId = ref.id();
            case CONTACT -> contactId = ref.id();
        }
    }

    public AssigneeRef ref() {
        if (memberId != null) {
            return AssigneeRef.member(memberId);
        }
        if (roleId != null) {
            return AssigneeRef.role(roleId);
        }
        return AssigneeRef.contact(contactId);
    }
}
