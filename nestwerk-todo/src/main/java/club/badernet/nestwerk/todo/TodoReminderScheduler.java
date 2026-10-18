package club.badernet.nestwerk.todo;

import club.badernet.nestwerk.settings.AppSettings;
import club.badernet.nestwerk.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Erinnerungen zu ToDos. Läuft minütlich, wirkt aber nur einmal am Tag ab der eingestellten Uhrzeit und
 * holt versäumte Tage nach. Doppelte Benachrichtigungen verhindert der Stichtag im eindeutigen Schlüssel.
 */
@Component
public class TodoReminderScheduler {

    static final int MAX_CATCH_UP_DAYS = 31;
    private static final Logger log = LoggerFactory.getLogger(TodoReminderScheduler.class);

    private final TodoRepository todos;
    private final TodoNotifier notifier;
    private final AppSettings settings;
    private final Clock clock;

    public TodoReminderScheduler(TodoRepository todos, TodoNotifier notifier, AppSettings settings, Clock clock) {
        this.todos = todos;
        this.notifier = notifier;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (now.getHour() < settings.reminderHour()) {
            return;
        }
        LocalDate today = now.toLocalDate();
        LocalDate from = catchUpFrom(settings.lastReminderRun().orElse(null), today);
        if (from == null) {
            return;
        }
        try {
            runFor(from, today);
            settings.markReminderRun(today);
        } catch (RuntimeException e) {
            log.error("Erinnerungen für {} bis {} fehlgeschlagen", from, today, e);
        }
    }

    /** Erster nachzuholender Tag oder {@code null}, wenn heute schon gelaufen. */
    static LocalDate catchUpFrom(LocalDate lastRun, LocalDate today) {
        if (lastRun == null) {
            return today;
        }
        if (!lastRun.isBefore(today)) {
            return null;
        }
        LocalDate from = lastRun.plusDays(1);
        LocalDate earliest = today.minusDays(MAX_CATCH_UP_DAYS - 1);
        return from.isBefore(earliest) ? earliest : from;
    }

    /** Erinnert für alle Tage von {@code from} bis {@code to} (jeweils einschließlich). */
    @Transactional
    public void runFor(LocalDate from, LocalDate to) {
        int defaultLead = settings.defaultLeadDays();
        List<Todo> open = todos.findAllByStatusIn(List.of(TodoStatus.OPEN, TodoStatus.IN_PROGRESS));
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            for (Todo t : open) {
                remindFor(t, day, defaultLead);
            }
        }
    }

    private void remindFor(Todo t, LocalDate day, int defaultLead) {
        if (day.equals(t.getAvailableFrom())) {
            notifier.remind(t, NotificationType.TODO_AVAILABLE, day);
        }
        if (t.getDueOn() != null) {
            int lead = t.getRemindDaysBefore() != null ? t.getRemindDaysBefore() : defaultLead;
            boolean dormant = t.getAvailableFrom() != null && t.getAvailableFrom().isAfter(day);
            if (!dormant && day.equals(t.getDueOn().minusDays(lead))) {
                notifier.remind(t, NotificationType.TODO_DUE_SOON, day);
            }
            long late = ChronoUnit.DAYS.between(t.getDueOn(), day);
            if (late >= 1 && (late - 1) % 7 == 0) {
                notifier.remind(t, NotificationType.TODO_OVERDUE, day);
            }
        }
        if (day.equals(t.getFollowUpOn())) {
            notifier.remind(t, NotificationType.TODO_FOLLOW_UP, day);
        }
    }
}
