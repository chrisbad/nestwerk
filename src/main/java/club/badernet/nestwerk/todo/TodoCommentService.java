package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Kommentare an ToDos und @Erwähnungen darin. */
@Service
public class TodoCommentService {

    public static final int MAX_LENGTH = 10_000;

    /** {@code @name}, aber nicht mitten in einer E-Mail-Adresse. */
    private static final Pattern MENTION = Pattern.compile("(?<![\\p{L}\\p{N}._@-])@([\\p{L}\\p{N}._-]{1,100})");

    private final TodoCommentRepository comments;
    private final TodoService todos;
    private final TodoAccess access;
    private final UserRepository users;

    public TodoCommentService(TodoCommentRepository comments, TodoService todos, TodoAccess access,
                              UserRepository users) {
        this.comments = comments;
        this.todos = todos;
        this.access = access;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<TodoComment> forTodo(Long todoId, User viewer) {
        todos.get(todoId, viewer);
        return comments.findAllByTodoIdOrderByCreatedAtAsc(todoId);
    }

    @Transactional
    public TodoComment add(Long todoId, String body, User actor) {
        Todo todo = todos.get(todoId, actor);
        if (!access.can(actor, "COMMENT", todo)) {
            throw new AccessDeniedException("Keine Berechtigung zum Kommentieren");
        }
        return comments.save(new TodoComment(todoId, actor.getId(), requireBody(body)));
    }

    @Transactional
    public void edit(Long commentId, String body, User actor) {
        TodoComment comment = load(commentId, actor);
        if (!comment.getAuthorId().equals(actor.getId())) {
            throw new AccessDeniedException("Nur eigene Kommentare können bearbeitet werden");
        }
        comment.edit(requireBody(body));
    }

    @Transactional
    public void delete(Long commentId, User actor) {
        TodoComment comment = load(commentId, actor);
        if (!comment.getAuthorId().equals(actor.getId()) && !actor.isAdministrator()) {
            throw new AccessDeniedException("Nur eigene Kommentare können gelöscht werden");
        }
        comments.delete(comment);
    }

    /** Erwähnte, aktive Benutzer, die das ToDo sehen dürfen (Groß-/Kleinschreibung egal). */
    @Transactional(readOnly = true)
    public Set<User> mentioned(String body, Todo todo) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(body == null ? "" : body);
        while (m.find()) {
            names.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        Set<User> result = new LinkedHashSet<>();
        if (names.isEmpty()) {
            return result;
        }
        for (User user : users.findAll()) {
            if (user.isEnabled() && names.contains(user.getUsername().toLowerCase(Locale.ROOT))
                    && access.can(user, "VIEW", todo)) {
                result.add(user);
            }
        }
        return result;
    }

    public TodoComment load(Long commentId, User viewer) {
        TodoComment comment = comments.findById(commentId).orElseThrow(TodoNotFoundException::new);
        todos.get(comment.getTodoId(), viewer);
        return comment;
    }

    private static String requireBody(String body) {
        String cleaned = body == null ? "" : body.strip();
        if (cleaned.isEmpty() || cleaned.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Ein Kommentar muss 1–" + MAX_LENGTH + " Zeichen lang sein.");
        }
        return cleaned;
    }
}
