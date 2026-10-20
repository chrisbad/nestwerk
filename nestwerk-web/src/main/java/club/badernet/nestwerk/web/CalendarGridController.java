package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.CalendarGrid;
import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Time grid views (day, three days, week) and the bottom sheet of an event in the grid. */
@Controller
@RequestMapping("/calendar")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarGridController {

    private static final int THREE_DAYS = 3;
    /** How far from its original start a moved occurrence is still looked up for the sheet. */
    private static final Duration OCCURRENCE_LOOKUP = Duration.ofDays(180);
    private static final DateTimeFormatter DAY_TITLE = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d. MMM", Locale.GERMAN);
    private static final DateTimeFormatter SHORT_YEAR = DateTimeFormatter.ofPattern("d. MMM yyyy", Locale.GERMAN);

    private final TimeGridPage page;
    private final CalendarPageData pageData;
    private final CalendarEventService events;
    private final CurrentUser currentUser;
    private final CalendarSettings settings;

    public CalendarGridController(TimeGridPage page, CalendarPageData pageData, CalendarEventService events,
                                  CurrentUser currentUser, CalendarSettings settings) {
        this.page = page;
        this.pageData = pageData;
        this.events = events;
        this.currentUser = currentUser;
        this.settings = settings;
    }

    @GetMapping("/day")
    String day(@RequestParam(required = false) String date, Model model) {
        LocalDate day = pageData.parseDate(date);
        return grid(model, List.of(day), "day", DAY_TITLE.format(day), day.minusDays(1), day.plusDays(1));
    }

    @GetMapping("/days3")
    String threeDays(@RequestParam(required = false) String date, Model model) {
        LocalDate first = pageData.parseDate(date);
        List<LocalDate> days = first.datesUntil(first.plusDays(THREE_DAYS)).toList();
        return grid(model, days, "days3", range(days), first.minusDays(THREE_DAYS), first.plusDays(THREE_DAYS));
    }

    @GetMapping("/week")
    String week(@RequestParam(required = false) String date, Model model) {
        List<LocalDate> days = CalendarGrid.weekDays(pageData.parseDate(date));
        return grid(model, days, "week", range(days), days.getFirst().minusWeeks(1), days.getFirst().plusWeeks(1));
    }

    /** Bottom sheet of an occurrence: details, quick edit and delete. Loaded into the grid's dialog. */
    @GetMapping("/events/{id}/sheet")
    String sheet(@PathVariable Long id, @RequestParam(required = false) Instant occurrence, Model model) {
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

    private String grid(Model model, List<LocalDate> days, String view, String title, LocalDate prev,
                        LocalDate next) {
        page.fill(model, days, view, currentUser.require());
        model.addAttribute("title", title);
        model.addAttribute("prev", prev);
        model.addAttribute("next", next);
        return "calendar/grid";
    }

    private static String range(List<LocalDate> days) {
        return SHORT.format(days.getFirst()) + " – " + SHORT_YEAR.format(days.getLast());
    }
}
