package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.Conflict;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.TimeChange;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.List;
import java.util.function.Supplier;

/**
 * Actions of the time grid, called by its touch and mouse gestures. Every action goes through the domain services
 * and answers with a short message and the warnings of blockers as JSON.
 */
@RestController
@RequestMapping("/calendar/actions")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarActionsController {

    /** What a change applies to: a single event, one occurrence of a series or the whole series. */
    public enum Scope { SINGLE, OCCURRENCE, SERIES }

    /** New times from a drag or resize: all-day with inclusive days, otherwise start and exclusive end. */
    public record TimesForm(Boolean allDay,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {

        /** Only the person changes (dragged sideways into another person's column at the same time). */
        boolean isEmpty() {
            return start == null && startDate == null;
        }

        TimeChange change() {
            return Boolean.TRUE.equals(allDay) ? new TimeChange.AllDay(startDate, endDate)
                    : new TimeChange.Timed(start, end);
        }
    }

    private static final String PERSON_CHANGED = "Die Person wurde geändert.";

    private final CalendarEventService events;
    private final CurrentUser currentUser;

    public CalendarActionsController(CalendarEventService events, CurrentUser currentUser) {
        this.events = events;
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

    /**
     * New times from dragging or resizing. With {@code fromMember}/{@code toMember} the event was dragged into another
     * person's column: that person takes the place of the other one in the same step (empty: column "Alle").
     */
    @PostMapping("/events/{id}/reschedule")
    ActionResult reschedule(@PathVariable Long id, @RequestParam Scope scope,
                            @RequestParam(required = false) Instant recurrenceId,
                            @RequestParam(required = false) Long fromMember,
                            @RequestParam(required = false) Long toMember,
                            @ModelAttribute TimesForm times) {
        User actor = currentUser.require();
        Supplier<List<Conflict>> timeChange = () -> times.isEmpty() ? List.of() : switch (scope) {
            case SINGLE -> events.reschedule(id, times.change(), actor);
            case OCCURRENCE -> events.rescheduleOccurrence(id, requireOccurrence(recurrenceId), times.change(), actor);
            case SERIES -> rescheduleSeries(id, times.change(), actor);
        };
        MemberSwap swap = new MemberSwap(fromMember, toMember);
        List<Conflict> found = swap.changesSomething()
                ? events.moveToMember(id, swap.from(), swap.to(), timeChange, actor)
                : timeChange.get();
        String moved = scope == Scope.SERIES ? "Die Serie wurde verschoben." : "Termin wurde verschoben.";
        String message = times.isEmpty() ? PERSON_CHANGED
                : swap.changesSomething() ? moved + " " + PERSON_CHANGED : moved;
        return new ActionResult(message, warnings(found), id);
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
