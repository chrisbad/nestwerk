package club.badernet.nestwerk.school;

import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.CompletionMode;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoInput;
import club.badernet.nestwerk.todo.TodoNotFoundException;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hausaufgaben eines Schuljahrs. Eine Hausaufgabe ist ein ToDo, das dem Kind zugewiesen ist; wiederkehrende
 * Hausaufgaben nutzen die ToDo-Serien (beim Erledigen entsteht die nächste Kopie).
 */
@Service
public class HomeworkService {

    public static final String TAG = "Hausaufgabe";
    private static final int MAX_FOLLOW = 1000;

    /** Eine Hausaufgabe für die Anzeige: das aktuelle ToDo der Serie und sein Fach. */
    public record Item(Todo todo, SchoolSubject subject) {
    }

    private final HomeworkRepository homework;
    private final SchoolYearRepository years;
    private final SchoolProfileRepository profiles;
    private final SchoolSubjectRepository subjects;
    private final TodoService todos;
    private final SchoolAccess access;

    public HomeworkService(HomeworkRepository homework, SchoolYearRepository years, SchoolProfileRepository profiles,
                           SchoolSubjectRepository subjects, TodoService todos, SchoolAccess access) {
        this.homework = homework;
        this.years = years;
        this.profiles = profiles;
        this.subjects = subjects;
        this.todos = todos;
        this.access = access;
    }

    @Transactional
    public Todo create(Long yearId, Long subjectId, String title, String description, LocalDate dueOn, String rrule,
                       User actor) {
        access.require(actor, SchoolPermission.SCHOOL_HOMEWORK);
        SchoolYear year = years.findById(yearId).orElseThrow(() -> new IllegalArgumentException("Schuljahr nicht gefunden"));
        SchoolProfile profile = profiles.findById(year.getProfileId()).orElseThrow();
        SchoolSubject subject = null;
        if (subjectId != null) {
            subject = subjects.findById(subjectId).filter(s -> s.getYearId().equals(yearId))
                    .orElseThrow(() -> new IllegalArgumentException("Fach nicht gefunden"));
        }
        if (dueOn == null) {
            throw new IllegalArgumentException("Bitte angeben, bis wann die Hausaufgabe fällig ist.");
        }
        Set<String> tags = new LinkedHashSet<>();
        tags.add(TAG);
        if (subject != null) {
            tags.add(subject.getName().length() > TodoService.MAX_TAG
                    ? subject.getName().substring(0, TodoService.MAX_TAG) : subject.getName());
        }
        TodoInput input = new TodoInput(title, description, TodoPriority.NORMAL, null, dueOn, null, null, false,
                CompletionMode.ANY, tags, Set.of(AssigneeRef.member(profile.getMemberId())), null, rrule);
        Todo todo = todos.create(input, actor);
        homework.save(new Homework(todo.getId(), yearId, subject == null ? null : subject.getId()));
        return todo;
    }

    /**
     * Hausaufgaben des Jahrs, die der Betrachter sehen darf, nach Fälligkeit sortiert.
     *
     * @param done {@code false}: offene (auch in Arbeit), {@code true}: erledigte und verworfene
     */
    @Transactional(readOnly = true)
    public List<Item> list(Long yearId, Long subjectId, boolean done, User viewer) {
        Map<Long, SchoolSubject> bySubject = subjects.findAllByYearIdOrderByNameAsc(yearId).stream()
                .collect(Collectors.toMap(SchoolSubject::getId, Function.identity()));
        List<Item> result = new ArrayList<>();
        for (Homework link : homework.findAllByYearId(yearId)) {
            if (subjectId != null && !subjectId.equals(link.getSubjectId())) {
                continue;
            }
            Todo current = current(link.getTodoId(), viewer);
            if (current == null || current.isClosed() != done) {
                continue;
            }
            result.add(new Item(current, link.getSubjectId() == null ? null : bySubject.get(link.getSubjectId())));
        }
        result.sort(Comparator.comparing((Item i) -> i.todo().getDueOn(),
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(i -> i.todo().getId()));
        return result;
    }

    /** Erledigt eine Hausaufgabe oder öffnet sie wieder. */
    @Transactional
    public void setDone(Long todoId, boolean done, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_HOMEWORK);
        Todo todo = todos.get(todoId, actor);
        Long root = todo.getSeriesId() != null ? todo.getSeriesId() : todo.getId();
        if (!homework.existsById(root)) {
            throw new IllegalArgumentException("Das ist keine Hausaufgabe.");
        }
        todos.changeStatus(todoId, done ? TodoStatus.DONE : TodoStatus.OPEN, actor);
    }

    /** Folgt der Serie bis zum aktuellen ToDo; {@code null}, wenn es der Betrachter nicht sehen darf. */
    private Todo current(Long todoId, User viewer) {
        Long id = todoId;
        Todo last = null;
        for (int i = 0; i < MAX_FOLLOW && id != null; i++) {
            try {
                last = todos.get(id, viewer);
            } catch (TodoNotFoundException e) {
                return last;
            }
            id = last.getSuccessorId();
        }
        return last;
    }
}
