package club.badernet.nestwerk.web;

import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The personal day agenda, the default view of the calendar: only what concerns the signed-in user. */
@Controller
@RequestMapping("/calendar")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarMyDayController {

    private static final DateTimeFormatter DAY_TITLE = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN);

    private final MyDayPage page;
    private final CalendarPageData pageData;
    private final CurrentUser currentUser;

    public CalendarMyDayController(MyDayPage page, CalendarPageData pageData, CurrentUser currentUser) {
        this.page = page;
        this.pageData = pageData;
        this.currentUser = currentUser;
    }

    @GetMapping("/my")
    String myDay(@RequestParam(required = false) String date, Model model) {
        User viewer = currentUser.require();
        LocalDate focus = pageData.parseDate(date);
        model.addAttribute("day", page.forDay(focus, viewer));
        model.addAttribute("title", DAY_TITLE.format(focus));
        model.addAttribute("focus", focus);
        model.addAttribute("prev", CalendarView.MY_DAY.previous(focus));
        model.addAttribute("next", CalendarView.MY_DAY.next(focus));
        pageData.addCommon(model, CalendarView.MY_DAY.path(), viewer);
        return "calendar/my";
    }
}
