package club.badernet.nestwerk.web;

import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreEffort;
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
    private Integer minutes = 15;
    private ChoreEffort effort = ChoreEffort.MEDIUM;
    private ChoreKind kind = ChoreKind.RECURRING;
    private String rrule = "FREQ=WEEKLY";
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startsOn;
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime dueTime;
    private Integer intervalDays = 7;
    private Distribution distribution = Distribution.ROTATION;
    private List<Long> memberIds = new ArrayList<>();

    public static ChoreForm forNew(LocalDate today) {
        ChoreForm f = new ChoreForm();
        f.startsOn = today;
        return f;
    }

    public static ChoreForm from(Chore c) {
        ChoreForm f = new ChoreForm();
        f.title = c.getTitle();
        f.area = c.getArea();
        f.description = c.getDescription();
        f.minutes = c.getMinutes();
        f.effort = c.getEffort();
        f.kind = c.getKind();
        f.rrule = c.getRrule();
        f.startsOn = c.getStartsOn();
        f.dueTime = c.getDueTime();
        f.intervalDays = c.getIntervalDays();
        f.distribution = c.getDistribution();
        f.memberIds = new ArrayList<>(c.getMemberIds());
        return f;
    }

    public ChoreInput toInput() {
        return new ChoreInput(title, area, description, minutes, effort, kind, rrule, startsOn, dueTime, intervalDays,
                distribution, memberIds);
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getMinutes() { return minutes; }
    public void setMinutes(Integer minutes) { this.minutes = minutes; }
    public ChoreEffort getEffort() { return effort; }
    public void setEffort(ChoreEffort effort) { this.effort = effort; }
    public ChoreKind getKind() { return kind; }
    public void setKind(ChoreKind kind) { this.kind = kind; }
    public String getRrule() { return rrule; }
    public void setRrule(String rrule) { this.rrule = rrule; }
    public LocalDate getStartsOn() { return startsOn; }
    public void setStartsOn(LocalDate startsOn) { this.startsOn = startsOn; }
    public LocalTime getDueTime() { return dueTime; }
    public void setDueTime(LocalTime dueTime) { this.dueTime = dueTime; }
    public Integer getIntervalDays() { return intervalDays; }
    public void setIntervalDays(Integer intervalDays) { this.intervalDays = intervalDays; }
    public Distribution getDistribution() { return distribution; }
    public void setDistribution(Distribution distribution) { this.distribution = distribution; }
    public List<Long> getMemberIds() { return memberIds; }
    public void setMemberIds(List<Long> memberIds) { this.memberIds = memberIds == null ? new ArrayList<>() : memberIds; }
}
