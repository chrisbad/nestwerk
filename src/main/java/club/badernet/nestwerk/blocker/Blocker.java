package club.badernet.nestwerk.blocker;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Eine wiederkehrende Zeit, in der Personen nicht (oder nur nach Rückfrage) eingeplant werden: an den gewählten
 * Wochentagen von {@code startTime} bis {@code endTime}; liegt das Ende vor dem Start, reicht sie über Mitternacht.
 */
@Entity
@Table(name = "blockers")
public class Blocker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BlockerKind kind;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Convert(converter = WeekdaysConverter.class)
    @Column(nullable = false)
    private Set<DayOfWeek> weekdays = EnumSet.noneOf(DayOfWeek.class);

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BlockerStrength strength;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BlockerVisibility visibility;

    @Column(nullable = false)
    private boolean quiet;

    @Column(name = "holidays_off", nullable = false)
    private boolean holidaysOff;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "blocker_members", joinColumns = @JoinColumn(name = "blocker_id"))
    @Column(name = "member_id", nullable = false)
    private Set<Long> memberIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "blocker_planners", joinColumns = @JoinColumn(name = "blocker_id"))
    @Column(name = "member_id", nullable = false)
    private Set<Long> plannerIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "blocker_skips", joinColumns = @JoinColumn(name = "blocker_id"))
    @Column(name = "day", nullable = false)
    private Set<LocalDate> skips = new TreeSet<>();

    protected Blocker() {
    }

    public Blocker(String title, BlockerKind kind, LocalTime startTime, LocalTime endTime, Set<DayOfWeek> weekdays,
                   LocalDate validFrom, LocalDate validUntil, BlockerStrength strength, BlockerVisibility visibility,
                   boolean quiet, boolean holidaysOff, Long createdBy) {
        this.createdBy = createdBy;
        update(title, kind, startTime, endTime, weekdays, validFrom, validUntil, strength, visibility, quiet,
                holidaysOff);
    }

    public void update(String title, BlockerKind kind, LocalTime startTime, LocalTime endTime,
                       Set<DayOfWeek> weekdays, LocalDate validFrom, LocalDate validUntil, BlockerStrength strength,
                       BlockerVisibility visibility, boolean quiet, boolean holidaysOff) {
        this.title = title;
        this.kind = kind;
        this.startTime = startTime;
        this.endTime = endTime;
        this.weekdays = weekdays.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weekdays);
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.strength = strength;
        this.visibility = visibility;
        this.quiet = quiet;
        this.holidaysOff = holidaysOff;
    }

    /** Endet am Folgetag (z. B. Schlaf 22:00–06:30). */
    public boolean isOvernight() {
        return !endTime.isAfter(startTime);
    }

    /** Ob der Blocker an diesem Tag beginnt (ohne Feiertage, die prüft {@link BlockerSchedule}). */
    public boolean startsOn(LocalDate day) {
        return weekdays.contains(day.getDayOfWeek()) && !day.isBefore(validFrom)
                && (validUntil == null || !day.isAfter(validUntil)) && !skips.contains(day);
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public BlockerKind getKind() { return kind; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public Set<DayOfWeek> getWeekdays() { return weekdays; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidUntil() { return validUntil; }
    public BlockerStrength getStrength() { return strength; }
    public BlockerVisibility getVisibility() { return visibility; }
    public boolean isQuiet() { return quiet; }
    public boolean isHolidaysOff() { return holidaysOff; }
    public Long getCreatedBy() { return createdBy; }
    public Set<Long> getMemberIds() { return memberIds; }
    public Set<Long> getPlannerIds() { return plannerIds; }
    public Set<LocalDate> getSkips() { return skips; }

    public void setMemberIds(Set<Long> memberIds) {
        this.memberIds.clear();
        this.memberIds.addAll(memberIds);
    }

    public void setPlannerIds(Set<Long> plannerIds) {
        this.plannerIds.clear();
        this.plannerIds.addAll(plannerIds);
    }

    public void setSkips(Set<LocalDate> skips) {
        this.skips.clear();
        this.skips.addAll(skips);
    }
}
