package club.badernet.nestwerk.web;

import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.Distribution;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Formularobjekt für Hausarbeiten; die Kandidaten kommen in der Reihenfolge der Auswahl. */
public class ChoreForm {

    private String title;
    private String area;
    private String description;
    private Integer minutesEasy = 10;
    private Integer minutesMedium = 15;
    private Integer minutesHard = 30;
    private ChoreKind kind = ChoreKind.RECURRING;
    private String rrule = "FREQ=WEEKLY";
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startsOn;
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime dueTime;
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime windowStart;
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime windowEnd;
    private Integer intervalDays = 7;
    private Distribution distribution = Distribution.ROTATION;
    private List<Long> memberIds = new ArrayList<>();

    public static ChoreForm forNew() {
        return new ChoreForm();
    }

    public static ChoreForm from(Chore c) {
        ChoreForm f = new ChoreForm();
        f.title = c.getTitle();
        f.area = c.getArea();
        f.description = c.getDescription();
        f.minutesEasy = c.getMinutesEasy();
        f.minutesMedium = c.getMinutesMedium();
        f.minutesHard = c.getMinutesHard();
        f.kind = c.getKind();
        f.rrule = c.getRrule();
        f.startsOn = c.getStartsOn();
        f.dueTime = c.getDueTime();
        f.windowStart = c.getWindowStart();
        f.windowEnd = c.getWindowEnd();
        f.intervalDays = c.getIntervalDays();
        f.distribution = c.getDistribution();
        f.memberIds = new ArrayList<>(c.getMemberIds());
        return f;
    }

    public ChoreInput toInput() {
        return new ChoreInput(title, area, description, minutesEasy, minutesMedium, minutesHard, kind, rrule, startsOn,
                dueTime, intervalDays, distribution, memberIds, windowStart, windowEnd);
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getMinutesEasy() { return minutesEasy; }
    public void setMinutesEasy(Integer minutesEasy) { this.minutesEasy = minutesEasy; }
    public Integer getMinutesMedium() { return minutesMedium; }
    public void setMinutesMedium(Integer minutesMedium) { this.minutesMedium = minutesMedium; }
    public Integer getMinutesHard() { return minutesHard; }
    public void setMinutesHard(Integer minutesHard) { this.minutesHard = minutesHard; }
    public ChoreKind getKind() { return kind; }
    public void setKind(ChoreKind kind) { this.kind = kind; }
    public String getRrule() { return rrule; }
    public void setRrule(String rrule) { this.rrule = rrule; }
    public LocalDate getStartsOn() { return startsOn; }
    public void setStartsOn(LocalDate startsOn) { this.startsOn = startsOn; }
    public LocalTime getDueTime() { return dueTime; }
    public LocalTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalTime windowStart) { this.windowStart = windowStart; }
    public LocalTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalTime windowEnd) { this.windowEnd = windowEnd; }
    public void setDueTime(LocalTime dueTime) { this.dueTime = dueTime; }
    public Integer getIntervalDays() { return intervalDays; }
    public void setIntervalDays(Integer intervalDays) { this.intervalDays = intervalDays; }
    public Distribution getDistribution() { return distribution; }
    public void setDistribution(Distribution distribution) { this.distribution = distribution; }
    public List<Long> getMemberIds() { return memberIds; }
    public void setMemberIds(List<Long> memberIds) { this.memberIds = memberIds == null ? new ArrayList<>() : memberIds; }
}
