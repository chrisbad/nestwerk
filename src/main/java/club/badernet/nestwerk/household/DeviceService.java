package club.badernet.nestwerk.household;

import club.badernet.nestwerk.security.AdminPermission;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Kopplung von Wand-Tablets: ein Berechtigter erzeugt einen kurzlebigen Code, das Tablet tauscht ihn gegen ein
 * dauerhaftes Geräte-Token (Cookie). Gespeichert werden nur Hashes; Widerruf wirkt sofort.
 */
@Service
public class DeviceService {

    /** Ergebnis einer Kopplungsanfrage: der Klartext-Code wird nur dieses eine Mal angezeigt. */
    public record Pairing(HouseholdDevice device, String code) {
    }

    static final Duration PAIRING_VALIDITY = Duration.ofMinutes(15);
    static final int MAX_FAILURES_PER_MINUTE = 5;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    private final HouseholdDeviceRepository devices;
    private final PermissionCatalog permissions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Deque<Instant> failures = new ArrayDeque<>();

    public DeviceService(HouseholdDeviceRepository devices, PermissionCatalog permissions, Clock clock) {
        this.devices = devices;
        this.permissions = permissions;
        this.clock = clock;
    }

    public boolean canManage(User user) {
        return permissions.effective(user).contains(AdminPermission.HOUSEHOLD_DEVICE_MANAGE.name());
    }

    @Transactional(readOnly = true)
    public List<HouseholdDevice> all(User actor) {
        requireManage(actor);
        return devices.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public Pairing create(String name, User actor) {
        requireManage(actor);
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 100) {
            throw new IllegalArgumentException("Der Name muss 1–100 Zeichen lang sein.");
        }
        HouseholdDevice device = devices.save(new HouseholdDevice(clean, actor.getId()));
        return new Pairing(device, newCode(device));
    }

    /** Neuer Kopplungscode für ein bestehendes Gerät; ein altes Token bleibt bis zur Kopplung gültig. */
    @Transactional
    public Pairing repair(Long id, User actor) {
        requireManage(actor);
        HouseholdDevice device = devices.findById(id).orElseThrow(ChoreNotFoundException::new);
        return new Pairing(device, newCode(device));
    }

    @Transactional
    public void revoke(Long id, User actor) {
        requireManage(actor);
        devices.findById(id).orElseThrow(ChoreNotFoundException::new).revoke(clock.instant());
    }

    /**
     * Tauscht einen gültigen Code gegen ein neues Geräte-Token.
     *
     * @return das Token im Klartext (nur für das Cookie), leer bei falschem oder abgelaufenem Code
     */
    @Transactional
    public Optional<String> pair(String code) {
        Instant now = clock.instant();
        synchronized (failures) {
            while (!failures.isEmpty() && failures.peekFirst().isBefore(now.minus(Duration.ofMinutes(1)))) {
                failures.pollFirst();
            }
            if (failures.size() >= MAX_FAILURES_PER_MINUTE) {
                throw new IllegalStateException("Zu viele Versuche. Bitte in einer Minute erneut probieren.");
            }
        }
        String normalized = code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        Optional<HouseholdDevice> match = normalized.length() == CODE_LENGTH
                ? devices.findByPairingHash(sha256(normalized))
                .filter(d -> d.getPairingExpiresAt() != null && d.getPairingExpiresAt().isAfter(now))
                : Optional.empty();
        if (match.isEmpty()) {
            synchronized (failures) {
                failures.addLast(now);
            }
            return Optional.empty();
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        match.get().paired(sha256(token), now);
        return Optional.of(token);
    }

    /** Das aktive Gerät zum Token; aktualisiert „zuletzt gesehen“ höchstens einmal pro Minute. */
    @Transactional
    public Optional<HouseholdDevice> authenticate(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return devices.findByTokenHash(sha256(token)).filter(HouseholdDevice::isActive).map(d -> {
            if (d.getLastSeenAt() == null || d.getLastSeenAt().isBefore(now.minusSeconds(60))) {
                d.seen(now);
            }
            return d;
        });
    }

    private String newCode(HouseholdDevice device) {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        String code = sb.toString();
        device.startPairing(sha256(code), clock.instant().plus(PAIRING_VALIDITY));
        return code;
    }

    private void requireManage(User actor) {
        if (!canManage(actor)) {
            throw new AccessDeniedException("Keine Berechtigung, Wand-Tablets zu verwalten");
        }
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
