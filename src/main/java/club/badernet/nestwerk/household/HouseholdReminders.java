package club.badernet.nestwerk.household;

import club.badernet.nestwerk.notification.AppSettings;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Erinnerungen zum Haushaltsplan: Aufgaben mit Uhrzeit eine Stunde vorher, ohne Uhrzeit zur Erinnerungsstunde,
 * überfällige einmal (bis zu einer Woche nach Fälligkeit).
 */
@Component
public class HouseholdReminders {

    static final int LEAD_MINUTES = 60;
    static final int OVERDUE_WINDOW_DAYS = 7;

    private final ChoreTaskRepository tasks;
    private final HouseholdNotifier notifier;
    private final AppSettings settings;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;

    public HouseholdReminders(ChoreTaskRepository tasks, HouseholdNotifier notifier, AppSettings settings,
                              NamedParameterJdbcTemplate jdbc, EntityManager entityManager) {
        this.tasks = tasks;
        this.notifier = notifier;
        this.settings = settings;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    @Transactional
    public void run(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDateTime local = now.toLocalDateTime();
        boolean afterReminderHour = now.getHour() >= settings.reminderHour();
        for (ChoreTask task : tasks.findAllByStatusAndDueOnAndNotifiedDueFalse(ChoreTaskStatus.OPEN, today)) {
            boolean due = task.getDueTime() != null
                    ? !local.isBefore(today.atTime(task.getDueTime()).minusMinutes(LEAD_MINUTES))
                    : afterReminderHour;
            if (due) {
                notifier.due(task);
                task.markNotifiedDue();
            }
        }
        if (afterReminderHour) {
            entityManager.flush();
            List<Long> overdue = jdbc.queryForList("""
                    SELECT t.id FROM chore_tasks t
                    WHERE t.status = 'OPEN' AND t.due_on < :today AND t.due_on >= :from
                      AND NOT EXISTS (SELECT 1 FROM notifications n
                                      WHERE n.type = 'CHORE_OVERDUE' AND n.source_type = 'CHORE_TASK'
                                        AND n.source_id = t.id)""",
                    Map.of("today", today, "from", today.minusDays(OVERDUE_WINDOW_DAYS)), Long.class);
            tasks.findAllById(overdue).forEach(notifier::overdue);
        }
    }
}
