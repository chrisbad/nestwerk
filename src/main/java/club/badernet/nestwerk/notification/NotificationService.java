package club.badernet.nestwerk.notification;

import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Modulunabhängiger Kern: legt Benachrichtigungen idempotent an (ein Eintrag je Empfänger, Anlass,
 * Quelle und Stichtag) und liefert sie für Glocke und Übersicht.
 */
@Service
public class NotificationService {

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationRepository notifications;
    private final UserRepository users;
    private final List<NotificationChannel> channels;

    public NotificationService(NamedParameterJdbcTemplate jdbc, NotificationRepository notifications,
                               UserRepository users, List<NotificationChannel> channels) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.users = users;
        this.channels = channels;
    }

    /**
     * Legt die Benachrichtigung an, falls es sie für diesen Stichtag noch nicht gibt.
     *
     * @return {@code true}, wenn sie neu ist
     */
    @Transactional
    public boolean notify(Long userId, NotificationType type, String sourceType, Long sourceId, String dueKey,
                          String title, String body, String link) {
        String cleanTitle = title.length() > 200 ? title.substring(0, 197) + "…" : title;
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO notifications (user_id, type, source_type, source_id, due_key, title, body, link)
                VALUES (:user, :type, :sourceType, :sourceId, :dueKey, :title, :body, :link)
                ON CONFLICT (user_id, type, source_type, source_id, due_key) DO NOTHING
                RETURNING id""", new MapSqlParameterSource()
                .addValue("user", userId).addValue("type", type.name()).addValue("sourceType", sourceType)
                .addValue("sourceId", sourceId).addValue("dueKey", dueKey).addValue("title", cleanTitle)
                .addValue("body", body).addValue("link", link), Long.class);
        if (ids.isEmpty()) {
            return false;
        }
        for (NotificationChannel channel : channels) {
            channel.enqueue(ids.getFirst(), userId, type, sourceType, sourceId);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    /** Für die Glocke im Layout. */
    @Transactional(readOnly = true)
    public long unreadCount(String username) {
        return users.findByUsername(username).map(u -> unreadCount(u.getId())).orElse(0L);
    }

    @Transactional(readOnly = true)
    public List<Notification> latest(Long userId, int limit) {
        return notifications.findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<Notification> list(Long userId, boolean onlyUnread, int limit) {
        return onlyUnread
                ? notifications.findAllByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, limit))
                : notifications.findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, limit));
    }

    /** Markiert die eigene Benachrichtigung als gelesen; fremde gelten als nicht vorhanden. */
    @Transactional
    public Notification open(Long id, User user) {
        Notification n = notifications.findById(id).filter(x -> x.getUserId().equals(user.getId()))
                .orElseThrow(NotificationNotFoundException::new);
        n.markRead();
        return n;
    }

    @Transactional
    public void markAllRead(Long userId) {
        notifications.markAllRead(userId);
    }
}
