package club.badernet.nestwerk.web;

import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.TodoFilter;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.todo.TodoTiming;
import org.springframework.web.util.UriComponentsBuilder;

/** Filter aus der URL; gemeinsam für Liste, Board und Baum. */
public class TodoFilterParams {

    private TodoFilter.Involvement involvement;
    private String assignee;
    private TodoStatus status;
    private TodoTiming timing;
    private TodoPriority priority;
    private String tag;
    private String q;
    private boolean closed;

    public TodoFilter toFilter() {
        AssigneeRef ref = assignee == null || assignee.isBlank() ? null : AssigneeRef.parse(assignee);
        return new TodoFilter(involvement == null ? TodoFilter.Involvement.ALL : involvement, ref, status, timing,
                priority, blank(tag), blank(q), closed);
    }

    /** Query-String ohne führendes „?“ (leer, wenn keine Filter gesetzt sind) für den Ansichtsumschalter. */
    public String queryString() {
        UriComponentsBuilder b = UriComponentsBuilder.newInstance();
        if (involvement != null && involvement != TodoFilter.Involvement.ALL) b.queryParam("involvement", involvement.name());
        if (blank(assignee) != null) b.queryParam("assignee", assignee.trim());
        if (status != null) b.queryParam("status", status.name());
        if (timing != null) b.queryParam("timing", timing.name());
        if (priority != null) b.queryParam("priority", priority.name());
        if (blank(tag) != null) b.queryParam("tag", tag.trim());
        if (blank(q) != null) b.queryParam("q", q.trim());
        if (closed) b.queryParam("closed", "true");
        String query = b.build().encode().getQuery();
        return query == null ? "" : query;
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    public TodoFilter.Involvement getInvolvement() { return involvement; }
    public void setInvolvement(TodoFilter.Involvement involvement) { this.involvement = involvement; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public TodoStatus getStatus() { return status; }
    public void setStatus(TodoStatus status) { this.status = status; }
    public TodoTiming getTiming() { return timing; }
    public void setTiming(TodoTiming timing) { this.timing = timing; }
    public TodoPriority getPriority() { return priority; }
    public void setPriority(TodoPriority priority) { this.priority = priority; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getQ() { return q; }
    public void setQ(String q) { this.q = q; }
    public boolean isClosed() { return closed; }
    public void setClosed(boolean closed) { this.closed = closed; }
}
