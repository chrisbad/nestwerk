package club.badernet.nestwerk.school;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoNotFoundException;
import club.badernet.nestwerk.todo.TodoRepository;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * „Heute in der Schule“ für die Startseite und das Wand-Tablet: Stunden des Tages, fällige Hausaufgaben und der
 * nächste Termin je Kind mit aktivem Schuljahr. Noten kommen hier nie vor.
 */
@Component
public class SchoolToday {

    /** Eine fällige Hausaufgabe. */
    public record HomeworkRow(Long todoId, String title, LocalDate dueOn, String subject, String color,
                              boolean overdue) {
    }

    /** Der Schultag eines Kindes. */
    public record ChildDay(FamilyMember member, SchoolYear year, List<Timetable.Entry> lessons, String free,
                           List<HomeworkRow> homework, SchoolExam nextExam) {

        /** Erste bis letzte stattfindende Stunde, z. B. „08:00–12:30“, oder {@code null}. */
        public String span() {
            return Timetable.span(lessons).map(s -> s.start() + "–" + s.end()).orElse(null);
        }
    }

    private static final int EXAM_LOOKAHEAD_DAYS = 14;
    private static final int MAX_FOLLOW = 1000;

    private final SchoolProfileRepository profiles;
    private final SchoolYearRepository years;
    private final FamilyMemberRepository members;
    private final TimetableService timetable;
    private final HomeworkRepository homework;
    private final TodoRepository todos;
    private final SchoolSubjectRepository subjects;
    private final SchoolDays days;
    private final ExamService exams;
    private final TodoService todoService;

    public SchoolToday(SchoolProfileRepository profiles, SchoolYearRepository years, FamilyMemberRepository members,
                       TimetableService timetable, HomeworkRepository homework, TodoRepository todos,
                       SchoolSubjectRepository subjects, SchoolDays days, ExamService exams,
                       TodoService todoService) {
        this.todoService = todoService;
        this.profiles = profiles;
        this.years = years;
        this.members = members;
        this.timetable = timetable;
        this.homework = homework;
        this.todos = todos;
        this.subjects = subjects;
        this.days = days;
        this.exams = exams;
    }

    /**
     * Der Schultag aller Kinder mit aktivem Schuljahr, nach Namen sortiert. Hausaufgaben: fällig bis morgen oder
     * überfällig und offen. Wer das Wand-Tablet betrachtet, hat keinen Benutzer; dort sind private ToDos ausgenommen.
     */
    @Transactional(readOnly = true)
    public List<ChildDay> forDate(LocalDate day) {
        List<ChildDay> result = new ArrayList<>();
        for (SchoolProfile profile : profiles.findAll()) {
            Optional<SchoolYear> year = years.findByProfileIdAndActiveTrue(profile.getId());
            Optional<FamilyMember> member = members.findById(profile.getMemberId());
            if (year.isEmpty() || member.isEmpty()) {
                continue;
            }
            SchoolYear y = year.get();
            SchoolExam next = exams.list(y.getId()).stream()
                    .filter(e -> !e.getDay().isBefore(day) && !e.getDay().isAfter(day.plusDays(EXAM_LOOKAHEAD_DAYS)))
                    .findFirst().orElse(null);
            result.add(new ChildDay(member.get(), y, timetable.day(y.getId(), day),
                    days.reason(day, profile.getState()).orElse(null), dueHomework(y.getId(), day), next));
        }
        result.sort(Comparator.comparing(c -> c.member().getName(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Tagesansicht für einen Benutzer: ToDos, die er nicht sehen darf, fehlen. */
    @Transactional(readOnly = true)
    public List<ChildDay> forDate(LocalDate day, User viewer) {
        return forDate(day).stream().map(c -> new ChildDay(c.member(), c.year(), c.lessons(), c.free(),
                c.homework().stream().filter(h -> maySee(h.todoId(), viewer)).toList(), c.nextExam())).toList();
    }

    private boolean maySee(Long todoId, User viewer) {
        try {
            todoService.get(todoId, viewer);
            return true;
        } catch (TodoNotFoundException e) {
            return false;
        }
    }

    private List<HomeworkRow> dueHomework(Long yearId, LocalDate day) {
        Map<Long, SchoolSubject> bySubject = subjects.findAllByYearIdOrderByNameAsc(yearId).stream()
                .collect(Collectors.toMap(SchoolSubject::getId, Function.identity()));
        List<HomeworkRow> rows = new ArrayList<>();
        for (Homework link : homework.findAllByYearId(yearId)) {
            Todo current = current(link.getTodoId());
            if (current == null || current.isClosed() || current.isPrivateTodo() || current.getDueOn() == null
                    || current.getDueOn().isAfter(day.plusDays(1))) {
                continue;
            }
            SchoolSubject subject = link.getSubjectId() == null ? null : bySubject.get(link.getSubjectId());
            rows.add(new HomeworkRow(current.getId(), current.getTitle(), current.getDueOn(),
                    subject == null ? null : subject.getName(), subject == null ? null : subject.getColor(),
                    current.getDueOn().isBefore(day)));
        }
        rows.sort(Comparator.comparing(HomeworkRow::dueOn).thenComparing(HomeworkRow::todoId));
        return rows;
    }

    private Todo current(Long todoId) {
        Long id = todoId;
        Todo last = null;
        for (int i = 0; i < MAX_FOLLOW && id != null; i++) {
            Todo next = todos.findById(id).orElse(null);
            if (next == null) {
                return last;
            }
            last = next;
            id = last.getSuccessorId();
        }
        return last;
    }
}
