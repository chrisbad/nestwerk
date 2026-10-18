package club.badernet.nestwerk.web;

import club.badernet.nestwerk.household.ChoreConflictException;
import club.badernet.nestwerk.household.ChoreEffort;
import club.badernet.nestwerk.household.ChoreNotFoundException;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskRepository;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.household.ChoreTaskStatus;
import club.badernet.nestwerk.household.DeviceService;
import club.badernet.nestwerk.household.HouseholdDevice;
import club.badernet.nestwerk.household.MemberPins;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wand-Tablet: ohne Benutzer-Login, nur mit gekoppeltem Geräte-Cookie. Zeigt ausschließlich die Aufgaben von
 * heute, morgen und überfällige; Abhaken nur mit PIN des Mitglieds.
 */
@Controller
@RequestMapping("/tablet")
public class TabletController {

    static final String COOKIE = "NESTWERK_DEVICE";
    private static final Duration COOKIE_AGE = Duration.ofDays(400);

    /** Eine Spalte (Mitglied oder „offen“) mit ihren Aufgaben. */
    public record Column(String name, String color, List<Card> cards) {
    }

    /** Eine Aufgabe als Karte; {@code effort} nur bei erledigten, die drei Zeitaufwände für den Buchen-Dialog. */
    public record Card(Long id, String title, String time, int minutes, String effort, String effortLabel,
                       boolean done, boolean overdue, String dayLabel, int minutesEasy, int minutesMedium,
                       int minutesHard) {
    }

    private final DeviceService devices;
    private final MemberPins pins;
    private final ChoreTaskRepository tasks;
    private final ChoreTaskService taskService;
    private final FamilyMemberRepository members;
    private final HouseholdViews views;
    private final Clock clock;

    public TabletController(DeviceService devices, MemberPins pins, ChoreTaskRepository tasks,
                            ChoreTaskService taskService, FamilyMemberRepository members, HouseholdViews views,
                            Clock clock) {
        this.devices = devices;
        this.pins = pins;
        this.tasks = tasks;
        this.taskService = taskService;
        this.members = members;
        this.views = views;
        this.clock = clock;
    }

    @GetMapping("/pair")
    String pairForm() {
        return "tablet/pair";
    }

    @PostMapping("/pair")
    String pair(@RequestParam(required = false) String code, HttpServletRequest request, HttpServletResponse response,
                Model model) {
        Optional<String> token;
        try {
            token = devices.pair(code);
        } catch (IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            return "tablet/pair";
        }
        if (token.isEmpty()) {
            model.addAttribute("error", "Der Code stimmt nicht oder ist abgelaufen.");
            return "tablet/pair";
        }
        ResponseCookie cookie = ResponseCookie.from(COOKIE, token.get())
                .httpOnly(true).secure(request.isSecure()).sameSite("Strict").path("/tablet").maxAge(COOKIE_AGE)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return "redirect:/tablet";
    }

    @GetMapping
    String board(@CookieValue(name = COOKIE, required = false) String token, Model model) {
        Optional<HouseholdDevice> device = devices.authenticate(token);
        if (device.isEmpty()) {
            return "redirect:/tablet/pair";
        }
        LocalDate today = LocalDate.now(clock);
        List<FamilyMember> all = members.findAllByOrderByNameAsc();
        Map<Long, FamilyMember> byId = new LinkedHashMap<>();
        all.forEach(m -> byId.put(m.getId(), m));
        List<ChoreTask> open = tasks.findAllByStatusAndDueOnLessThanEqual(ChoreTaskStatus.OPEN, today.plusDays(1));
        List<ChoreTask> doneToday = tasks.findAllByDueOnBetween(today, today).stream().filter(t -> !t.isOpen()).toList();
        List<ChoreTask> shown = new ArrayList<>(open);
        shown.addAll(doneToday);
        shown.sort(Comparator.comparing(ChoreTask::getDueOn)
                .thenComparing(t -> t.getDueTime() == null ? java.time.LocalTime.MAX : t.getDueTime()));
        List<Column> columns = new ArrayList<>();
        for (FamilyMember m : all) {
            columns.add(new Column(m.getName(), m.getColor(), cards(shown, t -> m.getId().equals(t.getAssigneeMemberId()), today)));
        }
        List<Card> unassigned = cards(shown, t -> t.getAssigneeMemberId() == null || !byId.containsKey(t.getAssigneeMemberId()), today);
        if (!unassigned.isEmpty()) {
            columns.add(new Column("Offen", null, unassigned));
        }
        model.addAttribute("device", device.get());
        model.addAttribute("columns", columns);
        model.addAttribute("members", all.stream().filter(FamilyMember::hasPin).toList());
        model.addAttribute("todayLabel", HouseholdViews.longDay(today));
        return "tablet/board";
    }

    @PostMapping("/tasks/{id}/complete")
    String complete(@PathVariable Long id, @CookieValue(name = COOKIE, required = false) String token,
                    @RequestParam(required = false) Long member, @RequestParam(required = false) String pin,
                    @RequestParam(required = false) ChoreEffort effort, @RequestParam(required = false) Integer minutes,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime performedAt,
                    RedirectAttributes redirect) {
        Optional<HouseholdDevice> device = devices.authenticate(token);
        if (device.isEmpty()) {
            return "redirect:/tablet/pair";
        }
        if (member == null) {
            redirect.addFlashAttribute("error", "Bitte antippen, wer es erledigt hat.");
            return "redirect:/tablet";
        }
        LocalDate today = LocalDate.now(clock);
        ChoreTask task = tasks.findById(id).filter(t -> !t.getDueOn().isAfter(today.plusDays(1)))
                .orElseThrow(ChoreNotFoundException::new);
        switch (pins.check(device.get().getId(), member, pin)) {
            case OK -> {
                try {
                    taskService.completeAsMember(task.getId(), member, Set.of(),
                            new ChoreTaskService.Booking(effort, minutes, performedAt));
                    redirect.addFlashAttribute("success", "„" + task.getChore().getTitle() + "“ erledigt – danke!");
                } catch (ChoreConflictException | IllegalArgumentException e) {
                    redirect.addFlashAttribute("error", e.getMessage());
                }
            }
            case WRONG -> redirect.addFlashAttribute("error", "Die PIN stimmt nicht.");
            case LOCKED -> redirect.addFlashAttribute("error", "Zu viele falsche Versuche. Bitte in 5 Minuten erneut.");
            case NO_PIN -> redirect.addFlashAttribute("error", "Für dieses Mitglied ist keine PIN eingerichtet.");
        }
        return "redirect:/tablet";
    }

    private List<Card> cards(List<ChoreTask> tasks, java.util.function.Predicate<ChoreTask> filter, LocalDate today) {
        return tasks.stream().filter(filter).map(t -> new Card(t.getId(), t.getChore().getTitle(), t.timeLabel(),
                        views.minutes(t), HouseholdViews.effortKey(t.getEffort()),
                        t.getEffort() == null ? null : t.getEffort().label(), !t.isOpen(), t.isOverdue(today),
                        HouseholdViews.dayLabel(t.getDueOn(), today), t.getChore().getMinutesEasy(),
                        t.getChore().getMinutesMedium(), t.getChore().getMinutesHard()))
                .toList();
    }
}
