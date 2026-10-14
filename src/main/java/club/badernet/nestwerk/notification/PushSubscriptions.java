package club.badernet.nestwerk.notification;

import club.badernet.nestwerk.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Push-Abos der Geräte eines Benutzers. */
@Service
public class PushSubscriptions {

    private final PushSubscriptionRepository subscriptions;
    private final boolean allowInsecure;

    public PushSubscriptions(PushSubscriptionRepository subscriptions,
                             @Value("${nestwerk.push.allow-insecure-endpoints:false}") boolean allowInsecure) {
        this.subscriptions = subscriptions;
        this.allowInsecure = allowInsecure;
    }

    @Transactional(readOnly = true)
    public List<PushSubscription> of(User user) {
        return subscriptions.findAllByUserIdOrderByCreatedAtAsc(user.getId());
    }

    /** Legt das Abo an oder übernimmt es (gleicher Endpunkt, z. B. nach Benutzerwechsel im Browser). */
    @Transactional
    public PushSubscription subscribe(User user, String endpoint, String p256dh, String auth, String userAgent) {
        requireSafeEndpoint(endpoint);
        requireKey(p256dh, 65, "p256dh");
        requireKey(auth, 16, "auth");
        return subscriptions.findByEndpoint(endpoint)
                .map(existing -> {
                    existing.update(user.getId(), p256dh, auth, userAgent);
                    return existing;
                })
                .orElseGet(() -> subscriptions.save(new PushSubscription(user.getId(), endpoint, p256dh, auth, userAgent)));
    }

    /** Entfernt ein eigenes Abo; fremde gelten als nicht vorhanden. */
    @Transactional
    public void remove(Long id, User user) {
        PushSubscription sub = subscriptions.findById(id).filter(s -> s.getUserId().equals(user.getId()))
                .orElseThrow(NotificationNotFoundException::new);
        subscriptions.delete(sub);
    }

    /**
     * Push-Dienste sind öffentliche HTTPS-Adressen. Lokale Ziele und IP-Adressen werden abgelehnt, damit der
     * Server nicht als Werkzeug gegen interne Dienste missbraucht werden kann.
     */
    void requireSafeEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint == null ? "" : endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ungültige Push-Adresse.");
        }
        if (endpoint.length() > 1000 || uri.getHost() == null) {
            throw new IllegalArgumentException("Ungültige Push-Adresse.");
        }
        if (allowInsecure) {
            return;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean ip = host.matches("[0-9.]+") || host.contains(":") || host.startsWith("[");
        if (!"https".equals(uri.getScheme()) || ip || host.equals("localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || !host.contains(".")) {
            throw new IllegalArgumentException("Push-Adressen müssen öffentliche HTTPS-Adressen sein.");
        }
    }

    private static void requireKey(String value, int length, String name) {
        try {
            if (value != null && Base64.getUrlDecoder().decode(value).length == length) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // fällt auf die Meldung unten
        }
        throw new IllegalArgumentException("Ungültiger Push-Schlüssel (" + name + ").");
    }
}
