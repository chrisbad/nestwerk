package club.badernet.nestwerk.household;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.security.AdminPermission;
import club.badernet.nestwerk.security.PermissionCatalog;
import club.badernet.nestwerk.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** PINs der Mitglieder fürs Wand-Tablet, mit Sperre nach wiederholten Fehlversuchen je Gerät und Mitglied. */
@Service
public class MemberPins {

    /** Ergebnis einer PIN-Prüfung. */
    public enum Check { OK, WRONG, LOCKED, NO_PIN }

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(5);

    private record Attempts(int failures, Instant lockedUntil) {
    }

    private final FamilyMemberRepository members;
    private final PasswordEncoder encoder;
    private final PermissionCatalog permissions;
    private final HouseholdAccess access;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public MemberPins(FamilyMemberRepository members, PasswordEncoder encoder, PermissionCatalog permissions,
                      HouseholdAccess access, Clock clock) {
        this.members = members;
        this.encoder = encoder;
        this.permissions = permissions;
        this.access = access;
        this.clock = clock;
    }

    /** Das eigene Mitglied oder – mit Recht zur Mitgliederverwaltung – jedes; leere PIN entfernt sie. */
    @Transactional
    public void setPin(Long memberId, String pin, User actor) {
        boolean own = access.memberId(actor).map(memberId::equals).orElse(false);
        if (!own && !permissions.effective(actor).contains(AdminPermission.MEMBER_MANAGE.name())) {
            throw new AccessDeniedException("Keine Berechtigung, diese PIN zu ändern");
        }
        FamilyMember member = members.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Familienmitglied."));
        if (pin == null || pin.isBlank()) {
            member.setPinHash(null);
            return;
        }
        if (!pin.matches("\\d{4,8}")) {
            throw new IllegalArgumentException("Die PIN muss aus 4 bis 8 Ziffern bestehen.");
        }
        member.setPinHash(encoder.encode(pin));
    }

    @Transactional(readOnly = true)
    public Check check(Long deviceId, Long memberId, String pin) {
        String key = deviceId + ":" + memberId;
        Instant now = clock.instant();
        Attempts current = attempts.get(key);
        if (current != null && current.lockedUntil() != null && current.lockedUntil().isAfter(now)) {
            return Check.LOCKED;
        }
        Optional<FamilyMember> member = members.findById(memberId);
        if (member.isEmpty() || !member.get().hasPin()) {
            return Check.NO_PIN;
        }
        if (pin != null && pin.length() <= 8 && encoder.matches(pin, member.get().getPinHash())) {
            attempts.remove(key);
            return Check.OK;
        }
        attempts.compute(key, (k, a) -> {
            int failures = (a == null || (a.lockedUntil() != null && !a.lockedUntil().isAfter(now))) ? 1 : a.failures() + 1;
            return new Attempts(failures, failures >= MAX_FAILURES ? now.plus(LOCK) : null);
        });
        return attempts.get(key).lockedUntil() != null ? Check.LOCKED : Check.WRONG;
    }
}
