package club.badernet.nestwerk.todo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Verknüpfung zwischen ToDos und Kalenderterminen („Termin aus ToDo“). Rechte prüfen die Aufrufer. */
@Component
public class TodoEventLinks {

    private final JdbcTemplate jdbc;

    public TodoEventLinks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void link(Long todoId, Long eventId) {
        jdbc.update("INSERT INTO todo_event_links (todo_id, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                todoId, eventId);
    }

    public List<Long> eventIds(Long todoId) {
        return jdbc.queryForList("SELECT event_id FROM todo_event_links WHERE todo_id = ? ORDER BY event_id",
                Long.class, todoId);
    }

    public List<Long> todoIds(Long eventId) {
        return jdbc.queryForList("SELECT todo_id FROM todo_event_links WHERE event_id = ? ORDER BY todo_id",
                Long.class, eventId);
    }
}
