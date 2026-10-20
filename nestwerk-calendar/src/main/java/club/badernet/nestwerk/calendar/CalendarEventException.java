package club.badernet.nestwerk.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "calendar_event_exceptions")
public class CalendarEventException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private CalendarEvent event;

    @Column(name = "recurrence_id", nullable = false)
    private Instant recurrenceId;

    @Column(nullable = false)
    private boolean cancelled;

    @Column(length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 255)
    private String location;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    protected CalendarEventException() {
    }

    CalendarEventException(CalendarEvent event, Instant recurrenceId) {
        this.event = event;
        this.recurrenceId = recurrenceId;
    }

    void referTo(Instant recurrenceId) {
        this.recurrenceId = recurrenceId;
    }

    void cancel() {
        this.cancelled = true;
    }

    void override(String title, String description, String location, Instant startsAt, Instant endsAt) {
        this.cancelled = false;
        this.title = title;
        this.description = description;
        this.location = location;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public Instant getRecurrenceId() { return recurrenceId; }
    public boolean isCancelled() { return cancelled; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getLocation() { return location; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
}
