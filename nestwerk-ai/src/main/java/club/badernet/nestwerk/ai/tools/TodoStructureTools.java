package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.notification.Channel;
import club.badernet.nestwerk.notification.NotificationPreferences;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoCommentService;
import club.badernet.nestwerk.todo.TodoHistoryEntry;
import club.badernet.nestwerk.todo.TodoHistoryRepository;
import club.badernet.nestwerk.todo.TodoLinkType;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStructureService;
import club.badernet.nestwerk.web.TodoForm;
import club.badernet.nestwerk.web.TodoHistoryFormatter;
import club.badernet.nestwerk.web.TodoViews;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ToDo-Struktur und Zusammenarbeit: Unteraufgaben, Verknüpfungen, Beobachter, Übernehmen, Wiedervorlage,
 * Benachrichtigungen und Verlauf.
 */
@Component
public class TodoStructureTools {

    public record TodoRef(Long id, String title, String status, boolean closed) {
    }

    public record LinkItem(Long linkId, String relation, TodoRef other) {
    }

    public record Structure(Long todoId, TodoRef parent, List<TodoRef> ancestors, List<TodoRef> children,
                            int hiddenChildren, String progress, List<LinkItem> links, List<String> watchers,
                            boolean watching) {
    }

    public record HistoryItem(String at, String who, String change) {
    }

    private final Tools tools;
    private final TodoService todos;
    private final TodoStructureService structure;
    private final TodoCommentService comments;
    private final TodoHistoryRepository history;
    private final TodoHistoryFormatter formatter;
    private final TodoViews views;
    private final TodoAccess access;
    private final NotificationPreferences preferences;

    public TodoStructureTools(Tools tools, TodoService todos, TodoStructureService structure,
                              TodoCommentService comments, TodoHistoryRepository history,
                              TodoHistoryFormatter formatter, TodoViews views, TodoAccess access,
                              NotificationPreferences preferences) {
        this.tools = tools;
        this.todos = todos;
        this.structure = structure;
        this.comments = comments;
        this.history = history;
        this.formatter = formatter;
        this.views = views;
        this.access = access;
        this.preferences = preferences;
    }

    @McpTool(name = "get_todo_structure", description = "Zeigt Struktur und Beteiligte eines ToDos: übergeordnete "
            + "ToDos, Unteraufgaben mit Fortschritt, Verknüpfungen (blockiert, verwandt, Duplikat) und Beobachter. "
            + "Die linkId braucht unlink_todos.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Structure getTodoStructure(McpTransportContext context,
                                      @McpToolParam(description = "ID des ToDos") Long todoId) {
        return tools.read(context, user -> structureOf(Tools.required(todoId, "todoId"), user));
    }

    @McpTool(name = "take_todo", description = "„Ich kümmere mich darum“: setzt das ToDo auf In Arbeit.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public TodoRef takeTodo(McpTransportContext context, @McpToolParam(description = "ID des ToDos") Long todoId) {
        return tools.write(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            todos.take(id, user);
            return ref(todos.get(id, user));
        });
    }

    @McpTool(name = "follow_up_todo", description = "Reagiert auf die Wiedervorlage: DONE (erledigt), DISCARD "
            + "(verwerfen), WEEK (in einer Woche wieder vorlegen) oder MONTH (in einem Monat).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public TodoRef followUpTodo(McpTransportContext context,
                                @McpToolParam(description = "ID des ToDos") Long todoId,
                                @McpToolParam(description = "DONE, DISCARD, WEEK oder MONTH") String action) {
        return tools.write(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            todos.followUp(id, Tools.required(Tools.enumValue(TodoService.FollowUpAction.class, action, "action"),
                    "action"), user);
            return ref(todos.get(id, user));
        });
    }

    @McpTool(name = "create_subtodo", description = "Legt eine Unteraufgabe unter einem ToDo an (braucht das Recht "
            + "zum Verknüpfen am übergeordneten ToDo).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public TodoRef createSubtodo(McpTransportContext context,
                                 @McpToolParam(description = "ID des übergeordneten ToDos") Long parentId,
                                 @McpToolParam(description = "Titel") String title,
                                 @McpToolParam(description = "Beschreibung (Markdown)", required = false)
                                 String description,
                                 @McpToolParam(description = "LOW, NORMAL, HIGH, URGENT", required = false)
                                 String priority,
                                 @McpToolParam(description = "Fällig bis, JJJJ-MM-TT", required = false) String dueOn) {
        return tools.write(context, user -> {
            TodoForm form = new TodoForm();
            form.setTitle(Tools.required(title, "title"));
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
            return ref(structure.createChild(Tools.required(parentId, "parentId"), form.toInput(), user));
        });
    }

    @McpTool(name = "move_todo", description = "Hängt ein ToDo unter ein anderes. Ohne parentId steht es danach auf "
            + "der obersten Ebene.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public Structure moveTodo(McpTransportContext context,
                              @McpToolParam(description = "ID des ToDos") Long todoId,
                              @McpToolParam(description = "ID des neuen übergeordneten ToDos", required = false)
                              Long parentId) {
        return tools.write(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            structure.moveTo(id, parentId, user);
            return structureOf(id, user);
        });
    }

    @McpTool(name = "link_todos", description = "Verknüpft zwei ToDos: BLOCKS (das erste blockiert das zweite), "
            + "RELATES (verwandt) oder DUPLICATES (das erste ist Duplikat des zweiten und wird verworfen).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public Structure linkTodos(McpTransportContext context,
                               @McpToolParam(description = "ID des ersten ToDos (Quelle)") Long sourceId,
                               @McpToolParam(description = "ID des zweiten ToDos (Ziel)") Long targetId,
                               @McpToolParam(description = "BLOCKS, RELATES oder DUPLICATES") String type) {
        return tools.write(context, user -> {
            Long source = Tools.required(sourceId, "sourceId");
            structure.link(source, Tools.required(targetId, "targetId"),
                    Tools.required(Tools.enumValue(TodoLinkType.class, type, "type"), "type"), user);
            return structureOf(source, user);
        });
    }

    @McpTool(name = "unlink_todos", description = "Löst eine Verknüpfung (linkId aus get_todo_structure). Die "
            + "Verknüpfung wird entfernt, die ToDos bleiben bestehen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false))
    public Structure unlinkTodos(McpTransportContext context,
                                 @McpToolParam(description = "ID des ToDos, an dem die Verknüpfung hängt") Long todoId,
                                 @McpToolParam(description = "linkId aus get_todo_structure") Long linkId) {
        return tools.write(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            structure.unlink(Tools.required(linkId, "linkId"), user);
            return structureOf(id, user);
        });
    }

    @McpTool(name = "watch_todo", description = "Beobachtet ein ToDo (watch=true) oder beendet das Beobachten "
            + "(watch=false). Mit userId wird ein anderer Benutzer hinzugefügt oder entfernt (braucht das Recht "
            + "„Beobachter verwalten“).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public Structure watchTodo(McpTransportContext context,
                               @McpToolParam(description = "ID des ToDos") Long todoId,
                               @McpToolParam(description = "true = beobachten, false = nicht mehr beobachten")
                               Boolean watch,
                               @McpToolParam(description = "Benutzer-ID, sonst der Benutzer selbst", required = false)
                               Long userId) {
        return tools.write(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            boolean on = Tools.required(watch, "watch");
            if (userId == null || userId.equals(user.getId())) {
                if (on) {
                    todos.watch(id, user);
                } else {
                    todos.unwatch(id, user);
                }
            } else if (on) {
                todos.addWatcher(id, userId, user);
            } else {
                todos.removeWatcher(id, userId, user);
            }
            return structureOf(id, user);
        });
    }

    @McpTool(name = "edit_todo_comment", description = "Ändert einen eigenen Kommentar (Markdown).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public String editTodoComment(McpTransportContext context,
                                  @McpToolParam(description = "ID des Kommentars (aus get_todo)") Long commentId,
                                  @McpToolParam(description = "Neuer Text") String body) {
        return tools.write(context, user -> {
            comments.edit(Tools.required(commentId, "commentId"), Tools.required(body, "body"), user);
            return "Kommentar geändert.";
        });
    }

    @McpTool(name = "todo_history", description = "Zeigt den Verlauf eines ToDos (Änderungen, Statuswechsel, "
            + "Verknüpfungen) in zeitlicher Reihenfolge.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<HistoryItem> todoHistory(McpTransportContext context,
                                         @McpToolParam(description = "ID des ToDos") Long todoId) {
        return tools.read(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            todos.get(id, user);
            TodoViews.Labels labels = views.labels(user);
            return history.findAllByTodoIdOrderByAtAscIdAsc(id).stream()
                    .map((TodoHistoryEntry e) -> new HistoryItem(String.valueOf(e.getAt()),
                            labels.user(e.getActorId()), formatter.text(e, labels, user)))
                    .toList();
        });
    }

    @McpTool(name = "set_todo_notifications", description = "Stellt für ein ToDo ein, ob E-Mail und Push "
            + "benachrichtigen: DEFAULT (Standard des Benutzers), ON oder OFF. Nicht angegebene Kanäle bleiben "
            + "unverändert.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public String setTodoNotifications(McpTransportContext context,
                                       @McpToolParam(description = "ID des ToDos") Long todoId,
                                       @McpToolParam(description = "E-Mail: DEFAULT, ON oder OFF", required = false)
                                       String email,
                                       @McpToolParam(description = "Push: DEFAULT, ON oder OFF", required = false)
                                       String push) {
        return tools.write(context, user -> {
            Long id = Tools.required(todoId, "todoId");
            Todo todo = todos.get(id, user);
            if (!access.can(user, "NOTIFY_OVERRIDE", todo)) {
                throw new AccessDeniedException("Benachrichtigungen dieses ToDos ändern");
            }
            NotificationPreferences.Choice mail = Tools.enumValue(NotificationPreferences.Choice.class, email, "email");
            NotificationPreferences.Choice pushChoice =
                    Tools.enumValue(NotificationPreferences.Choice.class, push, "push");
            if (mail != null) {
                preferences.set(NotificationPreferences.Scope.TODO, id, NotificationPreferences.ALL, Channel.EMAIL,
                        mail);
            }
            if (pushChoice != null) {
                preferences.set(NotificationPreferences.Scope.TODO, id, NotificationPreferences.ALL, Channel.PUSH,
                        pushChoice);
            }
            return "E-Mail: " + preferences.choice(NotificationPreferences.Scope.TODO, id,
                    NotificationPreferences.ALL, Channel.EMAIL) + ", Push: "
                    + preferences.choice(NotificationPreferences.Scope.TODO, id, NotificationPreferences.ALL,
                    Channel.PUSH);
        });
    }

    private Structure structureOf(Long id, club.badernet.nestwerk.user.User user) {
        Todo todo = todos.get(id, user);
        TodoViews.Labels labels = views.labels(user);
        TodoStructureService.Children children = structure.children(id, user);
        List<Todo> ancestors = structure.ancestors(id, user);
        TodoRef parent = ancestors.isEmpty() ? null : ref(ancestors.get(ancestors.size() - 1));
        List<LinkItem> links = structure.links(id, user).stream()
                .map(l -> new LinkItem(l.linkId(), l.label(), ref(l.other()))).toList();
        return new Structure(id, parent, ancestors.stream().map(this::ref).toList(),
                children.visible().stream().map(this::ref).toList(), children.hiddenCount(), children.progress(),
                links, todo.getWatcherUserIds().stream().map(labels::user).sorted().toList(),
                todo.getWatcherUserIds().contains(user.getId()));
    }

    private TodoRef ref(Todo t) {
        return new TodoRef(t.getId(), t.getTitle(), t.getStatus().name(), t.isClosed());
    }
}
