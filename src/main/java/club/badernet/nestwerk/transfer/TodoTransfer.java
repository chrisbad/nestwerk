package club.badernet.nestwerk.transfer;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoFilter;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoQuery;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ToDos als CSV (Semikolon, UTF-8 mit BOM, für Excel). Zuständige stehen als Namen in der Datei: Mitglieder mit
 * Namen, Rollen als „Rolle: …“, Kontakte als „Kontakt: …“. Mit ID wird ein vorhandenes ToDo aktualisiert.
 */
@Service
public class TodoTransfer {

    static final List<String> COLUMNS = List.of("ID", "Titel", "Beschreibung", "Status", "Priorität", "Ab", "Bis",
            "Wiedervorlage", "Schlagworte", "Zuständige", "Verantwortlich", "Privat");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** Streng, damit z. B. der 31.02. als Fehler auffällt statt still zum 28.02. zu werden. */
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("d.M.uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final String ROLE = "Rolle:";
    private static final String CONTACT = "Kontakt:";

    private final TodoQuery query;
    private final TodoService todos;
    private final TodoAccess access;
    private final TodoRepository repository;
    private final FamilyMemberRepository members;
    private final RoleRepository roles;
    private final ContactService contactService;
    private final UserRepository users;
    private final RowRunner runner;

    public TodoTransfer(TodoQuery query, TodoService todos, TodoAccess access, TodoRepository repository,
                        FamilyMemberRepository members,
                        RoleRepository roles, ContactService contactService, UserRepository users,
                        RowRunner runner) {
        this.query = query;
        this.todos = todos;
        this.access = access;
        this.repository = repository;
        this.members = members;
        this.roles = roles;
        this.contactService = contactService;
        this.users = users;
        this.runner = runner;
    }

    // --- Export ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public byte[] exportCsv(User viewer, TodoFilter filter) {
        Names names = names(viewer);
        StringBuilder out = new StringBuilder("﻿");
        try (CSVPrinter printer = new CSVPrinter(out, ContactTransfer.excel())) {
            printer.printRecord(COLUMNS);
            for (Todo t : query.list(viewer, filter).stream().flatMap(g -> g.todos().stream()).toList()) {
                printer.printRecord(t.getId(), t.getTitle(), t.getDescription(), t.getStatus().label(),
                        t.getPriority().label(), day(t.getAvailableFrom()), day(t.getDueOn()),
                        day(t.getFollowUpOn()), String.join(", ", t.getTags().stream().sorted().toList()),
                        t.assigneeRefs().stream().map(names::label).collect(Collectors.joining(", ")),
                        t.getResponsibleUserId() == null ? "" : names.users.getOrDefault(t.getResponsibleUserId(), ""),
                        t.isPrivateTodo() ? "ja" : "nein");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String day(LocalDate date) {
        return date == null ? "" : date.format(DAY);
    }

    // --- Import ------------------------------------------------------------------------

    public ImportReport importCsv(byte[] data, User actor, boolean commit) {
        if (!access.mayOpen(actor.getUsername())) {
            throw new AccessDeniedException("Keine Berechtigung für ToDos");
        }
        String text = new String(data, StandardCharsets.UTF_8).replaceFirst("^﻿", "");
        String header = text.lines().findFirst().orElse("");
        char delimiter = header.contains(";") || !header.contains(",") ? ';' : ',';
        Names names = names(actor);
        List<ImportReport.Row> rows = new ArrayList<>();
        try (CSVParser parser = CSVFormat.EXCEL.builder().setDelimiter(delimiter).setHeader()
                .setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true).get()
                .parse(new StringReader(text))) {
            if (!parser.getHeaderNames().contains("Titel")) {
                throw new IllegalArgumentException("Die CSV-Datei braucht eine Spalte „Titel“.");
            }
            for (CSVRecord record : parser) {
                if (rows.size() >= ContactTransfer.MAX_ROWS) {
                    throw new IllegalArgumentException("Die Datei enthält mehr als " + ContactTransfer.MAX_ROWS
                            + " ToDos.");
                }
                Function<String, String> get = col -> record.isMapped(col) && !record.get(col).isBlank()
                        ? record.get(col) : null;
                String title = Optional.ofNullable(get.apply("Titel")).orElse("(ohne Titel)");
                String detail = Optional.ofNullable(get.apply("Bis")).map(d -> "bis " + d).orElse("");
                rows.add(runner.run(title, detail, commit, () -> apply(get, title, detail, names, actor)));
            }
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalArgumentException("Die CSV-Datei ist nicht lesbar: " + e.getMessage());
        }
        return new ImportReport(rows, List.of(), commit);
    }

    private ImportReport.Row apply(Function<String, String> get, String title, String detail, Names names,
                                   User actor) {
        Set<AssigneeRef> assignees = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        String assigned = get.apply("Zuständige");
        if (assigned != null) {
            for (String name : assigned.split(",")) {
                if (!name.isBlank()) {
                    names.ref(name.trim()).ifPresentOrElse(assignees::add, () -> unknown.add(name.trim()));
                }
            }
        }
        if (!unknown.isEmpty()) {
            return ImportReport.Row.skip(title, detail, "unbekannte Zuständige: " + String.join(", ", unknown));
        }
        Long responsible = null;
        String responsibleName = get.apply("Verantwortlich");
        if (responsibleName != null) {
            responsible = names.userIds.get(responsibleName.toLowerCase(Locale.ROOT));
            if (responsible == null) {
                return ImportReport.Row.skip(title, detail, "unbekannter Benutzer „" + responsibleName + "“");
            }
        }
        Set<String> tags = Optional.ofNullable(get.apply("Schlagworte")).map(t -> Arrays.stream(t.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new)))
                .orElse(new LinkedHashSet<>());
        TodoPriority priority = label(TodoPriority.values(), get.apply("Priorität"), TodoPriority::label, "Priorität");
        TodoStatus status = label(TodoStatus.values(), get.apply("Status"), TodoStatus::label, "Status");
        boolean privateTodo = Optional.ofNullable(get.apply("Privat"))
                .map(v -> List.of("ja", "yes", "true", "1", "x").contains(v.toLowerCase(Locale.ROOT))).orElse(false);
        Long id = Optional.ofNullable(get.apply("ID")).map(v -> {
            try {
                return Long.valueOf(v.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ID „" + v + "“ ist keine Zahl");
            }
        }).orElse(null);
        // unbekannte oder fremde ID: als neues ToDo anlegen (ohne Ausnahme, die die Transaktion verwerfen würde)
        Optional<Todo> existing = id == null ? Optional.empty()
                : repository.findById(id).filter(t -> access.can(actor, "VIEW", t));
        if (existing.isPresent()) {
            Todo t = existing.get();
            todos.update(t.getId(), new TodoInput(title, get.apply("Beschreibung"),
                    priority != null ? priority : t.getPriority(), date(get.apply("Ab")), date(get.apply("Bis")),
                    date(get.apply("Wiedervorlage")), t.getRemindDaysBefore(), privateTodo, t.getCompletionMode(),
                    tags, assignees, responsible, t.getRrule()), t.getVersion(), actor);
            if (status != null && status != t.getStatus()) {
                todos.changeStatus(t.getId(), status, actor);
            }
            return new ImportReport.Row(title, detail, ImportReport.Action.UPDATE, null);
        }
        Todo created = todos.create(new TodoInput(title, get.apply("Beschreibung"), priority, date(get.apply("Ab")),
                date(get.apply("Bis")), date(get.apply("Wiedervorlage")), null, privateTodo, null, tags, assignees,
                responsible), actor);
        if (status != null && status != created.getStatus()) {
            todos.changeStatus(created.getId(), status, actor);
        }
        return new ImportReport.Row(title, detail, ImportReport.Action.NEW, null);
    }

    private static <E extends Enum<E>> E label(E[] values, String value, Function<E, String> label, String column) {
        if (value == null) {
            return null;
        }
        for (E e : values) {
            if (e.name().equalsIgnoreCase(value) || label.apply(e).equalsIgnoreCase(value)) {
                return e;
            }
        }
        throw new IllegalArgumentException(column + " „" + value + "“ unbekannt");
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return value.contains(".") ? LocalDate.parse(value, GERMAN_DATE) : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Datum „" + value + "“ unverständlich (TT.MM.JJJJ)");
        }
    }

    /** Namen für Zuständige und Benutzer in beide Richtungen. */
    private record Names(Map<Long, String> members, Map<Long, String> roles, Map<Long, String> contacts,
                         Map<Long, String> users, Map<String, Long> userIds) {

        String label(AssigneeRef ref) {
            return switch (ref.type()) {
                case MEMBER -> members.getOrDefault(ref.id(), "?");
                case ROLE -> ROLE + " " + roles.getOrDefault(ref.id(), "?");
                case CONTACT -> CONTACT + " " + contacts.getOrDefault(ref.id(), "(nicht sichtbar)");
            };
        }

        Optional<AssigneeRef> ref(String text) {
            if (text.regionMatches(true, 0, ROLE, 0, ROLE.length())) {
                return unique(roles, text.substring(ROLE.length()).trim()).map(AssigneeRef::role);
            }
            if (text.regionMatches(true, 0, CONTACT, 0, CONTACT.length())) {
                return unique(contacts, text.substring(CONTACT.length()).trim()).map(AssigneeRef::contact);
            }
            return unique(members, text).map(AssigneeRef::member);
        }

        private static Optional<Long> unique(Map<Long, String> names, String name) {
            List<Long> ids = names.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(name))
                    .map(Map.Entry::getKey).toList();
            return ids.size() == 1 ? Optional.of(ids.getFirst()) : Optional.empty();
        }
    }

    /** Kontakte nur, soweit der Benutzer sie sehen darf: sonst verrieten Export und Zuordnung fremde Namen. */
    private Names names(User viewer) {
        Map<Long, String> userNames = users.findAll().stream().collect(Collectors.toMap(User::getId,
                User::getUsername));
        return new Names(
                members.findAll().stream().collect(Collectors.toMap(FamilyMember::getId, FamilyMember::getName)),
                roles.findAll().stream().collect(Collectors.toMap(Role::getId, Role::getName)),
                contactService.findVisible(viewer).stream()
                        .collect(Collectors.toMap(Contact::getId, Contact::getName, (a, b) -> a)),
                userNames,
                userNames.entrySet().stream().collect(Collectors.toMap(e -> e.getValue().toLowerCase(Locale.ROOT),
                        Map.Entry::getKey)));
    }
}
