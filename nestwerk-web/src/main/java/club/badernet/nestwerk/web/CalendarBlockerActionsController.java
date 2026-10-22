package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.Blocker;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/** Creates a time (work, sleep, rest ...) from the calendar's dialog "Neu"; the rest of the form stays at its defaults. */
@RestController
@RequestMapping("/calendar/actions/blockers")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarBlockerActionsController {

    private final BlockerService blockers;
    private final CurrentUser currentUser;

    public CalendarBlockerActionsController(BlockerService blockers, CurrentUser currentUser) {
        this.blockers = blockers;
        this.currentUser = currentUser;
    }

    /** Without {@code memberIds} the time belongs to the caller's own member. */
    @PostMapping
    ActionResult create(@RequestParam String title, @RequestParam BlockerKind kind,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
                        @RequestParam(name = "weekdays", required = false) Set<DayOfWeek> weekdays,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
                        @RequestParam(name = "memberIds", required = false) Set<Long> memberIds) {
        User actor = currentUser.require();
        Long own = blockers.ownMember(actor).orElse(null);
        BlockerForm form = BlockerForm.forNew(kind, memberIds == null || memberIds.isEmpty() ? own : null, validFrom);
        form.setTitle(title);
        form.setStartTime(startTime);
        form.setEndTime(endTime);
        form.setWeekdays(weekdays);
        if (memberIds != null && !memberIds.isEmpty()) {
            form.setMemberIds(new HashSet<>(memberIds));
        }
        Blocker saved = blockers.create(form.toInput(), actor);
        return ActionResult.of("„" + saved.getTitle() + "“ ist gespeichert.");
    }
}
