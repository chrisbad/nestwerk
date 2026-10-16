package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.notification.Notification;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class ExpiryNotifierTest {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    static final LocalDate DAY = LocalDate.of(2026, 10, 12);

    @Autowired ExpiryNotifier notifier;
    @Autowired PantryRepository pantry;
    @Autowired NotificationService notifications;
    @Autowired UserService userService;
    @Autowired RoleService roleService;

    User shopper;
    User viewer;

    @BeforeEach
    void setUp() {
        Role shopping = roleService.create("Einkäufer", null, Set.of("KITCHEN_VIEW", "KITCHEN_SHOPPING"));
        shopper = userService.create("einkauf", "passwort1", Set.of(shopping.getId()));
        Role view = roleService.create("Schauer", null, Set.of("KITCHEN_VIEW"));
        viewer = userService.create("schau", "passwort1", Set.of(view.getId()));
    }

    private ZonedDateTime at(int hour) {
        return DAY.atTime(hour, 5).atZone(BERLIN);
    }

    @Test
    void sendsOneSummaryToShoppersWhenSomethingExpiresSoon() {
        pantry.save(new PantryItem(null, "Milch", 1, Unit.L, "Kühlschrank", DAY.plusDays(1)));
        pantry.save(new PantryItem(null, "Joghurt", 4, Unit.PIECE, "Kühlschrank", DAY));
        pantry.save(new PantryItem(null, "Reis", 1, Unit.KG, "Vorrat", DAY.plusDays(200)));

        int sent = notifier.run(at(8));

        List<Notification> mine = notifications.latest(shopper.getId(), 10);
        assertThat(sent).isGreaterThanOrEqualTo(1);
        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().getTitle()).isEqualTo("2 Posten im Vorrat laufen bald ab");
        assertThat(mine.getFirst().getBody()).contains("Milch (morgen)", "Joghurt (heute)").doesNotContain("Reis");
        assertThat(notifications.latest(viewer.getId(), 10)).isEmpty();
    }

    @Test
    void staysQuietWithoutHitsBeforeEightAndOnRepeat() {
        assertThat(notifier.run(at(8))).isZero();
        assertThat(notifications.latest(shopper.getId(), 10)).isEmpty();

        pantry.save(new PantryItem(null, "Milch", 1, Unit.L, "Kühlschrank", DAY.plusDays(1)));
        assertThat(notifier.run(at(7))).isZero();
        assertThat(notifier.run(at(9))).isZero();
        assertThat(notifications.latest(shopper.getId(), 10)).isEmpty();
    }

    @Test
    void sendsOncePerDay() {
        pantry.save(new PantryItem(null, "Milch", 1, Unit.L, "Kühlschrank", DAY.plusDays(1)));

        notifier.run(at(8));
        notifier.run(at(9));

        assertThat(notifications.latest(shopper.getId(), 10)).hasSize(1);
    }
}
