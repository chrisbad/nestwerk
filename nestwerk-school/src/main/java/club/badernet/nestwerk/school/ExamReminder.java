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
 * Erinnert ans Lernen und an Schultermine: am Tag der Lernerinnerung ({@code studyDays} vor dem Termin) und am Vortag.
 * Läuft einmal täglich ab der eingestellten Erinnerungsuhrzeit; Doppelte verhindert der Stichtag im Schlüssel.
 */
@Component
public class ExamReminder {

    static final String LAST_RUN = "school.examReminderLastRun";
    static final String SOURCE = "SCHOOL_EXAM";
    private static final Logger log = LoggerFactory.getLogger(ExamReminder.class);

    private final SchoolExamRepository exams;
    private final SchoolYearRepository years;
    private final SchoolProfileRepository profiles;
    private final club.badernet.nestwerk.member.FamilyMemberRepository members;
    private final ExamService examService;
    private final NotificationService notifications;
    private final UserRepository users;
    private final SchoolAccess access;
    private final AppSettings settings;
    private final CalendarSettings calendar;

    public ExamReminder(SchoolExamRepository exams, SchoolYearRepository years, SchoolProfileRepository profiles,
                        club.badernet.nestwerk.member.FamilyMemberRepository members, ExamService examService,
                        NotificationService notifications, UserRepository users, SchoolAccess access,
                        AppSettings settings, CalendarSettings calendar) {
        this.exams = exams;
        this.years = years;
        this.profiles = profiles;
        this.members = members;
        this.examService = examService;
        this.notifications = notifications;
        this.users = users;
        this.access = access;
        this.settings = settings;
        this.calendar = calendar;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
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
            log.error("Schul-Erinnerungen für {} fehlgeschlagen", today, e);
        }
    }

    /** Verschickt die Erinnerungen für {@code today}; liefert die Zahl der Benachrichtigungen. */
    @Transactional
    public int run(LocalDate today) {
        int sent = 0;
        for (SchoolExam exam : exams.findAllByDayGreaterThanEqual(today)) {
            long days = ChronoUnit.DAYS.between(today, exam.getDay());
            boolean study = exam.getStudyDays() > 0 && days == exam.getStudyDays();
            if (!(study || days == 1 || days == 0)) {
                continue;
            }
            SchoolYear year = years.findById(exam.getYearId()).orElse(null);
            if (year == null || !year.isActive()) {
                continue;
            }
            String child = profiles.findById(year.getProfileId())
                    .flatMap(p -> members.findById(p.getMemberId())).map(m -> m.getName()).orElse("");
            String what = examService.eventTitle(exam);
            String title = days == 0 ? what + " ist heute" : days == 1 ? what + " ist morgen"
                    : what + " in " + days + " Tagen";
            String body = child.isBlank() ? title : title + " (" + child + ")";
            for (User u : users.findAllByOrderByUsernameAsc()) {
                if (u.isEnabled() && access.has(u, SchoolPermission.SCHOOL_VIEW)
                        && notifications.notify(u.getId(), NotificationType.SCHOOL_EXAM, SOURCE, exam.getId(),
                        today.toString(), title, body, "/school/years/" + exam.getYearId() + "/exams")) {
                    sent++;
                }
            }
        }
        return sent;
    }
}
