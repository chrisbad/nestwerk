package club.badernet.nestwerk.notification;

import club.badernet.nestwerk.blocker.QuietHours;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Arbeitet den Postausgang ab: bis zu 5 Versuche mit wachsendem Abstand, abgelaufene Push-Abos werden gelöscht. */
@Component
public class DeliveryWorker {

    public static final int MAX_ATTEMPTS = 5;
    public static final int DAILY_MAIL_LIMIT = 20;
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    /** Ein offener Auftrag samt Inhalt der Benachrichtigung. */
    record Job(Long id, Channel channel, int attempts, Long userId, String title, String body, String link) {
    }

    /** Ein endgültig gescheiterter Auftrag für die Verwaltung. */
    public record Failure(String channel, String user, String title, String error, Instant at) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;
    private final MailDelivery mail;
    private final WebPushSender push;
    private final PushSubscriptionRepository subscriptions;
    private final Clock clock;
    private final PushSubscriptions safety;
    private final QuietHours quietHours;

    public DeliveryWorker(NamedParameterJdbcTemplate jdbc, UserRepository users, MailDelivery mail,
                          WebPushSender push, PushSubscriptionRepository subscriptions, Clock clock,
                          PushSubscriptions safety, QuietHours quietHours) {
        this.safety = safety;
        this.quietHours = quietHours;
        this.jdbc = jdbc;
        this.users = users;
        this.mail = mail;
        this.push = push;
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void run() {
        try {
            process();
        } catch (RuntimeException e) {
            log.error("Versand von Benachrichtigungen fehlgeschlagen", e);
        }
    }

    @Transactional
    public int process() {
        List<Job> jobs = jdbc.query("""
                SELECT d.id, d.channel, d.attempts, n.user_id, n.title, n.body, n.link
                FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id
                WHERE d.status = 'PENDING' AND d.next_attempt_at <= now()
                ORDER BY d.next_attempt_at LIMIT 50""", (rs, i) -> new Job(rs.getLong("id"),
                Channel.valueOf(rs.getString("channel")), rs.getInt("attempts"), rs.getLong("user_id"),
                rs.getString("title"), rs.getString("body"), rs.getString("link")));
        for (Job job : jobs) {
            Optional<User> user = users.findById(job.userId());
            if (user.isEmpty() || !user.get().isEnabled()) {
                finish(job, "SKIPPED", "Empfänger nicht aktiv");
                continue;
            }
            Optional<Instant> quietUntil = quietHours.quietUntil(job.userId(), clock.instant());
            if (quietUntil.isPresent()) {
                // Ruhe-Modus: nach der Ruhezeit zustellen, ohne einen Versuch zu verbrauchen
                jdbc.update("UPDATE notification_deliveries SET next_attempt_at = :at WHERE id = :id",
                        new MapSqlParameterSource("at", java.sql.Timestamp.from(quietUntil.get()))
                                .addValue("id", job.id()));
                continue;
            }
            if (job.channel() == Channel.EMAIL) {
                deliverMail(job, user.get());
            } else {
                deliverPush(job);
            }
        }
        return jobs.size();
    }

    private void deliverMail(Job job, User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            finish(job, "SKIPPED", "Keine E-Mail-Adresse hinterlegt");
            return;
        }
        Instant dayStart = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        Integer sentToday = jdbc.queryForObject("""
                SELECT count(*) FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id
                WHERE n.user_id = :user AND d.channel = 'EMAIL' AND d.status = 'SENT' AND d.sent_at >= :since""",
                new MapSqlParameterSource("user", user.getId()).addValue("since", java.sql.Timestamp.from(dayStart)),
                Integer.class);
        if (sentToday != null && sentToday >= DAILY_MAIL_LIMIT) {
            finish(job, "SKIPPED", "Tageslimit von " + DAILY_MAIL_LIMIT + " Mails erreicht");
            return;
        }
        try {
            mail.send(user.getEmail(), "Nestwerk: " + job.title(), job.body(), job.link());
            finish(job, "SENT", null);
        } catch (RuntimeException e) {
            retry(job, e.getMessage());
        }
    }

    private void deliverPush(Job job) {
        List<PushSubscription> subs = subscriptions.findAllByUserIdOrderByCreatedAtAsc(job.userId());
        if (subs.isEmpty()) {
            finish(job, "SKIPPED", "Kein Gerät für Push eingerichtet");
            return;
        }
        String json = "{\"title\":" + json(job.title()) + ",\"body\":" + json(job.body() == null ? "" : job.body())
                + ",\"url\":" + json(mail.absolute(job.link())) + "}";
        int sent = 0;
        List<String> errors = new ArrayList<>();
        for (PushSubscription sub : subs) {
            if (!safety.isSafe(sub.getEndpoint())) {
                subscriptions.delete(sub);
                continue;
            }
            WebPushSender.Outcome outcome = push.send(sub, json);
            switch (outcome.result()) {
                case SENT -> sent++;
                case GONE -> subscriptions.delete(sub);
                case FAILED -> errors.add(outcome.error());
            }
        }
        if (sent > 0) {
            finish(job, "SENT", errors.isEmpty() ? null : String.join("; ", errors));
        } else if (!errors.isEmpty()) {
            retry(job, String.join("; ", errors));
        } else {
            finish(job, "SKIPPED", "Alle Push-Abos waren abgelaufen");
        }
    }

    private void retry(Job job, String error) {
        int attempts = job.attempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            jdbc.update("UPDATE notification_deliveries SET status = 'FAILED', attempts = :a, last_error = :e WHERE id = :id",
                    Map.of("a", attempts, "e", trim(error), "id", job.id()));
            log.warn("Benachrichtigung {} über {} endgültig fehlgeschlagen: {}", job.id(), job.channel(), error);
            return;
        }
        Instant next = Instant.now().plus((long) Math.pow(2, attempts), ChronoUnit.MINUTES);
        jdbc.update("UPDATE notification_deliveries SET attempts = :a, last_error = :e, next_attempt_at = :n WHERE id = :id",
                new MapSqlParameterSource("a", attempts).addValue("e", trim(error))
                        .addValue("n", java.sql.Timestamp.from(next)).addValue("id", job.id()));
    }

    private void finish(Job job, String status, String error) {
        jdbc.update("UPDATE notification_deliveries SET status = :s, attempts = attempts + 1, last_error = :e, "
                        + "sent_at = CASE WHEN :s = 'SENT' THEN now() ELSE sent_at END WHERE id = :id",
                new MapSqlParameterSource("s", status).addValue("e", trim(error)).addValue("id", job.id()));
    }

    /** Endgültig gescheiterte Aufträge der letzten Zeit. */
    @Transactional(readOnly = true)
    public List<Failure> recentFailures(int limit) {
        return jdbc.query("""
                SELECT d.channel, u.username, n.title, d.last_error, n.created_at
                FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id
                JOIN users u ON u.id = n.user_id
                WHERE d.status = 'FAILED' ORDER BY n.created_at DESC LIMIT :limit""", Map.of("limit", limit),
                (rs, i) -> new Failure(rs.getString("channel"), rs.getString("username"), rs.getString("title"),
                        rs.getString("last_error"), rs.getTimestamp("created_at").toInstant()));
    }

    private static String trim(String text) {
        return text == null ? null : text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    static String json(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
