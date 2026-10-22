package club.badernet.nestwerk.household;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.security.acl.AclService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Minutenkonto und Belohnungen. Kontostand = wirksame Gutschriften minus beantragte und bestätigte Einlösungen;
 * die Auswertung bleibt davon unberührt.
 */
@Service
public class RewardService {

    public static final String TYPE = "REWARD";
    static final String SOURCE = "REDEMPTION";

    private final RewardRepository rewards;
    private final RedemptionRepository redemptions;
    private final FamilyMemberRepository members;
    private final UserRepository users;
    private final AclService acl;
    private final HouseholdAccess access;
    private final NotificationService notifications;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    public RewardService(RewardRepository rewards, RedemptionRepository redemptions, FamilyMemberRepository members,
                         UserRepository users, AclService acl, HouseholdAccess access,
                         NotificationService notifications, NamedParameterJdbcTemplate jdbc,
                         EntityManager entityManager, Clock clock) {
        this.rewards = rewards;
        this.redemptions = redemptions;
        this.members = members;
        this.users = users;
        this.acl = acl;
        this.access = access;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public boolean can(User user, String action) {
        return acl.permits(user, TYPE, action, Set.of("ANY"));
    }

    @Transactional(readOnly = true)
    public boolean mayOpen(String username) {
        return users.findByUsername(username).map(u -> can(u, "VIEW")).orElse(false);
    }

    /* --- Kontostand ------------------------------------------------------------ */

    @Transactional(readOnly = true)
    public int balance(Long memberId) {
        return balances().getOrDefault(memberId, 0);
    }

    @Transactional(readOnly = true)
    public Map<Long, Integer> balances() {
        entityManager.flush();
        Map<Long, Integer> result = new HashMap<>();
        jdbc.query("SELECT member_id, sum(coalesce(corrected_minutes, minutes)) AS m FROM chore_credits GROUP BY member_id",
                rs -> { result.merge(rs.getLong("member_id"), rs.getInt("m"), Integer::sum); });
        jdbc.query("""
                SELECT member_id, sum(cost_minutes) AS m FROM reward_redemptions
                WHERE status IN ('REQUESTED', 'APPROVED') GROUP BY member_id""",
                rs -> { result.merge(rs.getLong("member_id"), -rs.getInt("m"), Integer::sum); });
        return result;
    }

    /* --- Belohnungen verwalten ------------------------------------------------- */

    @Transactional(readOnly = true)
    public List<Reward> rewards() {
        return rewards.findAllByOrderByActiveDescCostMinutesAscTitleAsc();
    }

    @Transactional(readOnly = true)
    public Reward get(Long id) {
        return rewards.findById(id).orElseThrow(ChoreNotFoundException::new);
    }

    @Transactional
    public Reward create(String title, String description, Integer cost, User actor) {
        requireManage(actor);
        Reward reward = new Reward(cleanTitle(title), cleanDescription(description), cleanCost(cost));
        return rewards.save(reward);
    }

    @Transactional
    public void update(Long id, String title, String description, Integer cost, boolean active, User actor) {
        requireManage(actor);
        get(id).change(cleanTitle(title), cleanDescription(description), cleanCost(cost), active);
    }

    /**
     * Löscht eine Belohnung. Offene Anträge müssen erst entschieden werden; wurde sie schon einmal beantragt, bleibt
     * sie für das Minutenkonto erhalten und lässt sich nur noch aus dem Angebot nehmen.
     */
    @Transactional
    public void delete(Long id, User actor) {
        requireManage(actor);
        Reward reward = get(id);
        if (redemptions.existsByRewardIdAndStatus(id, RedemptionStatus.REQUESTED)) {
            throw new ChoreConflictException("Für „" + reward.getTitle() + "“ gibt es noch offene Anträge. "
                    + "Entscheide erst darüber.");
        }
        if (redemptions.existsByRewardId(id)) {
            throw new ChoreConflictException("„" + reward.getTitle() + "“ wurde schon eingelöst und zählt im "
                    + "Minutenkonto. Nimm sie stattdessen aus dem Angebot (deaktivieren).");
        }
        rewards.delete(reward);
    }

    /* --- Einlösen -------------------------------------------------------------- */

    /** Beantragt eine Belohnung für {@code memberId} (Standard: das eigene Mitglied). */
    @Transactional
    public Redemption request(Long rewardId, Long memberId, User actor) {
        if (!can(actor, "VIEW")) {
            throw new AccessDeniedException("Keine Berechtigung für Belohnungen");
        }
        Optional<Long> own = access.memberId(actor);
        Long member = memberId != null ? memberId : own.orElseThrow(() ->
                new IllegalArgumentException("Bitte auswählen, für wen die Belohnung ist."));
        if (!own.map(member::equals).orElse(false) && !can(actor, "REDEEM")) {
            throw new AccessDeniedException("Du kannst Belohnungen nur für dich selbst einlösen");
        }
        FamilyMember fm = members.findById(member)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Familienmitglied ausgewählt."));
        Reward reward = get(rewardId);
        if (!reward.isActive()) {
            throw new IllegalArgumentException("Diese Belohnung gibt es nicht mehr.");
        }
        // Gleichzeitige Anträge desselben Mitglieds nacheinander prüfen
        jdbc.query("SELECT id FROM family_members WHERE id = :id FOR UPDATE", Map.of("id", member), rs -> { });
        int balance = balance(member);
        if (balance < reward.getCostMinutes()) {
            throw new IllegalArgumentException("Nicht genug Minuten: " + fm.getName() + " hat " + balance
                    + " Minuten, die Belohnung kostet " + reward.getCostMinutes() + ".");
        }
        Redemption redemption = redemptions.save(new Redemption(reward, member, actor.getId(), clock.instant()));
        redemptions.flush();
        for (User approver : users.findAll()) {
            if (approver.isEnabled() && !approver.getId().equals(actor.getId()) && can(approver, "APPROVE")) {
                notifications.notify(approver.getId(), NotificationType.REWARD_REQUESTED, SOURCE, redemption.getId(),
                        "r", reward.getTitle(), fm.getName() + " möchte „" + reward.getTitle() + "“ für "
                                + reward.getCostMinutes() + " Minuten einlösen.", "/household/rewards");
            }
        }
        return redemption;
    }

    @Transactional
    public void decide(Long redemptionId, boolean approve, User actor) {
        if (!can(actor, "APPROVE")) {
            throw new AccessDeniedException("Keine Berechtigung, Einlösungen zu bestätigen");
        }
        Redemption redemption = redemptions.findById(redemptionId).orElseThrow(ChoreNotFoundException::new);
        if (!redemption.isPending()) {
            throw new ChoreConflictException("Über diesen Antrag wurde schon entschieden.");
        }
        redemption.decide(approve, actor.getId(), clock.instant());
        String name = members.findById(redemption.getMemberId()).map(FamilyMember::getName).orElse("");
        String text = approve
                ? actor.getUsername() + " hat „" + redemption.getReward().getTitle() + "“ für " + name + " bestätigt."
                : actor.getUsername() + " hat „" + redemption.getReward().getTitle() + "“ für " + name
                + " abgelehnt. Die Minuten sind wieder auf dem Konto.";
        Set<Long> recipients = new java.util.LinkedHashSet<>();
        if (redemption.getRequestedBy() != null) {
            recipients.add(redemption.getRequestedBy());
        }
        members.findById(redemption.getMemberId()).map(FamilyMember::getUserId).ifPresent(recipients::add);
        recipients.remove(actor.getId());
        for (Long userId : recipients) {
            notifications.notify(userId, NotificationType.REWARD_DECIDED, SOURCE, redemption.getId(), "d",
                    redemption.getReward().getTitle(), text, "/household/rewards");
        }
    }

    @Transactional(readOnly = true)
    public List<Redemption> pending() {
        return redemptions.findAllByStatusOrderByRequestedAtAsc(RedemptionStatus.REQUESTED);
    }

    /** Letzte Einlösungen: alle für Berechtigte (Bestätigen oder für andere Einlösen), sonst die eigenen. */
    @Transactional(readOnly = true)
    public List<Redemption> recent(User viewer, int limit) {
        if (can(viewer, "APPROVE") || can(viewer, "REDEEM")) {
            return redemptions.findAllByOrderByRequestedAtDescIdDesc(PageRequest.of(0, limit));
        }
        return access.memberId(viewer)
                .map(m -> redemptions.findAllByMemberIdOrderByRequestedAtDescIdDesc(m, PageRequest.of(0, limit)))
                .orElse(List.of());
    }

    private void requireManage(User actor) {
        if (!can(actor, "MANAGE")) {
            throw new AccessDeniedException("Keine Berechtigung, Belohnungen zu verwalten");
        }
    }

    private static String cleanTitle(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || t.length() > 100) {
            throw new IllegalArgumentException("Der Titel muss 1–100 Zeichen lang sein.");
        }
        return t;
    }

    private static String cleanDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String d = description.trim();
        if (d.length() > 500) {
            throw new IllegalArgumentException("Die Beschreibung darf höchstens 500 Zeichen lang sein.");
        }
        return d;
    }

    private static int cleanCost(Integer cost) {
        if (cost == null || cost < 1 || cost > 100_000) {
            throw new IllegalArgumentException("Der Preis muss zwischen 1 und 100000 Minuten liegen.");
        }
        return cost;
    }
}
