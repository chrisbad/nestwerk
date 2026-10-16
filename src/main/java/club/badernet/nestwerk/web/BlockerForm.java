package club.badernet.nestwerk.web;

import club.badernet.nestwerk.blocker.Blocker;
import club.badernet.nestwerk.blocker.BlockerInput;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BlockerVisibility;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Formular „Zeit anlegen/ändern“; Ausnahmetage als Text („23.10.2026, 30.10.2026“). */
public class BlockerForm {

    private static final DateTimeFormatter GERMAN = DateTimeFormatter.ofPattern("d.M.uuuu");
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd.MM.uuuu");

    private String title;
    private BlockerKind kind = BlockerKind.WORK;
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime startTime;
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime endTime;
    private Set<DayOfWeek> weekdays = EnumSet.noneOf(DayOfWeek.class);
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validUntil;
    private BlockerStrength strength = BlockerStrength.WARN;
    private BlockerVisibility visibility = BlockerVisibility.TITLE;
    private boolean quiet;
    private boolean holidaysOff;
    private Set<Long> memberIds = new HashSet<>();
    private Set<Long> plannerIds = new HashSet<>();
    private String skips;

    /** Vorbelegung je Art: typische Zeiten, Tage und Regeln. */
    public static BlockerForm forNew(BlockerKind kind, Long memberId, LocalDate today) {
        BlockerForm f = new BlockerForm();
        f.kind = kind;
        f.validFrom = today;
        if (memberId != null) {
            f.memberIds.add(memberId);
        }
        switch (kind) {
            case WORK -> {
                f.startTime = LocalTime.of(8, 0);
                f.endTime = LocalTime.of(16, 0);
                f.weekdays = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
                f.holidaysOff = true;
            }
            case SLEEP -> {
                f.startTime = LocalTime.of(22, 0);
                f.endTime = LocalTime.of(6, 30);
                f.weekdays = EnumSet.allOf(DayOfWeek.class);
                f.strength = BlockerStrength.BLOCK;
                f.visibility = BlockerVisibility.BUSY;
                f.quiet = true;
            }
            case REST -> {
                f.startTime = LocalTime.of(13, 0);
                f.endTime = LocalTime.of(14, 0);
                f.weekdays = EnumSet.allOf(DayOfWeek.class);
                f.quiet = true;
            }
            case LEISURE -> {
                f.startTime = LocalTime.of(10, 0);
                f.endTime = LocalTime.of(18, 0);
                f.weekdays = EnumSet.of(DayOfWeek.SUNDAY);
                f.strength = BlockerStrength.SHOW;
            }
            case CUSTOM -> {
                f.startTime = LocalTime.of(9, 0);
                f.endTime = LocalTime.of(12, 0);
                f.weekdays = EnumSet.of(DayOfWeek.MONDAY);
            }
        }
        return f;
    }

    public static BlockerForm from(Blocker b) {
        BlockerForm f = new BlockerForm();
        f.title = b.getTitle();
        f.kind = b.getKind();
        f.startTime = b.getStartTime();
        f.endTime = b.getEndTime();
        f.weekdays = b.getWeekdays().isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(b.getWeekdays());
        f.validFrom = b.getValidFrom();
        f.validUntil = b.getValidUntil();
        f.strength = b.getStrength();
        f.visibility = b.getVisibility();
        f.quiet = b.isQuiet();
        f.holidaysOff = b.isHolidaysOff();
        f.memberIds = new HashSet<>(b.getMemberIds());
        f.plannerIds = new HashSet<>(b.getPlannerIds());
        f.skips = new TreeSet<>(b.getSkips()).stream().map(SHOWN::format).collect(Collectors.joining(", "));
        return f;
    }

    public BlockerInput toInput() {
        return new BlockerInput(title, kind, startTime, endTime, weekdays, validFrom, validUntil, strength,
                visibility, quiet, holidaysOff, memberIds, plannerIds, parseSkips());
    }

    Set<LocalDate> parseSkips() {
        Set<LocalDate> days = new TreeSet<>();
        if (skips == null || skips.isBlank()) {
            return days;
        }
        for (String part : skips.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                days.add(part.contains(".") ? LocalDate.parse(part, GERMAN) : LocalDate.parse(part));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Ausnahme „" + part + "“ ist kein Datum (TT.MM.JJJJ).");
            }
        }
        if (days.size() > 366) {
            throw new IllegalArgumentException("Höchstens 366 Ausnahmetage.");
        }
        return days;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public BlockerKind getKind() { return kind; }
    public void setKind(BlockerKind kind) { this.kind = kind; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public Set<DayOfWeek> getWeekdays() { return weekdays; }
    public void setWeekdays(Set<DayOfWeek> weekdays) {
        this.weekdays = weekdays == null || weekdays.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weekdays);
    }
    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
    public BlockerStrength getStrength() { return strength; }
    public void setStrength(BlockerStrength strength) { this.strength = strength; }
    public BlockerVisibility getVisibility() { return visibility; }
    public void setVisibility(BlockerVisibility visibility) { this.visibility = visibility; }
    public boolean isQuiet() { return quiet; }
    public void setQuiet(boolean quiet) { this.quiet = quiet; }
    public boolean isHolidaysOff() { return holidaysOff; }
    public void setHolidaysOff(boolean holidaysOff) { this.holidaysOff = holidaysOff; }
    public Set<Long> getMemberIds() { return memberIds; }
    public void setMemberIds(Set<Long> memberIds) { this.memberIds = memberIds == null ? new HashSet<>() : memberIds; }
    public Set<Long> getPlannerIds() { return plannerIds; }
    public void setPlannerIds(Set<Long> plannerIds) { this.plannerIds = plannerIds == null ? new HashSet<>() : plannerIds; }
    public String getSkips() { return skips; }
    public void setSkips(String skips) { this.skips = skips; }
}
