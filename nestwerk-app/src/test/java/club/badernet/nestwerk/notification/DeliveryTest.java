package club.badernet.nestwerk.notification;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.blocker.Blocker;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerRepository;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BlockerVisibility;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import club.badernet.nestwerk.user.UserService;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"nestwerk.admin.password=test-admin-pw",
        "spring.mail.host=localhost", "spring.mail.port=3025",
        "spring.mail.properties.mail.smtp.auth=false", "spring.mail.properties.mail.smtp.starttls.enable=false",
        "nestwerk.mail.from=nestwerk@example.org", "nestwerk.base-url=https://nest.example.org",
        "nestwerk.push.allow-insecure-endpoints=true"})
@Transactional
class DeliveryTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    static final NotificationType TYPE = NotificationType.TODO_DUE_SOON;

    @Autowired NotificationService notifications;
    @Autowired NotificationPreferences preferences;
    @Autowired DeliveryWorker worker;
    @Autowired PushSubscriptions pushSubscriptions;
    @Autowired PushSubscriptionRepository subscriptionRepository;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyMemberRepository members;
    @Autowired BlockerRepository blockers;
    @Autowired CalendarSettings calendar;

    HttpServer pushServer;
    final AtomicInteger pushStatus = new AtomicInteger(201);
    final Map<String, String> pushHeaders = new ConcurrentHashMap<>();
    User mama;

    @BeforeEach
    void setUp() throws Exception {
        mama = userService.create("mama", "passwort1", Set.of());
        pushServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        pushServer.createContext("/push", exchange -> {
            exchange.getRequestHeaders().forEach((k, v) -> pushHeaders.put(k.toLowerCase(), v.getFirst()));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(pushStatus.get(), -1);
            exchange.close();
        });
        pushServer.start();
    }

    @AfterEach
    void tearDown() {
        pushServer.stop(0);
    }

    private void notifyMama(String dueKey) {
        notifications.notify(mama.getId(), TYPE, "TODO", 7L, dueKey, "Steuer", "Fällig bis Freitag.", "/todos/7");
    }

    private PushSubscription subscribe() {
        KeyPair ua = WebPushCrypto.generateKeyPair();
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return pushSubscriptions.subscribe(mama, "http://localhost:" + pushServer.getAddress().getPort() + "/push/1",
                b64.encodeToString(WebPushCrypto.encodePoint((ECPublicKey) ua.getPublic())), b64.encodeToString(auth),
                "Mozilla/5.0 (Linux; Android 14) Chrome/130.0");
    }

    private String status(String channel) {
        return jdbc.queryForObject("SELECT status FROM notification_deliveries WHERE channel = ?", String.class, channel);
    }

    private int deliveries() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Integer.class);
    }

    @Test
    void mailIsSentWithAbsoluteLink() throws Exception {
        mama.setEmail("mama@example.org");
        users.saveAndFlush(mama);
        preferences.set(NotificationPreferences.Scope.GLOBAL, 0L, TYPE.name(), Channel.EMAIL, NotificationPreferences.Choice.ON);

        notifyMama("k1");
        worker.process();

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo("Nestwerk: Steuer");
        assertThat(GreenMailUtil.getBody(received[0])).contains("https://nest.example.org/todos/7");
        assertThat(status("EMAIL")).isEqualTo("SENT");
    }

    @Test
    void quietBlockerPostponesDeliveryUntilItEnds() {
        mama.setEmail("mama@example.org");
        users.saveAndFlush(mama);
        preferences.set(NotificationPreferences.Scope.GLOBAL, 0L, TYPE.name(), Channel.EMAIL, NotificationPreferences.Choice.ON);
        FamilyMember member = members.save(new FamilyMember("Mama", "#aa0000", mama.getId()));
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(calendar.zone());
        java.time.LocalTime from = now.toLocalTime().minusHours(1).withSecond(0).withNano(0);
        java.time.LocalTime to = now.toLocalTime().plusHours(1).withSecond(0).withNano(0);
        blockers.save(quiet(from, to, member.getId(), now.toLocalDate().minusDays(1)));

        notifyMama("k1");
        worker.process();

        assertThat(greenMail.getReceivedMessages()).isEmpty();
        java.sql.Timestamp next = jdbc.queryForObject(
                "SELECT next_attempt_at FROM notification_deliveries WHERE status = 'PENDING'", java.sql.Timestamp.class);
        assertThat(next.toInstant()).isAfter(now.toInstant().plusSeconds(3000));
    }

    private static Blocker quiet(java.time.LocalTime from, java.time.LocalTime to, Long memberId,
                                 java.time.LocalDate validFrom) {
        Blocker b = new Blocker("Ruhe", BlockerKind.REST, from, to, java.util.EnumSet.allOf(java.time.DayOfWeek.class),
                validFrom, null, BlockerStrength.SHOW, BlockerVisibility.TITLE, true, false, null);
        b.setMemberIds(Set.of(memberId));
        return b;
    }

    @Test
    void noAddressNoMail() {
        preferences.set(NotificationPreferences.Scope.GLOBAL, 0L, TYPE.name(), Channel.EMAIL, NotificationPreferences.Choice.ON);

        notifyMama("k1");

        assertThat(deliveries()).isZero();
    }

    @Test
    void pushIsEncryptedAndSigned() {
        subscribe();

        notifyMama("k1");
        worker.process();

        assertThat(status("PUSH")).isEqualTo("SENT");
        assertThat(pushHeaders.get("content-encoding")).isEqualTo("aes128gcm");
        assertThat(pushHeaders.get("authorization")).startsWith("vapid t=").contains(", k=");
        assertThat(pushHeaders.get("ttl")).isEqualTo("86400");
    }

    @Test
    void goneSubscriptionIsRemoved() {
        PushSubscription sub = subscribe();
        pushStatus.set(410);

        notifyMama("k1");
        worker.process();

        assertThat(subscriptionRepository.findById(sub.getId())).isEmpty();
        assertThat(status("PUSH")).isEqualTo("SKIPPED");
    }

    @Test
    void failedDeliveryIsRetriedThenGivenUp() {
        subscribe();
        pushStatus.set(500);
        notifyMama("k1");

        for (int i = 1; i <= DeliveryWorker.MAX_ATTEMPTS; i++) {
            worker.process();
            jdbc.update("UPDATE notification_deliveries SET next_attempt_at = now() - interval '1 minute'");
        }

        assertThat(status("PUSH")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_deliveries", Integer.class))
                .isEqualTo(DeliveryWorker.MAX_ATTEMPTS);
        assertThat(worker.recentFailures(10)).anySatisfy(f -> assertThat(f.error()).contains("500"));
    }

    @Test
    void retryWaitsBeforeNextAttempt() {
        subscribe();
        pushStatus.set(500);
        notifyMama("k1");

        worker.process();
        worker.process();

        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_deliveries", Integer.class)).isEqualTo(1);
        assertThat(status("PUSH")).isEqualTo("PENDING");
    }

    @Test
    void preferenceOffCreatesNoDelivery() {
        subscribe();
        preferences.set(NotificationPreferences.Scope.USER, mama.getId(), TYPE.name(), Channel.PUSH,
                NotificationPreferences.Choice.OFF);

        notifyMama("k1");

        assertThat(deliveries()).isZero();
    }

    @Test
    void foreignSubscriptionCannotBeRemoved() {
        PushSubscription sub = subscribe();
        User papa = userService.create("papa", "passwort1", Set.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pushSubscriptions.remove(sub.getId(), papa))
                .isInstanceOf(NotificationNotFoundException.class);
        pushSubscriptions.remove(sub.getId(), mama);
        assertThat(subscriptionRepository.findById(sub.getId())).isEmpty();
    }
}
