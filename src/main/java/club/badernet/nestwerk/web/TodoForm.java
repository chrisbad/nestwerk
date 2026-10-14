package club.badernet.nestwerk.web;

import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.CompletionMode;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoPriority;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Formularobjekt für ToDos; Schlagworte kommagetrennt, Zuständige als {@code member:5} usw. */
public class TodoForm {

    private String title;
    private String description;
    private TodoPriority priority = TodoPriority.NORMAL;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate availableFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueOn;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate followUpOn;
    private Integer remindDaysBefore;
    private boolean privateTodo;
    private CompletionMode completionMode = CompletionMode.ANY;
    private String tags;
    private List<String> assignees = new ArrayList<>();
    private Long responsibleUserId;
    private Integer version;

    public static TodoForm from(Todo t) {
        TodoForm f = new TodoForm();
        f.title = t.getTitle();
        f.description = t.getDescription();
        f.priority = t.getPriority();
        f.availableFrom = t.getAvailableFrom();
        f.dueOn = t.getDueOn();
        f.followUpOn = t.getFollowUpOn();
        f.remindDaysBefore = t.getRemindDaysBefore();
        f.privateTodo = t.isPrivateTodo();
        f.completionMode = t.getCompletionMode();
        f.tags = String.join(", ", t.getTags().stream().sorted().toList());
        f.assignees = new ArrayList<>(t.assigneeRefs().stream().map(AssigneeRef::key).toList());
        f.responsibleUserId = t.getResponsibleUserId();
        f.version = t.getVersion();
        return f;
    }

    public TodoInput toInput() {
        Set<String> tagSet = new LinkedHashSet<>();
        if (tags != null) {
            for (String tag : tags.split(",")) {
                tagSet.add(tag);
            }
        }
        Set<AssigneeRef> refs = new LinkedHashSet<>();
        if (assignees != null) {
            for (String a : assignees) {
                if (a != null && !a.isBlank()) {
                    refs.add(AssigneeRef.parse(a));
                }
            }
        }
        return new TodoInput(title, description, priority, availableFrom, dueOn, followUpOn, remindDaysBefore,
                privateTodo, completionMode, tagSet, refs, responsibleUserId);
    }

    /** Übernimmt Felder, die der Benutzer nicht ändern darf (im Formular gesperrt und daher nicht gesendet). */
    public void keepContentOf(Todo t) {
        title = t.getTitle();
        description = t.getDescription();
        priority = t.getPriority();
        availableFrom = t.getAvailableFrom();
        dueOn = t.getDueOn();
        followUpOn = t.getFollowUpOn();
        remindDaysBefore = t.getRemindDaysBefore();
        privateTodo = t.isPrivateTodo();
        tags = String.join(", ", t.getTags());
    }

    public void keepAssigneesOf(Todo t) {
        assignees = new ArrayList<>(t.assigneeRefs().stream().map(AssigneeRef::key).toList());
        completionMode = t.getCompletionMode();
    }

    public void keepResponsibleOf(Todo t) {
        responsibleUserId = t.getResponsibleUserId();
    }

    public boolean hasAssignee(String key) {
        return assignees != null && assignees.contains(key);
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public TodoPriority getPriority() { return priority; }
    public void setPriority(TodoPriority priority) { this.priority = priority; }
    public LocalDate getAvailableFrom() { return availableFrom; }
    public void setAvailableFrom(LocalDate availableFrom) { this.availableFrom = availableFrom; }
    public LocalDate getDueOn() { return dueOn; }
    public void setDueOn(LocalDate dueOn) { this.dueOn = dueOn; }
    public LocalDate getFollowUpOn() { return followUpOn; }
    public void setFollowUpOn(LocalDate followUpOn) { this.followUpOn = followUpOn; }
    public Integer getRemindDaysBefore() { return remindDaysBefore; }
    public void setRemindDaysBefore(Integer remindDaysBefore) { this.remindDaysBefore = remindDaysBefore; }
    public boolean isPrivateTodo() { return privateTodo; }
    public void setPrivateTodo(boolean privateTodo) { this.privateTodo = privateTodo; }
    public CompletionMode getCompletionMode() { return completionMode; }
    public void setCompletionMode(CompletionMode completionMode) { this.completionMode = completionMode; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public List<String> getAssignees() { return assignees; }
    public void setAssignees(List<String> assignees) { this.assignees = assignees; }
    public Long getResponsibleUserId() { return responsibleUserId; }
    public void setResponsibleUserId(Long responsibleUserId) { this.responsibleUserId = responsibleUserId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
