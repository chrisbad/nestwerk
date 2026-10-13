package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.CalendarGrid;
import club.badernet.nestwerk.calendar.CalendarSettings;
import club.badernet.nestwerk.calendar.DayCell;
import club.badernet.nestwerk.calendar.Occurrence;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/calendar")
@PreAuthorize("hasAuthority('CALENDAR_VIEW')")
public class CalendarController {

    private static final int AGENDA_DAYS = 60;

    private final CalendarEventService events;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings settings;

    public CalendarController(CalendarEventService events, FamilyMemberService members,
                              CurrentUser currentUser, CalendarSettings settings) {
        this.events = events;
        this.members = members;
        this.currentUser = currentUser;
        this.settings = settings;
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
        Map<LocalDate, List<Occurrence>> nonEmpty = new LinkedHashMap<>();
        byDay.forEach((d, l) -> {
            if (!l.isEmpty()) {
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

    private Map<LocalDate, List<Occurrence>> load(List<LocalDate> days) {
        User viewer = currentUser.require();
        Instant from = days.getFirst().atStartOfDay(settings.zone()).toInstant();
        Instant to = days.getLast().plusDays(1).atStartOfDay(settings.zone()).toInstant();
        return CalendarGrid.groupByDay(events.findOccurrences(from, to, viewer), days);
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
