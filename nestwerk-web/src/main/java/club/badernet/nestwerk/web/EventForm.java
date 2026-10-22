package club.badernet.nestwerk.web;

import club.badernet.nestwerk.calendar.CalendarEvent;
import club.badernet.nestwerk.calendar.EventInput;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

public class EventForm {

    private String title;
    private String description;
    private String location;
    private boolean allDay;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime startTime;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime endTime;
    private String rrule;
    private boolean privateEvent;
    private boolean absence;
    private boolean noOverlap;
    private Set<Long> memberIds = new HashSet<>();
    private Long categoryId;
    private Long todoId;
    /** "Save anyway" after a warning of blockers. */
    private boolean confirmBlockers;

    public static EventForm forNew(LocalDate date) {
        EventForm f = new EventForm();
        f.startDate = date;
        f.endDate = date;
        f.startTime = LocalTime.of(10, 0);
        f.endTime = LocalTime.of(11, 0);
        return f;
    }

    public static EventForm from(CalendarEvent e) {
        ZoneId zone = ZoneId.of(e.getTimezone());
        EventForm f = new EventForm();
        f.title = e.getTitle();
        f.description = e.getDescription();
        f.location = e.getLocation();
        f.allDay = e.isAllDay();
        f.startDate = e.getStartsAt().atZone(zone).toLocalDate();
        f.startTime = e.getStartsAt().atZone(zone).toLocalTime();
        if (e.isAllDay()) {
            f.endDate = e.getEndsAt().atZone(zone).toLocalDate().minusDays(1);
            f.endTime = null;
            f.startTime = null;
        } else {
            f.endDate = e.getEndsAt().atZone(zone).toLocalDate();
            f.endTime = e.getEndsAt().atZone(zone).toLocalTime();
        }
        f.rrule = e.getRrule();
        f.privateEvent = e.isPrivateEvent();
        f.absence = e.isAbsence();
        f.noOverlap = e.isNoOverlap();
        f.memberIds = new HashSet<>(e.getMemberIds());
        f.categoryId = e.getCategoryId().orElse(null);
        return f;
    }

    public EventInput toInput() {
        return new EventInput(title, description, location, startDate, startTime, endDate, endTime,
                allDay, rrule, privateEvent, memberIds, absence, categoryId, noOverlap);
    }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public boolean isConfirmBlockers() { return confirmBlockers; }
    public void setConfirmBlockers(boolean confirmBlockers) { this.confirmBlockers = confirmBlockers; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public boolean isAllDay() { return allDay; }
    public void setAllDay(boolean allDay) { this.allDay = allDay; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public String getRrule() { return rrule; }
    public void setRrule(String rrule) { this.rrule = rrule; }
    public boolean isPrivateEvent() { return privateEvent; }
    public void setPrivateEvent(boolean privateEvent) { this.privateEvent = privateEvent; }
    public boolean isAbsence() { return absence; }
    public void setAbsence(boolean absence) { this.absence = absence; }
    public boolean isNoOverlap() { return noOverlap; }
    public void setNoOverlap(boolean noOverlap) { this.noOverlap = noOverlap; }
    public Set<Long> getMemberIds() { return memberIds; }
    public void setMemberIds(Set<Long> memberIds) { this.memberIds = memberIds == null ? new HashSet<>() : memberIds; }

    public Long getTodoId() {
        return todoId;
    }

    public void setTodoId(Long todoId) {
        this.todoId = todoId;
    }
}
