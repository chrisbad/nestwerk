package club.badernet.nestwerk.demo;

import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.settings.AppSettings;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Fills a fresh installation with one example family and sample data for every feature area. Runs once after
 * start-up, only when {@code nestwerk.demo-data.enabled=true}, and never again once the marker is set.
 */
@Component
@ConditionalOnProperty(name = "nestwerk.demo-data.enabled", havingValue = "true")
class DemoDataSeeder {

    static final String MARKER = "demo.dataSeeded";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String COLOR_PARENT = "#3e63dd";
    private static final String COLOR_PARTNER = "#d6409f";
    private static final String COLOR_DAUGHTER = "#12a594";
    private static final String COLOR_SON = "#f76b15";

    private final List<DemoArea> areas;
    private final FamilyMemberService members;
    private final UserRepository users;
    private final AppSettings settings;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String adminUsername;

    DemoDataSeeder(List<DemoArea> areas, FamilyMemberService members, UserRepository users, AppSettings settings,
                   TransactionTemplate transaction, Clock clock,
                   @Value("${nestwerk.admin.username}") String adminUsername) {
        this.areas = areas;
        this.members = members;
        this.users = users;
        this.settings = settings;
        this.transaction = transaction;
        this.clock = clock;
        this.adminUsername = adminUsername;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        if (settings.get(MARKER).isPresent()) {
            log.info("Demo data already loaded, skipping.");
            return;
        }
        User actor = users.findByUsername(adminUsername)
                .orElseThrow(() -> new IllegalStateException("Administrator '" + adminUsername + "' not found"));
        DemoContext context = new DemoContext(actor, createFamily(actor), LocalDate.now(clock));
        for (DemoArea area : areas) {
            seedArea(area, context);
        }
        settings.set(MARKER, context.today().toString());
        log.info("Demo data loaded.");
    }

    private DemoFamily createFamily(User actor) {
        return transaction.execute(status -> {
            FamilyMember parent = members.findByUserId(actor.getId())
                    .orElseGet(() -> members.create("Chris", COLOR_PARENT, actor.getId()));
            return new DemoFamily(parent, members.create("Anna", COLOR_PARTNER, null),
                    members.create("Lena", COLOR_DAUGHTER, null), members.create("Max", COLOR_SON, null));
        });
    }

    /** One area per transaction, so a failure in one area does not discard the others. */
    private void seedArea(DemoArea area, DemoContext context) {
        try {
            transaction.executeWithoutResult(status -> area.seed(context));
            log.info("Demo data loaded for {}.", area.name());
        } catch (RuntimeException e) {
            log.warn("Demo data for {} skipped: {}", area.name(), e.getMessage(), e);
        }
    }
}
