package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.settings.AppSettings;
import club.badernet.nestwerk.notification.Channel;
import club.badernet.nestwerk.notification.DeliveryWorker;
import club.badernet.nestwerk.notification.MailDelivery;
import club.badernet.nestwerk.notification.NotificationPreferences;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.notification.PushSubscription;
import club.badernet.nestwerk.notification.PushSubscriptions;
import club.badernet.nestwerk.notification.WebPushSender;
import club.badernet.nestwerk.security.AdminPermission;
import club.badernet.nestwerk.user.UserService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** Eigene Benachrichtigungswege und (mit Recht) die globalen Benachrichtigungs-Einstellungen. */
@Component
public class NotificationSettingsTools {

    public record Preference(String type, String label, String email, String push, boolean defaultEmail,
                             boolean defaultPush) {
    }

    public record Device(Long id, String label, String since) {
    }

    public record MySettings(String email, boolean mailConfigured, List<Preference> preferences,
                             List<Device> pushDevices) {
    }

    public record AdminSettings(int reminderHour, int defaultLeadDays, boolean mailConfigured,
                                List<Preference> globalDefaults, List<String> recentFailures) {
    }

    private final Tools tools;
    private final AdminGuard guard;
    private final UserService userService;
    private final NotificationPreferences preferences;
    private final PushSubscriptions subscriptions;
    private final MailDelivery mail;
    private final WebPushSender push;
    private final AppSettings appSettings;
    private final DeliveryWorker worker;

    public NotificationSettingsTools(Tools tools, AdminGuard guard, UserService userService,
                                     NotificationPreferences preferences, PushSubscriptions subscriptions,
                                     MailDelivery mail, WebPushSender push, AppSettings appSettings,
                                     DeliveryWorker worker) {
        this.tools = tools;
        this.guard = guard;
        this.userService = userService;
        this.preferences = preferences;
        this.subscriptions = subscriptions;
        this.mail = mail;
        this.push = push;
        this.appSettings = appSettings;
        this.worker = worker;
    }

    @McpTool(name = "notification_settings", description = "Zeigt die eigenen Benachrichtigungswege: E-Mail-Adresse, "
            + "je Anlass (type) die Wahl für E-Mail und Push (DEFAULT, ON, OFF) samt globalem Standard, und die "
            + "Geräte mit Push. Push-Geräte lassen sich nur im Browser einrichten.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public MySettings notificationSettings(McpTransportContext context) {
        return tools.read(context, user -> new MySettings(user.getEmail(), mail.configured(),
                rows(NotificationPreferences.Scope.USER, user.getId()), subscriptions.of(user).stream()
                .map(d -> new Device(d.getId(), d.deviceLabel(), String.valueOf(d.getCreatedAt()))).toList()));
    }

    @McpTool(name = "set_notification_email", description = "Setzt die eigene E-Mail-Adresse für Benachrichtigungen; "
            + "leer entfernt sie.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public String setNotificationEmail(McpTransportContext context,
                                       @McpToolParam(description = "E-Mail-Adresse", required = false) String email) {
        return tools.write(context, user -> {
            userService.changeEmail(user.getId(), email);
            return "E-Mail-Adresse gespeichert.";
        });
    }

    @McpTool(name = "set_notification_preference", description = "Stellt für einen Anlass ein, ob er per E-Mail "
            + "und/oder Push benachrichtigt: DEFAULT (globaler Standard), ON oder OFF. type aus "
            + "notification_settings, z. B. TODO_COMMENT. Nicht angegebene Kanäle bleiben.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public List<Preference> setNotificationPreference(McpTransportContext context,
                                                      @McpToolParam(description = "Anlass, z. B. TODO_COMMENT")
                                                      String type,
                                                      @McpToolParam(description = "E-Mail: DEFAULT, ON oder OFF",
                                                              required = false) String email,
                                                      @McpToolParam(description = "Push: DEFAULT, ON oder OFF",
                                                              required = false) String pushChoice) {
        return tools.write(context, user -> {
            NotificationType t = Tools.required(Tools.enumValue(NotificationType.class, type, "type"), "type");
            apply(NotificationPreferences.Scope.USER, user.getId(), t, email, pushChoice);
            return rows(NotificationPreferences.Scope.USER, user.getId());
        });
    }

    @McpTool(name = "send_push_test", description = "Schickt eine Testnachricht an alle eigenen Push-Geräte.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = true))
    public String sendPushTest(McpTransportContext context) {
        return tools.write(context, user -> {
            List<PushSubscription> devices = subscriptions.of(user);
            if (devices.isEmpty()) {
                throw new Tools.ToolException("Auf keinem Gerät ist Push eingerichtet.");
            }
            long ok = devices.stream().filter(d -> push.send(d,
                    "{\"title\":\"Nestwerk\",\"body\":\"Push funktioniert.\",\"url\":\""
                            + mail.absolute("/settings/notifications") + "\"}").result()
                    == WebPushSender.Result.SENT).count();
            return ok > 0 ? "Testnachricht an " + ok + " Gerät(e) geschickt."
                    : "Die Testnachricht konnte nicht zugestellt werden.";
        });
    }

    @McpTool(name = "admin_notification_settings", description = "Verwaltung: Erinnerungszeit, Vorlauf, globale "
            + "Standard-Kanäle je Anlass und die letzten Zustellfehler. Braucht NOTIFICATION_SETTINGS_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public AdminSettings adminNotificationSettings(McpTransportContext context) {
        return tools.read(context, user -> {
            guard.require(user, AdminPermission.NOTIFICATION_SETTINGS_MANAGE);
            return adminSettings();
        });
    }

    @McpTool(name = "set_reminder_schedule", description = "Verwaltung: Uhrzeit (0–23) der täglichen Erinnerungen und "
            + "Vorlauf in Tagen (0–60). Braucht NOTIFICATION_SETTINGS_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public AdminSettings setReminderSchedule(McpTransportContext context,
                                             @McpToolParam(description = "Stunde 0–23") Integer hour,
                                             @McpToolParam(description = "Vorlauf in Tagen") Integer leadDays) {
        return tools.write(context, user -> {
            guard.require(user, AdminPermission.NOTIFICATION_SETTINGS_MANAGE);
            appSettings.updateReminders(Tools.required(hour, "hour"), Tools.required(leadDays, "leadDays"));
            return adminSettings();
        });
    }

    @McpTool(name = "set_global_notification_default", description = "Verwaltung: Globaler Standard je Anlass: ON "
            + "oder OFF für E-Mail und/oder Push. Braucht NOTIFICATION_SETTINGS_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public AdminSettings setGlobalNotificationDefault(McpTransportContext context,
                                                      @McpToolParam(description = "Anlass, z. B. TODO_COMMENT")
                                                      String type,
                                                      @McpToolParam(description = "E-Mail: ON oder OFF",
                                                              required = false) String email,
                                                      @McpToolParam(description = "Push: ON oder OFF",
                                                              required = false) String pushChoice) {
        return tools.write(context, user -> {
            guard.require(user, AdminPermission.NOTIFICATION_SETTINGS_MANAGE);
            NotificationType t = Tools.required(Tools.enumValue(NotificationType.class, type, "type"), "type");
            apply(NotificationPreferences.Scope.GLOBAL, 0L, t, email, pushChoice);
            return adminSettings();
        });
    }

    @McpTool(name = "send_test_mail", description = "Verwaltung: Schickt eine Testmail an die eigene Adresse. "
            + "Braucht NOTIFICATION_SETTINGS_MANAGE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = true))
    public String sendTestMail(McpTransportContext context) {
        return tools.write(context, user -> {
            guard.require(user, AdminPermission.NOTIFICATION_SETTINGS_MANAGE);
            String to = user.getEmail();
            if (to == null || to.isBlank()) {
                throw new Tools.ToolException("Hinterlege zuerst mit set_notification_email deine E-Mail-Adresse.");
            }
            mail.send(to, "Nestwerk: Testnachricht", "Der Mailversand funktioniert.", "/admin/notifications");
            return "Testmail an " + to + " verschickt.";
        });
    }

    private void apply(NotificationPreferences.Scope scope, Long scopeId, NotificationType type, String email,
                       String pushChoice) {
        NotificationPreferences.Choice mailChoice =
                Tools.enumValue(NotificationPreferences.Choice.class, email, "email");
        NotificationPreferences.Choice pushValue =
                Tools.enumValue(NotificationPreferences.Choice.class, pushChoice, "push");
        if (mailChoice != null) {
            preferences.set(scope, scopeId, type.name(), Channel.EMAIL, mailChoice);
        }
        if (pushValue != null) {
            preferences.set(scope, scopeId, type.name(), Channel.PUSH, pushValue);
        }
    }

    private List<Preference> rows(NotificationPreferences.Scope scope, Long scopeId) {
        return Arrays.stream(NotificationType.values()).map(t -> new Preference(t.name(), t.label(),
                preferences.choice(scope, scopeId, t.name(), Channel.EMAIL).name(),
                preferences.choice(scope, scopeId, t.name(), Channel.PUSH).name(),
                preferences.globalEnabled(t, Channel.EMAIL), preferences.globalEnabled(t, Channel.PUSH))).toList();
    }

    private AdminSettings adminSettings() {
        List<Preference> defaults = Arrays.stream(NotificationType.values()).map(t -> new Preference(t.name(),
                t.label(), null, null, preferences.globalEnabled(t, Channel.EMAIL),
                preferences.globalEnabled(t, Channel.PUSH))).toList();
        return new AdminSettings(appSettings.reminderHour(), appSettings.defaultLeadDays(), mail.configured(),
                defaults, worker.recentFailures(10).stream().map(f -> f.channel() + " an " + f.user() + ": "
                + f.title() + (f.error() == null ? "" : " – " + f.error())).toList());
    }
}
