package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.settings.AppSettings;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

/** Sagt täglich um 8 Uhr, was im Vorrat in den nächsten zwei Tagen abläuft (eine Sammelnachricht). */
@Component
public class ExpiryNotifier {

    static final String LAST_RUN = "kitchen.expiryLastRun";
    static final int HOUR = 8;
    static final String SOURCE = "PANTRY";

    private final PantryRepository pantry;
    private final NotificationService notifications;
    private final UserRepository users;
    private final KitchenAccess access;
    private final AppSettings settings;

    public ExpiryNotifier(PantryRepository pantry, NotificationService notifications, UserRepository users,
                          KitchenAccess access, AppSettings settings) {
        this.pantry = pantry;
        this.notifications = notifications;
        this.users = users;
        this.access = access;
        this.settings = settings;
    }

    /** Läuft minütlich; handelt höchstens einmal pro Tag, ab 8 Uhr. Liefert die Zahl der Benachrichtigten. */
    @Transactional
    public int run(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        if (now.getHour() < HOUR || settings.get(LAST_RUN).map(LocalDate::parse).filter(today::equals).isPresent()) {
            return 0;
        }
        settings.set(LAST_RUN, today.toString());
        List<PantryItem> expiring = pantry.findByBestBeforeBetweenOrderByBestBeforeAsc(today,
                today.plusDays(PantryService.WARN_DAYS));
        if (expiring.isEmpty()) {
            return 0;
        }
        String title = expiring.size() == 1 ? "1 Posten im Vorrat läuft bald ab"
                : expiring.size() + " Posten im Vorrat laufen bald ab";
        String body = expiring.stream().map(i -> i.getName() + " (" + label(i.getBestBefore(), today) + ")")
                .collect(Collectors.joining(", "));
        int sent = 0;
        for (User u : users.findAllByOrderByUsernameAsc()) {
            if (u.isEnabled() && access.has(u, KitchenPermission.KITCHEN_SHOPPING)
                    && notifications.notify(u.getId(), NotificationType.PANTRY_EXPIRING, SOURCE, 0L, today.toString(),
                    title, body, "/kitchen/pantry")) {
                sent++;
            }
        }
        return sent;
    }

    private static String label(LocalDate day, LocalDate today) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, day);
        return days == 0 ? "heute" : days == 1 ? "morgen" : "in " + days + " Tagen";
    }
}
