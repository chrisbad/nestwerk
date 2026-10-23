package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerService;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.CalendarGrid;
import club.badernet.nestwerk.calendar.EventCategory;
import club.badernet.nestwerk.calendar.EventCategoryService;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoQuery;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** What every calendar page shows: occurrences, planned chores, due to-dos, members and categories. */
@Component
public class CalendarPageData {

    private final CalendarEventService events;
    private final EventCategoryService categories;
    private final FamilyMemberService members;
    private final CalendarSettings settings;
    private final TodoQuery todoQuery;
    private final TodoAccess todoAccess;
    private final ChoreTaskService choreTasks;
    private final ChoreService choreService;
    private final HouseholdAccess householdAccess;
    private final BlockerService blockers;

    public CalendarPageData(CalendarEventService events, EventCategoryService categories,
                            FamilyMemberService members, CalendarSettings settings, TodoQuery todoQuery,
                            TodoAccess todoAccess, ChoreTaskService choreTasks, ChoreService choreService,
                            HouseholdAccess householdAccess, BlockerService blockers) {
        this.events = events;
        this.categories = categories;
        this.members = members;
        this.settings = settings;
        this.todoQuery = todoQuery;
        this.todoAccess = todoAccess;
        this.choreTasks = choreTasks;
        this.choreService = choreService;
        this.householdAccess = householdAccess;
        this.blockers = blockers;
    }

    public LocalDate today() {
        return LocalDate.now(settings.zone());
    }

    /** The date of a request parameter; missing or invalid values mean today. */
    public LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return today();
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return today();
        }
    }

    /** Occurrences per day; an occurrence spanning several days appears on each of them. */
    public Map<LocalDate, List<Occurrence>> occurrences(List<LocalDate> days, User viewer) {
        Instant from = days.getFirst().atStartOfDay(settings.zone()).toInstant();
        Instant to = days.getLast().plusDays(1).atStartOfDay(settings.zone()).toInstant();
        return CalendarGrid.groupByDay(events.findOccurrences(from, to, viewer), days);
    }

    /** Due to-dos of the period if the viewer may use the to-do module. */
    public Map<LocalDate, List<Todo>> addTodos(Model model, LocalDate from, LocalDate to, User viewer) {
        boolean show = todoAccess.mayOpen(viewer.getUsername());
        Map<LocalDate, List<Todo>> todos = show ? todoQuery.dueBetween(viewer, from, to) : Map.of();
        model.addAttribute("showTodos", show);
        model.addAttribute("todosByDay", todos);
        return todos;
    }

    /** May the viewer change the to-do (e.g. move it to another day in the calendar)? */
    public boolean canEdit(Todo todo, User viewer) {
        return todoAccess.can(viewer, "EDIT", todo);
    }

    /**
     * Open chores of the period if the viewer may use the household plan. Chores with a planned time and a
     * responsible person are placed in the time grid, all others stay in the all-day row.
     */
    public CalendarChores addChores(Model model, LocalDate from, LocalDate to, User viewer) {
        Map<LocalDate, List<CalendarChore>> timed = new LinkedHashMap<>();
        Map<LocalDate, List<UntimedChore>> untimed = new LinkedHashMap<>();
        boolean show = householdAccess.mayOpen(viewer.getUsername());
        if (show) {
            Map<Long, FamilyMember> people = memberById();
            for (ChoreTask t : choreTasks.between(viewer, from, to)) {
                if (!t.isOpen() && !t.isSkipped()) {
                    continue;
                }
                FamilyMember who = t.getAssigneeMemberId() == null ? null : people.get(t.getAssigneeMemberId());
                if (t.getPlannedAt() != null && who != null) {
                    timed.computeIfAbsent(t.getDueOn(), d -> new ArrayList<>()).add(new CalendarChore(t.getId(),
                            t.getChore().getTitle() + " · " + who.getName(), t.getDueOn(), t.getPlannedAt(),
                            t.minutes(), who.getColor(),
                            !t.isSkipped() && householdAccess.can(viewer, "ASSIGN", t), t.getAssigneeMemberId(),
                            t.isSkipped(), t.getChore().isNoOverlap()));
                } else {
                    untimed.computeIfAbsent(t.getDueOn(), d -> new ArrayList<>()).add(new UntimedChore(t.getId(),
                            t.getChore().getTitle() + " · " + (who == null ? "offen" : who.getName()), t.getDueOn(),
                            who == null ? null : who.getColor(), who == null ? null : who.getId(),
                            t.isSkipped()));
                }
            }
        }
        model.addAttribute("showChores", show);
        model.addAttribute("choresByDay", timed);
        model.addAttribute("untimedChoresByDay", untimed);
        return new CalendarChores(timed, untimed);
    }

    /** Members, categories, the view name and today for toolbar, legend and colours. */
    public void addCommon(Model model, String view, User viewer) {
        model.addAttribute("memberById", memberById());
        model.addAttribute("categoryById", categoryById(viewer));
        model.addAttribute("canManageCategories", categories.canManage(viewer));
        model.addAttribute("view", view);
        model.addAttribute("today", today());
        model.addAttribute("showTodos", todoAccess.mayOpen(viewer.getUsername()));
        boolean chores = householdAccess.mayOpen(viewer.getUsername());
        model.addAttribute("showChores", chores);
        model.addAttribute("quickChores", chores ? quickChores(viewer) : List.<QuickChore>of());
        model.addAttribute("ownMemberId", householdAccess.memberId(viewer).orElse(null));
        addCreateOptions(model, viewer);
    }

    /** What the dialog "Neu" offers: every type the viewer may create, with the lists its fields need. */
    private void addCreateOptions(Model model, User viewer) {
        boolean event = events.canCreate(viewer);
        boolean chore = (Boolean) model.getAttribute("showChores")
                && !((List<?>) model.getAttribute("quickChores")).isEmpty();
        boolean todo = todoAccess.mayOpen(viewer.getUsername()) && todoAccess.canCreate(viewer);
        boolean time = blockers.canCreate(viewer);
        model.addAttribute("canCreate", event);
        model.addAttribute("canCreateChore", chore);
        model.addAttribute("canCreateTodo", todo);
        model.addAttribute("canCreateTime", time);
        model.addAttribute("canCreateAny", event || chore || todo || time);
        model.addAttribute("allMembers", List.copyOf(memberById().values()));
        model.addAttribute("allCategories", List.copyOf(categoryById(viewer).values()));
        model.addAttribute("todoPriorities", TodoPriority.values());
        model.addAttribute("blockerKinds", BlockerKind.values());
    }

    /** Active chores the viewer may plan or book, by title; for the quick dialog of the toolbar. */
    private List<QuickChore> quickChores(User viewer) {
        return choreService.visible(viewer).stream()
                .filter(chore -> choreTasks.canBook(viewer, chore))
                .map(chore -> new QuickChore(chore.getId(), chore.getTitle(), chore.getMinutesEasy(),
                        chore.getMinutesMedium(), chore.getMinutesHard()))
                .sorted(java.util.Comparator.comparing(QuickChore::title, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Categories by id, sorted by name. */
    public Map<Long, EventCategory> categoryById(User viewer) {
        return categories.list(viewer).stream()
                .collect(Collectors.toMap(EventCategory::getId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
    }

    /** Family members by id. */
    public Map<Long, FamilyMember> memberById() {
        Map<Long, FamilyMember> byId = new LinkedHashMap<>();
        members.findAll().forEach(m -> byId.put(m.getId(), m));
        return byId;
    }
}
