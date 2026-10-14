package club.badernet.nestwerk.web;

import club.badernet.nestwerk.notification.AppSettings;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Verwaltung der Benachrichtigungen: Erinnerungszeit und Standard-Vorlauf (Kanäle folgen). */
@Controller
@RequestMapping("/admin/notifications")
@PreAuthorize("hasAuthority('NOTIFICATION_SETTINGS_MANAGE')")
public class AdminNotificationController {

    private final AppSettings settings;

    public AdminNotificationController(AppSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    String page(Model model) {
        model.addAttribute("hour", settings.reminderHour());
        model.addAttribute("leadDays", settings.defaultLeadDays());
        return "admin/notifications";
    }

    @PostMapping("/reminders")
    String reminders(@RequestParam int hour, @RequestParam int leadDays, RedirectAttributes redirect) {
        try {
            settings.updateReminders(hour, leadDays);
            redirect.addFlashAttribute("success", "Erinnerungen wurden gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/notifications";
    }
}
