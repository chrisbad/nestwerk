package club.badernet.nestwerk.notification;

import club.badernet.nestwerk.user.UserRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * E-Mail und Push als Kanäle: legen einen Versandauftrag an, wenn der Kanal eingerichtet ist, die aufgelöste
 * Einstellung „an“ ist und der Empfänger erreichbar ist (Adresse bzw. mindestens ein Gerät).
 */
@Component
public class DeliveryChannels implements NotificationChannel {

    private final NotificationPreferences preferences;
    private final MailDelivery mail;
    private final UserRepository users;
    private final PushSubscriptionRepository subscriptions;
    private final NamedParameterJdbcTemplate jdbc;

    public DeliveryChannels(NotificationPreferences preferences, MailDelivery mail, UserRepository users,
                            PushSubscriptionRepository subscriptions, NamedParameterJdbcTemplate jdbc) {
        this.preferences = preferences;
        this.mail = mail;
        this.users = users;
        this.subscriptions = subscriptions;
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(Long notificationId, Long userId, NotificationType type, String sourceType, Long sourceId) {
        Long todoId = "TODO".equals(sourceType) ? sourceId : null;
        if (mail.configured() && preferences.enabled(Channel.EMAIL, type, userId, todoId)
                && users.findById(userId).map(u -> u.getEmail() != null && !u.getEmail().isBlank()).orElse(false)) {
            insert(notificationId, Channel.EMAIL);
        }
        if (preferences.enabled(Channel.PUSH, type, userId, todoId) && subscriptions.existsByUserId(userId)) {
            insert(notificationId, Channel.PUSH);
        }
    }

    private void insert(Long notificationId, Channel channel) {
        jdbc.update("INSERT INTO notification_deliveries (notification_id, channel) VALUES (:id, :channel) "
                + "ON CONFLICT (notification_id, channel) DO NOTHING", Map.of("id", notificationId, "channel", channel.name()));
    }
}
