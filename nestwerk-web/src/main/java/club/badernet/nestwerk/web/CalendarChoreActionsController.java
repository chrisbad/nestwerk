package club.badernet.nestwerk.web;

import club.badernet.nestwerk.household.ChoreEffort;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Set;

/**
 * Actions on planned chores from the calendar's dialog and gestures: book, undo, move, reassign, drop a single date
 * and edit or delete the whole chore. Everything goes through the household services with their permissions.
 */
@RestController
@RequestMapping("/calendar/actions/chores")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarChoreActionsController {

    /** A member id from a form; empty means "nobody, open to take". */
    private static final String NOBODY = "";

    private final ChoreTaskService tasks;
    private final ChoreService chores;
    private final HouseholdAccess access;
    private final CurrentUser currentUser;

    public CalendarChoreActionsController(ChoreTaskService tasks, ChoreService chores, HouseholdAccess access,
                                          CurrentUser currentUser) {
        this.tasks = tasks;
        this.chores = chores;
        this.access = access;
        this.currentUser = currentUser;
    }

    @PostMapping("/{id}/complete")
    ActionResult complete(@PathVariable Long id, @RequestParam(required = false) Long doer,
                          @RequestParam(name = "helpers", required = false) Set<Long> helpers,
                          @RequestParam(required = false) ChoreEffort effort,
                          @RequestParam(required = false) Integer minutes,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                          LocalDateTime performedAt) {
        tasks.complete(id, requireModule(), doer, helpers, new ChoreTaskService.Booking(effort, minutes, performedAt));
        return ActionResult.of("Erledigt – danke!");
    }

    @PostMapping("/{id}/undo")
    ActionResult undo(@PathVariable Long id) {
        tasks.undo(id, requireModule());
        return ActionResult.of("Die Hausarbeit ist wieder offen.");
    }

    /**
     * Moves the task to another day and time and, if {@code member} is sent, gives it to that member (empty: nobody).
     * Used by the dialog and by dragging, also into another person's column.
     */
    @PostMapping("/{id}/update")
    ActionResult update(@PathVariable Long id,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
                        @RequestParam(required = false) String member) {
        User actor = requireModule();
        ChoreTask task = tasks.get(id, actor);
        Long memberId = member == null ? task.getAssigneeMemberId() : parseMember(member);
        if (Objects.equals(memberId, task.getAssigneeMemberId())) {
            tasks.reschedule(id, date, start, actor);
            return ActionResult.of("Hausarbeit wurde verschoben.");
        }
        tasks.reassignAndReschedule(id, memberId, date, start, actor);
        return ActionResult.of(memberId == null ? "Die Hausarbeit ist jetzt offen zum Nehmen."
                : "Hausarbeit wurde verschoben und neu zugewiesen.");
    }

    /** Drops the task for its date only; the chore and its other dates stay. */
    @PostMapping("/{id}/cancel")
    ActionResult cancel(@PathVariable Long id) {
        tasks.cancel(id, requireModule());
        return ActionResult.of("Der Termin der Hausarbeit wurde gelöscht.");
    }

    @PostMapping("/templates/{choreId}/update")
    ActionResult updateChore(@PathVariable Long choreId, @ModelAttribute ChoreForm form) {
        chores.update(choreId, form.toInput(), requireModule());
        return ActionResult.of("Hausarbeit wurde gespeichert. Die kommenden Termine sind neu geplant.");
    }

    @PostMapping("/templates/{choreId}/delete")
    ActionResult deleteChore(@PathVariable Long choreId) {
        chores.delete(choreId, requireModule());
        return ActionResult.of("Hausarbeit wurde gelöscht.");
    }

    private User requireModule() {
        User user = currentUser.require();
        if (!access.mayOpen(user.getUsername())) {
            throw new AccessDeniedException("Keine Berechtigung für den Haushaltsplan");
        }
        return user;
    }

    private static Long parseMember(String member) {
        if (NOBODY.equals(member.trim())) {
            return null;
        }
        try {
            return Long.valueOf(member.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unbekanntes Familienmitglied ausgewählt.");
        }
    }
}
