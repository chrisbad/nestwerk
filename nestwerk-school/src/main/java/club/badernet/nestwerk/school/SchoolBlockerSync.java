package club.badernet.nestwerk.school;

import club.badernet.nestwerk.blocker.Blocker;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerRepository;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BlockerVisibility;
import club.badernet.nestwerk.blocker.BlockersChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hält die Blocker der Schulzeit aktuell: je Wochentag und Zeitraum ein Blocker für das aktive Schuljahr. Tage ohne
 * Schule (Ferien, Feiertage, Totalausfall, A/B-Wechsel) stehen als ausgenommene Tage im Blocker. Die Blocker werden bei
 * jeder Änderung neu erzeugt.
 */
@Component
public class SchoolBlockerSync {

    private final SchoolYearRepository years;
    private final SchoolProfileRepository profiles;
    private final LessonRepository lessons;
    private final SchoolSubjectRepository subjects;
    private final LessonChangeRepository changes;
    private final BlockerRepository blockers;
    private final SchoolDays days;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher publisher;

    public SchoolBlockerSync(SchoolYearRepository years, SchoolProfileRepository profiles, LessonRepository lessons,
                             SchoolSubjectRepository subjects, LessonChangeRepository changes,
                             BlockerRepository blockers, SchoolDays days, JdbcTemplate jdbc,
                             ApplicationEventPublisher publisher) {
        this.years = years;
        this.profiles = profiles;
        this.lessons = lessons;
        this.subjects = subjects;
        this.changes = changes;
        this.blockers = blockers;
        this.days = days;
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    @Transactional
    public void sync(Long yearId) {
        List<Long> old = jdbc.queryForList("SELECT blocker_id FROM school_blocker WHERE year_id = ?", Long.class,
                yearId);
        if (!old.isEmpty()) {
            blockers.deleteAllById(old);
            blockers.flush();
        }
        SchoolYear year = years.findById(yearId).orElse(null);
        boolean created = false;
        if (year != null && year.isActive()) {
            SchoolProfile profile = profiles.findById(year.getProfileId()).orElseThrow();
            for (Blocker blocker : build(year, profile)) {
                Blocker saved = blockers.save(blocker);
                jdbc.update("INSERT INTO school_blocker (blocker_id, year_id) VALUES (?, ?)", saved.getId(), yearId);
                created = true;
            }
        }
        if (created || !old.isEmpty()) {
            publisher.publishEvent(new BlockersChangedEvent());
        }
    }

    /** Alle aktiven Jahre neu abgleichen, z. B. nach geänderten Ferien. */
    @Transactional
    public void syncAllActive() {
        years.findAll().stream().filter(SchoolYear::isActive).forEach(y -> sync(y.getId()));
    }

    private List<Blocker> build(SchoolYear year, SchoolProfile profile) {
        List<Lesson> yearLessons = lessons.findAllByYearIdOrderByWeekdayAscStartsAtAsc(year.getId());
        if (yearLessons.isEmpty()) {
            return List.of();
        }
        Map<Long, SchoolSubject> subjectsById = subjects.findAllByYearIdOrderByNameAsc(year.getId()).stream()
                .collect(Collectors.toMap(SchoolSubject::getId, Function.identity()));
        Set<Long> lessonIds = yearLessons.stream().map(Lesson::getId).collect(Collectors.toSet());
        Map<LocalDate, Map<Long, LessonChange>> changesByDay = new LinkedHashMap<>();
        changes.findInRange(lessonIds, year.getStartsOn(), year.getEndsOn())
                .forEach(c -> changesByDay.computeIfAbsent(c.getDay(), d -> new LinkedHashMap<>())
                        .put(c.getLessonId(), c));

        record Key(DayOfWeek weekday, LocalTime start, LocalTime end) {
        }
        Map<Key, Set<LocalDate>> groups = new LinkedHashMap<>();
        Map<DayOfWeek, Set<LocalDate>> allDays = new LinkedHashMap<>();
        for (LocalDate cursor = year.getStartsOn(); !cursor.isAfter(year.getEndsOn()); cursor = cursor.plusDays(1)) {
            final LocalDate day = cursor;
            allDays.computeIfAbsent(day.getDayOfWeek(), d -> new TreeSet<>()).add(day);
            if (days.isFree(day, profile.getState())) {
                continue;
            }
            var entries = Timetable.day(day, year, yearLessons, subjectsById,
                    changesByDay.getOrDefault(day, Map.of()));
            Timetable.span(entries).ifPresent(span -> groups
                    .computeIfAbsent(new Key(day.getDayOfWeek(), span.start(), span.end()), k -> new TreeSet<>())
                    .add(day));
        }
        List<Blocker> result = new java.util.ArrayList<>();
        for (Map.Entry<Key, Set<LocalDate>> group : groups.entrySet()) {
            Key key = group.getKey();
            Set<LocalDate> skips = new HashSet<>(allDays.get(key.weekday()));
            skips.removeAll(group.getValue());
            Blocker blocker = new Blocker("Schule", BlockerKind.SCHOOL, key.start(), key.end(), Set.of(key.weekday()),
                    year.getStartsOn(), year.getEndsOn(), BlockerStrength.WARN, BlockerVisibility.TITLE, false, false,
                    null);
            blocker.setMemberIds(Set.of(profile.getMemberId()));
            blocker.setPlannerIds(Set.of());
            blocker.setSkips(skips);
            result.add(blocker);
        }
        return result;
    }
}
