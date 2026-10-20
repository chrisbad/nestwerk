package club.badernet.nestwerk.school;

import club.badernet.nestwerk.blocker.BlockerBands.Band;
import club.badernet.nestwerk.blocker.CalendarBandSource;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * School holidays and cancelled lessons of the children with an active school year as calendar bands, for viewers
 * with school access. Substitutions and room changes are left out; they do not affect the family's planning. The
 * bands keep the colour of their kind so that they stand out from the school blocker in the child's colour.
 */
@Component
public class SchoolCalendarBands implements CalendarBandSource {

    static final String HOLIDAY = "holiday";
    static final String CANCELLED = "cancel";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final String ALL_DAY = "ganztägig";

    private final SchoolProfileRepository profiles;
    private final SchoolYearRepository years;
    private final SchoolSubjectRepository subjects;
    private final LessonRepository lessons;
    private final LessonChangeRepository changes;
    private final SchoolHolidayRepository holidays;
    private final FamilyMemberRepository members;
    private final SchoolAccess access;

    public SchoolCalendarBands(SchoolProfileRepository profiles, SchoolYearRepository years,
                               SchoolSubjectRepository subjects, LessonRepository lessons,
                               LessonChangeRepository changes, SchoolHolidayRepository holidays,
                               FamilyMemberRepository members, SchoolAccess access) {
        this.profiles = profiles;
        this.years = years;
        this.subjects = subjects;
        this.lessons = lessons;
        this.changes = changes;
        this.holidays = holidays;
        this.members = members;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<LocalDate, List<Band>> bands(LocalDate from, LocalDate to, User viewer) {
        Map<LocalDate, List<Band>> result = new TreeMap<>();
        if (!access.has(viewer, SchoolPermission.SCHOOL_VIEW)) {
            return result;
        }
        Map<Long, FamilyMember> people = members.findAll().stream()
                .collect(Collectors.toMap(FamilyMember::getId, Function.identity()));
        List<Child> children = new ArrayList<>();
        for (SchoolProfile profile : profiles.findAll()) {
            FamilyMember member = people.get(profile.getMemberId());
            years.findByProfileIdAndActiveTrue(profile.getId())
                    .filter(y -> member != null)
                    .ifPresent(year -> children.add(new Child(member, profile.getState(), year)));
        }
        addHolidays(children, from, to, result);
        children.forEach(child -> addCancellations(child, from, to, result));
        return result;
    }

    private record Child(FamilyMember member, String state, SchoolYear year) {
    }

    /** One band per holiday and day; children of the same state share it. */
    private void addHolidays(List<Child> children, LocalDate from, LocalDate to, Map<LocalDate, List<Band>> result) {
        Map<String, List<FamilyMember>> byState = new LinkedHashMap<>();
        children.stream().filter(c -> c.state() != null)
                .forEach(c -> byState.computeIfAbsent(c.state(), s -> new ArrayList<>()).add(c.member()));
        byState.forEach((state, who) -> {
            String names = who.stream().map(FamilyMember::getName).sorted().collect(Collectors.joining(", "));
            for (SchoolHoliday holiday : holidays.findOverlapping(state, from, to)) {
                LocalDate first = holiday.getStartsOn().isBefore(from) ? from : holiday.getStartsOn();
                LocalDate last = holiday.getEndsOn().isAfter(to) ? to : holiday.getEndsOn();
                for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                    result.computeIfAbsent(day, d -> new ArrayList<>())
                            .add(new Band(names + " · " + holiday.getName(), ALL_DAY, HOLIDAY, null));
                }
            }
        });
    }

    private void addCancellations(Child child, LocalDate from, LocalDate to, Map<LocalDate, List<Band>> result) {
        SchoolYear year = child.year();
        List<Lesson> all = lessons.findAllByYearIdOrderByWeekdayAscStartsAtAsc(year.getId());
        if (all.isEmpty()) {
            return;
        }
        Map<LocalDate, Map<Long, LessonChange>> byDay = changes
                .findInRange(all.stream().map(Lesson::getId).toList(), from, to).stream()
                .filter(c -> c.getKind() == ChangeKind.CANCELLED)
                .collect(Collectors.groupingBy(LessonChange::getDay,
                        Collectors.toMap(LessonChange::getLessonId, Function.identity())));
        if (byDay.isEmpty()) {
            return;
        }
        Map<Long, SchoolSubject> subjectsById = subjects.findAllByYearIdOrderByNameAsc(year.getId()).stream()
                .collect(Collectors.toMap(SchoolSubject::getId, Function.identity()));
        byDay.forEach((day, dayChanges) -> {
            List<Timetable.Entry> entries = Timetable.day(day, year, all, subjectsById, dayChanges);
            for (Band band : cancellationBands(child.member(), entries)) {
                result.computeIfAbsent(day, d -> new ArrayList<>()).add(band);
            }
        });
    }

    /**
     * Summarises the cancelled lessons of a day: everything cancelled, a later start, an earlier end, and single
     * lessons in between.
     */
    static List<Band> cancellationBands(FamilyMember member, List<Timetable.Entry> entries) {
        List<Band> bands = new ArrayList<>();
        if (entries.stream().noneMatch(Timetable.Entry::cancelled)) {
            return bands;
        }
        String who = member.getName() + " · ";
        if (entries.stream().allMatch(Timetable.Entry::cancelled)) {
            bands.add(new Band(who + "Unterricht entfällt", ALL_DAY, CANCELLED, null));
            return bands;
        }
        int first = 0;
        while (entries.get(first).cancelled()) {
            first++;
        }
        int last = entries.size() - 1;
        while (entries.get(last).cancelled()) {
            last--;
        }
        if (first > 0) {
            String start = TIME.format(entries.get(first).lesson().getStartsAt());
            bands.add(new Band(who + "Beginn erst " + start, "ab " + start, CANCELLED, null));
        }
        for (int i = first + 1; i < last; i++) {
            Timetable.Entry entry = entries.get(i);
            if (entry.cancelled()) {
                bands.add(new Band(who + entry.subject().getName() + " entfällt" + period(entry), times(entry),
                        CANCELLED, null));
            }
        }
        if (last < entries.size() - 1) {
            String end = TIME.format(entries.get(last).lesson().getEndsAt());
            bands.add(new Band(who + "frei ab " + end, "ab " + end, CANCELLED, null));
        }
        return bands;
    }

    private static String period(Timetable.Entry entry) {
        Lesson lesson = entry.lesson();
        if (!lesson.inPeriods()) {
            return "";
        }
        return lesson.getPeriodFrom().equals(lesson.getPeriodTo()) ? " (" + lesson.getPeriodFrom() + ".)"
                : " (" + lesson.getPeriodFrom() + ".–" + lesson.getPeriodTo() + ".)";
    }

    private static String times(Timetable.Entry entry) {
        return TIME.format(entry.lesson().getStartsAt()) + "–" + TIME.format(entry.lesson().getEndsAt());
    }
}
