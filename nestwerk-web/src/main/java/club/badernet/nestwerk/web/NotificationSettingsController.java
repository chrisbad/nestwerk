package club.badernet.nestwerk.web;

import club.badernet.nestwerk.notification.Channel;
import club.badernet.nestwerk.notification.DeliveryWorker;
import club.badernet.nestwerk.notification.MailDelivery;
import club.badernet.nestwerk.notification.NotificationPreferences;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.notification.PushSubscription;
import club.badernet.nestwerk.notification.PushSubscriptions;
import club.badernet.nestwerk.notification.VapidKeys;
import club.badernet.nestwerk.notification.WebPushSender;
import club.badernet.nestwerk.security.CurrentUser;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/** Eigene Benachrichtigungswege: E-Mail-Adresse, Kanäle je Anlass, Geräte für Push. */
@Controller
@RequestMapping("/settings/notifications")
public class NotificationSettingsController {

    /** Eine Zeile der Matrix: Anlass mit eigener Wahl und globalem Standard je Kanal. */
    public record Row(NotificationType type, NotificationPreferences.Choice email, NotificationPreferences.Choice push,
                      boolean defaultEmail, boolean defaultPush) {
    }

    /** Push-Abo, wie es der Browser liefert. */
    public record SubscriptionJson(String endpoint, Map<String, String> keys) {
    }

    private final CurrentUser currentUser;
    private final UserService userService;
    private final NotificationPreferences preferences;
    private final PushSubscriptions subscriptions;
    private final VapidKeys vapid;
    private final MailDelivery mail;
    private final WebPushSender push;

    public NotificationSettingsController(CurrentUser currentUser, UserService userService,
                                          NotificationPreferences preferences, PushSubscriptions subscriptions,
                                          VapidKeys vapid, MailDelivery mail, WebPushSender push) {
        this.currentUser = currentUser;
        this.userService = userService;
        this.preferences = preferences;
        this.subscriptions = subscriptions;
        this.vapid = vapid;
        this.mail = mail;
        this.push = push;
    }

    @GetMapping
    String page(Model model) {
        User user = currentUser.require();
        model.addAttribute("email", user.getEmail());
        model.addAttribute("rows", rows(NotificationPreferences.Scope.USER, user.getId()));
        model.addAttribute("choices", NotificationPreferences.Choice.values());
        model.addAttribute("devices", subscriptions.of(user));
        model.addAttribute("vapidKey", vapid.publicKey());
        model.addAttribute("mailConfigured", mail.configured());
        return "settings/notifications";
    }

    @PostMapping("/email")
    String email(@RequestParam(required = false) String email, RedirectAttributes redirect) {
        try {
            userService.changeEmail(currentUser.require().getId(), email);
            redirect.addFlashAttribute("success", "E-Mail-Adresse wurde gespeichert.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings/notifications";
    }

    @PostMapping("/preferences")
    String preferences(@RequestParam Map<String, String> form, RedirectAttributes redirect) {
        User user = currentUser.require();
        applyMatrix(form, NotificationPreferences.Scope.USER, user.getId(), preferences);
        redirect.addFlashAttribute("success", "Einstellungen wurden gespeichert.");
        return "redirect:/settings/notifications";
    }

    @PostMapping(value = "/push-subscriptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    ResponseEntity<String> subscribe(@RequestBody SubscriptionJson body,
                                     @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        try {
            Map<String, String> keys = body.keys() == null ? Map.of() : body.keys();
            subscriptions.subscribe(currentUser.require(), body.endpoint(), keys.get("p256dh"), keys.get("auth"), userAgent);
            return ResponseEntity.status(HttpStatus.CREATED).body("Gerät wurde eingerichtet.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                    .body(e.getMessage());
        }
    }

    @PostMapping("/push-subscriptions/{id}/delete")
    String unsubscribe(@PathVariable Long id, RedirectAttributes redirect) {
        subscriptions.remove(id, currentUser.require());
        redirect.addFlashAttribute("success", "Gerät wurde entfernt.");
        return "redirect:/settings/notifications";
    }

    @PostMapping("/push-test")
    String pushTest(RedirectAttributes redirect) {
        User user = currentUser.require();
        List<PushSubscription> devices = subscriptions.of(user);
        if (devices.isEmpty()) {
            redirect.addFlashAttribute("error", "Auf keinem Gerät ist Push eingerichtet.");
            return "redirect:/settings/notifications";
        }
        int ok = 0;
        for (PushSubscription device : devices) {
            if (push.send(device, "{\"title\":\"Nestwerk\",\"body\":\"Push funktioniert.\",\"url\":"
                    + DeliveryWorkerJson.quote(mail.absolute("/settings/notifications")) + "}").result()
                    == WebPushSender.Result.SENT) {
                ok++;
            }
        }
        redirect.addFlashAttribute(ok > 0 ? "success" : "error", ok > 0
                ? "Testnachricht an " + ok + (ok == 1 ? " Gerät" : " Geräte") + " geschickt."
                : "Die Testnachricht konnte nicht zugestellt werden.");
        return "redirect:/settings/notifications";
    }

    /** Matrix-Zeilen für einen Bereich. */
    List<Row> rows(NotificationPreferences.Scope scope, Long scopeId) {
        return java.util.Arrays.stream(NotificationType.values()).map(t -> new Row(t,
                preferences.choice(scope, scopeId, t.name(), Channel.EMAIL),
                preferences.choice(scope, scopeId, t.name(), Channel.PUSH),
                preferences.globalEnabled(t, Channel.EMAIL), preferences.globalEnabled(t, Channel.PUSH))).toList();
    }

    /** Übernimmt Formularfelder der Form {@code TYP:KANAL=DEFAULT|ON|OFF}; Unbekanntes wird ignoriert. */
    static void applyMatrix(Map<String, String> form, NotificationPreferences.Scope scope, Long scopeId,
                            NotificationPreferences preferences) {
        for (NotificationType type : NotificationType.values()) {
            for (Channel channel : Channel.values()) {
                String value = form.get(type.name() + ":" + channel.name());
                if (value == null) {
                    continue;
                }
                NotificationPreferences.Choice choice;
                try {
                    choice = NotificationPreferences.Choice.valueOf(value);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (scope == NotificationPreferences.Scope.GLOBAL && choice == NotificationPreferences.Choice.DEFAULT) {
                    continue;
                }
                preferences.set(scope, scopeId, type.name(), channel, choice);
            }
        }
    }

    /** Kleiner JSON-Helfer für die Testnachricht. */
    static final class DeliveryWorkerJson {
        private DeliveryWorkerJson() {
        }

        static String quote(String text) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}
