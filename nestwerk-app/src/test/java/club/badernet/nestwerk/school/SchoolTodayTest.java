package club.badernet.nestwerk.school;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.notification.Notification;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.settings.CalendarSettings;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class SchoolTodayTest {

    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired HomeworkService homework;
    @Autowired ExamService exams;
    @Autowired SchoolToday today;
    @Autowired NotificationService notifications;
    @Autowired CalendarSettings calendar;
    @Autowired FamilyMemberService members;
    @Autowired UserService users;
    @Autowired RoleRepository roles;

    User admin;
    User guest;
    FamilyMember lena;
    FamilyMember tom;
    SchoolYear lenaYear;
    SchoolSubject math;
    Lesson mathLesson;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        guest = users.create("gast", "passwort1", Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        lena = members.create("Lena", "#aabbcc", null);
        tom = members.create("Tom", "#ccbbaa", null);
        lenaYear = school.startYear(school.createProfile(lena.getId(), "BY").getId(), "2026/27", "Grundschule", "3a",
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 7, 31));
        math = timetable.addSubject(lenaYear.getId(), "Mathe", "#4d74ff", null, admin);
        mathLesson = timetable.addLesson(lenaYear.getId(), math.getId(), DayOfWeek.MONDAY, LocalTime.of(8, 0),
                LocalTime.of(8, 45), "R12", WeekKind.EVERY, admin);
        timetable.addLesson(lenaYear.getId(), math.getId(), DayOfWeek.MONDAY, LocalTime.of(9, 0),
                LocalTime.of(9, 45), "R12", WeekKind.EVERY, admin);
        school.startYear(school.createProfile(tom.getId(), null).getId(), "2026/27", "Gymnasium", "5b",
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 7, 31));
    }

    @Test
    void showsLessonsHomeworkAndNextExamPerChild() {
        homework.create(lenaYear.getId(), math.getId(), "Seite 12", null, MONDAY, null, admin);
        homework.create(lenaYear.getId(), null, "Später", null, MONDAY.plusDays(10), null, admin);
        exams.create(lenaYear.getId(), math.getId(), ExamKind.EXAM, "Brüche", MONDAY.plusDays(3), 0, admin);

        List<SchoolToday.ChildDay> days = today.forDate(MONDAY);

        assertThat(days).extracting(c -> c.member().getName()).containsExactly("Lena", "Tom");
        SchoolToday.ChildDay lenaDay = days.getFirst();
        assertThat(lenaDay.lessons()).hasSize(2);
        assertThat(lenaDay.span()).isEqualTo("08:00–09:45");
        assertThat(lenaDay.free()).isNull();
        assertThat(lenaDay.homework()).extracting(SchoolToday.HomeworkRow::title).containsExactly("Seite 12");
        assertThat(lenaDay.nextExam().getTitle()).isEqualTo("Brüche");
        assertThat(days.get(1).lessons()).isEmpty();
        assertThat(days.get(1).span()).isNull();
    }

    @Test
    void overdueHomeworkIsFlaggedAndDoneHomeworkDisappears() {
        Todo old = homework.create(lenaYear.getId(), null, "Alt", null, MONDAY.minusDays(2), null, admin);
        Todo done = homework.create(lenaYear.getId(), null, "Fertig", null, MONDAY, null, admin);
        homework.setDone(done.getId(), true, admin);

        List<SchoolToday.HomeworkRow> rows = today.forDate(MONDAY).getFirst().homework();

        assertThat(rows).extracting(SchoolToday.HomeworkRow::title).containsExactly("Alt");
        assertThat(rows.getFirst().overdue()).isTrue();
        assertThat(rows.getFirst().todoId()).isEqualTo(old.getId());
    }

    @Test
    void holidayShowsAsFreeAndCancelledLessonsAreKept() {
        timetable.markLesson(mathLesson.getId(), MONDAY, ChangeKind.CANCELLED, null, admin);

        SchoolToday.ChildDay lenaDay = today.forDate(MONDAY).getFirst();
        assertThat(lenaDay.lessons().getFirst().cancelled()).isTrue();
        assertThat(lenaDay.span()).isEqualTo("09:00–09:45");

        assertThat(today.forDate(LocalDate.of(2026, 10, 3)).getFirst().free()).isEqualTo("Feiertag");
    }

    @Test
    void viewerSeesOnlyTodosHeMayOpen() {
        homework.create(lenaYear.getId(), null, "Sichtbar", null, MONDAY, null, admin);

        assertThat(today.forDate(MONDAY, admin).getFirst().homework()).hasSize(1);
        User outsider = users.create("fremd", "passwort1", Set.of());
        assertThat(today.forDate(MONDAY, guest).getFirst().homework()).as("normale Benutzer sehen ToDos").hasSize(1);
        assertThat(today.forDate(MONDAY, outsider).getFirst().homework()).as("ohne Rolle keine ToDos").isEmpty();
    }

    @Test
    void cancellationTodayNotifiesEveryoneWithSchoolRight() {
        LocalDate day = LocalDate.now(calendar.zone());
        DayOfWeek weekday = day.getDayOfWeek();
        SchoolYear current = school.startYear(school.profileOf(lena.getId()).orElseThrow().getId(), "aktuell",
                "Grundschule", "3a", day.minusMonths(1), day.plusMonths(6));
        SchoolSubject art = timetable.addSubject(current.getId(), "Kunst", null, null, admin);
        Lesson lesson = timetable.addLesson(current.getId(), art.getId(), weekday, LocalTime.of(10, 0),
                LocalTime.of(10, 45), null, WeekKind.EVERY, admin);

        timetable.markLesson(lesson.getId(), day, ChangeKind.CANCELLED, "Lehrerin krank", admin);

        List<Notification> mine = notifications.list(guest.getId(), false, 10);
        assertThat(mine).extracting(Notification::getType).containsExactly(NotificationType.SCHOOL_CANCELLED);
        assertThat(mine.getFirst().getTitle()).isEqualTo("Kunst heute (10:00) entfällt");
        assertThat(mine.getFirst().getBody()).contains("Lena").contains("Lehrerin krank");
    }

    @Test
    void cancellationFarInTheFutureOrSubstitutesStaySilent() {
        LocalDate day = LocalDate.now(calendar.zone());
        SchoolYear current = school.startYear(school.profileOf(lena.getId()).orElseThrow().getId(), "aktuell",
                "Grundschule", "3a", day.minusMonths(1), day.plusMonths(6));
        SchoolSubject art = timetable.addSubject(current.getId(), "Kunst", null, null, admin);
        Lesson inAWeek = timetable.addLesson(current.getId(), art.getId(), day.plusDays(7).getDayOfWeek(),
                LocalTime.of(10, 0), LocalTime.of(10, 45), null, WeekKind.EVERY, admin);

        timetable.markLesson(inAWeek.getId(), day.plusDays(7), ChangeKind.CANCELLED, null, admin);
        timetable.markLesson(inAWeek.getId(), day.plusDays(7), ChangeKind.SUBSTITUTE, "Vertretung", admin);

        assertThat(notifications.list(guest.getId(), false, 10)).isEmpty();
    }
}
