package club.badernet.nestwerk.web;

import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.security.CurrentUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Entry of the school module: opens the timetable of the chosen child or leads to the settings. */
@Controller
@RequestMapping("/school")
@PreAuthorize("hasAuthority('SCHOOL_VIEW')")
public class SchoolController {

    private final SchoolHeader header;
    private final SchoolAccess access;
    private final CurrentUser currentUser;

    public SchoolController(SchoolHeader header, SchoolAccess access, CurrentUser currentUser) {
        this.header = header;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping
    String page(@RequestParam(required = false) Long child, HttpSession session, Model model) {
        var year = header.chooseYear(child, session);
        if (year.isPresent()) {
            return "redirect:/school/years/" + year.get().getId() + "/timetable";
        }
        var user = currentUser.require();
        if (access.has(user, SchoolPermission.SCHOOL_SETTINGS) || access.has(user, SchoolPermission.SCHOOL_EDIT)) {
            return "redirect:/school/settings/years";
        }
        return "school/index";
    }
}
