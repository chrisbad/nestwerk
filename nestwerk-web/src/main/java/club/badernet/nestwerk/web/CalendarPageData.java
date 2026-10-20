package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.CalendarGrid;
import club.badernet.nestwerk.calendar.EventCategory;
import club.badernet.nestwerk.calendar.EventCategoryService;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
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

    /** Chores shorter than this would be too small to read and to grab in the calendar. */
    private static final int MIN_CHORE_MINUTES = 5;

    private final CalendarEventService events;
    private final EventCategoryService categories;
    private final FamilyMemberService members;
    private final CalendarSettings settings;
    private final TodoQuery todoQuery;
    private final TodoAccess todoAccess;
    private final ChoreTaskService choreTasks;
    private final HouseholdAccess householdAccess;

    public CalendarPageData(CalendarEventService events, EventCategoryService categories,
                            FamilyMemberService members, CalendarSettings settings, TodoQuery todoQuery,
                            TodoAccess todoAccess, ChoreTaskService choreTasks, HouseholdAccess householdAccess) {
        this.events = events;
        this.categories = categories;
        this.members = members;
        this.settings = settings;
        this.todoQuery = todoQuery;
        this.todoAccess = todoAccess;
        this.choreTasks = choreTasks;
        this.householdAccess = householdAccess;
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

    /** Open chores with a planned time if the viewer may use the household plan. */
    public Map<LocalDate, List<CalendarChore>> addChores(Model model, LocalDate from, LocalDate to, User viewer) {
        Map<LocalDate, List<CalendarChore>> byDay = new LinkedHashMap<>();
        if (householdAccess.mayOpen(viewer.getUsername())) {
            Map<Long, FamilyMember> people = memberById();
            for (ChoreTask t : choreTasks.between(viewer, from, to)) {
                if (!t.isOpen() || t.getPlannedAt() == null || t.getAssigneeMemberId() == null) {
                    continue;
                }
                FamilyMember who = people.get(t.getAssigneeMemberId());
                byDay.computeIfAbsent(t.getDueOn(), d -> new ArrayList<>()).add(new CalendarChore(t.getId(),
                        t.getChore().getTitle() + (who == null ? "" : " · " + who.getName()), t.getDueOn(),
                        t.getPlannedAt(), Math.max(MIN_CHORE_MINUTES, t.getChore().getMinutesMedium()),
                        who == null ? null : who.getColor(), householdAccess.can(viewer, "ASSIGN", t)));
            }
        }
        model.addAttribute("choresByDay", byDay);
        return byDay;
    }

    /** Members, categories, the view name and today for toolbar, legend and colours. */
    public void addCommon(Model model, String view, User viewer) {
        model.addAttribute("memberById", memberById());
        model.addAttribute("categoryById", categoryById(viewer));
        model.addAttribute("canManageCategories", categories.canManage(viewer));
        model.addAttribute("view", view);
        model.addAttribute("today", today());
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
