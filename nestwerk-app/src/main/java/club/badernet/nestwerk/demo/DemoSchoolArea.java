package club.badernet.nestwerk.demo;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactInput;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.school.ExamKind;
import club.badernet.nestwerk.school.ExamService;
import club.badernet.nestwerk.school.GradeKind;
import club.badernet.nestwerk.school.GradeService;
import club.badernet.nestwerk.school.HomeworkService;
import club.badernet.nestwerk.school.LessonInput;
import club.badernet.nestwerk.school.SchoolContacts;
import club.badernet.nestwerk.school.SchoolProfile;
import club.badernet.nestwerk.school.SchoolService;
import club.badernet.nestwerk.school.SchoolSubject;
import club.badernet.nestwerk.school.SchoolYear;
import club.badernet.nestwerk.school.TimetableService;
import club.badernet.nestwerk.school.WeekKind;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** One school profile with timetable, exams, grades and homework per child. */
@Component
class DemoSchoolArea implements DemoArea {

    private static final String STATE = "BW";
    private static final int SCHOOL_YEAR_START_MONTH = 8;
    private static final int GRADE_WEIGHT_PERCENT = 100;
    private static final int GRADE_WEIGHT_TEST_PERCENT = 200;
    private static final int FIRST_TERM = 1;
    private static final int STUDY_DAYS = 3;

    private static final List<LessonSlot> DAILY_SLOTS = List.of(
            new LessonSlot("08:00", "08:45"), new LessonSlot("08:50", "09:35"),
            new LessonSlot("09:55", "10:40"), new LessonSlot("10:45", "11:30"));

    private final SchoolService school;
    private final TimetableService timetable;
    private final ExamService exams;
    private final GradeService grades;
    private final HomeworkService homework;
    private final ContactService contacts;

    DemoSchoolArea(SchoolService school, TimetableService timetable, ExamService exams, GradeService grades,
                   HomeworkService homework, ContactService contacts) {
        this.school = school;
        this.timetable = timetable;
        this.exams = exams;
        this.grades = grades;
        this.homework = homework;
        this.contacts = contacts;
    }

    @Override
    public String name() {
        return "school";
    }

    @Override
    public void seed(DemoContext context) {
        Contact schoolContact = contacts.create(new ContactInput("Gemeinschaftsschule Remchingen",
                SchoolContacts.SCHOOL, null, "07232 301 00", "sekretariat@schule-remchingen.example",
                "Schulstraße 1", "75196", "Remchingen", null, "Sekretariat: Mo–Fr 7:30–12:00 Uhr"),
                context.actor());
        seedChild(context, context.family().daughter(), schoolContact, "5b",
                List.of("Mathematik", "Deutsch", "Englisch", "Biologie"));
        seedChild(context, context.family().son(), schoolContact, "3a",
                List.of("Mathematik", "Deutsch", "Sachunterricht", "Sport"));
    }

    private void seedChild(DemoContext context, FamilyMember child, Contact schoolContact, String className,
                           List<String> subjectNames) {
        LocalDate today = context.today();
        int startYear = today.getMonthValue() >= SCHOOL_YEAR_START_MONTH ? today.getYear() : today.getYear() - 1;
        SchoolProfile profile = school.createProfile(child.getId(), STATE);
        SchoolYear year = school.startYear(profile.getId(), startYear + "/" + (startYear + 1) % 100,
                schoolContact.getId(), className, LocalDate.of(startYear, 9, 14), LocalDate.of(startYear + 1, 7, 30));

        List<SchoolSubject> subjects = subjectNames.stream()
                .map(subjectName -> timetable.addSubject(year.getId(), subjectName, null, context.actor()))
                .toList();
        addTeachers(context, subjects);
        addLessons(context, year, subjects);
        addExamsAndGrades(context, year, subjects);
        homework.create(year.getId(), subjects.get(0).getId(), "Arbeitsblatt Seite 42 lösen",
                "Aufgaben 1 bis 5, Rechenweg aufschreiben.", today.plusDays(2), null, context.actor());
        homework.create(year.getId(), subjects.get(1).getId(), "Lesetagebuch weiterführen", null,
                today.plusDays(5), null, context.actor());
    }

    private void addTeachers(DemoContext context, List<SchoolSubject> subjects) {
        Map<Integer, String> teachers = Map.of(0, "Frau Hoffmann", 1, "Herr Becker", 2, "Frau Lang", 3, "Herr Roth");
        teachers.forEach((index, teacher) -> timetable.addTeacher(subjects.get(index).getId(), teacher,
                null, context.actor()));
    }

    private void addLessons(DemoContext context, SchoolYear year, List<SchoolSubject> subjects) {
        DayOfWeek[] days = {DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY};
        for (int dayIndex = 0; dayIndex < days.length; dayIndex++) {
            for (int slotIndex = 0; slotIndex < DAILY_SLOTS.size(); slotIndex++) {
                LessonSlot slot = DAILY_SLOTS.get(slotIndex);
                SchoolSubject subject = subjects.get((dayIndex + slotIndex) % subjects.size());
                timetable.addLesson(year.getId(), LessonInput.timed(subject.getId(), days[dayIndex],
                        LocalTime.parse(slot.from()), LocalTime.parse(slot.to()), "Raum " + (101 + slotIndex),
                        WeekKind.EVERY), context.actor());
            }
        }
    }

    private void addExamsAndGrades(DemoContext context, SchoolYear year, List<SchoolSubject> subjects) {
        LocalDate today = context.today();
        exams.create(year.getId(), subjects.get(0).getId(), ExamKind.EXAM, "Klassenarbeit Bruchrechnen",
                today.plusDays(12), STUDY_DAYS, context.actor());
        exams.create(year.getId(), subjects.get(1).getId(), ExamKind.TEST, "Diktat", today.plusDays(6), 2,
                context.actor());
        grades.add(year.getId(), subjects.get(0).getId(), FIRST_TERM, "2+", GRADE_WEIGHT_TEST_PERCENT,
                GradeKind.WRITTEN, today.minusDays(20), "Klassenarbeit", context.actor());
        grades.add(year.getId(), subjects.get(0).getId(), FIRST_TERM, "3", GRADE_WEIGHT_PERCENT, GradeKind.ORAL,
                today.minusDays(9), "Mitarbeit", context.actor());
        grades.add(year.getId(), subjects.get(1).getId(), FIRST_TERM, "2", GRADE_WEIGHT_PERCENT, GradeKind.WRITTEN,
                today.minusDays(14), "Aufsatz", context.actor());
        grades.add(year.getId(), subjects.get(2).getId(), FIRST_TERM, "1-", GRADE_WEIGHT_PERCENT, GradeKind.ORAL,
                today.minusDays(4), "Vokabeltest", context.actor());
    }

    private record LessonSlot(String from, String to) {
    }
}
