package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.Conflict;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.CategoryNotFoundException;
import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.calendar.TimeChange;
import club.badernet.nestwerk.household.ChoreConflictException;
import club.badernet.nestwerk.household.ChoreNotFoundException;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Actions of the time grid, called by its touch and mouse gestures. Every action goes through the domain services
 * and answers with a short message and the warnings of blockers as JSON.
 */
@RestController
@RequestMapping("/calendar/actions")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarActionsController {

    /** Answer of an action: message for a toast, warnings of blockers and the affected event, if any. */
    public record ActionResult(String message, List<String> warnings, Long eventId) {

        static ActionResult of(String message) {
            return new ActionResult(message, List.of(), null);
        }
    }

    /** What a change applies to: a single event, one occurrence of a series or the whole series. */
    public enum Scope { SINGLE, OCCURRENCE, SERIES }

    /** New times from a drag or resize: all-day with inclusive days, otherwise start and exclusive end. */
    public record TimesForm(Boolean allDay,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {

        TimeChange change() {
            return Boolean.TRUE.equals(allDay) ? new TimeChange.AllDay(startDate, endDate)
                    : new TimeChange.Timed(start, end);
        }
    }

    private final CalendarEventService events;
    private final ChoreTaskService chores;
    private final CurrentUser currentUser;

    public CalendarActionsController(CalendarEventService events, ChoreTaskService chores, CurrentUser currentUser) {
        this.events = events;
        this.chores = chores;
        this.currentUser = currentUser;
    }

    /** Quick creation from the grid; blocker warnings are reported but do not stop the creation. */
    @PostMapping("/events")
    ActionResult create(@ModelAttribute EventForm form) {
        User actor = currentUser.require();
        List<String> warnings = warnings(events.conflicts(form.toInput(), null, actor));
        CalendarEvent created = events.create(form.toInput(), actor);
        return new ActionResult("Termin „" + created.getTitle() + "“ wurde angelegt.", warnings, created.getId());
    }

    @PostMapping("/events/{id}/reschedule")
    ActionResult reschedule(@PathVariable Long id, @RequestParam Scope scope,
                            @RequestParam(required = false) Instant recurrenceId,
                            @ModelAttribute TimesForm times) {
        User actor = currentUser.require();
        List<Conflict> found = switch (scope) {
            case SINGLE -> events.reschedule(id, times.change(), actor);
            case OCCURRENCE -> events.rescheduleOccurrence(id, requireOccurrence(recurrenceId), times.change(), actor);
            case SERIES -> rescheduleSeries(id, times.change(), actor);
        };
        return new ActionResult(scope == Scope.SERIES ? "Die Serie wurde verschoben." : "Termin wurde verschoben.",
                warnings(found), id);
    }

    /**
     * Saves the quick edit of the bottom sheet. {@code shownStart}/{@code shownEnd} are the times the sheet was
     * opened with; for a series only a change of them moves the series.
     */
    @PostMapping("/events/{id}/update")
    ActionResult update(@PathVariable Long id, @RequestParam Scope scope,
                        @RequestParam(required = false) Instant recurrenceId,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                        LocalDateTime shownStart,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                        LocalDateTime shownEnd,
                        @ModelAttribute("form") EventForm changes) {
        User actor = currentUser.require();
        TimeChange change = changes.toInput().timeChange();
        return switch (scope) {
            case SINGLE -> {
                EventForm form = withDetails(EventForm.from(events.get(id, actor)), changes);
                form.setAllDay(changes.isAllDay());
                form.setStartDate(changes.getStartDate());
                form.setStartTime(changes.getStartTime());
                form.setEndDate(changes.getEndDate());
                form.setEndTime(changes.getEndTime());
                yield save(id, form, actor, "Termin wurde gespeichert.");
            }
            case OCCURRENCE -> new ActionResult("Das Vorkommen wurde geändert.", warnings(events.changeOccurrence(id,
                    requireOccurrence(recurrenceId), changes.getTitle(), change, actor)), id);
            case SERIES -> {
                boolean timesChanged = change instanceof TimeChange.Timed(LocalDateTime start, LocalDateTime end)
                        && !(start.equals(shownStart) && end.equals(shownEnd));
                List<String> moved = timesChanged ? warnings(rescheduleSeries(id, change, actor)) : List.of();
                ActionResult saved = save(id, withDetails(EventForm.from(events.get(id, actor)), changes), actor,
                        "Die Serie wurde gespeichert.");
                yield new ActionResult(saved.message(), moved.isEmpty() ? saved.warnings() : moved, id);
            }
        };
    }

    @PostMapping("/events/{id}/delete")
    ActionResult delete(@PathVariable Long id) {
        events.delete(id, currentUser.require());
        return ActionResult.of("Termin wurde gelöscht.");
    }

    @PostMapping("/events/{id}/occurrence/cancel")
    ActionResult cancelOccurrence(@PathVariable Long id, @RequestParam Instant recurrenceId) {
        events.cancelOccurrence(id, recurrenceId, currentUser.require());
        return ActionResult.of("Das Vorkommen wurde abgesagt.");
    }

    /** Plans a chore at another time of its day. */
    @PostMapping("/chores/{id}/reschedule")
    ActionResult rescheduleChore(@PathVariable Long id,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start) {
        chores.reschedule(id, start, currentUser.require());
        return ActionResult.of("Hausarbeit wurde verschoben.");
    }

    @ExceptionHandler({IllegalArgumentException.class, ChoreConflictException.class})
    ResponseEntity<ActionResult> invalid(RuntimeException e) {
        return ResponseEntity.badRequest().body(ActionResult.of(e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ActionResult> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ActionResult.of("Das darfst du hier nicht ändern."));
    }

    @ExceptionHandler({EventNotFoundException.class, CategoryNotFoundException.class, ChoreNotFoundException.class})
    ResponseEntity<ActionResult> missing(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ActionResult.of(e.getMessage()));
    }

    private ActionResult save(Long id, EventForm form, User actor, String message) {
        List<String> warnings = warnings(events.conflicts(form.toInput(), id, actor));
        events.update(id, form.toInput(), actor);
        return new ActionResult(message, warnings, id);
    }

    /** Title, category and members from the sheet; everything else stays as stored. */
    private static EventForm withDetails(EventForm stored, EventForm changes) {
        stored.setTitle(changes.getTitle());
        stored.setCategoryId(changes.getCategoryId());
        stored.setMemberIds(changes.getMemberIds());
        return stored;
    }

    private List<Conflict> rescheduleSeries(Long id, TimeChange change, User actor) {
        if (!(change instanceof TimeChange.Timed(LocalDateTime start, LocalDateTime end))) {
            throw new IllegalArgumentException("Bei ganztägigen Serien lässt sich nur ein einzelnes Vorkommen verschieben.");
        }
        return events.rescheduleSeries(id, start.toLocalTime(), Duration.between(start, end), actor);
    }

    private static Instant requireOccurrence(Instant recurrenceId) {
        if (recurrenceId == null) {
            throw new IllegalArgumentException("Welches Vorkommen der Serie ist gemeint?");
        }
        return recurrenceId;
    }

    private static List<String> warnings(List<Conflict> found) {
        return found.stream().filter(c -> c.strength() == BlockerStrength.WARN).map(Conflict::message).distinct()
                .toList();
    }
}
