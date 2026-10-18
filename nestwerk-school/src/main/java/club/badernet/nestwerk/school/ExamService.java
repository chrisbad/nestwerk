package club.badernet.nestwerk.school;

import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Klassenarbeiten, Tests, Elternabende und Ausflüge; jeder Termin steht auch als ganztägiger Kalendertermin. */
@Service
public class ExamService {

    private final SchoolExamRepository exams;
    private final SchoolYearRepository years;
    private final SchoolProfileRepository profiles;
    private final SchoolSubjectRepository subjects;
    private final CalendarEventService events;
    private final SchoolAccess access;

    public ExamService(SchoolExamRepository exams, SchoolYearRepository years, SchoolProfileRepository profiles,
                       SchoolSubjectRepository subjects, CalendarEventService events, SchoolAccess access) {
        this.exams = exams;
        this.years = years;
        this.profiles = profiles;
        this.subjects = subjects;
        this.events = events;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<SchoolExam> list(Long yearId) {
        return exams.findAllByYearIdOrderByDayAscIdAsc(yearId);
    }

    @Transactional(readOnly = true)
    public List<SchoolExam> upcoming(LocalDate from) {
        return exams.findAllByDayGreaterThanEqual(from);
    }

    @Transactional
    public SchoolExam create(Long yearId, Long subjectId, ExamKind kind, String title, LocalDate day, int studyDays,
                             User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolYear year = years.findById(yearId).orElseThrow(() -> new IllegalArgumentException("Schuljahr nicht gefunden"));
        validate(yearId, subjectId, kind, day, studyDays);
        SchoolExam exam = new SchoolExam(yearId, subjectId, kind, cleanTitle(title, kind), day, studyDays);
        SchoolProfile profile = profiles.findById(year.getProfileId()).orElseThrow();
        exam.linkEvent(events.create(eventFor(exam, profile.getMemberId()), actor).getId());
        return exams.save(exam);
    }

    @Transactional
    public void update(Long id, Long subjectId, ExamKind kind, String title, LocalDate day, int studyDays,
                       User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolExam exam = require(id);
        validate(exam.getYearId(), subjectId, kind, day, studyDays);
        exam.change(subjectId, kind, cleanTitle(title, kind), day, studyDays);
        SchoolYear year = years.findById(exam.getYearId()).orElseThrow();
        Long memberId = profiles.findById(year.getProfileId()).orElseThrow().getMemberId();
        if (exam.getEventId() != null) {
            try {
                events.update(exam.getEventId(), eventFor(exam, memberId), actor);
                return;
            } catch (EventNotFoundException e) {
                // der Kalendertermin wurde inzwischen gelöscht: neu anlegen
            }
        }
        exam.linkEvent(events.create(eventFor(exam, memberId), actor).getId());
    }

    @Transactional
    public void delete(Long id, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolExam exam = require(id);
        if (exam.getEventId() != null) {
            try {
                events.delete(exam.getEventId(), actor);
            } catch (EventNotFoundException e) {
                // Kalendertermin ist schon weg
            }
        }
        exams.delete(exam);
    }

    /** Titel des Kalendertermins, z. B. „Klassenarbeit Mathe: Bruchrechnen“. */
    public String eventTitle(SchoolExam exam) {
        String subject = exam.getSubjectId() == null ? null
                : subjects.findById(exam.getSubjectId()).map(SchoolSubject::getName).orElse(null);
        StringBuilder text = new StringBuilder(exam.getKind().label());
        if (subject != null) {
            text.append(' ').append(subject);
        }
        if (!exam.getTitle().equals(exam.getKind().label())) {
            text.append(": ").append(exam.getTitle());
        }
        return text.toString();
    }

    private EventInput eventFor(SchoolExam exam, Long memberId) {
        return new EventInput(eventTitle(exam), null, null, exam.getDay(), null, exam.getDay(), null, true, null,
                false, Set.of(memberId));
    }

    private void validate(Long yearId, Long subjectId, ExamKind kind, LocalDate day, int studyDays) {
        if (kind == null) {
            throw new IllegalArgumentException("Bitte die Art wählen.");
        }
        if (day == null) {
            throw new IllegalArgumentException("Bitte das Datum angeben.");
        }
        if (studyDays < 0 || studyDays > 60) {
            throw new IllegalArgumentException("Die Lernerinnerung liegt zwischen 0 und 60 Tagen vorher.");
        }
        if (subjectId != null && subjects.findById(subjectId).filter(s -> s.getYearId().equals(yearId)).isEmpty()) {
            throw new IllegalArgumentException("Fach nicht gefunden");
        }
    }

    private static String cleanTitle(String title, ExamKind kind) {
        String clean = title == null ? "" : title.trim();
        if (clean.length() > 150) {
            throw new IllegalArgumentException("Der Titel darf höchstens 150 Zeichen lang sein.");
        }
        return clean.isEmpty() ? kind.label() : clean;
    }

    private SchoolExam require(Long id) {
        return exams.findById(id).orElseThrow(() -> new IllegalArgumentException("Termin nicht gefunden"));
    }
}
