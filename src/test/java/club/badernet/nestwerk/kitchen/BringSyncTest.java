package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.backup.SecretBox;
import club.badernet.nestwerk.kitchen.integration.BringApi;
import club.badernet.nestwerk.kitchen.integration.BringException;
import club.badernet.nestwerk.kitchen.integration.BringList;
import club.badernet.nestwerk.notification.AppSettings;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class BringSyncTest {

    /** Merkt sich, was gesendet wurde, und kann ausfallen. */
    static class FakeBring implements BringApi {
        final List<String> added = new ArrayList<>();
        final List<String> open = new ArrayList<>();
        String failure;
        int failAfter = Integer.MAX_VALUE;
        int calls;

        @Override
        public List<BringList> lists(String email, String password) {
            fail();
            return List.of(new BringList("l-1", "Zuhause"), new BringList("l-2", "Garten"));
        }

        @Override
        public List<String> open(String email, String password, String listUuid) {
            fail();
            return open;
        }

        @Override
        public void add(String email, String password, String listUuid, String name, String specification) {
            if (calls++ >= failAfter) {
                throw new BringException(failure);
            }
            fail();
            added.add(name + (specification.isEmpty() ? "" : " | " + specification));
        }

        private void fail() {
            if (failure != null && failAfter == Integer.MAX_VALUE) {
                throw new BringException(failure);
            }
        }
    }

    @Autowired ShoppingRepository shopping;
    @Autowired KitchenSettings settings;
    @Autowired KitchenAccess access;
    @Autowired UserService userService;
    @Autowired AppSettings appSettings;
    @Autowired SecretBox secrets;
    @Autowired JdbcTemplate jdbc;

    FakeBring api = new FakeBring();
    BringSync sync;
    User chef;

    @BeforeEach
    void setUp() {
        chef = userService.createAdministrator("chef", "passwort1");
        sync = new BringSync(shopping, settings, api, access, Clock.systemUTC());
    }

    private ShoppingItem item(String name, Double amount, Unit unit) {
        return shopping.save(ShoppingItem.manual(name, null, amount, unit));
    }

    @Test
    void connectingStoresThePasswordEncryptedAndPicksTheFirstList() {
        sync.connect("lena@example.org", "geheim", chef);

        KitchenSettings.BringAccount account = settings.bring();
        assertThat(account.email()).isEqualTo("lena@example.org");
        assertThat(account.password()).isEqualTo("geheim");
        assertThat(account.listUuid()).isEqualTo("l-1");
        assertThat(account.listName()).isEqualTo("Zuhause");
        assertThat(jdbc.queryForObject("SELECT value FROM app_settings WHERE key = 'kitchen.bring.password'",
                String.class)).startsWith("v1:").doesNotContain("geheim");
    }

    @Test
    void wrongCredentialsSaveNothing() {
        api.failure = "Bring! lehnt die Anmeldung ab – bitte E-Mail und Passwort prüfen.";

        assertThatThrownBy(() -> sync.connect("lena@example.org", "falsch", chef))
                .isInstanceOf(BringException.class);
        assertThat(settings.bring().configured()).isFalse();
    }

    @Test
    void sendingMarksOpenItemsAsSentWithSpecification() {
        sync.connect("lena@example.org", "geheim", chef);
        ShoppingItem flour = item("Mehl", 500.0, Unit.G);
        ShoppingItem soap = item("Seife", null, null);
        ShoppingItem done = item("Brot", null, null);
        done.markBought(Instant.now());

        BringSync.Result result = sync.send(chef);

        assertThat(result.failed()).isFalse();
        assertThat(result.count()).isEqualTo(2);
        assertThat(api.added).containsExactly("Mehl | 500 g", "Seife");
        assertThat(flour.getStatus()).isEqualTo(ShoppingStatus.SENT);
        assertThat(flour.getSentAt()).isNotNull();
        assertThat(soap.getStatus()).isEqualTo(ShoppingStatus.SENT);
        assertThat(done.getStatus()).isEqualTo(ShoppingStatus.BOUGHT);
    }

    @Test
    void outageLeavesTheListUnchangedAndReportsAMessage() {
        sync.connect("lena@example.org", "geheim", chef);
        ShoppingItem flour = item("Mehl", 500.0, Unit.G);
        api.failure = "Bring! ist gerade nicht erreichbar.";

        BringSync.Result result = sync.send(chef);

        assertThat(result.error()).isEqualTo("Bring! ist gerade nicht erreichbar.");
        assertThat(result.count()).isZero();
        assertThat(flour.getStatus()).isEqualTo(ShoppingStatus.OPEN);
        assertThat(flour.getSentAt()).isNull();
    }

    @Test
    void outageInTheMiddleKeepsWhatReallyWasSent() {
        sync.connect("lena@example.org", "geheim", chef);
        ShoppingItem first = item("Mehl", 500.0, Unit.G);
        ShoppingItem second = item("Butter", 250.0, Unit.G);
        api.failure = "Bring! ist gerade nicht erreichbar.";
        api.failAfter = 1;

        BringSync.Result result = sync.send(chef);

        assertThat(result.failed()).isTrue();
        assertThat(result.count()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(ShoppingStatus.SENT);
        assertThat(second.getStatus()).isEqualTo(ShoppingStatus.OPEN);
    }

    @Test
    void syncMarksItemsNoLongerOnTheListAsBought() {
        sync.connect("lena@example.org", "geheim", chef);
        ShoppingItem flour = item("Mehl", 500.0, Unit.G);
        ShoppingItem butter = item("Butter", 250.0, Unit.G);
        ShoppingItem untouched = item("Seife", null, null);
        flour.markSent(Instant.now());
        butter.markSent(Instant.now());
        api.open.add("butter");

        BringSync.Result result = sync.sync(chef);

        assertThat(result.count()).isEqualTo(1);
        assertThat(flour.getStatus()).isEqualTo(ShoppingStatus.BOUGHT);
        assertThat(flour.getBoughtAt()).isNotNull();
        assertThat(butter.getStatus()).isEqualTo(ShoppingStatus.SENT);
        assertThat(untouched.getStatus()).isEqualTo(ShoppingStatus.OPEN);
    }

    @Test
    void failedSyncChangesNothing() {
        sync.connect("lena@example.org", "geheim", chef);
        ShoppingItem flour = item("Mehl", 500.0, Unit.G);
        flour.markSent(Instant.now());
        api.failure = "Bring! ist gerade nicht erreichbar.";

        BringSync.Result result = sync.sync();

        assertThat(result.failed()).isTrue();
        assertThat(flour.getStatus()).isEqualTo(ShoppingStatus.SENT);
    }

    @Test
    void withoutSetupNothingHappensAndTheUserIsTold() {
        item("Mehl", 500.0, Unit.G);

        assertThat(sync.configured()).isFalse();
        assertThat(sync.send(chef).error()).contains("noch nicht eingerichtet");
        assertThat(sync.sync().error()).contains("noch nicht eingerichtet");
    }

    @Test
    void disconnectingForgetsTheAccount() {
        sync.connect("lena@example.org", "geheim", chef);

        sync.disconnect(chef);

        assertThat(settings.bring().configured()).isFalse();
        assertThat(sync.configured()).isFalse();
    }
}
