package club.badernet.nestwerk.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Push-Abo eines Geräts (Browser). */
@Entity
@Table(name = "push_subscriptions")
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 1000, unique = true)
    private String endpoint;

    @Column(nullable = false, length = 200)
    private String p256dh;

    @Column(nullable = false, length = 100)
    private String auth;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PushSubscription() {
    }

    PushSubscription(Long userId, String endpoint, String p256dh, String auth, String userAgent) {
        this.userId = userId;
        this.endpoint = endpoint;
        update(userId, p256dh, auth, userAgent);
    }

    void update(Long userId, String p256dh, String auth, String userAgent) {
        this.userId = userId;
        this.p256dh = p256dh;
        this.auth = auth;
        this.userAgent = userAgent == null ? null : userAgent.length() > 300 ? userAgent.substring(0, 300) : userAgent;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getEndpoint() { return endpoint; }
    public String getP256dh() { return p256dh; }
    public String getAuth() { return auth; }
    public String getUserAgent() { return userAgent; }
    public Instant getCreatedAt() { return createdAt; }

    /** Kurzname des Geräts für die Einstellungsseite. */
    public String deviceLabel() {
        String ua = userAgent == null ? "" : userAgent;
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : "Browser";
        String os = ua.contains("Android") ? "Android" : ua.contains("iPhone") || ua.contains("iPad") ? "iOS"
                : ua.contains("Windows") ? "Windows" : ua.contains("Mac OS") ? "macOS"
                : ua.contains("Linux") ? "Linux" : "";
        return os.isEmpty() ? browser : browser + " auf " + os;
    }
}
