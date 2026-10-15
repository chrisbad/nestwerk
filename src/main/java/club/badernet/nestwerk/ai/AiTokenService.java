package club.badernet.nestwerk.ai;

import club.badernet.nestwerk.security.AdminPermission;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Persönliche Zugangstoken für KI-Assistenten. Ein Token handelt immer als sein Benutzer und nur, solange dieser
 * aktiv ist und das Recht {@link AdminPermission#AI_ACCESS} hat. Der Klartext wird nur beim Anlegen herausgegeben.
 */
@Service
public class AiTokenService {

    /** Ergebnis beim Anlegen: der Klartext wird nur dieses eine Mal angezeigt. */
    public record Issued(AiToken token, String plaintext) {
    }

    static final String PREFIX = "nw_";
    private static final int MAX_TOKEN_LENGTH = 100;

    private final AiTokenRepository tokens;
    private final UserRepository users;
    private final PermissionCatalog permissions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AiTokenService(AiTokenRepository tokens, UserRepository users, PermissionCatalog permissions,
                          Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.permissions = permissions;
        this.clock = clock;
    }

    public boolean mayUse(User user) {
        return user.isEnabled() && permissions.effective(user).contains(AdminPermission.AI_ACCESS.name());
    }

    @Transactional(readOnly = true)
    public List<AiToken> mine(User user) {
        return tokens.findAllByUserIdOrderByIdDesc(user.getId());
    }

    @Transactional
    public Issued create(String name, LocalDate expiresOn, User actor) {
        if (!mayUse(actor)) {
            throw new AccessDeniedException("Keine Berechtigung für KI-Zugänge");
        }
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 100) {
            throw new IllegalArgumentException("Der Name muss 1–100 Zeichen lang sein.");
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String plaintext = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        AiToken token = tokens.save(new AiToken(actor.getId(), clean, sha256(plaintext), expiresOn));
        return new Issued(token, plaintext);
    }

    @Transactional
    public void revoke(Long id, User actor) {
        AiToken token = tokens.findById(id).filter(t -> t.getUserId().equals(actor.getId()))
                .orElseThrow(AiTokenNotFoundException::new);
        token.revoke(clock.instant());
    }

    /** Der Benutzer zum Token; aktualisiert „zuletzt benutzt“ höchstens einmal pro Minute. */
    @Transactional
    public Optional<User> authenticate(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(PREFIX) || plaintext.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        Optional<AiToken> token = tokens.findByTokenHash(sha256(plaintext)).filter(t -> t.isActive(today));
        Optional<User> user = token.flatMap(t -> users.findById(t.getUserId())).filter(this::mayUse);
        user.ifPresent(u -> {
            AiToken t = token.get();
            if (t.getLastUsedAt() == null || t.getLastUsedAt().isBefore(now.minusSeconds(60))) {
                t.used(now);
            }
        });
        return user;
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
