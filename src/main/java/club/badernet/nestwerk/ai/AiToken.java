package club.badernet.nestwerk.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** Persönliches Zugangstoken eines Benutzers für den MCP-Server. Gespeichert wird nur der SHA-256-Hash. */
@Entity
@Table(name = "ai_tokens")
public class AiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AiToken() {
    }

    AiToken(Long userId, String name, String tokenHash, LocalDate expiresOn) {
        this.userId = userId;
        this.name = name;
        this.tokenHash = tokenHash;
        this.expiresOn = expiresOn;
    }

    void used(Instant at) {
        this.lastUsedAt = at;
    }

    void revoke(Instant at) {
        if (revokedAt == null) {
            this.revokedAt = at;
        }
    }

    /** Nicht widerrufen und am Tag {@code today} noch gültig (das Ablaufdatum zählt mit). */
    public boolean isActive(LocalDate today) {
        return revokedAt == null && (expiresOn == null || !today.isAfter(expiresOn));
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getTokenHash() { return tokenHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public Instant getRevokedAt() { return revokedAt; }
}
