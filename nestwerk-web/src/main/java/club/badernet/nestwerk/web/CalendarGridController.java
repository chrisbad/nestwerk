package club.badernet.nestwerk.web;

import club.badernet.nestwerk.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Time grid views (day, three days, week); the dialogs of the entries live in {@link CalendarSheetController}. */
@Controller
@RequestMapping("/calendar")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarGridController {

    private static final DateTimeFormatter DAY_TITLE = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d. MMM", Locale.GERMAN);
    private static final DateTimeFormatter SHORT_YEAR = DateTimeFormatter.ofPattern("d. MMM yyyy", Locale.GERMAN);

    private final TimeGridPage page;
    private final CalendarPageData pageData;
    private final CurrentUser currentUser;

    public CalendarGridController(TimeGridPage page, CalendarPageData pageData, CurrentUser currentUser) {
        this.page = page;
        this.pageData = pageData;
        this.currentUser = currentUser;
    }

    /** The day in lanes: time runs sideways and every person has a lane, whatever the split cookie says. */
    @GetMapping("/day")
    String day(@RequestParam(required = false) String date, Model model) {
        LocalDate focus = pageData.parseDate(date);
        grid(model, CalendarView.DAY, focus, GridSplit.BY_PERSON);
        model.addAttribute("lanes", true);
        return "calendar/lanes";
    }

    @GetMapping("/days3")
    String threeDays(@RequestParam(required = false) String date,
                     @CookieValue(name = GridSplit.COOKIE, required = false) String split, Model model) {
        return grid(model, CalendarView.THREE_DAYS, pageData.parseDate(date), GridSplit.fromCookie(split));
    }

    @GetMapping("/week")
    String week(@RequestParam(required = false) String date,
                @CookieValue(name = GridSplit.COOKIE, required = false) String split, Model model) {
        return grid(model, CalendarView.WEEK, pageData.parseDate(date), GridSplit.fromCookie(split));
    }

    private String grid(Model model, CalendarView view, LocalDate focus, GridSplit split) {
        List<LocalDate> days = view.days(focus);
        page.fill(model, days, view.path(), split, currentUser.require());
        model.addAttribute("title", view == CalendarView.DAY ? DAY_TITLE.format(focus) : range(days));
        model.addAttribute("focus", focus);
        model.addAttribute("prev", view.previous(focus));
        model.addAttribute("next", view.next(focus));
        return "calendar/grid";
    }

    private static String range(List<LocalDate> days) {
        return SHORT.format(days.getFirst()) + " – " + SHORT_YEAR.format(days.getLast());
    }
}
