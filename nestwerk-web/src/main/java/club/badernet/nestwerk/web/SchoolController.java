package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.GermanHolidays;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.GradeService;
import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.settings.CalendarSettings;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Schulübersicht: Schulprofile der Kinder und ihre Schuljahre. */
@Controller
@RequestMapping("/school")
@PreAuthorize("hasAuthority('SCHOOL_VIEW')")
public class SchoolController {

    private final SchoolService school;
    private final SchoolAccess access;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings calendarSettings;
    private final GradeService grades;

    public SchoolController(SchoolService school, SchoolAccess access, FamilyMemberService members,
                            CurrentUser currentUser, CalendarSettings calendarSettings, GradeService grades) {
        this.grades = grades;
        this.school = school;
        this.access = access;
        this.members = members;
        this.currentUser = currentUser;
        this.calendarSettings = calendarSettings;
    }

    /** Ein Kind mit seinem Profil und den Schuljahren für die Anzeige. */
    public record ProfileRow(SchoolProfile profile, FamilyMember member, SchoolYear active, List<SchoolYear> years) {
    }

    @GetMapping
    String page(Model model) {
        var user = currentUser.require();
        Map<Long, FamilyMember> byId = members.findAll().stream()
                .collect(Collectors.toMap(FamilyMember::getId, Function.identity()));
        List<ProfileRow> rows = school.profiles().stream()
                .filter(p -> byId.containsKey(p.getMemberId()))
                .map(p -> {
                    List<SchoolYear> years = school.yearsOf(p.getId());
                    SchoolYear active = years.stream().filter(SchoolYear::isActive).findFirst().orElse(null);
                    return new ProfileRow(p, byId.get(p.getMemberId()), active, years);
                })
                .sorted((a, b) -> a.member().getName().compareToIgnoreCase(b.member().getName()))
                .toList();
        var withProfile = rows.stream().map(r -> r.member().getId()).collect(Collectors.toSet());
        model.addAttribute("rows", rows);
        model.addAttribute("gradeYears", rows.stream().map(ProfileRow::active).filter(java.util.Objects::nonNull)
                .map(SchoolYear::getId).filter(id -> grades.mayView(id, user)).collect(Collectors.toSet()));
        model.addAttribute("freeMembers", members.findAll().stream()
                .filter(m -> !withProfile.contains(m.getId())).toList());
        model.addAttribute("states", new TreeMap<>(GermanHolidays.STATES));
        model.addAttribute("canEdit", access.has(user, SchoolPermission.SCHOOL_EDIT));
        model.addAttribute("canSettings", access.has(user, SchoolPermission.SCHOOL_SETTINGS));
        LocalDate today = LocalDate.now(calendarSettings.zone());
        int startYear = today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
        model.addAttribute("suggestedLabel", startYear + "/" + String.format("%02d", (startYear + 1) % 100));
        model.addAttribute("suggestedStart", LocalDate.of(startYear, 8, 1));
        model.addAttribute("suggestedEnd", LocalDate.of(startYear + 1, 7, 31));
        return "school/index";
    }

    @PostMapping("/profiles")
    @PreAuthorize("hasAuthority('SCHOOL_SETTINGS')")
    String createProfile(@RequestParam Long memberId, @RequestParam(required = false) String state,
                         RedirectAttributes redirect) {
        return run(redirect, "Schulprofil angelegt.", () -> school.createProfile(memberId, state));
    }

    @PostMapping("/profiles/{id}/state")
    @PreAuthorize("hasAuthority('SCHOOL_SETTINGS')")
    String changeState(@PathVariable Long id, @RequestParam(required = false) String state,
                       RedirectAttributes redirect) {
        return run(redirect, "Bundesland gespeichert.", () -> school.changeState(id, state));
    }

    @PostMapping("/profiles/{id}/years")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String startYear(@PathVariable Long id, @RequestParam String label, @RequestParam String school,
                     @RequestParam String className,
                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn,
                     RedirectAttributes redirect) {
        return run(redirect, "Schuljahr angelegt und aktiviert.",
                () -> this.school.startYear(id, label, school, className, startsOn, endsOn));
    }

    @PostMapping("/years/{id}")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String changeYear(@PathVariable Long id, @RequestParam String label, @RequestParam String school,
                      @RequestParam String className,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn,
                      RedirectAttributes redirect) {
        return run(redirect, "Schuljahr gespeichert.",
                () -> this.school.changeYear(id, label, school, className, startsOn, endsOn));
    }

    @PostMapping("/years/{id}/activate")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String activate(@PathVariable Long id, RedirectAttributes redirect) {
        return run(redirect, "Schuljahr aktiviert.", () -> school.activate(id));
    }

    private static String run(RedirectAttributes redirect, String success, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("success", success);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/school";
    }
}
