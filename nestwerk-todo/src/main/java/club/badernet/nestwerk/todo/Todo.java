package club.badernet.nestwerk.todo;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ein Eintrag im Familien-Backlog. */
@Entity
@Table(name = "todos")
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TodoStatus status = TodoStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TodoPriority priority = TodoPriority.NORMAL;

    @Column(name = "available_from")
    private LocalDate availableFrom;

    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "follow_up_on")
    private LocalDate followUpOn;

    @Column(name = "remind_days_before")
    private Integer remindDaysBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_mode", nullable = false, length = 10)
    private CompletionMode completionMode = CompletionMode.ANY;

    @Column(name = "private_todo", nullable = false)
    private boolean privateTodo;

    @Column(name = "responsible_user_id", nullable = false)
    private Long responsibleUserId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(length = 500)
    private String rrule;

    @Column(name = "series_id")
    private Long seriesId;

    @Column(name = "successor_id")
    private Long successorId;

    @Version
    private int version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "todo_tags", joinColumns = @JoinColumn(name = "todo_id"))
    @Column(name = "tag", nullable = false, length = 40)
    private Set<String> tags = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "todo_watchers", joinColumns = @JoinColumn(name = "todo_id"))
    @Column(name = "user_id", nullable = false)
    private Set<Long> watcherUserIds = new HashSet<>();

    @OneToMany(mappedBy = "todo", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<TodoAssignee> assignees = new HashSet<>();

    @OneToMany(mappedBy = "todo", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<TodoCompletion> completions = new HashSet<>();

    protected Todo() {
    }

    public Todo(String title, Long createdBy) {
        this.title = title;
        this.createdBy = createdBy;
        this.responsibleUserId = createdBy;
    }

    // --- Lesen ---------------------------------------------------------------

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public TodoStatus getStatus() { return status; }
    public TodoPriority getPriority() { return priority; }
    public LocalDate getAvailableFrom() { return availableFrom; }
    public LocalDate getDueOn() { return dueOn; }
    public LocalDate getFollowUpOn() { return followUpOn; }
    public Integer getRemindDaysBefore() { return remindDaysBefore; }
    public CompletionMode getCompletionMode() { return completionMode; }
    public boolean isPrivateTodo() { return privateTodo; }
    public Long getResponsibleUserId() { return responsibleUserId; }
    public Long getCreatedBy() { return createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getCompletedBy() { return completedBy; }
    public int getVersion() { return version; }
    public Long getParentId() { return parentId; }
    public String getRrule() { return rrule; }
    public Long getSeriesId() { return seriesId; }
    public Long getSuccessorId() { return successorId; }

    public Set<String> getTags() {
        return Set.copyOf(tags);
    }

    public Set<Long> getWatcherUserIds() {
        return Set.copyOf(watcherUserIds);
    }

    public Set<AssigneeRef> assigneeRefs() {
        Set<AssigneeRef> refs = new LinkedHashSet<>();
        assignees.forEach(a -> refs.add(a.ref()));
        return refs;
    }

    public List<TodoCompletion> getCompletions() {
        return new ArrayList<>(completions);
    }

    public long completedCount() {
        return completions.stream().filter(TodoCompletion::isDone).count();
    }

    public boolean isClosed() {
        return status.isClosed();
    }

    // --- Ändern (nur über TodoService) ----------------------------------------

    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setPriority(TodoPriority priority) { this.priority = priority; }
    public void setAvailableFrom(LocalDate availableFrom) { this.availableFrom = availableFrom; }
    public void setDueOn(LocalDate dueOn) { this.dueOn = dueOn; }
    public void setFollowUpOn(LocalDate followUpOn) { this.followUpOn = followUpOn; }
    public void setRemindDaysBefore(Integer remindDaysBefore) { this.remindDaysBefore = remindDaysBefore; }
    public void setCompletionMode(CompletionMode completionMode) { this.completionMode = completionMode; }
    public void setPrivateTodo(boolean privateTodo) { this.privateTodo = privateTodo; }
    public void setResponsibleUserId(Long responsibleUserId) { this.responsibleUserId = responsibleUserId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public void setRrule(String rrule) { this.rrule = rrule; }
    void setSeriesId(Long seriesId) { this.seriesId = seriesId; }
    void setSuccessorId(Long successorId) { this.successorId = successorId; }

    public void setTags(Collection<String> newTags) {
        tags.clear();
        tags.addAll(newTags);
    }

    public void replaceAssignees(Collection<AssigneeRef> refs) {
        assignees.removeIf(a -> !refs.contains(a.ref()));
        Set<AssigneeRef> present = assigneeRefs();
        for (AssigneeRef ref : refs) {
            if (!present.contains(ref)) {
                assignees.add(new TodoAssignee(this, ref));
            }
        }
    }

    public void addWatcher(Long userId) {
        watcherUserIds.add(userId);
    }

    public void removeWatcher(Long userId) {
        watcherUserIds.remove(userId);
    }

    /**
     * Gleicht die abhakpflichtigen Stellen ab: neue kommen offen hinzu, weggefallene verschwinden,
     * bereits erledigte bleiben erledigt.
     */
    void syncCompletions(Set<Long> memberIds, Set<Long> contactIds) {
        completions.removeIf(c -> c.getMemberId() != null ? !memberIds.contains(c.getMemberId())
                : !contactIds.contains(c.getContactId()));
        Set<Long> presentMembers = new HashSet<>();
        Set<Long> presentContacts = new HashSet<>();
        for (TodoCompletion c : completions) {
            if (c.getMemberId() != null) {
                presentMembers.add(c.getMemberId());
            } else {
                presentContacts.add(c.getContactId());
            }
        }
        memberIds.stream().filter(m -> !presentMembers.contains(m))
                .forEach(m -> completions.add(new TodoCompletion(this, m, null)));
        contactIds.stream().filter(c -> !presentContacts.contains(c))
                .forEach(c -> completions.add(new TodoCompletion(this, null, c)));
    }

    void clearCompletions() {
        completions.clear();
    }

    void changeStatus(TodoStatus newStatus, Long userId) {
        if (newStatus.isClosed() && !status.isClosed()) {
            completedAt = Instant.now();
            completedBy = userId;
        } else if (!newStatus.isClosed()) {
            completedAt = null;
            completedBy = null;
        }
        status = newStatus;
    }

    void markChanged(Long userId) {
        updatedBy = userId;
        updatedAt = Instant.now();
    }
}
