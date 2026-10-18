package club.badernet.nestwerk.web;

import club.badernet.nestwerk.attachment.AttachmentOwner;
import club.badernet.nestwerk.attachment.AttachmentService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.school.LetterService;
import club.badernet.nestwerk.school.SchoolAccess;
import club.badernet.nestwerk.school.SchoolLetter;
import club.badernet.nestwerk.school.SchoolPermission;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.settings.CalendarSettings;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

/** Elternbriefe eines Schuljahrs mit Frist, Anhang und Unterschrift. */
@Controller
@RequestMapping("/school")
@PreAuthorize("hasAuthority('SCHOOL_VIEW')")
public class LetterController {

    private final SchoolService school;
    private final LetterService letters;
    private final AttachmentService attachments;
    private final SchoolAccess access;
    private final FamilyMemberService members;
    private final CurrentUser currentUser;
    private final CalendarSettings calendarSettings;

    public LetterController(SchoolService school, LetterService letters, AttachmentService attachments,
                            SchoolAccess access, FamilyMemberService members, CurrentUser currentUser,
                            CalendarSettings calendarSettings) {
        this.school = school;
        this.letters = letters;
        this.attachments = attachments;
        this.access = access;
        this.members = members;
        this.currentUser = currentUser;
        this.calendarSettings = calendarSettings;
    }

    @GetMapping("/years/{yearId}/letters")
    String page(@PathVariable Long yearId, Model model) {
        SchoolYear year = school.year(yearId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SchoolProfile profile = school.profile(year.getProfileId()).orElseThrow();
        FamilyMember member = members.findAll().stream().filter(m -> m.getId().equals(profile.getMemberId()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("year", year);
        model.addAttribute("member", member);
        model.addAttribute("items", letters.list(yearId));
        model.addAttribute("today", LocalDate.now(calendarSettings.zone()));
        model.addAttribute("canEdit", access.has(currentUser.require(), SchoolPermission.SCHOOL_EDIT));
        return "school/letters";
    }

    @PostMapping("/years/{yearId}/letters")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String create(@PathVariable Long yearId, @RequestParam String title,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueOn,
                  @RequestParam(required = false) String note,
                  @RequestParam(name = "files", required = false) List<MultipartFile> files,
                  RedirectAttributes redirect) {
        try {
            var user = currentUser.require();
            SchoolLetter letter = letters.create(yearId, title, dueOn, note, user);
            store(letter.getId(), files);
            redirect.addFlashAttribute("success", "Brief angelegt.");
        } catch (IllegalArgumentException | AccessDeniedException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/school/years/" + yearId + "/letters";
    }

    @PostMapping("/letters/{id}/attachments")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String upload(@PathVariable Long id,
                  @RequestParam(name = "files", required = false) List<MultipartFile> files,
                  RedirectAttributes redirect) {
        Long yearId = letters.get(id).getYearId();
        try {
            int stored = store(id, files);
            redirect.addFlashAttribute(stored == 0 ? "error" : "success",
                    stored == 0 ? "Bitte eine Datei auswählen." : "Datei wurde hochgeladen.");
        } catch (IllegalArgumentException | AccessDeniedException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/school/years/" + yearId + "/letters";
    }

    @PostMapping("/letters/{id}/sign")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String sign(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean signed,
                RedirectAttributes redirect) {
        Long yearId = letters.get(id).getYearId();
        letters.setSigned(id, signed, currentUser.require());
        redirect.addFlashAttribute("success", signed ? "Als unterschrieben markiert." : "Unterschrift zurückgenommen.");
        return "redirect:/school/years/" + yearId + "/letters";
    }

    @PostMapping("/letters/{id}/delete")
    @PreAuthorize("hasAuthority('SCHOOL_EDIT')")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        Long yearId = letters.get(id).getYearId();
        letters.delete(id, currentUser.require());
        redirect.addFlashAttribute("success", "Brief gelöscht.");
        return "redirect:/school/years/" + yearId + "/letters";
    }

    private int store(Long letterId, List<MultipartFile> files) {
        int stored = 0;
        if (files != null) {
            var user = currentUser.require();
            for (MultipartFile f : files) {
                if (f != null && !f.isEmpty()) {
                    attachments.store(AttachmentOwner.letter(letterId), f, user);
                    stored++;
                }
            }
        }
        return stored;
    }
}
