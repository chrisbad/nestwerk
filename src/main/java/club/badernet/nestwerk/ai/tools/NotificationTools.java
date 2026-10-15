package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.notification.Notification;
import club.badernet.nestwerk.notification.NotificationService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** Eigene Benachrichtigungen lesen. */
@Component
public class NotificationTools {

    public record NotificationItem(Long id, String type, String title, String body, String createdAt, boolean unread,
                                   String sourceType, Long sourceId) {
    }

    private static final int LIMIT = 50;

    private final Tools tools;
    private final NotificationService notifications;

    public NotificationTools(Tools tools, NotificationService notifications) {
        this.tools = tools;
        this.notifications = notifications;
    }

    @McpTool(name = "list_notifications", description = "Die neuesten 50 eigenen Benachrichtigungen. sourceType/"
            + "sourceId verweisen auf das ToDo, den Termin oder die Hausarbeit.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<NotificationItem> listNotifications(McpTransportContext context,
                                                    @McpToolParam(description = "Nur ungelesene", required = false)
                                                    Boolean onlyUnread) {
        return tools.read(context, user -> notifications.list(user.getId(), Boolean.TRUE.equals(onlyUnread), LIMIT)
                .stream().map(NotificationTools::item).toList());
    }

    @McpTool(name = "mark_notifications_read", description = "Markiert eine Benachrichtigung (notificationId) oder "
            + "ohne Angabe alle eigenen als gelesen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    openWorldHint = false, idempotentHint = true))
    public String markNotificationsRead(McpTransportContext context,
                                        @McpToolParam(description = "ID der Benachrichtigung", required = false)
                                        Long notificationId) {
        return tools.write(context, user -> {
            if (notificationId == null) {
                notifications.markAllRead(user.getId());
                return "Alle Benachrichtigungen sind gelesen.";
            }
            notifications.open(notificationId, user);
            return "Benachrichtigung " + notificationId + " ist gelesen.";
        });
    }

    private static NotificationItem item(Notification n) {
        return new NotificationItem(n.getId(), n.getType().name(), n.getTitle(), n.getBody(),
                String.valueOf(n.getCreatedAt()), n.isUnread(), n.getSourceType(), n.getSourceId());
    }
}
