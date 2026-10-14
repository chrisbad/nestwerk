package club.badernet.nestwerk.web;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactRepository;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.CompletionMode;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoCompletion;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.todo.TodoStructureService;
import club.badernet.nestwerk.todo.TodoTiming;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Baut die Anzeige-Modelle für ToDo-Seiten (Namen, Farben, Fälligkeitstexte, Abhak-Zustand). */
@Component
public class TodoViews {

    static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d. MMM", Locale.GERMANY);
    static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMANY);

    /** Ein Zuständiger, wie er angezeigt wird; {@code kind} ist member, role oder contact. */
    public record AssigneeView(String key, String label, String color, String kind) {
    }

    /** Eine Zeile in Liste, Kachel oder Board. */
    public record TodoRow(Todo todo, TodoTiming timing, String dueLabel, List<AssigneeView> assignees,
                          boolean canCheck, boolean checked, String progress, String childProgress,
                          String blockedWarning) {
    }

    /** Namensverzeichnis für einen Betrachter; fremde, unsichtbare Kontakte werden maskiert. */
    public static final class Labels {
        private final Map<Long, FamilyMember> members = new HashMap<>();
        private final Map<Long, Role> roles = new HashMap<>();
        private final Map<Long, Contact> visibleContacts = new HashMap<>();
        private final Map<Long, String> userNames = new HashMap<>();

        public AssigneeView assignee(AssigneeRef ref) {
            return switch (ref.type()) {
                case MEMBER -> {
                    FamilyMember m = members.get(ref.id());
                    yield new AssigneeView(ref.key(), m == null ? "Mitglied" : m.getName(),
                            m == null ? null : m.getColor(), "member");
                }
                case ROLE -> {
                    Role r = roles.get(ref.id());
                    yield new AssigneeView(ref.key(), r == null ? "Rolle" : r.getName(), null, "role");
                }
                case CONTACT -> {
                    Contact c = visibleContacts.get(ref.id());
                    yield new AssigneeView(ref.key(), c == null ? "Externer Kontakt" : c.getName(), null, "contact");
                }
            };
        }

        public String user(Long id) {
            return id == null ? null : userNames.getOrDefault(id, "unbekannt");
        }

        public String member(Long id) {
            FamilyMember m = members.get(id);
            return m == null ? "Mitglied" : m.getName();
        }

        public String contact(Long id) {
            Contact c = visibleContacts.get(id);
            return c == null ? "Externer Kontakt" : c.getName();
        }

        public List<FamilyMember> members() {
            return members.values().stream().sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).toList();
        }

        public List<Role> roles() {
            return roles.values().stream().sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).toList();
        }

        public List<Contact> contacts() {
            return visibleContacts.values().stream()
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).toList();
        }
    }

    private final FamilyMemberRepository members;
    private final RoleRepository roles;
    private final ContactRepository contacts;
    private final ContactService contactService;
    private final UserRepository users;
    private final TodoAccess access;
    private final TodoService todoService;
    private final TodoStructureService structure;

    public TodoViews(FamilyMemberRepository members, RoleRepository roles, ContactRepository contacts,
                     ContactService contactService, UserRepository users, TodoAccess access, TodoService todoService,
                     TodoStructureService structure) {
        this.members = members;
        this.roles = roles;
        this.contacts = contacts;
        this.contactService = contactService;
        this.users = users;
        this.access = access;
        this.todoService = todoService;
        this.structure = structure;
    }

    @Transactional(readOnly = true)
    public Labels labels(User viewer) {
        Labels labels = new Labels();
        members.findAll().forEach(m -> labels.members.put(m.getId(), m));
        roles.findAll().forEach(r -> labels.roles.put(r.getId(), r));
        contacts.findAll().stream().filter(c -> contactService.can(viewer, "VIEW", c))
                .forEach(c -> labels.visibleContacts.put(c.getId(), c));
        users.findAll().forEach(u -> labels.userNames.put(u.getId(), u.getUsername()));
        return labels;
    }

    public TodoRow row(Todo todo, User viewer, Labels labels, LocalDate today) {
        List<AssigneeView> assignees = new ArrayList<>();
        todo.assigneeRefs().forEach(ref -> assignees.add(labels.assignee(ref)));
        boolean each = todo.getCompletionMode() == CompletionMode.EACH && !todo.getCompletions().isEmpty();
        boolean mustCheck = each && todoService.mustCheck(todo, viewer);
        boolean canStatus = access.can(viewer, "CHANGE_STATUS", todo);
        boolean canCheck = canStatus && (!each || mustCheck);
        boolean checked = mustCheck ? todoService.hasCheckedOwn(todo, viewer) : todo.getStatus() == TodoStatus.DONE;
        String progress = each ? todo.completedCount() + " von " + todo.getCompletions().size() : null;
        TodoTiming timing = TodoTiming.of(todo, today);
        String childProgress = structure.children(todo.getId(), viewer).progress();
        String blocked = todo.isClosed() ? null : structure.openBlockers(todo, viewer).warning();
        return new TodoRow(todo, timing, dueLabel(todo, timing, today), assignees, canCheck, checked, progress,
                childProgress, blocked);
    }

    public List<String> completionLines(Todo todo, Labels labels) {
        List<String> lines = new ArrayList<>();
        for (TodoCompletion c : todo.getCompletions()) {
            String who = c.getMemberId() != null ? labels.member(c.getMemberId()) : labels.contact(c.getContactId());
            lines.add((c.isDone() ? "✓ " : "○ ") + who);
        }
        return lines;
    }

    static String dueLabel(Todo todo, TodoTiming timing, LocalDate today) {
        if (timing == TodoTiming.DORMANT) {
            return "ab " + SHORT.format(todo.getAvailableFrom());
        }
        if (todo.getDueOn() == null || timing == TodoTiming.CLOSED) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(today, todo.getDueOn());
        if (days == 0) return "heute fällig";
        if (days == 1) return "morgen fällig";
        if (days == -1) return "seit gestern überfällig";
        if (days < 0) return "seit " + (-days) + " Tagen überfällig";
        if (days < 7) return "in " + days + " Tagen fällig";
        return "bis " + SHORT.format(todo.getDueOn());
    }

    private static final Map<String, String> RULES = Map.of(
            "FREQ=DAILY", "Täglich",
            "FREQ=WEEKLY", "Wöchentlich",
            "FREQ=WEEKLY;INTERVAL=2", "Alle 2 Wochen",
            "FREQ=MONTHLY", "Monatlich",
            "FREQ=MONTHLY;INTERVAL=6", "Halbjährlich",
            "FREQ=YEARLY", "Jährlich");

    public static String repeatLabel(String rrule) {
        return rrule == null ? null : RULES.getOrDefault(rrule, "Eigene Regel: " + rrule);
    }

    public static String longDate(LocalDate date) {
        return date == null ? null : LONG.format(date);
    }
}
