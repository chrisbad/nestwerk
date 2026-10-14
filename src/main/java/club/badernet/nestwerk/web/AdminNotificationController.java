package club.badernet.nestwerk.web;

import club.badernet.nestwerk.notification.AppSettings;
import club.badernet.nestwerk.notification.Channel;
import club.badernet.nestwerk.notification.DeliveryWorker;
import club.badernet.nestwerk.notification.MailDelivery;
import club.badernet.nestwerk.notification.NotificationPreferences;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.notification.VapidKeys;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.util.Arrays;
import java.util.Map;

/** Verwaltung der Benachrichtigungen: Erinnerungszeit, globale Standard-Kanäle, Status von E-Mail und Push. */
@Controller
@RequestMapping("/admin/notifications")
@PreAuthorize("hasAuthority('NOTIFICATION_SETTINGS_MANAGE')")
public class AdminNotificationController {

    /** Globale Einstellung eines Anlasses. */
    public record GlobalRow(NotificationType type, boolean email, boolean push) {
    }

    private final AppSettings settings;
    private final NotificationPreferences preferences;
    private final MailDelivery mail;
    private final VapidKeys vapid;
    private final DeliveryWorker worker;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AdminNotificationController(AppSettings settings, NotificationPreferences preferences, MailDelivery mail,
                                       VapidKeys vapid, DeliveryWorker worker, CurrentUser currentUser, Clock clock) {
        this.settings = settings;
        this.preferences = preferences;
        this.mail = mail;
        this.vapid = vapid;
        this.worker = worker;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping
    String page(Model model) {
        model.addAttribute("hour", settings.reminderHour());
        model.addAttribute("leadDays", settings.defaultLeadDays());
        model.addAttribute("rows", Arrays.stream(NotificationType.values()).map(t -> new GlobalRow(t,
                preferences.globalEnabled(t, Channel.EMAIL), preferences.globalEnabled(t, Channel.PUSH))).toList());
        model.addAttribute("mailConfigured", mail.configured());
        model.addAttribute("vapidKey", vapid.publicKey());
        model.addAttribute("failures", worker.recentFailures(20).stream()
                .map(f -> Map.of("channel", f.channel(), "user", f.user(), "title", f.title(),
                        "error", f.error() == null ? "" : f.error(),
                        "at", NotificationController.WHEN.format(f.at().atZone(clock.getZone()))))
                .toList());
        currentUser.find().map(User::getEmail).ifPresent(e -> model.addAttribute("adminEmail", e));
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

    @PostMapping("/preferences")
    String globalPreferences(@RequestParam Map<String, String> form, RedirectAttributes redirect) {
        NotificationSettingsController.applyMatrix(form, NotificationPreferences.Scope.GLOBAL, 0L, preferences);
        redirect.addFlashAttribute("success", "Standard-Kanäle wurden gespeichert.");
        return "redirect:/admin/notifications";
    }

    @PostMapping("/test-mail")
    String testMail(RedirectAttributes redirect) {
        String to = currentUser.require().getEmail();
        if (to == null || to.isBlank()) {
            redirect.addFlashAttribute("error", "Hinterlege zuerst unter „Meine Benachrichtigungswege“ deine E-Mail-Adresse.");
            return "redirect:/admin/notifications";
        }
        try {
            mail.send(to, "Nestwerk: Testnachricht", "Der Mailversand funktioniert.", "/admin/notifications");
            redirect.addFlashAttribute("success", "Testmail an " + to + " wurde verschickt.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/notifications";
    }
}
