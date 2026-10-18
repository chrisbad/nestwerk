package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.user.User;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Unteraufgaben und Verknüpfungen zwischen ToDos; Änderungen brauchen die ACL-Aktion {@code LINK}. */
@Service
public class TodoStructureService {

    /** Unteraufgaben eines ToDos aus Sicht eines Betrachters. */
    public record Children(List<Todo> visible, int hiddenCount, int doneCount, int total) {
        public String progress() {
            return total == 0 ? null : doneCount + "/" + total;
        }

        public int percent() {
            return total == 0 ? 0 : Math.round(100f * doneCount / total);
        }
    }

    /** Eine Verknüpfung aus Sicht eines ToDos. */
    public record LinkView(Long linkId, TodoLinkType type, boolean outgoing, String label, Todo other) {
    }

    /** Offene blockierende ToDos; unsichtbare zählen mit, ihre Titel bleiben verborgen. */
    public record Blockers(long count, List<String> visibleTitles) {
        public boolean any() {
            return count > 0;
        }

        /** Text für die Rückfrage beim Abhaken. */
        public String warning() {
            if (count == 0) {
                return null;
            }
            String names = visibleTitles.isEmpty() ? "" : " („" + String.join("“, „", visibleTitles) + "“"
                    + (visibleTitles.size() < count ? " und weitere" : "") + ")";
            return "Wird noch von " + (count == 1 ? "einem offenen ToDo" : count + " offenen ToDos") + " blockiert"
                    + names + ". Trotzdem erledigen?";
        }
    }

    private static final int MAX_DEPTH = 100;

    private final TodoRepository todos;
    private final TodoLinkRepository links;
    private final TodoService service;
    private final TodoAccess access;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final TodoHistoryRecorder history;

    public TodoStructureService(TodoRepository todos, TodoLinkRepository links, TodoService service,
                                TodoAccess access, NamedParameterJdbcTemplate jdbc, EntityManager entityManager,
                                TodoHistoryRecorder history) {
        this.history = history;
        this.todos = todos;
        this.links = links;
        this.service = service;
        this.access = access;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    // --- Unteraufgaben -----------------------------------------------------------

    @Transactional
    public Todo createChild(Long parentId, TodoInput input, User actor) {
        Todo parent = service.get(parentId, actor);
        require(actor, "LINK", parent);
        Todo child = service.create(input, actor);
        child.setParentId(parent.getId());
        history.record(parent.getId(), actor.getId(), "CHILD_ADDED", null, String.valueOf(child.getId()));
        return child;
    }

    /** Hängt ein ToDo unter ein anderes ({@code null} = oberste Ebene). */
    @Transactional
    public void moveTo(Long id, Long newParentId, User actor) {
        Todo todo = service.get(id, actor);
        require(actor, "LINK", todo);
        if (newParentId != null) {
            Todo parent = service.get(newParentId, actor);
            require(actor, "LINK", parent);
            if (newParentId.equals(id) || descendantIds(id).contains(newParentId)) {
                throw new IllegalArgumentException("Das ergäbe einen Kreis: Ein ToDo kann nicht unter sich selbst hängen.");
            }
        }
        Long oldParent = todo.getParentId();
        todo.setParentId(newParentId);
        todo.markChanged(actor.getId());
        history.record(id, actor.getId(), "PARENT", oldParent == null ? null : oldParent.toString(),
                newParentId == null ? null : newParentId.toString());
        if (newParentId != null) {
            history.record(newParentId, actor.getId(), "CHILD_ADDED", null, String.valueOf(id));
        }
    }

    @Transactional(readOnly = true)
    public Children children(Long id, User viewer) {
        List<Todo> all = todos.findAllByParentId(id);
        List<Todo> visible = new ArrayList<>(all.stream().filter(t -> access.can(viewer, "VIEW", t)).toList());
        visible.sort(TodoQuery.ORDER);
        int done = (int) all.stream().filter(Todo::isClosed).count();
        return new Children(visible, all.size() - visible.size(), done, all.size());
    }

    /** Übergeordnete ToDos von oben nach unten; unsichtbare werden ausgelassen. */
    @Transactional(readOnly = true)
    public List<Todo> ancestors(Long id, User viewer) {
        List<Todo> chain = new ArrayList<>();
        Long current = todos.findById(id).map(Todo::getParentId).orElse(null);
        int guard = 0;
        while (current != null && guard++ < MAX_DEPTH) {
            Optional<Todo> parent = todos.findById(current);
            if (parent.isEmpty()) {
                break;
            }
            if (access.can(viewer, "VIEW", parent.get())) {
                chain.add(parent.get());
            }
            current = parent.get().getParentId();
        }
        Collections.reverse(chain);
        return chain;
    }

    @Transactional(readOnly = true)
    public int descendantCount(Long id) {
        return descendantIds(id).size();
    }

    Set<Long> descendantIds(Long id) {
        entityManager.flush();
        return new HashSet<>(jdbc.queryForList("""
                WITH RECURSIVE d(id) AS (
                    SELECT id FROM todos WHERE parent_id = :id
                    UNION
                    SELECT t.id FROM todos t JOIN d ON t.parent_id = d.id
                )
                SELECT id FROM d""", Map.of("id", id), Long.class));
    }

    // --- Verknüpfungen -----------------------------------------------------------

    @Transactional
    public TodoLink link(Long sourceId, Long targetId, TodoLinkType type, User actor) {
        Todo source = service.get(sourceId, actor);
        Todo target = service.get(targetId, actor);
        require(actor, "LINK", source);
        if (source.getId().equals(target.getId())) {
            throw new IllegalArgumentException("Ein ToDo kann nicht mit sich selbst verknüpft werden.");
        }
        boolean exists = links.existsBySourceIdAndTargetIdAndType(sourceId, targetId, type)
                || (type != TodoLinkType.BLOCKS && links.existsBySourceIdAndTargetIdAndType(targetId, sourceId, type));
        if (exists) {
            throw new IllegalArgumentException("Diese Verknüpfung gibt es schon.");
        }
        if (type == TodoLinkType.BLOCKS && blocksTransitively(targetId, sourceId)) {
            throw new IllegalArgumentException("Das ergäbe einen Kreis aus Blockaden.");
        }
        TodoLink link = links.save(new TodoLink(sourceId, targetId, type, actor.getId()));
        history.record(sourceId, actor.getId(), "LINK_ADDED", null, type.name() + ":" + targetId);
        history.record(targetId, actor.getId(), "LINK_ADDED", null, incoming(type) + ":" + sourceId);
        if (type == TodoLinkType.DUPLICATES && !source.isClosed()) {
            source.changeStatus(TodoStatus.DISCARDED, actor.getId());
            service.afterStatusChange(source, actor);
        }
        source.markChanged(actor.getId());
        return link;
    }

    @Transactional
    public void unlink(Long linkId, User actor) {
        TodoLink link = links.findById(linkId).orElseThrow(TodoNotFoundException::new);
        Todo source = service.get(link.getSourceId(), actor);
        Todo target = service.get(link.getTargetId(), actor);
        if (!access.can(actor, "LINK", source) && !access.can(actor, "LINK", target)) {
            throw new AccessDeniedException("Keine Berechtigung, diese Verknüpfung zu lösen");
        }
        links.delete(link);
        source.markChanged(actor.getId());
        history.record(source.getId(), actor.getId(), "LINK_REMOVED", link.getType().name() + ":" + target.getId(), null);
        history.record(target.getId(), actor.getId(), "LINK_REMOVED", incoming(link.getType()) + ":" + source.getId(), null);
    }

    /** Name der Gegenrichtung für den Verlauf. */
    static String incoming(TodoLinkType type) {
        return switch (type) {
            case BLOCKS -> "BLOCKED_BY";
            case RELATES -> "RELATES";
            case DUPLICATES -> "DUPLICATED_BY";
        };
    }

    /** Sichtbare Verknüpfungen eines ToDos in beide Richtungen. */
    @Transactional(readOnly = true)
    public List<LinkView> links(Long id, User viewer) {
        List<LinkView> result = new ArrayList<>();
        for (TodoLink link : links.findAllBySourceIdOrTargetId(id, id)) {
            boolean outgoing = link.getSourceId().equals(id);
            Long otherId = outgoing ? link.getTargetId() : link.getSourceId();
            todos.findById(otherId).filter(t -> access.can(viewer, "VIEW", t)).ifPresent(other ->
                    result.add(new LinkView(link.getId(), link.getType(), outgoing,
                            outgoing ? link.getType().outgoing() : link.getType().incoming(), other)));
        }
        result.sort((a, b) -> a.label().equals(b.label())
                ? a.other().getTitle().compareToIgnoreCase(b.other().getTitle())
                : a.label().compareTo(b.label()));
        return result;
    }

    @Transactional(readOnly = true)
    public Blockers openBlockers(Todo todo, User viewer) {
        long count = 0;
        List<String> titles = new ArrayList<>();
        for (TodoLink link : links.findAllByTargetIdAndType(todo.getId(), TodoLinkType.BLOCKS)) {
            Optional<Todo> blocker = todos.findById(link.getSourceId());
            if (blocker.isPresent() && !blocker.get().isClosed()) {
                count++;
                if (access.can(viewer, "VIEW", blocker.get())) {
                    titles.add(blocker.get().getTitle());
                }
            }
        }
        return new Blockers(count, titles);
    }

    /** Blockiert {@code from} (direkt oder über andere) das ToDo {@code to}? */
    private boolean blocksTransitively(Long from, Long to) {
        Set<Long> seen = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (current.equals(to)) {
                return true;
            }
            if (seen.add(current)) {
                links.findAllBySourceIdAndType(current, TodoLinkType.BLOCKS).forEach(l -> queue.add(l.getTargetId()));
            }
        }
        return false;
    }

    private void require(User actor, String action, Todo todo) {
        if (!access.can(actor, action, todo)) {
            throw new AccessDeniedException("Keine Berechtigung für diese Änderung");
        }
    }
}
