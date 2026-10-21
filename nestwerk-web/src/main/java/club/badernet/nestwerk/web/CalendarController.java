package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.BlockerBands;
import club.badernet.nestwerk.blocker.BlockerConflicts;
import club.badernet.nestwerk.blocker.Conflict;
import club.badernet.nestwerk.blocker.TimeRange;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventCategoryService;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.calendar.DayCell;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.calendar.OccurrenceInput;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.todo.AssigneeRef;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoEventLinks;
import club.badernet.nestwerk.todo.TodoNotFoundException;
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
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Month and agenda view and the event form. The time grid views live in {@link CalendarGridController}. */
@Controller
@RequestMapping("/calendar")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarController {

    private static final DateTimeFormatter ALTERNATIVE = DateTimeFormatter.ofPattern("EE d.M. HH:mm", Locale.GERMAN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final CalendarEventService events;
    private final EventCategoryService categories;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings settings;
    private final CalendarPageData pageData;
    private final TodoAccess todoAccess;
    private final TodoService todoService;
    private final TodoEventLinks todoLinks;
    private final BlockerConflicts blockerConflicts;
    private final BlockerBands blockerBands;

    public CalendarController(CalendarEventService events, EventCategoryService categories,
                              FamilyMemberService members, CurrentUser currentUser, CalendarSettings settings,
                              CalendarPageData pageData, TodoAccess todoAccess, TodoService todoService,
                              TodoEventLinks todoLinks, BlockerConflicts blockerConflicts,
                              BlockerBands blockerBands) {
        this.events = events;
        this.categories = categories;
        this.members = members;
        this.currentUser = currentUser;
        this.settings = settings;
        this.pageData = pageData;
        this.todoAccess = todoAccess;
        this.todoService = todoService;
        this.todoLinks = todoLinks;
        this.blockerConflicts = blockerConflicts;
        this.blockerBands = blockerBands;
    }

    @GetMapping
    String index() {
        return "redirect:/calendar/my";
    }

    @GetMapping("/month")
    String month(@RequestParam(required = false) String date, Model model) {
        User viewer = currentUser.require();
        LocalDate focus = pageData.parseDate(date);
        YearMonth month = YearMonth.from(focus);
        List<LocalDate> days = CalendarView.MONTH.days(focus);
        Map<LocalDate, List<Occurrence>> byDay = pageData.occurrences(days, viewer);
        LocalDate today = pageData.today();

        List<List<DayCell>> weeks = new ArrayList<>();
        for (int i = 0; i < days.size(); i += 7) {
            List<DayCell> week = new ArrayList<>();
            for (LocalDate d : days.subList(i, i + 7)) {
                week.add(new DayCell(d, YearMonth.from(d).equals(month), d.equals(today), byDay.get(d)));
            }
            weeks.add(week);
        }
        model.addAttribute("weeks", weeks);
        model.addAttribute("bands", blockerBands.between(days.getFirst(), days.getLast(), viewer));
        pageData.addChores(model, days.getFirst(), days.getLast(), viewer);
        pageData.addTodos(model, days.getFirst(), days.getLast(), viewer);
        model.addAttribute("month", month);
        model.addAttribute("focus", focus);
        model.addAttribute("prev", CalendarView.MONTH.previous(focus));
        model.addAttribute("next", CalendarView.MONTH.next(focus));
        pageData.addCommon(model, "month", viewer);
        return "calendar/month";
    }

    @GetMapping("/agenda")
    String agenda(@RequestParam(required = false) String from, Model model) {
        User viewer = currentUser.require();
        LocalDate start = pageData.parseDate(from);
        List<LocalDate> days = CalendarView.AGENDA.days(start);
        Map<LocalDate, List<Occurrence>> byDay = pageData.occurrences(days, viewer);
        Map<LocalDate, List<Todo>> todos = pageData.addTodos(model, days.getFirst(), days.getLast(), viewer);
        Map<LocalDate, List<Occurrence>> nonEmpty = new LinkedHashMap<>();
        byDay.forEach((d, l) -> {
            if (!l.isEmpty() || todos.containsKey(d)) {
                nonEmpty.put(d, l);
            }
        });
        model.addAttribute("agenda", nonEmpty);
        model.addAttribute("from", start);
        model.addAttribute("focus", start);
        model.addAttribute("prev", CalendarView.AGENDA.previous(start));
        model.addAttribute("next", CalendarView.AGENDA.next(start));
        pageData.addCommon(model, "agenda", viewer);
        return "calendar/agenda";
    }

    @GetMapping("/events/new")
    String newForm(@RequestParam(required = false) String date,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime end,
                   @RequestParam(required = false) Long todo, @RequestParam(required = false) String back,
                   Model model) {
        model.addAttribute("back", SafeRedirect.target(back, null));
        EventForm form = EventForm.forNew(pageData.parseDate(date));
        if (start != null && end != null && end.isAfter(start)) {
            form.setStartTime(start);
            form.setEndTime(end);
        }
        if (todo != null) {
            Todo t = linkableTodo(todo, currentUser.require());
            form = EventForm.forNew(t.getDueOn() != null ? t.getDueOn() : pageData.parseDate(date));
            form.setTitle(t.getTitle());
            form.setTodoId(t.getId());
            Set<Long> memberIds = new HashSet<>();
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
    String create(@ModelAttribute("form") EventForm form, @RequestParam(required = false) String back, Model model,
                  RedirectAttributes redirect) {
        model.addAttribute("back", SafeRedirect.target(back, null));
        try {
            User actor = currentUser.require();
            Todo fromTodo = form.getTodoId() == null ? null : linkableTodo(form.getTodoId(), actor);
            if (warnAboutBlockers(form, null, actor, model)) {
                model.addAttribute("action", "/calendar/events");
                model.addAttribute("isNew", true);
                addFormData(model);
                return "calendar/event-form";
            }
            CalendarEvent created = events.create(form.toInput(), actor);
            if (fromTodo != null) {
                todoLinks.link(fromTodo.getId(), created.getId());
            }
            redirect.addFlashAttribute("success", "Termin '" + created.getTitle() + "' wurde angelegt.");
            return backTo(back, form.getStartDate());
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("action", "/calendar/events");
            model.addAttribute("isNew", true);
            addFormData(model);
            return "calendar/event-form";
        }
    }

    @GetMapping("/events/{id}")
    String edit(@PathVariable Long id, @RequestParam(required = false) Instant occurrence,
                @RequestParam(required = false) String back, Model model) {
        model.addAttribute("back", SafeRedirect.target(back, null));
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
            event.getCategoryId().map(c -> categories.get(c, viewer))
                    .ifPresent(c -> model.addAttribute("category", c));
            return "calendar/event-view";
        }
        return "calendar/event-form";
    }

    @PostMapping("/events/{id}")
    String update(@PathVariable Long id, @ModelAttribute("form") EventForm form,
                  @RequestParam(required = false) String back, Model model, RedirectAttributes redirect) {
        model.addAttribute("back", SafeRedirect.target(back, null));
        User actor = currentUser.require();
        try {
            if (warnAboutBlockers(form, id, actor, model)) {
                throw new BlockerWarning();
            }
            events.update(id, form.toInput(), actor);
            redirect.addFlashAttribute("success", "Termin wurde gespeichert.");
            return backTo(back, form.getStartDate());
        } catch (IllegalArgumentException e) {
            CalendarEvent event = events.get(id, actor);
            if (!(e instanceof BlockerWarning)) {
                model.addAttribute("error", e.getMessage());
            }
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

    /** Dragging in the month view: puts a single event on another day. Answers with a short message. */
    @PostMapping(value = "/events/{id}/move", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    ResponseEntity<String> move(@PathVariable Long id,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            List<Conflict> warnings = events.moveToDate(id, date, currentUser.require());
            String notes = warnings.stream().map(Conflict::message).distinct().collect(Collectors.joining(" "));
            return ResponseEntity.ok(notes.isEmpty() ? "Termin wurde verschoben."
                    : "Termin wurde verschoben. Achtung: " + notes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Diesen Termin darfst du nicht verschieben.");
        }
    }

    @PostMapping("/events/{id}/delete")
    String delete(@PathVariable Long id, @RequestParam(required = false) String back,
                  RedirectAttributes redirect) {
        events.delete(id, currentUser.require());
        redirect.addFlashAttribute("success", "Termin wurde gelöscht.");
        return backTo(back, null);
    }

    @PostMapping("/events/{id}/occurrence/cancel")
    String cancelOccurrence(@PathVariable Long id, @RequestParam Instant recurrenceId,
                            @RequestParam(required = false) String back, RedirectAttributes redirect) {
        try {
            events.cancelOccurrence(id, recurrenceId, currentUser.require());
            redirect.addFlashAttribute("success", "Das Vorkommen wurde abgesagt.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return backTo(back, null);
    }

    @PostMapping("/events/{id}/occurrence")
    String overrideOccurrence(@PathVariable Long id, @RequestParam Instant recurrenceId,
                              @RequestParam String title,
                              @RequestParam(required = false) String description,
                              @RequestParam(required = false) String location,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
                              @RequestParam(required = false) String back, RedirectAttributes redirect) {
        try {
            events.overrideOccurrence(id, recurrenceId,
                    new OccurrenceInput(title, description, location, start, end),
                    currentUser.require());
            redirect.addFlashAttribute("success", "Das Vorkommen wurde geändert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return backTo(back, start.toLocalDate());
    }

    /** A free time offered as suggestion in the form. */
    public record Alternative(String label, LocalDate startDate, LocalTime startTime, LocalDate endDate,
                              LocalTime endTime) {
    }

    /** Signals "show the warning first" in the change form (not an error). */
    private static final class BlockerWarning extends IllegalArgumentException {
    }

    /**
     * Shows warnings of blockers once, with free alternatives; only with "save anyway" ({@code confirmBlockers})
     * the form is saved. The service itself refuses blocks when saving.
     */
    private boolean warnAboutBlockers(EventForm form, Long eventId, User actor, Model model) {
        if (form.isConfirmBlockers()) {
            return false;
        }
        List<Conflict> found = events.conflicts(form.toInput(), eventId, actor);
        if (found.isEmpty() || BlockerConflicts.blocks(found)) {
            return false;
        }
        model.addAttribute("blockerWarnings", found.stream().map(Conflict::message).distinct().toList());
        ZoneId zone = settings.zone();
        LocalDate startDate = form.getStartDate();
        Instant start = form.isAllDay() || form.getStartTime() == null ? startDate.atStartOfDay(zone).toInstant()
                : startDate.atTime(form.getStartTime()).atZone(zone).toInstant();
        Instant end = form.isAllDay() || form.getEndTime() == null
                ? form.getEndDate().plusDays(1).atStartOfDay(zone).toInstant()
                : form.getEndDate().atTime(form.getEndTime()).atZone(zone).toInstant();
        List<Alternative> alternatives = form.isAllDay() ? List.of()
                : blockerConflicts.alternatives(form.getMemberIds(), new TimeRange(start, end), eventId).stream()
                .map(t -> {
                    ZonedDateTime from = t.start().atZone(zone);
                    ZonedDateTime to = t.end().atZone(zone);
                    return new Alternative(ALTERNATIVE.format(from) + "–" + TIME.format(to), from.toLocalDate(),
                            from.toLocalTime(), to.toLocalDate(), to.toLocalTime());
                }).toList();
        model.addAttribute("alternatives", alternatives);
        return true;
    }

    /** Back to the calendar view the user came from, otherwise to the month of {@code date}. */
    private static String backTo(String back, LocalDate date) {
        return "redirect:" + SafeRedirect.target(back, date == null ? "/calendar/month"
                : "/calendar/month?date=" + date);
    }

    private Occurrence seriesStart(CalendarEvent event) {
        return new Occurrence(event.getId(), event.getUid(), event.getStartsAt(), event.getTitle(),
                event.getDescription(), event.getLocation(), event.getStartsAt().atZone(settings.zone()),
                event.getEndsAt().atZone(settings.zone()), event.isAllDay(), event.isPrivateEvent(),
                event.getRrule() != null, event.getCreatedBy(), event.getMemberIds(),
                event.getCategoryId().orElse(null));
    }

    private void addFormData(Model model) {
        model.addAttribute("allMembers", members.findAll());
        model.addAttribute("allCategories", categories.list(currentUser.require()));
    }

    /** To-do the user may attach an event to (ACL action {@code LINK}). */
    private Todo linkableTodo(Long todoId, User actor) {
        Todo todo = todoService.get(todoId, actor);
        if (!todoAccess.can(actor, "LINK", todo)) {
            throw new AccessDeniedException("Keine Berechtigung, einen Termin mit diesem ToDo zu verknüpfen");
        }
        return todo;
    }

    /** Linked to-dos the viewer may see. */
    private List<Todo> linkedTodos(Long eventId, User viewer) {
        List<Todo> result = new ArrayList<>();
        for (Long todoId : todoLinks.todoIds(eventId)) {
            try {
                result.add(todoService.get(todoId, viewer));
            } catch (TodoNotFoundException ignored) {
                // invisible or deleted
            }
        }
        return result;
    }
}
