package club.badernet.nestwerk.household;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Vorlage einer Hausarbeit: wann sie fällig wird, wie lange sie je nach Aufwand dauert und wer sie bekommt. Der
 * Aufwand selbst wird erst beim Buchen gewählt.
 */
@Entity
@Table(name = "chores")
public class Chore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 100)
    private String area;

    @Column(length = 2000)
    private String description;

    @Column(name = "minutes_easy", nullable = false)
    private int minutesEasy;

    @Column(name = "minutes_medium", nullable = false)
    private int minutesMedium;

    @Column(name = "minutes_hard", nullable = false)
    private int minutesHard;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChoreKind kind;

    @Column(length = 500)
    private String rrule;

    @Column(name = "starts_on")
    private LocalDate startsOn;

    @Column(name = "due_time")
    private LocalTime dueTime;

    @Column(name = "interval_days")
    private Integer intervalDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Distribution distribution;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "ical_url", length = 500)
    private String icalUrl;

    @Column(name = "ical_filter", length = 100)
    private String icalFilter;

    @Column(name = "ical_days_before", nullable = false)
    private int icalDaysBefore;

    @Column(name = "ical_time")
    private LocalTime icalTime;

    @Column(name = "ical_synced_at")
    private Instant icalSyncedAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "chore_members", joinColumns = @JoinColumn(name = "chore_id"))
    @OrderColumn(name = "position")
    @Column(name = "member_id")
    private List<Long> memberIds = new ArrayList<>();

    protected Chore() {
    }

    public Chore(String title, Long createdBy) {
        this.title = title;
        this.createdBy = createdBy;
    }

    /** Übernimmt eine bereits geprüfte Eingabe. */
    void apply(ChoreInput in) {
        this.title = in.title();
        this.area = in.area();
        this.description = in.description();
        this.minutesEasy = in.minutesEasy();
        this.minutesMedium = in.minutesMedium();
        this.minutesHard = in.minutesHard();
        this.kind = in.kind();
        this.rrule = in.rrule();
        this.startsOn = in.startsOn();
        this.dueTime = in.dueTime();
        this.intervalDays = in.intervalDays();
        this.distribution = in.distribution();
        this.memberIds.clear();
        this.memberIds.addAll(in.memberIds());
    }

    void setActive(boolean active) {
        this.active = active;
    }

    void configureIcal(String url, String filter, int daysBefore, LocalTime time) {
        this.icalUrl = url;
        this.icalFilter = filter;
        this.icalDaysBefore = daysBefore;
        this.icalTime = time;
    }

    void markIcalSynced(Instant at) {
        this.icalSyncedAt = at;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getArea() { return area; }
    public String getDescription() { return description; }
    public int getMinutesEasy() { return minutesEasy; }
    public int getMinutesMedium() { return minutesMedium; }
    public int getMinutesHard() { return minutesHard; }
    public ChoreKind getKind() { return kind; }
    public String getRrule() { return rrule; }
    public LocalDate getStartsOn() { return startsOn; }
    public LocalTime getDueTime() { return dueTime; }
    public Integer getIntervalDays() { return intervalDays; }
    public Distribution getDistribution() { return distribution; }
    public boolean isActive() { return active; }
    public String getIcalUrl() { return icalUrl; }
    public String getIcalFilter() { return icalFilter; }
    public int getIcalDaysBefore() { return icalDaysBefore; }
    public LocalTime getIcalTime() { return icalTime; }
    public Instant getIcalSyncedAt() { return icalSyncedAt; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }

    /** Zeitaufwand für den gewählten Aufwandsgrad. */
    public int minutesFor(ChoreEffort effort) {
        return switch (effort) {
            case EASY -> minutesEasy;
            case MEDIUM -> minutesMedium;
            case HARD -> minutesHard;
        };
    }

    /** Planwert (mittlerer Aufwand) für Wochensummen und ausgleichende Verteilung. */
    public int getPlanMinutes() {
        return minutesMedium;
    }

    /** Spanne der Zeitaufwände, z. B. „10–30“. */
    public String minutesRange() {
        int min = Math.min(minutesEasy, Math.min(minutesMedium, minutesHard));
        int max = Math.max(minutesEasy, Math.max(minutesMedium, minutesHard));
        return min == max ? String.valueOf(min) : min + "–" + max;
    }

    /** Kandidaten in Reihenfolge; Lücken durch gelöschte Mitglieder fallen weg. */
    public List<Long> getMemberIds() {
        return memberIds.stream().filter(Objects::nonNull).toList();
    }
}
