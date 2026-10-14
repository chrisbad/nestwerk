package club.badernet.nestwerk.notification;

/**
 * Zusätzlicher Zustellweg (E-Mail, Push). In-App ist die Benachrichtigung selbst und braucht keinen Kanal.
 * Kanäle entscheiden selbst anhand der Einstellungen, ob sie einen Versandauftrag anlegen.
 */
public interface NotificationChannel {

    void enqueue(Long notificationId, Long userId, NotificationType type, String sourceType, Long sourceId);
}
