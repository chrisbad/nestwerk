package club.badernet.nestwerk.school;

import club.badernet.nestwerk.calendar.CalendarEventService;
import club.badernet.nestwerk.calendar.EventInput;
import club.badernet.nestwerk.calendar.EventNotFoundException;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exams, tests and school appointments (parents' evening, parent talk, trip …). Each one is also a calendar event
 * for its participants, all-day or with time and location.
 */
@Service
public class ExamService {

    private final SchoolExamRepository exams;
    private final SchoolYearRepository years;
    private final SchoolProfileRepository profiles;
    private final SchoolSubjectRepository subjects;
    private final CalendarEventService events;
    private final SchoolAccess access;
    private final FamilyMemberRepository members;

    public ExamService(SchoolExamRepository exams, SchoolYearRepository years, SchoolProfileRepository profiles,
                       SchoolSubjectRepository subjects, CalendarEventService events, SchoolAccess access,
                       FamilyMemberRepository members) {
        this.exams = exams;
        this.years = years;
        this.profiles = profiles;
        this.subjects = subjects;
        this.events = events;
        this.access = access;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public List<SchoolExam> list(Long yearId) {
        return exams.findAllByYearIdOrderByDayAscIdAsc(yearId);
    }

    @Transactional(readOnly = true)
    public List<SchoolExam> upcoming(LocalDate from) {
        return exams.findAllByDayGreaterThanEqual(from);
    }

    /** Default participants: the parents for parents' evenings and talks, otherwise the child. */
    @Transactional(readOnly = true)
    public Set<Long> defaultMembers(Long yearId, ExamKind kind) {
        Long child = childOf(yearId);
        if (kind != null && kind.forParents()) {
            Set<Long> parents = access.parentMemberIds();
            parents.remove(child);
            if (!parents.isEmpty()) {
                return parents;
            }
        }
        return Set.of(child);
    }

    @Transactional
    public SchoolExam create(Long yearId, Long subjectId, ExamKind kind, String title, LocalDate day, int studyDays,
                             User actor) {
        return create(yearId, ExamInput.allDay(subjectId, kind, title, day, studyDays), actor);
    }

    @Transactional
    public SchoolExam create(Long yearId, ExamInput in, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        years.findById(yearId).orElseThrow(() -> new IllegalArgumentException("Schuljahr nicht gefunden"));
        validate(yearId, in);
        SchoolExam exam = new SchoolExam(yearId, in.subjectId(), in.kind(), cleanTitle(in.title(), in.kind()),
                in.day(), in.studyDays());
        apply(exam, yearId, in);
        exam.linkEvent(events.create(eventFor(exam), actor).getId());
        return exams.save(exam);
    }

    @Transactional
    public void update(Long id, Long subjectId, ExamKind kind, String title, LocalDate day, int studyDays,
                       User actor) {
        SchoolExam exam = require(id);
        update(id, new ExamInput(subjectId, kind, title, day, studyDays, exam.getStartsAt(), exam.getEndsAt(),
                exam.getLocation(), exam.getMemberIds()), actor);
    }

    @Transactional
    public void update(Long id, ExamInput in, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolExam exam = require(id);
        validate(exam.getYearId(), in);
        exam.change(in.subjectId(), in.kind(), cleanTitle(in.title(), in.kind()), in.day(),
                in.studyDays());
        apply(exam, exam.getYearId(), in);
        if (exam.getEventId() != null) {
            try {
                events.update(exam.getEventId(), eventFor(exam), actor);
                return;
            } catch (EventNotFoundException e) {
                // the calendar event was deleted in the meantime: create it again
            }
        }
        exam.linkEvent(events.create(eventFor(exam), actor).getId());
    }

    @Transactional
    public void delete(Long id, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolExam exam = require(id);
        if (exam.getEventId() != null) {
            try {
                events.delete(exam.getEventId(), actor);
            } catch (EventNotFoundException e) {
                // the calendar event is already gone
            }
        }
        exams.delete(exam);
    }

    /** Title of the calendar event, e.g. "Klassenarbeit Mathe: Bruchrechnen". */
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

    private void apply(SchoolExam exam, Long yearId, ExamInput in) {
        exam.schedule(in.startsAt(), in.startsAt() == null ? null
                : in.endsAt() != null ? in.endsAt() : in.startsAt().plusHours(1), cleanLocation(in.location()));
        Set<Long> chosen = in.memberIds() == null || in.memberIds().isEmpty()
                ? defaultMembers(yearId, in.kind()) : new HashSet<>(in.memberIds());
        exam.setMemberIds(chosen);
    }

    private EventInput eventFor(SchoolExam exam) {
        String title = eventTitle(exam);
        if (exam.isAllDay()) {
            return new EventInput(title, null, exam.getLocation(), exam.getDay(), null, exam.getDay(), null, true,
                    null, false, exam.getMemberIds());
        }
        return new EventInput(title, null, exam.getLocation(), exam.getDay(), exam.getStartsAt(), exam.getDay(),
                exam.getEndsAt(), false, null, false, exam.getMemberIds());
    }

    private Long childOf(Long yearId) {
        SchoolYear year = years.findById(yearId)
                .orElseThrow(() -> new IllegalArgumentException("Schuljahr nicht gefunden"));
        return profiles.findById(year.getProfileId()).orElseThrow().getMemberId();
    }

    private void validate(Long yearId, ExamInput in) {
        if (in.kind() == null) {
            throw new IllegalArgumentException("Bitte die Art wählen.");
        }
        if (in.day() == null) {
            throw new IllegalArgumentException("Bitte das Datum angeben.");
        }
        if (in.studyDays() < 0 || in.studyDays() > 60) {
            throw new IllegalArgumentException("Die Lernerinnerung liegt zwischen 0 und 60 Tagen vorher.");
        }
        Long subjectId = in.subjectId();
        if (subjectId != null && subjects.findById(subjectId).filter(s -> s.getYearId().equals(yearId)).isEmpty()) {
            throw new IllegalArgumentException("Fach nicht gefunden");
        }
        LocalTime start = in.startsAt();
        if (start == null && in.endsAt() != null) {
            throw new IllegalArgumentException("Ohne Beginn ist der Termin ganztägig – bitte auch das Ende leeren.");
        }
        if (start != null && in.endsAt() != null && !in.endsAt().isAfter(start)) {
            throw new IllegalArgumentException("Der Termin muss nach seinem Beginn enden.");
        }
        if (in.memberIds() != null && !in.memberIds().isEmpty()
                && members.findAllById(in.memberIds()).size() != in.memberIds().size()) {
            throw new IllegalArgumentException("Teilnehmende nicht gefunden");
        }
    }

    private static String cleanLocation(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String clean = location.trim();
        if (clean.length() > 150) {
            throw new IllegalArgumentException("Der Ort darf höchstens 150 Zeichen lang sein.");
        }
        return clean;
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
