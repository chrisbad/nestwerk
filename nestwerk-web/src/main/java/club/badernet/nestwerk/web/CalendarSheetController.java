package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoPriority;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.todo.TodoStatus;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/** The dialogs ("sheets") the calendar opens when an event, a planned chore or a due to-do is tapped. */
@Controller
@RequestMapping("/calendar")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarSheetController {

    /** How far from its original start a moved occurrence is still looked up for the sheet. */
    private static final Duration OCCURRENCE_LOOKUP = Duration.ofDays(180);
    /** The statuses offered in the to-do dialog; "discarded" stays on the to-do's own page. */
    private static final List<TodoStatus> TODO_STATUSES = List.of(TodoStatus.OPEN, TodoStatus.IN_PROGRESS,
            TodoStatus.DONE);

    private final CalendarPageData pageData;
    private final CalendarEventService events;
    private final ChoreTaskService choreTasks;
    private final HouseholdAccess householdAccess;
    private final HouseholdViews householdViews;
    private final TodoService todos;
    private final TodoAccess todoAccess;
    private final CurrentUser currentUser;
    private final CalendarSettings settings;

    public CalendarSheetController(CalendarPageData pageData, CalendarEventService events,
                                   ChoreTaskService choreTasks, HouseholdAccess householdAccess,
                                   HouseholdViews householdViews, TodoService todos, TodoAccess todoAccess,
                                   CurrentUser currentUser, CalendarSettings settings) {
        this.pageData = pageData;
        this.events = events;
        this.choreTasks = choreTasks;
        this.householdAccess = householdAccess;
        this.householdViews = householdViews;
        this.todos = todos;
        this.todoAccess = todoAccess;
        this.currentUser = currentUser;
        this.settings = settings;
    }

    /** An occurrence: details, quick edit and delete. */
    @GetMapping("/events/{id}/sheet")
    String event(@PathVariable Long id, @RequestParam(required = false) Instant occurrence, Model model) {
        User viewer = currentUser.require();
        CalendarEvent event = events.get(id, viewer);
        boolean recurring = event.getRrule() != null;
        Occurrence shown = recurring && occurrence != null
                ? events.findOccurrences(occurrence.minus(OCCURRENCE_LOOKUP), occurrence.plus(OCCURRENCE_LOOKUP), viewer)
                .stream().filter(o -> o.eventId().equals(id) && o.recurrenceId().equals(occurrence))
                .findFirst().orElseThrow(EventNotFoundException::new)
                : null;
        EventForm form = EventForm.from(event);
        if (shown != null) {
            form.setTitle(shown.title());
            form.setStartDate(shown.start().withZoneSameInstant(settings.zone()).toLocalDate());
            form.setEndDate(shown.allDay() ? shown.end().minusDays(1).toLocalDate()
                    : shown.end().withZoneSameInstant(settings.zone()).toLocalDate());
            if (!shown.allDay()) {
                form.setStartTime(shown.start().withZoneSameInstant(settings.zone()).toLocalTime());
                form.setEndTime(shown.end().withZoneSameInstant(settings.zone()).toLocalTime());
            }
        }
        model.addAttribute("event", event);
        model.addAttribute("form", form);
        model.addAttribute("occurrenceId", shown == null ? null : shown.recurrenceId());
        model.addAttribute("recurring", recurring);
        model.addAttribute("canEdit", events.canEdit(event, viewer));
        model.addAttribute("canDelete", events.canDelete(event, viewer));
        model.addAttribute("memberById", pageData.memberById());
        model.addAttribute("allCategories", List.copyOf(pageData.categoryById(viewer).values()));
        return "calendar/sheet :: event";
    }

    /** A planned chore: tab "Termin" for this date, tab "Hausarbeit" for the whole chore. */
    @GetMapping("/chores/{taskId}/sheet")
    String chore(@PathVariable Long taskId, Model model) {
        User viewer = currentUser.require();
        if (!householdAccess.mayOpen(viewer.getUsername())) {
            throw new AccessDeniedException("Keine Berechtigung für den Haushaltsplan");
        }
        ChoreTask task = choreTasks.get(taskId, viewer);
        Chore chore = task.getChore();
        HouseholdViews.Context ctx = householdViews.context(viewer);
        boolean open = task.isOpen();
        LocalTime time = task.getPlannedAt() != null ? task.getPlannedAt() : task.getDueTime();
        model.addAttribute("task", task);
        model.addAttribute("chore", chore);
        model.addAttribute("c", householdViews.chip(task, ctx));
        model.addAttribute("time", time);
        model.addAttribute("today", ctx.today());
        model.addAttribute("allMembers", ctx.members().values());
        model.addAttribute("ownMember", ctx.ownMember());
        model.addAttribute("completeAny", ctx.completeAny());
        model.addAttribute("canComplete", open && householdAccess.can(viewer, "COMPLETE", task));
        model.addAttribute("canAssign", open && householdAccess.can(viewer, "ASSIGN", task));
        model.addAttribute("assignAny", householdAccess.canAny(viewer, "ASSIGN"));
        model.addAttribute("canCancel", open && householdAccess.can(viewer, "EDIT", chore));
        model.addAttribute("canEditChore", householdAccess.can(viewer, "EDIT", chore));
        model.addAttribute("canDeleteChore", householdAccess.can(viewer, "DELETE", chore));
        model.addAttribute("form", ChoreForm.from(chore));
        model.addAttribute("kinds", ChoreKind.values());
        model.addAttribute("distributions", Distribution.values());
        return "calendar/chore-sheet :: chore";
    }

    /** A due to-do: status, quick edit of title, due date, priority and members, delete. */
    @GetMapping("/todos/{id}/sheet")
    String todo(@PathVariable Long id, Model model) {
        User viewer = currentUser.require();
        if (!todoAccess.mayOpen(viewer.getUsername())) {
            throw new AccessDeniedException("Keine Berechtigung für ToDos");
        }
        Todo todo = todos.get(id, viewer);
        model.addAttribute("todo", todo);
        model.addAttribute("form", TodoForm.from(todo));
        model.addAttribute("memberById", pageData.memberById());
        model.addAttribute("otherAssignees", todo.assigneeRefs().stream()
                .filter(r -> r.type() != AssigneeRef.Type.MEMBER).map(AssigneeRef::key).toList());
        model.addAttribute("statuses", TODO_STATUSES);
        model.addAttribute("priorities", TodoPriority.values());
        model.addAttribute("canEdit", todoAccess.can(viewer, "EDIT", todo));
        model.addAttribute("canAssign", todoAccess.can(viewer, "ASSIGN", todo));
        model.addAttribute("canChangeStatus", todoAccess.can(viewer, "CHANGE_STATUS", todo));
        model.addAttribute("canDelete", todoAccess.can(viewer, "DELETE", todo));
        return "calendar/todo-sheet :: todo";
    }
}
