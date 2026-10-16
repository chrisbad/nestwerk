package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.CalendarGrid;
import club.badernet.nestwerk.calendar.CalendarSettings;
import club.badernet.nestwerk.calendar.DayCell;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.calendar.OccurrenceInput;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoEventLinks;
import club.badernet.nestwerk.todo.TodoNotFoundException;
import club.badernet.nestwerk.todo.TodoQuery;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.user.User;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/calendar")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarController {

    private static final int AGENDA_DAYS = 60;

    private final CalendarEventService events;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings settings;
    private final TodoQuery todoQuery;
    private final TodoAccess todoAccess;
    private final TodoService todoService;
    private final TodoEventLinks todoLinks;

    public CalendarController(CalendarEventService events, FamilyMemberService members,
                              CurrentUser currentUser, CalendarSettings settings, TodoQuery todoQuery,
                              TodoAccess todoAccess, TodoService todoService, TodoEventLinks todoLinks) {
        this.events = events;
        this.members = members;
        this.currentUser = currentUser;
        this.settings = settings;
        this.todoQuery = todoQuery;
        this.todoAccess = todoAccess;
        this.todoService = todoService;
        this.todoLinks = todoLinks;
    }

    /** Fällige ToDos des Zeitraums, wenn der Betrachter das ToDo-Modul nutzen darf. */
    private Map<LocalDate, List<Todo>> addTodos(Model model, LocalDate from, LocalDate to) {
        User viewer = currentUser.require();
        boolean show = todoAccess.mayOpen(viewer.getUsername());
        Map<LocalDate, List<Todo>> todos = show ? todoQuery.dueBetween(viewer, from, to) : Map.of();
        model.addAttribute("showTodos", show);
        model.addAttribute("todosByDay", todos);
        return todos;
    }

    @GetMapping
    String index() {
        return "redirect:/calendar/month";
    }

    @GetMapping("/month")
    String month(@RequestParam(required = false) String date, Model model) {
        LocalDate day = parseDate(date);
        YearMonth month = YearMonth.from(day);
        List<LocalDate> days = CalendarGrid.monthDays(month);
        Map<LocalDate, List<Occurrence>> byDay = load(days);
        LocalDate today = LocalDate.now(settings.zone());

        List<List<DayCell>> weeks = new ArrayList<>();
        for (int i = 0; i < days.size(); i += 7) {
            List<DayCell> week = new ArrayList<>();
            for (LocalDate d : days.subList(i, i + 7)) {
                week.add(new DayCell(d, YearMonth.from(d).equals(month), d.equals(today), byDay.get(d)));
            }
            weeks.add(week);
        }
        model.addAttribute("weeks", weeks);
        addTodos(model, days.getFirst(), days.getLast());
        model.addAttribute("month", month);
        model.addAttribute("prev", month.minusMonths(1).atDay(1));
        model.addAttribute("next", month.plusMonths(1).atDay(1));
        addCommon(model, "month");
        return "calendar/month";
    }

    @GetMapping("/week")
    String week(@RequestParam(required = false) String date, Model model) {
        LocalDate day = parseDate(date);
        List<LocalDate> days = CalendarGrid.weekDays(day);
        Map<LocalDate, List<Occurrence>> byDay = load(days);
        LocalDate today = LocalDate.now(settings.zone());

        List<DayCell> cells = days.stream()
                .map(d -> new DayCell(d, true, d.equals(today), byDay.get(d))).toList();
        model.addAttribute("days", cells);
        addTodos(model, days.getFirst(), days.getLast());
        model.addAttribute("first", days.getFirst());
        model.addAttribute("last", days.getLast());
        model.addAttribute("prev", days.getFirst().minusWeeks(1));
        model.addAttribute("next", days.getFirst().plusWeeks(1));
        addCommon(model, "week");
        return "calendar/week";
    }

    @GetMapping("/agenda")
    String agenda(@RequestParam(required = false) String from, Model model) {
        LocalDate start = parseDate(from);
        List<LocalDate> days = start.datesUntil(start.plusDays(AGENDA_DAYS)).toList();
        Map<LocalDate, List<Occurrence>> byDay = load(days);
        Map<LocalDate, List<Todo>> todos = addTodos(model, days.getFirst(), days.getLast());
        Map<LocalDate, List<Occurrence>> nonEmpty = new LinkedHashMap<>();
        byDay.forEach((d, l) -> {
            if (!l.isEmpty() || todos.containsKey(d)) {
                nonEmpty.put(d, l);
            }
        });
        model.addAttribute("agenda", nonEmpty);
        model.addAttribute("from", start);
        model.addAttribute("prev", start.minusDays(AGENDA_DAYS));
        model.addAttribute("next", start.plusDays(AGENDA_DAYS));
        addCommon(model, "agenda");
        return "calendar/agenda";
    }

    @GetMapping("/events/new")
    String newForm(@RequestParam(required = false) String date, @RequestParam(required = false) Long todo,
                   Model model) {
        EventForm form = EventForm.forNew(parseDate(date));
        if (todo != null) {
            Todo t = linkableTodo(todo, currentUser.require());
            form = EventForm.forNew(t.getDueOn() != null ? t.getDueOn() : parseDate(date));
            form.setTitle(t.getTitle());
            form.setTodoId(t.getId());
            Set<Long> memberIds = new java.util.HashSet<>();
            t.assigneeRefs().stream().filter(r -> r.type() == AssigneeRef.Type.MEMBER).forEach(r -> memberIds.add(r.id()));
            form.setMemberIds(memberIds);
            model.addAttribute("fromTodo", t);
        }
        model.addAttribute("form", form);
        model.addAttribute("action", "/calendar/events");
        model.addAttribute("isNew", true);
        addFormData(model);
        return "calendar/event-form";
    }

    @PostMapping("/events")
    String create(@ModelAttribute("form") EventForm form, Model model, RedirectAttributes redirect) {
        try {
            User actor = currentUser.require();
            Todo fromTodo = form.getTodoId() == null ? null : linkableTodo(form.getTodoId(), actor);
            CalendarEvent created = events.create(form.toInput(), actor);
            if (fromTodo != null) {
                todoLinks.link(fromTodo.getId(), created.getId());
            }
            redirect.addFlashAttribute("success", "Termin '" + created.getTitle() + "' wurde angelegt.");
            return "redirect:/calendar/month?date=" + form.getStartDate();
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("action", "/calendar/events");
            model.addAttribute("isNew", true);
            addFormData(model);
            return "calendar/event-form";
        }
    }

    @GetMapping("/events/{id}")
    String edit(@PathVariable Long id, @RequestParam(required = false) Instant occurrence, Model model) {
        User viewer = currentUser.require();
        CalendarEvent event = events.get(id, viewer);
        model.addAttribute("form", EventForm.from(event));
        model.addAttribute("linkedTodos", linkedTodos(id, viewer));
        model.addAttribute("event", event);
        model.addAttribute("action", "/calendar/events/" + id);
        model.addAttribute("isNew", false);
        model.addAttribute("canEdit", events.canEdit(event, viewer));
        model.addAttribute("canDelete", events.canDelete(event, viewer));
        model.addAttribute("recurring", event.getRrule() != null);
        Occurrence shown = null;
        if (occurrence != null && event.getRrule() != null) {
            model.addAttribute("occurrenceId", occurrence);
            shown = events.findOccurrences(occurrence.minusSeconds(1), occurrence.plusSeconds(86_400L * 31), viewer)
                    .stream().filter(o -> o.eventId().equals(id) && o.recurrenceId().equals(occurrence))
                    .findFirst().orElse(null);
            model.addAttribute("occurrence", shown);
        }
        addFormData(model);
        if (!events.canEdit(event, viewer)) {
            model.addAttribute("details", EventDetails.of(shown != null ? shown : seriesStart(event), event.getRrule()));
            return "calendar/event-view";
        }
        return "calendar/event-form";
    }

    @PostMapping("/events/{id}")
    String update(@PathVariable Long id, @ModelAttribute("form") EventForm form,
                  Model model, RedirectAttributes redirect) {
        User actor = currentUser.require();
        try {
            events.update(id, form.toInput(), actor);
            redirect.addFlashAttribute("success", "Termin wurde gespeichert.");
            return "redirect:/calendar/month?date=" + form.getStartDate();
        } catch (IllegalArgumentException e) {
            CalendarEvent event = events.get(id, actor);
            model.addAttribute("error", e.getMessage());
            model.addAttribute("event", event);
            model.addAttribute("action", "/calendar/events/" + id);
            model.addAttribute("isNew", false);
            model.addAttribute("canEdit", events.canEdit(event, actor));
            model.addAttribute("canDelete", events.canDelete(event, actor));
            model.addAttribute("recurring", event.getRrule() != null);
            addFormData(model);
            return "calendar/event-form";
        }
    }

    /** Ziehen im Kalender: einzelnen Termin auf einen anderen Tag legen. Antwortet mit einer kurzen Meldung. */
    @PostMapping(value = "/events/{id}/move", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    ResponseEntity<String> move(@PathVariable Long id,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            events.moveToDate(id, date, currentUser.require());
            return ResponseEntity.ok("Termin wurde verschoben.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Diesen Termin darfst du nicht verschieben.");
        }
    }

    @PostMapping("/events/{id}/delete")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        events.delete(id, currentUser.require());
        redirect.addFlashAttribute("success", "Termin wurde gelöscht.");
        return "redirect:/calendar/month";
    }

    @PostMapping("/events/{id}/occurrence/cancel")
    String cancelOccurrence(@PathVariable Long id, @RequestParam Instant recurrenceId,
                            RedirectAttributes redirect) {
        try {
            events.cancelOccurrence(id, recurrenceId, currentUser.require());
            redirect.addFlashAttribute("success", "Das Vorkommen wurde abgesagt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/month";
    }

    @PostMapping("/events/{id}/occurrence")
    String overrideOccurrence(@PathVariable Long id, @RequestParam Instant recurrenceId,
                              @RequestParam String title,
                              @RequestParam(required = false) String description,
                              @RequestParam(required = false) String location,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
                              RedirectAttributes redirect) {
        try {
            events.overrideOccurrence(id, recurrenceId,
                    new OccurrenceInput(title, description, location, start, end),
                    currentUser.require());
            redirect.addFlashAttribute("success", "Das Vorkommen wurde geändert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/month";
    }

    private Occurrence seriesStart(CalendarEvent event) {
        return new Occurrence(event.getId(), event.getUid(), event.getStartsAt(), event.getTitle(),
                event.getDescription(), event.getLocation(), event.getStartsAt().atZone(settings.zone()),
                event.getEndsAt().atZone(settings.zone()), event.isAllDay(), event.isPrivateEvent(),
                event.getRrule() != null, event.getCreatedBy(), event.getMemberIds());
    }

    private void addFormData(Model model) {
        model.addAttribute("allMembers", members.findAll());
    }

    private Map<LocalDate, List<Occurrence>> load(List<LocalDate> days) {
        User viewer = currentUser.require();
        Instant from = days.getFirst().atStartOfDay(settings.zone()).toInstant();
        Instant to = days.getLast().plusDays(1).atStartOfDay(settings.zone()).toInstant();
        return CalendarGrid.groupByDay(events.findOccurrences(from, to, viewer), days);
    }

    /** ToDo, an das der Benutzer einen Termin hängen darf (ACL-Aktion {@code LINK}). */
    private Todo linkableTodo(Long todoId, User actor) {
        Todo todo = todoService.get(todoId, actor);
        if (!todoAccess.can(actor, "LINK", todo)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Keine Berechtigung, einen Termin mit diesem ToDo zu verknüpfen");
        }
        return todo;
    }

    /** Verknüpfte ToDos, die der Betrachter sehen darf. */
    private List<Todo> linkedTodos(Long eventId, User viewer) {
        List<Todo> result = new ArrayList<>();
        for (Long todoId : todoLinks.todoIds(eventId)) {
            try {
                result.add(todoService.get(todoId, viewer));
            } catch (TodoNotFoundException ignored) {
                // unsichtbar oder gelöscht
            }
        }
        return result;
    }

    private void addCommon(Model model, String view) {
        Map<Long, FamilyMember> byId = new LinkedHashMap<>();
        members.findAll().forEach(m -> byId.put(m.getId(), m));
        model.addAttribute("memberById", byId);
        model.addAttribute("view", view);
        model.addAttribute("today", LocalDate.now(settings.zone()));
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return LocalDate.now(settings.zone());
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return LocalDate.now(settings.zone());
        }
    }
}
