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

    /** Push-Dienste der Browser (Chrome/Edge-Android, Firefox, Edge/Windows, Safari). */
    static final List<String> PUSH_SERVICES = List.of("fcm.googleapis.com", "push.services.mozilla.com",
            "notify.windows.com", "push.apple.com");

    public boolean isSafe(String endpoint) {
        try {
            requireSafeEndpoint(endpoint);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Nur bekannte Push-Dienste über HTTPS auf dem Standardport. So kann der Server nicht als Werkzeug gegen
     * interne Dienste missbraucht werden – auch nicht über DNS-Namen, die auf interne Adressen zeigen.
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
        boolean known = PUSH_SERVICES.stream().anyMatch(s -> host.equals(s) || host.endsWith("." + s));
        if (!"https".equals(uri.getScheme()) || !known || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Diese Push-Adresse gehört zu keinem bekannten Push-Dienst.");
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
