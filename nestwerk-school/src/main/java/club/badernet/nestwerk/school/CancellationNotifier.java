package club.badernet.nestwerk.school;

import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Meldet ausfallende Stunden von heute und morgen allen, die die Schule sehen dürfen. */
@Component
class CancellationNotifier {

    private final NotificationService notifications;
    private final UserRepository users;
    private final SchoolAccess access;
    private final CalendarSettings calendar;

    CancellationNotifier(NotificationService notifications, UserRepository users, SchoolAccess access,
                         CalendarSettings calendar) {
        this.notifications = notifications;
        this.users = users;
        this.access = access;
        this.calendar = calendar;
    }

    void cancelled(Lesson lesson, SchoolSubject subject, SchoolYear year, String childName, LocalDate day,
                   String note) {
        LocalDate today = LocalDate.now(calendar.zone());
        if (day.isBefore(today) || day.isAfter(today.plusDays(1))) {
            return;
        }
        String when = day.equals(today) ? "heute" : "morgen";
        String title = subject.getName() + " " + when + " (" + lesson.getStartsAt() + ") entfällt";
        String body = (childName.isBlank() ? "" : childName + ": ") + title + (note == null ? "" : " – " + note);
        for (User u : users.findAllByOrderByUsernameAsc()) {
            if (u.isEnabled() && access.has(u, SchoolPermission.SCHOOL_VIEW)) {
                notifications.notify(u.getId(), NotificationType.SCHOOL_CANCELLED, "SCHOOL_LESSON", lesson.getId(),
                        day.toString(), title, body, "/school/years/" + year.getId() + "/timetable?week=" + day);
            }
        }
    }
}
