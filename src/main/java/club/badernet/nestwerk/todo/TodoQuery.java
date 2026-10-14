package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.user.User;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gefilterte, nach Zeitlage gruppierte ToDo-Listen. Die Auswahl der IDs (Sichtbarkeit und Filter) läuft
 * in SQL, die Gruppierung in Java.
 */
@Service
public class TodoQuery {

    /** Priorität absteigend, dann Fälligkeit aufsteigend (ohne Datum zuletzt), dann Titel. */
    public static final Comparator<Todo> ORDER = Comparator
            .comparing((Todo t) -> t.getPriority().ordinal(), Comparator.reverseOrder())
            .thenComparing(Todo::getDueOn, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(t -> t.getTitle().toLowerCase(Locale.GERMAN));

    private static final Set<TodoTiming> URGENT = Set.of(TodoTiming.OVERDUE, TodoTiming.DUE_SOON, TodoTiming.CURRENT);

    private final NamedParameterJdbcTemplate jdbc;
    private final TodoRepository todos;
    private final TodoAccess access;
    private final EntityManager entityManager;
    private final Clock clock;

    public TodoQuery(NamedParameterJdbcTemplate jdbc, TodoRepository todos, TodoAccess access,
                     EntityManager entityManager, Clock clock) {
        this.jdbc = jdbc;
        this.todos = todos;
        this.access = access;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public java.time.ZoneId zone() {
        return clock.getZone();
    }

    @Transactional(readOnly = true)
    public List<TodoGroup> list(User viewer, TodoFilter filter) {
        LocalDate today = today();
        Map<TodoTiming, List<Todo>> grouped = new EnumMap<>(TodoTiming.class);
        for (Todo todo : load(viewer, filter)) {
            TodoTiming timing = TodoTiming.of(todo, today);
            if (filter.timing() == null || filter.timing() == timing) {
                grouped.computeIfAbsent(timing, k -> new ArrayList<>()).add(todo);
            }
        }
        List<TodoGroup> result = new ArrayList<>();
        grouped.forEach((timing, list) -> {
            list.sort(ORDER);
            result.add(new TodoGroup(timing, list));
        });
        return result;
    }

    /** Für die Start-Kachel: eigene überfällige, bald fällige und aktuelle ToDos. */
    @Transactional(readOnly = true)
    public List<Todo> mine(User viewer, int limit) {
        return list(viewer, TodoFilter.defaults().withInvolvement(TodoFilter.Involvement.MINE)).stream()
                .filter(g -> URGENT.contains(g.timing()))
                .flatMap(g -> g.todos().stream())
                .limit(limit)
                .toList();
    }

    /** Alle Schlagworte an ToDos, die der Betrachter sehen darf. */
    @Transactional(readOnly = true)
    public List<String> tags(User viewer) {
        entityManager.flush();
        TodoAccess.VisibilitySql visible = access.visibility(viewer);
        return jdbc.queryForList("SELECT DISTINCT g.tag FROM todo_tags g JOIN todos t ON t.id = g.todo_id WHERE "
                + visible.sql() + " ORDER BY g.tag", visible.params(), String.class);
    }

    private List<Todo> load(User viewer, TodoFilter filter) {
        entityManager.flush();
        TodoAccess.VisibilitySql visible = access.visibility(viewer);
        StringBuilder sql = new StringBuilder("SELECT t.id FROM todos t WHERE ").append(visible.sql());
        Map<String, Object> params = new HashMap<>(visible.params());
        params.put("qUser", viewer.getId());

        switch (filter.involvement() == null ? TodoFilter.Involvement.ALL : filter.involvement()) {
            case MINE -> sql.append(" AND (t.responsible_user_id = :qUser OR ").append(watchingSql())
                    .append(" OR ").append(access.assignedSql(viewer, params)).append(")");
            case RESPONSIBLE -> sql.append(" AND t.responsible_user_id = :qUser");
            case WATCHING -> sql.append(" AND ").append(watchingSql());
            case ALL -> { }
        }
        if (filter.status() != null) {
            sql.append(" AND t.status = :qStatus");
            params.put("qStatus", filter.status().name());
        } else if (!filter.showsClosed()) {
            sql.append(" AND t.status IN ('OPEN', 'IN_PROGRESS')");
        }
        if (filter.priority() != null) {
            sql.append(" AND t.priority = :qPriority");
            params.put("qPriority", filter.priority().name());
        }
        if (filter.tag() != null && !filter.tag().isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM todo_tags g WHERE g.todo_id = t.id AND g.tag = :qTag)");
            params.put("qTag", filter.tag().trim().toLowerCase(Locale.GERMAN));
        }
        if (filter.text() != null && !filter.text().isBlank()) {
            sql.append(" AND (lower(t.title) LIKE :qText OR lower(coalesce(t.description, '')) LIKE :qText)");
            params.put("qText", "%" + escapeLike(filter.text().trim().toLowerCase(Locale.GERMAN)) + "%");
        }
        if (filter.assignee() != null) {
            String column = switch (filter.assignee().type()) {
                case MEMBER -> "member_id";
                case ROLE -> "role_id";
                case CONTACT -> "contact_id";
            };
            sql.append(" AND EXISTS (SELECT 1 FROM todo_assignees qa WHERE qa.todo_id = t.id AND qa.")
                    .append(column).append(" = :qAssignee)");
            params.put("qAssignee", filter.assignee().id());
        }
        List<Long> ids = jdbc.queryForList(sql.toString(), params, Long.class);
        return ids.isEmpty() ? List.of() : todos.findAllById(ids);
    }

    private static String watchingSql() {
        return "EXISTS (SELECT 1 FROM todo_watchers qw WHERE qw.todo_id = t.id AND qw.user_id = :qUser)";
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
