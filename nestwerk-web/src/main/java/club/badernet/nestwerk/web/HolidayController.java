package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.GermanHolidays;
import club.badernet.nestwerk.school.HolidayException;
import club.badernet.nestwerk.school.HolidayService;
import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolHoliday;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.settings.CalendarSettings;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Schulferien je Bundesland: Anzeige, Abruf aus der Online-Quelle und manuelle Einträge. */
@Controller
@RequestMapping("/school/holidays")
@PreAuthorize("hasAuthority('SCHOOL_VIEW')")
public class HolidayController {

    private final SchoolService school;
    private final HolidayService holidays;
    private final SchoolAccess access;
    private final CurrentUser currentUser;
    private final CalendarSettings calendarSettings;

    public HolidayController(SchoolService school, HolidayService holidays, SchoolAccess access,
                             CurrentUser currentUser, CalendarSettings calendarSettings) {
        this.school = school;
        this.holidays = holidays;
        this.access = access;
        this.currentUser = currentUser;
        this.calendarSettings = calendarSettings;
    }

    @GetMapping
    String page(Model model) {
        LocalDate today = LocalDate.now(calendarSettings.zone());
        Set<String> states = new TreeSet<>(school.profiles().stream().map(SchoolProfile::getState)
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.toSet()));
        Map<String, List<SchoolHoliday>> byState = new LinkedHashMap<>();
        for (String state : states) {
            byState.put(state, holidays.list(state, today.minusMonths(1), today.plusYears(1)));
        }
        model.addAttribute("byState", byState);
        model.addAttribute("stateNames", GermanHolidays.STATES);
        model.addAttribute("allStates", new TreeSet<>(GermanHolidays.STATES.keySet()));
        model.addAttribute("canSettings", access.has(currentUser.require(), SchoolPermission.SCHOOL_SETTINGS));
        return "school/holidays";
    }

    @PostMapping("/{state}/refresh")
    @PreAuthorize("hasAuthority('SCHOOL_SETTINGS')")
    String refresh(@PathVariable String state, RedirectAttributes redirect) {
        if (!GermanHolidays.STATES.containsKey(state)) {
            redirect.addFlashAttribute("error", "Unbekanntes Bundesland");
            return "redirect:/school/holidays";
        }
        try {
            boolean changed = holidays.refresh(state, LocalDate.now(calendarSettings.zone()));
            redirect.addFlashAttribute("success", changed ? "Ferien aktualisiert." : "Die Ferien sind aktuell.");
        } catch (HolidayException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/school/holidays";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCHOOL_SETTINGS')")
    String add(@RequestParam String state, @RequestParam String name,
               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn,
               RedirectAttributes redirect) {
        return run(redirect, "Eintrag gespeichert.",
                () -> holidays.addManual(state, name, startsOn, endsOn, currentUser.require()));
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('SCHOOL_SETTINGS')")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        return run(redirect, "Eintrag gelöscht.", () -> holidays.deleteManual(id, currentUser.require()));
    }

    private static String run(RedirectAttributes redirect, String success, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", success);
        } catch (IllegalArgumentException | AccessDeniedException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/school/holidays";
    }
}
