package club.badernet.nestwerk.school;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.notification.Notification;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class ExamServiceTest {

    static final LocalDate EXAM_DAY = LocalDate.of(2026, 10, 20);

    @Autowired SchoolService school;
    @Autowired TimetableService timetable;
    @Autowired ExamService exams;
    @Autowired ExamReminder reminder;
    @Autowired CalendarEventService calendar;
    @Autowired NotificationService notifications;
    @Autowired FamilyMemberService members;
    @Autowired UserService users;
    @Autowired RoleRepository roles;

    User admin;
    User parent;
    FamilyMember lena;
    SchoolYear year;
    SchoolSubject math;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        parent = users.create("mama", "passwort1", Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        lena = members.create("Lena", "#aabbcc", null);
        SchoolProfile profile = school.createProfile(lena.getId(), null);
        year = school.startYear(profile.getId(), "2026/27", null, "3a", LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 7, 31));
        math = timetable.addSubject(year.getId(), "Mathe", null, admin);
    }

    @Test
    void createsAnAllDayCalendarEventForTheChild() {
        SchoolExam exam = exams.create(year.getId(), math.getId(), ExamKind.EXAM, "Bruchrechnen", EXAM_DAY, 3, admin);

        CalendarEvent event = calendar.get(exam.getEventId(), admin);
        assertThat(event.getTitle()).isEqualTo("Klassenarbeit Mathe: Bruchrechnen");
        assertThat(event.isAllDay()).isTrue();
        assertThat(event.getMemberIds()).containsExactly(lena.getId());
        assertThat(exams.list(year.getId())).extracting(SchoolExam::getId).containsExactly(exam.getId());
    }

    @Test
    void blankTitleFallsBackToTheKind() {
        SchoolExam exam = exams.create(year.getId(), null, ExamKind.PARENTS_EVENING, " ", EXAM_DAY, 0, admin);

        assertThat(exam.getTitle()).isEqualTo("Elternabend");
        assertThat(calendar.get(exam.getEventId(), admin).getTitle()).isEqualTo("Elternabend");
    }

    @Test
    void updateMovesTheCalendarEventAndRecreatesAMissingOne() {
        SchoolExam exam = exams.create(year.getId(), math.getId(), ExamKind.TEST, "Zahlen", EXAM_DAY, 0, admin);
        Long eventId = exam.getEventId();

        exams.update(exam.getId(), math.getId(), ExamKind.EXAM, "Brüche", EXAM_DAY.plusDays(2), 2, admin);
        assertThat(calendar.get(eventId, admin).getTitle()).isEqualTo("Klassenarbeit Mathe: Brüche");

        calendar.delete(eventId, admin);
        exams.update(exam.getId(), math.getId(), ExamKind.EXAM, "Brüche", EXAM_DAY, 2, admin);
        Long recreated = exams.list(year.getId()).getFirst().getEventId();
        assertThat(recreated).isNotEqualTo(eventId);
        assertThat(calendar.get(recreated, admin).getTitle()).isEqualTo("Klassenarbeit Mathe: Brüche");
    }

    @Test
    void deleteRemovesTheCalendarEvent() {
        SchoolExam exam = exams.create(year.getId(), null, ExamKind.TRIP, "Zoo", EXAM_DAY, 0, admin);
        Long eventId = exam.getEventId();

        exams.delete(exam.getId(), admin);

        assertThat(exams.list(year.getId())).isEmpty();
        assertThatThrownBy(() -> calendar.get(eventId, admin)).isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void validatesInputAndRights() {
        assertThatThrownBy(() -> exams.create(year.getId(), null, null, "x", EXAM_DAY, 0, admin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exams.create(year.getId(), null, ExamKind.EXAM, "x", null, 0, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Datum");
        assertThatThrownBy(() -> exams.create(year.getId(), null, ExamKind.EXAM, "x", EXAM_DAY, 99, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Lernerinnerung");
        assertThatThrownBy(() -> exams.create(year.getId(), 999_999L, ExamKind.EXAM, "x", EXAM_DAY, 0, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Fach");
        assertThatThrownBy(() -> exams.create(year.getId(), null, ExamKind.EXAM, "x", EXAM_DAY, 0, parent))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void remindsOnTheStudyDayAndTheDayBeforeOnlyOnce() {
        SchoolExam exam = exams.create(year.getId(), math.getId(), ExamKind.EXAM, "Brüche", EXAM_DAY, 3, admin);

        assertThat(reminder.run(EXAM_DAY.minusDays(5))).isZero();
        int studyDay = reminder.run(EXAM_DAY.minusDays(3));
        assertThat(studyDay).isPositive();
        assertThat(reminder.run(EXAM_DAY.minusDays(3))).as("nicht doppelt").isZero();
        assertThat(reminder.run(EXAM_DAY.minusDays(2))).isZero();
        assertThat(reminder.run(EXAM_DAY.minusDays(1))).isPositive();

        List<Notification> mine = notifications.list(parent.getId(), false, 20);
        assertThat(mine).extracting(Notification::getType).containsOnly(NotificationType.SCHOOL_EXAM);
        assertThat(mine).extracting(Notification::getTitle)
                .contains("Klassenarbeit Mathe: Brüche in 3 Tagen", "Klassenarbeit Mathe: Brüche ist morgen");
        assertThat(mine.getFirst().getLink()).isEqualTo("/school/years/" + exam.getYearId() + "/exams");
    }

    @Test
    void noReminderForInactiveYearsOrWithoutStudyDays() {
        exams.create(year.getId(), null, ExamKind.TEST, "Kurz", EXAM_DAY, 0, admin);
        assertThat(reminder.run(EXAM_DAY.minusDays(3))).isZero();
        assertThat(reminder.run(EXAM_DAY)).isPositive();

        SchoolProfile profile = school.profileOf(lena.getId()).orElseThrow();
        school.startYear(profile.getId(), "2027/28", null, "4a", LocalDate.of(2027, 9, 1),
                LocalDate.of(2028, 7, 31));
        assertThat(reminder.run(EXAM_DAY.minusDays(1))).isZero();
    }

    @Test
    void appointmentsWithTimeLocationAndChosenParticipants() {
        FamilyMember mama = members.create("Mama", "#ff0000", parent.getId());
        SchoolExam evening = exams.create(year.getId(), new ExamInput(null, ExamKind.PARENTS_EVENING, null, EXAM_DAY,
                0, LocalTime.of(19, 30), null, "Aula", Set.of(mama.getId(), lena.getId())), admin);

        CalendarEvent event = calendar.get(evening.getEventId(), admin);
        assertThat(event.isAllDay()).isFalse();
        assertThat(event.getLocation()).isEqualTo("Aula");
        assertThat(evening.getEndsAt()).isEqualTo(LocalTime.of(20, 30));
        assertThat(event.getMemberIds()).containsExactlyInAnyOrder(mama.getId(), lena.getId());
    }

    @Test
    void parentsEveningsDefaultToTheParents() {
        FamilyMember papa = members.create("Papa", "#00ff00", admin.getId());

        SchoolExam evening = exams.create(year.getId(), null, ExamKind.PARENTS_EVENING, null, EXAM_DAY, 0, admin);
        SchoolExam trip = exams.create(year.getId(), null, ExamKind.TRIP, "Zoo", EXAM_DAY, 0, admin);

        assertThat(evening.getMemberIds()).containsExactly(papa.getId());
        assertThat(trip.getMemberIds()).containsExactly(lena.getId());
        assertThat(ExamKind.PARENT_TALK.forParents()).isTrue();
        assertThat(ExamKind.TEST.isExam()).isTrue();
    }

    @Test
    void rejectsAnEndWithoutStartAndUnknownParticipants() {
        assertThatThrownBy(() -> exams.create(year.getId(), new ExamInput(null, ExamKind.OTHER, null, EXAM_DAY, 0,
                null, LocalTime.of(10, 0), null, null), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ganztägig");
        assertThatThrownBy(() -> exams.create(year.getId(), new ExamInput(null, ExamKind.OTHER, null, EXAM_DAY, 0,
                null, null, null, Set.of(999_999L)), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Teilnehmende");
    }
}
