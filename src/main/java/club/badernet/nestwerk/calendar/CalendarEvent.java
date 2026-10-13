package club.badernet.nestwerk.calendar;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Entity
@Table(name = "calendar_events")
public class CalendarEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String uid;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 255)
    private String location;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(length = 500)
    private String rrule;

    @Column(name = "private_event", nullable = false)
    private boolean privateEvent;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "calendar_event_members", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "member_id", nullable = false)
    private Set<Long> memberIds = new HashSet<>();

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<CalendarEventException> exceptions = new HashSet<>();

    protected CalendarEvent() {
    }

    public CalendarEvent(String uid, String title, Instant startsAt, Instant endsAt,
                         boolean allDay, String timezone, Long createdBy) {
        this.uid = uid;
        this.title = title;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.allDay = allDay;
        this.timezone = timezone;
        this.createdBy = createdBy;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUid() { return uid; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getLocation() { return location; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public boolean isAllDay() { return allDay; }
    public String getTimezone() { return timezone; }
    public String getRrule() { return rrule; }
    public boolean isPrivateEvent() { return privateEvent; }
    public Long getCreatedBy() { return createdBy; }
    public int getSequence() { return sequence; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Set<Long> getMemberIds() { return Set.copyOf(memberIds); }
    public Set<CalendarEventException> getExceptions() { return Set.copyOf(exceptions); }

    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setLocation(String location) { this.location = location; }
    public void setRrule(String rrule) { this.rrule = rrule; }
    public void setPrivateEvent(boolean privateEvent) { this.privateEvent = privateEvent; }

    public void setTimes(Instant startsAt, Instant endsAt, boolean allDay) {
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.allDay = allDay;
    }

    public void setMemberIds(Set<Long> ids) {
        memberIds.clear();
        memberIds.addAll(ids);
    }

    /** Erhöht die iCal-SEQUENCE; bei jeder inhaltlichen Änderung aufzurufen. */
    public void markChanged() {
        sequence++;
    }

    public void clearExceptions() {
        exceptions.clear();
    }

    public Optional<CalendarEventException> exceptionFor(Instant recurrenceId) {
        return exceptions.stream().filter(e -> e.getRecurrenceId().equals(recurrenceId)).findFirst();
    }

    public void cancelOccurrence(Instant recurrenceId) {
        CalendarEventException ex = exceptionOrNew(recurrenceId);
        ex.cancel();
    }

    public void overrideOccurrence(Instant recurrenceId, String title, String description,
                                   String location, Instant startsAt, Instant endsAt) {
        CalendarEventException ex = exceptionOrNew(recurrenceId);
        ex.override(title, description, location, startsAt, endsAt);
    }

    private CalendarEventException exceptionOrNew(Instant recurrenceId) {
        return exceptionFor(recurrenceId).orElseGet(() -> {
            CalendarEventException created = new CalendarEventException(this, recurrenceId);
            exceptions.add(created);
            return created;
        });
    }
}
