package club.badernet.nestwerk.school;

import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.settings.AppSettings;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Erinnert an Elternbriefe, die noch nicht unterschrieben sind: drei Tage vorher, am Vortag und am Tag der Frist.
 * Läuft einmal täglich ab der eingestellten Erinnerungsuhrzeit.
 */
@Component
public class LetterReminder {

    static final String LAST_RUN = "school.letterReminderLastRun";
    static final String SOURCE = "SCHOOL_LETTER";
    private static final Logger log = LoggerFactory.getLogger(LetterReminder.class);

    private final SchoolLetterRepository letters;
    private final SchoolYearRepository years;
    private final NotificationService notifications;
    private final UserRepository users;
    private final SchoolAccess access;
    private final AppSettings settings;
    private final CalendarSettings calendar;

    public LetterReminder(SchoolLetterRepository letters, SchoolYearRepository years,
                          NotificationService notifications, UserRepository users, SchoolAccess access,
                          AppSettings settings, CalendarSettings calendar) {
        this.letters = letters;
        this.years = years;
        this.notifications = notifications;
        this.users = users;
        this.access = access;
        this.settings = settings;
        this.calendar = calendar;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(calendar.zone());
        if (now.getHour() < settings.reminderHour()) {
            return;
        }
        LocalDate today = now.toLocalDate();
        if (settings.get(LAST_RUN).map(LocalDate::parse).filter(today::equals).isPresent()) {
            return;
        }
        try {
            run(today);
            settings.set(LAST_RUN, today.toString());
        } catch (RuntimeException e) {
            log.error("Erinnerungen an Elternbriefe für {} fehlgeschlagen", today, e);
        }
    }

    /** Verschickt die Erinnerungen für {@code today}; liefert die Zahl der Benachrichtigungen. */
    @Transactional
    public int run(LocalDate today) {
        int sent = 0;
        for (SchoolLetter letter : letters.findAllBySignedAtIsNullAndDueOnGreaterThanEqual(today)) {
            long days = ChronoUnit.DAYS.between(today, letter.getDueOn());
            if (days != 3 && days != 1 && days != 0) {
                continue;
            }
            if (years.findById(letter.getYearId()).filter(SchoolYear::isActive).isEmpty()) {
                continue;
            }
            String title = days == 0 ? "Elternbrief „" + letter.getTitle() + "“ ist heute fällig"
                    : days == 1 ? "Elternbrief „" + letter.getTitle() + "“ ist morgen fällig"
                    : "Elternbrief „" + letter.getTitle() + "“ ist in " + days + " Tagen fällig";
            for (User u : users.findAllByOrderByUsernameAsc()) {
                if (u.isEnabled() && access.has(u, SchoolPermission.SCHOOL_VIEW)
                        && notifications.notify(u.getId(), NotificationType.SCHOOL_LETTER, SOURCE, letter.getId(),
                        today.toString(), title, "Noch nicht unterschrieben.",
                        "/school/years/" + letter.getYearId() + "/letters")) {
                    sent++;
                }
            }
        }
        return sent;
    }
}
