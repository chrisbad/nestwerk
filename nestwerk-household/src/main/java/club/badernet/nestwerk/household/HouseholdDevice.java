package club.badernet.nestwerk.household;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Ein gekoppeltes Wand-Tablet. Token und Kopplungscode liegen nur als SHA-256-Hash vor. */
@Entity
@Table(name = "household_devices")
public class HouseholdDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "token_hash", length = 64, unique = true)
    private String tokenHash;

    @Column(name = "pairing_hash", length = 64)
    private String pairingHash;

    @Column(name = "pairing_expires_at")
    private Instant pairingExpiresAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected HouseholdDevice() {
    }

    public HouseholdDevice(String name, Long createdBy) {
        this.name = name;
        this.createdBy = createdBy;
    }

    void startPairing(String hash, Instant expiresAt) {
        this.pairingHash = hash;
        this.pairingExpiresAt = expiresAt;
        this.revokedAt = null;
    }

    void paired(String tokenHash, Instant at) {
        this.tokenHash = tokenHash;
        this.pairingHash = null;
        this.pairingExpiresAt = null;
        this.lastSeenAt = at;
    }

    void seen(Instant at) {
        this.lastSeenAt = at;
    }

    void revoke(Instant at) {
        this.revokedAt = at;
        this.tokenHash = null;
        this.pairingHash = null;
        this.pairingExpiresAt = null;
    }

    public boolean isActive() {
        return revokedAt == null && tokenHash != null;
    }

    public boolean isPairing() {
        return pairingHash != null;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Instant getPairingExpiresAt() { return pairingExpiresAt; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
