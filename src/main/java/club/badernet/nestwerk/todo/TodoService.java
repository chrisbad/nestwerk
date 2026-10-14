package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.contact.ContactRepository;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Alle Änderungen an ToDos, jeweils mit Rechteprüfung über {@link TodoAccess}. */
@Service
public class TodoService {

    public static final int MAX_DESCRIPTION = 20_000;
    public static final int MAX_TAG = 40;
    public static final int MAX_TAGS = 20;

    private final TodoRepository todos;
    private final TodoAccess access;
    private final FamilyMemberRepository members;
    private final RoleRepository roles;
    private final ContactRepository contacts;
    private final UserRepository users;
    private final ContactService contactService;

    public TodoService(TodoRepository todos, TodoAccess access, FamilyMemberRepository members,
                       RoleRepository roles, ContactRepository contacts, UserRepository users,
                       ContactService contactService) {
        this.todos = todos;
        this.access = access;
        this.members = members;
        this.roles = roles;
        this.contacts = contacts;
        this.users = users;
        this.contactService = contactService;
    }

    /** Liefert das ToDo, wenn der Betrachter es sehen darf; sonst wie „nicht vorhanden“. */
    @Transactional(readOnly = true)
    public Todo get(Long id, User viewer) {
        Todo todo = todos.findById(id).orElseThrow(TodoNotFoundException::new);
        if (!access.can(viewer, "VIEW", todo)) {
            throw new TodoNotFoundException();
        }
        return todo;
    }

    @Transactional
    public Todo create(TodoInput in, User actor) {
        if (!access.canCreate(actor)) {
            throw new AccessDeniedException("Keine Berechtigung zum Anlegen von ToDos");
        }
        if (in.privateTodo() && !access.canCreatePrivate(actor)) {
            throw new AccessDeniedException("Keine Berechtigung für private ToDos");
        }
        Todo todo = new Todo(requireTitle(in.title()), actor.getId());
        applyContent(todo, in);
        todo.setPrivateTodo(in.privateTodo());
        todo.replaceAssignees(validAssignees(in.assignees(), actor, Set.of()));
        if (in.responsibleUserId() != null && !in.responsibleUserId().equals(actor.getId())) {
            if (!access.can(actor, "CHANGE_RESPONSIBLE", todo)) {
                throw new AccessDeniedException("Keine Berechtigung, jemand anderen verantwortlich zu machen");
            }
            todo.setResponsibleUserId(requireActiveUser(in.responsibleUserId()));
        }
        syncCompletions(todo);
        todo.markChanged(actor.getId());
        return todos.save(todo);
    }

    /**
     * Ändert ein ToDo. Jede geänderte Kategorie braucht ihr Recht: Inhalte {@code EDIT}, Zuständige
     * {@code ASSIGN}, Verantwortlicher {@code CHANGE_RESPONSIBLE}.
     */
    @Transactional
    public void update(Long id, TodoInput in, int expectedVersion, User actor) {
        Todo todo = get(id, actor);
        if (todo.getVersion() != expectedVersion) {
            throw new TodoConflictException(nameOf(todo.getUpdatedBy()));
        }
        String title = requireTitle(in.title());
        Set<AssigneeRef> assignees = validAssignees(in.assignees(), actor, todo.assigneeRefs());
        boolean contentChanged = contentDiffers(todo, title, in);
        boolean assigneesChanged = !todo.assigneeRefs().equals(assignees)
                || todo.getCompletionMode() != mode(in);
        boolean responsibleChanged = in.responsibleUserId() != null
                && !in.responsibleUserId().equals(todo.getResponsibleUserId());

        if (contentChanged) {
            require(actor, "EDIT", todo);
            if (in.privateTodo() && !todo.isPrivateTodo() && !access.canCreatePrivate(actor)) {
                throw new AccessDeniedException("Keine Berechtigung für private ToDos");
            }
        }
        if (assigneesChanged) {
            require(actor, "ASSIGN", todo);
        }
        if (responsibleChanged) {
            require(actor, "CHANGE_RESPONSIBLE", todo);
        }
        if (contentChanged) {
            todo.setTitle(title);
            applyContent(todo, in);
            todo.setPrivateTodo(in.privateTodo());
        }
        if (assigneesChanged) {
            todo.setCompletionMode(mode(in));
            todo.replaceAssignees(assignees);
            syncCompletions(todo);
        }
        if (responsibleChanged) {
            todo.setResponsibleUserId(requireActiveUser(in.responsibleUserId()));
        }
        if (contentChanged || assigneesChanged || responsibleChanged) {
            todo.markChanged(actor.getId());
        }
    }

    @Transactional
    public void changeStatus(Long id, TodoStatus status, User actor) {
        Todo todo = get(id, actor);
        require(actor, "CHANGE_STATUS", todo);
        todo.changeStatus(status, actor.getId());
        todo.markChanged(actor.getId());
    }

    /** „Ich kümmere mich“: setzt den Status auf In Arbeit. */
    @Transactional
    public void take(Long id, User actor) {
        changeStatus(id, TodoStatus.IN_PROGRESS, actor);
    }

    /**
     * Abhaken. Bei „einer reicht“ (oder ohne abhakpflichtige Stellen) ist das ToDo erledigt; bei „jeder
     * einzeln“ hakt der Benutzer seine Stelle ab (als Verantwortlicher auch die der Kontakte), und das
     * ToDo ist erledigt, sobald alle Stellen abgehakt sind.
     */
    @Transactional
    public void check(Long id, User actor) {
        Todo todo = get(id, actor);
        require(actor, "CHANGE_STATUS", todo);
        if (todo.getCompletionMode() == CompletionMode.ANY || todo.getCompletions().isEmpty()) {
            todo.changeStatus(TodoStatus.DONE, actor.getId());
        } else {
            Set<TodoCompletion> mine = ownCompletions(todo, actor);
            if (mine.isEmpty()) {
                throw new IllegalArgumentException("Bei „jeder einzeln“ hakt jeder Zuständige selbst ab. "
                        + "Über den Status lässt sich das ToDo trotzdem abschließen.");
            }
            mine.forEach(c -> c.complete(actor.getId()));
            if (todo.getCompletions().stream().allMatch(TodoCompletion::isDone)) {
                todo.changeStatus(TodoStatus.DONE, actor.getId());
            }
        }
        todo.markChanged(actor.getId());
    }

    @Transactional
    public void uncheck(Long id, User actor) {
        Todo todo = get(id, actor);
        require(actor, "CHANGE_STATUS", todo);
        if (todo.getCompletionMode() == CompletionMode.EACH) {
            ownCompletions(todo, actor).forEach(TodoCompletion::reopen);
        }
        if (todo.getStatus() == TodoStatus.DONE) {
            todo.changeStatus(TodoStatus.OPEN, actor.getId());
        }
        todo.markChanged(actor.getId());
    }

    /** Hat der Benutzer bei „jeder einzeln“ seine Stelle schon abgehakt? */
    public boolean hasCheckedOwn(Todo todo, User user) {
        Set<TodoCompletion> mine = ownCompletions(todo, user);
        return !mine.isEmpty() && mine.stream().allMatch(TodoCompletion::isDone);
    }

    /** Muss der Benutzer bei „jeder einzeln“ selbst abhaken? */
    public boolean mustCheck(Todo todo, User user) {
        return !ownCompletions(todo, user).isEmpty();
    }

    @Transactional
    public void delete(Long id, User actor) {
        Todo todo = get(id, actor);
        require(actor, "DELETE", todo);
        todos.delete(todo);
    }

    // --- intern ----------------------------------------------------------------

    private Set<TodoCompletion> ownCompletions(Todo todo, User user) {
        Long memberId = access.memberId(user);
        boolean responsible = user.getId().equals(todo.getResponsibleUserId());
        Set<TodoCompletion> mine = new HashSet<>();
        for (TodoCompletion c : todo.getCompletions()) {
            if ((c.getMemberId() != null && c.getMemberId().equals(memberId))
                    || (c.getContactId() != null && responsible)) {
                mine.add(c);
            }
        }
        return mine;
    }

    /** Berechnet die Stellen bei „jeder einzeln“: Mitglieder direkt, Rollen über ihre Benutzer, Kontakte. */
    private void syncCompletions(Todo todo) {
        if (todo.getCompletionMode() != CompletionMode.EACH) {
            todo.clearCompletions();
            return;
        }
        Set<Long> memberIds = new LinkedHashSet<>();
        Set<Long> contactIds = new LinkedHashSet<>();
        for (AssigneeRef ref : todo.assigneeRefs()) {
            switch (ref.type()) {
                case MEMBER -> memberIds.add(ref.id());
                case CONTACT -> contactIds.add(ref.id());
                case ROLE -> {
                    Set<Long> userIds = new HashSet<>();
                    users.findAllByRoles_Id(ref.id()).forEach(u -> userIds.add(u.getId()));
                    if (!userIds.isEmpty()) {
                        members.findAllByUserIdIn(userIds).stream().map(FamilyMember::getId).forEach(memberIds::add);
                    }
                }
            }
        }
        todo.syncCompletions(memberIds, contactIds);
    }

    private void applyContent(Todo todo, TodoInput in) {
        if (in.availableFrom() != null && in.dueOn() != null && in.availableFrom().isAfter(in.dueOn())) {
            throw new IllegalArgumentException("Das Datum „ab“ darf nicht nach „bis“ liegen.");
        }
        if (in.remindDaysBefore() != null && (in.remindDaysBefore() < 0 || in.remindDaysBefore() > 365)) {
            throw new IllegalArgumentException("Die Erinnerung muss zwischen 0 und 365 Tagen vorher liegen.");
        }
        String description = clean(in.description());
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("Die Beschreibung darf höchstens " + MAX_DESCRIPTION + " Zeichen lang sein.");
        }
        todo.setDescription(description);
        todo.setPriority(in.priority() == null ? TodoPriority.NORMAL : in.priority());
        todo.setAvailableFrom(in.availableFrom());
        todo.setDueOn(in.dueOn());
        todo.setFollowUpOn(in.followUpOn());
        todo.setRemindDaysBefore(in.remindDaysBefore());
        todo.setTags(cleanTags(in.tags()));
        if (todo.getId() == null) {
            todo.setCompletionMode(mode(in));
        }
    }

    private boolean contentDiffers(Todo todo, String title, TodoInput in) {
        return !title.equals(todo.getTitle())
                || !Objects.equals(clean(in.description()), todo.getDescription())
                || (in.priority() == null ? TodoPriority.NORMAL : in.priority()) != todo.getPriority()
                || !Objects.equals(in.availableFrom(), todo.getAvailableFrom())
                || !Objects.equals(in.dueOn(), todo.getDueOn())
                || !Objects.equals(in.followUpOn(), todo.getFollowUpOn())
                || !Objects.equals(in.remindDaysBefore(), todo.getRemindDaysBefore())
                || in.privateTodo() != todo.isPrivateTodo()
                || !cleanTags(in.tags()).equals(todo.getTags());
    }

    private static CompletionMode mode(TodoInput in) {
        return in.completionMode() == null ? CompletionMode.ANY : in.completionMode();
    }

    /**
     * Prüft die Zuständigen. Mitglieder und Rollen sind für alle sichtbar; neu hinzukommende Kontakte muss
     * der Handelnde sehen dürfen, sonst ließen sich fremde Kontakte über erratene IDs einsehen.
     */
    private Set<AssigneeRef> validAssignees(Set<AssigneeRef> refs, User actor, Set<AssigneeRef> existing) {
        Set<AssigneeRef> valid = new LinkedHashSet<>();
        if (refs == null) {
            return valid;
        }
        for (AssigneeRef ref : refs) {
            boolean exists = switch (ref.type()) {
                case MEMBER -> members.existsById(ref.id());
                case ROLE -> roles.existsById(ref.id());
                case CONTACT -> existing.contains(ref) || contacts.findById(ref.id())
                        .filter(c -> contactService.can(actor, "VIEW", c)).isPresent();
            };
            if (!exists) {
                throw new IllegalArgumentException("Unbekannte Zuständigkeit ausgewählt.");
            }
            valid.add(ref);
        }
        return valid;
    }

    private Long requireActiveUser(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Benutzer als Verantwortlicher."));
        if (!user.isEnabled()) {
            throw new IllegalArgumentException("Ein deaktivierter Benutzer kann nicht verantwortlich sein.");
        }
        return user.getId();
    }

    private void require(User actor, String action, Todo todo) {
        if (!access.can(actor, action, todo)) {
            throw new AccessDeniedException("Keine Berechtigung für diese Änderung");
        }
    }

    private String nameOf(Long userId) {
        return userId == null ? "jemand anderem" : users.findById(userId).map(User::getUsername).orElse("jemand anderem");
    }

    static Set<String> cleanTags(Set<String> tags) {
        Set<String> result = new LinkedHashSet<>();
        if (tags == null) {
            return result;
        }
        for (String tag : tags) {
            String t = clean(tag);
            if (t == null) {
                continue;
            }
            t = t.toLowerCase(Locale.GERMAN);
            if (t.length() > MAX_TAG) {
                throw new IllegalArgumentException("Ein Schlagwort darf höchstens " + MAX_TAG + " Zeichen lang sein.");
            }
            result.add(t);
        }
        if (result.size() > MAX_TAGS) {
            throw new IllegalArgumentException("Höchstens " + MAX_TAGS + " Schlagworte je ToDo.");
        }
        return result;
    }

    private static String requireTitle(String title) {
        String cleaned = clean(title);
        if (cleaned == null || cleaned.length() > 200) {
            throw new IllegalArgumentException("Der Titel muss 1–200 Zeichen lang sein.");
        }
        return cleaned;
    }

    private static String clean(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
