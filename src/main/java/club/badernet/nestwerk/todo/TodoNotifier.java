package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ermittelt Empfänger für ToDo-Ereignisse und Erinnerungen und legt die Benachrichtigungen an.
 * Empfänger müssen aktiv sein und das ToDo sehen dürfen; der Auslöser wird nie benachrichtigt.
 */
@Component
public class TodoNotifier {

    static final String SOURCE = "TODO";
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMANY);

    private final NotificationService notifications;
    private final TodoAccess access;
    private final UserRepository users;
    private final FamilyMemberRepository members;
    private final TodoLinkRepository links;
    private final TodoRepository todos;

    public TodoNotifier(NotificationService notifications, TodoAccess access, UserRepository users,
                        FamilyMemberRepository members, TodoLinkRepository links, TodoRepository todos) {
        this.notifications = notifications;
        this.access = access;
        this.users = users;
        this.members = members;
        this.links = links;
        this.todos = todos;
    }

    // --- Ereignisse ----------------------------------------------------------------

    public void assigned(Todo todo, Collection<AssigneeRef> added, User actor) {
        Set<User> recipients = new HashSet<>();
        added.forEach(ref -> recipients.addAll(usersOf(ref)));
        send(visible(recipients, todo, actor), todo, NotificationType.TODO_ASSIGNED, eventKey(),
                actor.getUsername() + " hat dir das ToDo zugewiesen.");
    }

    public void commented(Todo todo, TodoComment comment, Set<User> mentioned, User actor) {
        String excerpt = excerpt(comment.getBody());
        Set<User> mentionedVisible = visible(mentioned, todo, actor);
        send(mentionedVisible, todo, NotificationType.TODO_MENTION, "c" + comment.getId(),
                actor.getUsername() + " hat dich erwähnt: " + excerpt);
        Set<User> others = visible(involved(todo, false), todo, actor);
        others.removeAll(mentionedVisible);
        send(others, todo, NotificationType.TODO_COMMENT, "c" + comment.getId(),
                actor.getUsername() + " hat kommentiert: " + excerpt);
    }

    /** Erledigt/Verworfen an Verantwortlichen und Beobachter; danach ggf. „nicht mehr blockiert“. */
    public void statusChanged(Todo todo, TodoStatus before, User actor) {
        if (!todo.isClosed() || before.isClosed()) {
            return;
        }
        Set<User> recipients = new HashSet<>();
        user(todo.getResponsibleUserId()).ifPresent(recipients::add);
        todo.getWatcherUserIds().forEach(id -> user(id).ifPresent(recipients::add));
        send(visible(recipients, todo, actor), todo, NotificationType.TODO_STATUS, eventKey(),
                todo.getStatus().label() + " von " + actor.getUsername() + ".");

        for (TodoLink link : links.findAllBySourceIdAndType(todo.getId(), TodoLinkType.BLOCKS)) {
            todos.findById(link.getTargetId()).filter(t -> !t.isClosed()).filter(t -> !hasOpenBlocker(t))
                    .ifPresent(blocked -> send(visible(involved(blocked, false), blocked, actor), blocked,
                            NotificationType.TODO_UNBLOCKED, "u" + todo.getId(),
                            "Nicht mehr blockiert: „" + todo.getTitle() + "“ ist abgeschlossen."));
        }
    }

    // --- Erinnerungen --------------------------------------------------------------

    /** Erinnerung zu einem Stichtag; bei „jeder einzeln“ nur an Zuständige, die noch nicht abgehakt haben. */
    public void remind(Todo todo, NotificationType type, LocalDate day) {
        String body = switch (type) {
            case TODO_AVAILABLE -> "Ab heute aktuell.";
            case TODO_DUE_SOON -> "Fällig bis " + SHORT.format(todo.getDueOn()) + ".";
            case TODO_OVERDUE -> "Überfällig seit " + SHORT.format(todo.getDueOn()) + ".";
            case TODO_FOLLOW_UP -> "Wiedervorlage: Zeit, wieder draufzuschauen.";
            default -> type.label();
        };
        send(visible(involved(todo, true), todo, null), todo, type, day.toString(), body);
    }

    // --- Empfänger ------------------------------------------------------------------

    /** Verantwortlicher, Beobachter und Zuständige mit Konto. */
    Set<User> involved(Todo todo, boolean onlyPendingEach) {
        Set<User> result = new HashSet<>();
        user(todo.getResponsibleUserId()).ifPresent(result::add);
        todo.getWatcherUserIds().forEach(id -> user(id).ifPresent(result::add));
        Set<Long> doneMembers = new HashSet<>();
        if (onlyPendingEach && todo.getCompletionMode() == CompletionMode.EACH) {
            todo.getCompletions().stream().filter(TodoCompletion::isDone).map(TodoCompletion::getMemberId)
                    .filter(java.util.Objects::nonNull).forEach(doneMembers::add);
        }
        for (AssigneeRef ref : todo.assigneeRefs()) {
            for (User u : usersOf(ref)) {
                Optional<FamilyMember> m = members.findByUserId(u.getId());
                if (m.isEmpty() || !doneMembers.contains(m.get().getId())
                        || u.getId().equals(todo.getResponsibleUserId())) {
                    result.add(u);
                }
            }
        }
        return result;
    }

    private Set<User> usersOf(AssigneeRef ref) {
        Set<User> result = new HashSet<>();
        switch (ref.type()) {
            case MEMBER -> members.findById(ref.id()).map(FamilyMember::getUserId).flatMap(this::user)
                    .ifPresent(result::add);
            case ROLE -> result.addAll(users.findAllByRoles_Id(ref.id()));
            case CONTACT -> { }
        }
        return result;
    }

    private Set<User> visible(Collection<User> candidates, Todo todo, User actor) {
        Map<Long, User> byId = new LinkedHashMap<>();
        for (User u : candidates) {
            if (u.isEnabled() && (actor == null || !u.getId().equals(actor.getId())) && access.can(u, "VIEW", todo)) {
                byId.put(u.getId(), u);
            }
        }
        return new HashSet<>(byId.values());
    }

    private boolean hasOpenBlocker(Todo todo) {
        return links.findAllByTargetIdAndType(todo.getId(), TodoLinkType.BLOCKS).stream()
                .map(l -> todos.findById(l.getSourceId()))
                .anyMatch(t -> t.isPresent() && !t.get().isClosed());
    }

    private Optional<User> user(Long id) {
        return id == null ? Optional.empty() : users.findById(id);
    }

    private void send(Set<User> recipients, Todo todo, NotificationType type, String dueKey, String body) {
        for (User u : recipients) {
            notifications.notify(u.getId(), type, SOURCE, todo.getId(), dueKey, todo.getTitle(), body,
                    "/todos/" + todo.getId());
        }
    }

    private static String eventKey() {
        return "e" + Instant.now().toEpochMilli() + "-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    private static String excerpt(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 140 ? flat.substring(0, 137) + "…" : flat;
    }
}
