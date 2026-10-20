package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.CompletionMode;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoCommentService;
import club.badernet.nestwerk.todo.TodoFilter;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoQuery;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.todo.TodoTiming;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.web.TodoForm;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** ToDos suchen, anlegen, ändern, abhaken und kommentieren. Löschen gibt es hier bewusst nicht. */
@Component
public class TodoTools {

    public record TodoItem(Long id, String title, String status, String priority, String timing,
                           LocalDate availableFrom, LocalDate dueOn, LocalDate followUpOn, List<String> tags,
                           List<String> assignees, String completionMode, String responsible, boolean privateTodo,
                           Long parentId, String rrule, int version) {
    }

    public record Comment(Long id, String author, String createdAt, String body) {
    }

    public record TodoDetail(TodoItem todo, String description, List<Comment> comments) {
    }

    private static final int MAX_RESULTS = 200;

    private final Tools tools;
    private final TodoService todos;
    private final TodoQuery query;
    private final TodoCommentService comments;
    private final FamilyMemberRepository members;
    private final RoleRepository roles;
    private final ContactService contactService;
    private final UserRepository users;

    public TodoTools(Tools tools, TodoService todos, TodoQuery query, TodoCommentService comments,
                     FamilyMemberRepository members, RoleRepository roles, ContactService contactService,
                     UserRepository users) {
        this.tools = tools;
        this.todos = todos;
        this.query = query;
        this.comments = comments;
        this.members = members;
        this.roles = roles;
        this.contactService = contactService;
        this.users = users;
    }

    @McpTool(name = "list_todos", description = "Sucht sichtbare ToDos. Abgeschlossene erscheinen nur mit "
            + "includeClosed=true oder passendem Status. Ergebnis nach Dringlichkeit sortiert, höchstens 200.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<TodoItem> listTodos(McpTransportContext context,
                                    @McpToolParam(description = "ALL, MINE (mir zugewiesen), RESPONSIBLE, WATCHING",
                                            required = false) String involvement,
                                    @McpToolParam(description = "OPEN, IN_PROGRESS, DONE, DISCARDED", required = false)
                                    String status,
                                    @McpToolParam(description = "OVERDUE, DUE_SOON, CURRENT, DORMANT, NO_DATE, CLOSED",
                                            required = false) String timing,
                                    @McpToolParam(description = "LOW, NORMAL, HIGH, URGENT", required = false)
                                    String priority,
                                    @McpToolParam(description = "Schlagwort", required = false) String tag,
                                    @McpToolParam(description = "Suchtext in Titel und Beschreibung", required = false)
                                    String text,
                                    @McpToolParam(description = "Auch erledigte und verworfene", required = false)
                                    Boolean includeClosed) {
        return tools.read(context, user -> {
            TodoFilter.Involvement who = Tools.enumValue(TodoFilter.Involvement.class, involvement, "involvement");
            TodoFilter filter = new TodoFilter(who == null ? TodoFilter.Involvement.ALL : who, null,
                    Tools.enumValue(TodoStatus.class, status, "status"),
                    Tools.enumValue(TodoTiming.class, timing, "timing"),
                    Tools.enumValue(TodoPriority.class, priority, "priority"),
                    blankToNull(tag), blankToNull(text), Boolean.TRUE.equals(includeClosed));
            Names names = names(user);
            return query.list(user, filter).stream()
                    .flatMap(g -> g.todos().stream().map(t -> item(t, g.timing(), names)))
                    .limit(MAX_RESULTS).toList();
        });
    }

    @McpTool(name = "get_todo", description = "Zeigt ein ToDo mit Beschreibung und Kommentaren. Die version wird "
            + "für update_todo gebraucht.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    openWorldHint = false))
    public TodoDetail getTodo(McpTransportContext context, @McpToolParam(description = "ID des ToDos") Long todoId) {
        return tools.read(context, user -> detail(todos.get(Tools.required(todoId, "todoId"), user), user));
    }

    @McpTool(name = "create_todo", description = "Legt ein ToDo an. Zuständige als Liste wie member:5, role:2 oder "
            + "contact:7 (IDs aus list_members). Ohne responsibleUserId ist der Benutzer selbst verantwortlich.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public TodoDetail createTodo(McpTransportContext context,
                                 @McpToolParam(description = "Titel") String title,
                                 @McpToolParam(description = "Beschreibung (Markdown)", required = false)
                                 String description,
                                 @McpToolParam(description = "LOW, NORMAL, HIGH, URGENT", required = false)
                                 String priority,
                                 @McpToolParam(description = "Fällig bis, JJJJ-MM-TT", required = false) String dueOn,
                                 @McpToolParam(description = "Erst ab, JJJJ-MM-TT", required = false)
                                 String availableFrom,
                                 @McpToolParam(description = "Wiedervorlage am, JJJJ-MM-TT", required = false)
                                 String followUpOn,
                                 @McpToolParam(description = "Erinnerung Tage vor Fälligkeit", required = false)
                                 Integer remindDaysBefore,
                                 @McpToolParam(description = "Schlagworte", required = false) List<String> tags,
                                 @McpToolParam(description = "Zuständige, z. B. member:5", required = false)
                                 List<String> assignees,
                                 @McpToolParam(description = "ANY (einer reicht) oder EACH (jeder einzeln)",
                                         required = false) String completionMode,
                                 @McpToolParam(description = "Nur für Beteiligte sichtbar", required = false)
                                 Boolean privateTodo,
                                 @McpToolParam(description = "Benutzer-ID des Verantwortlichen", required = false)
                                 Long responsibleUserId,
                                 @McpToolParam(description = "Wiederholung als RRULE, z. B. FREQ=MONTHLY",
                                         required = false) String rrule) {
        return tools.write(context, user -> {
            TodoForm form = new TodoForm();
            form.setTitle(title);
            apply(form, description, priority, dueOn, availableFrom, followUpOn, remindDaysBefore, tags, assignees,
                    completionMode, privateTodo, responsibleUserId, rrule);
            Todo todo = todos.create(form.toInput(), user);
            return detail(todo, user);
        });
    }

    @McpTool(name = "update_todo", description = "Ändert ein ToDo. Nur angegebene Felder werden geändert; tags und "
            + "assignees ersetzen die Listen, leerer Text löscht ein Datum. version aus get_todo schützt vor "
            + "gleichzeitigen Änderungen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public TodoDetail updateTodo(McpTransportContext context,
                                 @McpToolParam(description = "ID des ToDos") Long todoId,
                                 @McpToolParam(description = "version aus get_todo") Integer version,
                                 @McpToolParam(description = "Titel", required = false) String title,
                                 @McpToolParam(description = "Beschreibung (Markdown)", required = false)
                                 String description,
                                 @McpToolParam(description = "LOW, NORMAL, HIGH, URGENT", required = false)
                                 String priority,
                                 @McpToolParam(description = "Fällig bis, JJJJ-MM-TT", required = false) String dueOn,
                                 @McpToolParam(description = "Erst ab, JJJJ-MM-TT", required = false)
                                 String availableFrom,
                                 @McpToolParam(description = "Wiedervorlage am, JJJJ-MM-TT", required = false)
                                 String followUpOn,
                                 @McpToolParam(description = "Erinnerung Tage vor Fälligkeit", required = false)
                                 Integer remindDaysBefore,
                                 @McpToolParam(description = "Schlagworte (ersetzt)", required = false)
                                 List<String> tags,
                                 @McpToolParam(description = "Zuständige (ersetzt), z. B. member:5", required = false)
                                 List<String> assignees,
                                 @McpToolParam(description = "ANY oder EACH", required = false) String completionMode,
                                 @McpToolParam(description = "Nur für Beteiligte sichtbar", required = false)
                                 Boolean privateTodo,
                                 @McpToolParam(description = "Benutzer-ID des Verantwortlichen", required = false)
                                 Long responsibleUserId,
                                 @McpToolParam(description = "Wiederholung als RRULE, leerer Text entfernt sie",
                                         required = false) String rrule) {
        return tools.write(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            Todo current = todos.get(id, user);
            TodoForm form = TodoForm.from(current);
            if (title != null) {
                form.setTitle(title);
            }
            apply(form, description, priority, dueOn, availableFrom, followUpOn, remindDaysBefore, tags, assignees,
                    completionMode, privateTodo, responsibleUserId, rrule);
            todos.update(id, form.toInput(), Tools.required(version, "version"), user);
            return detail(todos.get(id, user), user);
        });
    }

    @McpTool(name = "set_todo_status", description = "Setzt den Status: OPEN, IN_PROGRESS, DONE oder DISCARDED.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    openWorldHint = false, idempotentHint = true))
    public TodoItem setTodoStatus(McpTransportContext context,
                                  @McpToolParam(description = "ID des ToDos") Long todoId,
                                  @McpToolParam(description = "OPEN, IN_PROGRESS, DONE, DISCARDED") String status) {
        return tools.write(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            todos.changeStatus(id, Tools.required(Tools.enumValue(TodoStatus.class, status, "status"), "status"),
                    user);
            return item(todos.get(id, user), null, names(user));
        });
    }

    @McpTool(name = "delete_todo", description = "Löscht ein ToDo endgültig samt Unteraufgaben, Kommentaren und "
            + "Anhängen. Nur nach Rückfrage benutzen; zum Verwerfen ohne Löschen set_todo_status mit DISCARDED.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true,
                    openWorldHint = false, idempotentHint = false))
    public String deleteTodo(McpTransportContext context,
                             @McpToolParam(description = "ID des ToDos aus list_todos") Long todoId) {
        return tools.write(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            String title = todos.get(id, user).getTitle();
            todos.delete(id, user);
            return "ToDo „" + title + "“ wurde gelöscht.";
        });
    }

    @McpTool(name = "check_todo", description = "Hakt ein ToDo ab. Bei „jeder einzeln“ (EACH) nur den eigenen "
            + "Teil; erledigt ist es, wenn alle abgehakt haben.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    openWorldHint = false, idempotentHint = true))
    public TodoItem checkTodo(McpTransportContext context, @McpToolParam(description = "ID des ToDos") Long todoId) {
        return tools.write(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            todos.check(id, user);
            return item(todos.get(id, user), null, names(user));
        });
    }

    @McpTool(name = "uncheck_todo", description = "Nimmt das Abhaken zurück; ein erledigtes ToDo ist wieder offen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    openWorldHint = false, idempotentHint = true))
    public TodoItem uncheckTodo(McpTransportContext context, @McpToolParam(description = "ID des ToDos") Long todoId) {
        return tools.write(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            todos.uncheck(id, user);
            return item(todos.get(id, user), null, names(user));
        });
    }

    @McpTool(name = "add_todo_comment", description = "Schreibt einen Kommentar (Markdown) an ein ToDo. "
            + "@benutzername erwähnt jemanden und benachrichtigt ihn.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public Comment addTodoComment(McpTransportContext context,
                                  @McpToolParam(description = "ID des ToDos") Long todoId,
                                  @McpToolParam(description = "Text des Kommentars") String body) {
        return tools.write(context, user -> {
            var comment = comments.add(Tools.required(todoId, "todoId"), Tools.required(body, "body"), user);
            return new Comment(comment.getId(), user.getUsername(), String.valueOf(comment.getCreatedAt()),
                    comment.getBody());
        });
    }

    private void apply(TodoForm form, String description, String priority, String dueOn, String availableFrom,
                       String followUpOn, Integer remindDaysBefore, Collection<String> tags,
                       Collection<String> assignees, String completionMode, Boolean privateTodo,
                       Long responsibleUserId, String rrule) {
        if (description != null) {
            form.setDescription(description);
        }
        TodoPriority p = Tools.enumValue(TodoPriority.class, priority, "priority");
        if (p != null) {
            form.setPriority(p);
        }
        if (dueOn != null) {
            form.setDueOn(Tools.date(dueOn, "dueOn"));
        }
        if (availableFrom != null) {
            form.setAvailableFrom(Tools.date(availableFrom, "availableFrom"));
        }
        if (followUpOn != null) {
            form.setFollowUpOn(Tools.date(followUpOn, "followUpOn"));
        }
        if (remindDaysBefore != null) {
            form.setRemindDaysBefore(remindDaysBefore);
        }
        if (tags != null) {
            form.setTags(String.join(",", tags));
        }
        if (assignees != null) {
            form.setAssignees(new ArrayList<>(assignees));
        }
        CompletionMode mode = Tools.enumValue(CompletionMode.class, completionMode, "completionMode");
        if (mode != null) {
            form.setCompletionMode(mode);
        }
        if (privateTodo != null) {
            form.setPrivateTodo(privateTodo);
        }
        if (responsibleUserId != null) {
            form.setResponsibleUserId(responsibleUserId);
        }
        if (rrule != null) {
            form.setRrule(rrule.isBlank() ? null : rrule.trim());
        }
    }

    /** Anzeigenamen für Zuständige und Benutzer. */
    private record Names(Map<Long, String> members, Map<Long, String> roles, Map<Long, String> contacts,
                         Map<Long, String> users) {

        String of(AssigneeRef ref) {
            Map<Long, String> source = switch (ref.type()) {
                case MEMBER -> members;
                case ROLE -> roles;
                case CONTACT -> contacts;
            };
            return ref.key() + " (" + source.getOrDefault(ref.id(), "nicht sichtbar") + ")";
        }
    }

    /** Kontakte nur, soweit der Benutzer sie sehen darf (wie in der Weboberfläche). */
    private Names names(User viewer) {
        return new Names(byId(members.findAll(), FamilyMember::getId, FamilyMember::getName),
                byId(roles.findAll(), Role::getId, Role::getName),
                byId(contactService.findVisible(viewer), Contact::getId, Contact::getName),
                byId(users.findAll(), User::getId, User::getUsername));
    }

    private static <T> Map<Long, String> byId(List<T> list, Function<T, Long> id, Function<T, String> name) {
        return list.stream().collect(Collectors.toMap(id, name));
    }

    private TodoItem item(Todo t, TodoTiming timing, Names names) {
        TodoTiming effective = timing != null ? timing : TodoTiming.of(t, LocalDate.now(query.zone()));
        return new TodoItem(t.getId(), t.getTitle(), t.getStatus().name(), t.getPriority().name(), effective.name(),
                t.getAvailableFrom(), t.getDueOn(), t.getFollowUpOn(), t.getTags().stream().sorted().toList(),
                t.assigneeRefs().stream().map(names::of).toList(), t.getCompletionMode().name(),
                t.getResponsibleUserId() == null ? null : names.users().get(t.getResponsibleUserId()),
                t.isPrivateTodo(), t.getParentId(), t.getRrule(), t.getVersion());
    }

    private TodoDetail detail(Todo t, User viewer) {
        Names names = names(viewer);
        List<Comment> list = comments.forTodo(t.getId(), viewer).stream()
                .map(c -> new Comment(c.getId(), names.users().getOrDefault(c.getAuthorId(), "?"),
                        String.valueOf(c.getCreatedAt()), c.getBody()))
                .toList();
        return new TodoDetail(item(t, null, names), t.getDescription(), list);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
