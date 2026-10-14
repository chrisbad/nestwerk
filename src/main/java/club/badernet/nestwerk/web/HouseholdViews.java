package club.badernet.nestwerk.web;

import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Aufbereitung von Aufgaben und Hausarbeiten für die Templates. */
@Component
public class HouseholdViews {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d.M.", Locale.GERMAN);
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN);

    /** Eine Aufgabe als Chip mit allem, was die Ansicht braucht. */
    public record TaskChip(Long id, String title, String area, String time, int minutes, String effort,
                           String effortLabel, boolean done, boolean overdue, String dayLabel,
                           FamilyMember assignee, boolean canComplete, boolean canUndo) {
    }

    /** Was der Betrachter darf und wer er ist; einmal je Anfrage ermittelt. */
    public record Context(User viewer, Long ownMember, boolean completeAny, boolean correctAny, LocalDate today,
                          Map<Long, FamilyMember> members) {
    }

    private final HouseholdAccess access;
    private final FamilyMemberRepository members;
    private final Clock clock;

    public HouseholdViews(HouseholdAccess access, FamilyMemberRepository members, Clock clock) {
        this.access = access;
        this.members = members;
        this.clock = clock;
    }

    public Context context(User viewer) {
        Map<Long, FamilyMember> byId = new LinkedHashMap<>();
        members.findAllByOrderByNameAsc().forEach(m -> byId.put(m.getId(), m));
        return new Context(viewer, access.memberId(viewer).orElse(null), access.canAny(viewer, "COMPLETE"),
                access.canAny(viewer, "CORRECT"), LocalDate.now(clock), byId);
    }

    public TaskChip chip(ChoreTask t, Context ctx) {
        Chore c = t.getChore();
        boolean canComplete = t.isOpen() && ctx.ownMember() != null && access.can(ctx.viewer(), "COMPLETE", t);
        boolean ownToday = ctx.viewer().getId().equals(t.getCompletedByUser()) && t.getCompletedAt() != null
                && t.getCompletedAt().atZone(clock.getZone()).toLocalDate().equals(ctx.today());
        boolean canUndo = !t.isOpen() && (ownToday || ctx.correctAny());
        FamilyMember assignee = t.getAssigneeMemberId() == null ? null : ctx.members().get(t.getAssigneeMemberId());
        return new TaskChip(t.getId(), c.getTitle(), c.getArea(), t.timeLabel(), c.getMinutes(),
                c.getEffort().name().toLowerCase(Locale.ROOT), c.getEffort().label(), !t.isOpen(),
                t.isOverdue(ctx.today()), dayLabel(t.getDueOn(), ctx.today()), assignee, canComplete, canUndo);
    }

    public List<TaskChip> chips(List<ChoreTask> tasks, Context ctx) {
        return tasks.stream().map(t -> chip(t, ctx)).toList();
    }

    public static String dayLabel(LocalDate day, LocalDate today) {
        if (day.equals(today)) {
            return "heute";
        }
        if (day.equals(today.plusDays(1))) {
            return "morgen";
        }
        if (day.equals(today.minusDays(1))) {
            return "gestern";
        }
        return day.format(DAY);
    }

    public static String longDay(LocalDate day) {
        return day.format(LONG_DAY);
    }

    /** Kurzbeschreibung, wann eine Hausarbeit fällig wird. */
    public static String schedule(Chore c) {
        String time = c.getDueTime() == null ? "" : ", " + c.getDueTime() + " Uhr";
        return switch (c.getKind()) {
            case DATES -> "Feste Termine";
            case INTERVAL -> (c.getIntervalDays() == 1 ? "Jeden Tag" : "Alle " + c.getIntervalDays() + " Tage")
                    + " nach dem letzten Erledigen" + time;
            case RECURRING -> rruleLabel(c.getRrule()) + time;
        };
    }

    static String rruleLabel(String rrule) {
        if (rrule == null) {
            return "Regelmäßig";
        }
        return switch (rrule) {
            case "FREQ=DAILY" -> "Täglich";
            case "FREQ=WEEKLY" -> "Wöchentlich";
            case "FREQ=WEEKLY;INTERVAL=2" -> "Alle 2 Wochen";
            case "FREQ=MONTHLY" -> "Monatlich";
            case "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR" -> "Werktags";
            case "FREQ=WEEKLY;BYDAY=SA,SU" -> "Am Wochenende";
            default -> weekdays(rrule).orElse("Regelmäßig (" + rrule + ")");
        };
    }

    private static Optional<String> weekdays(String rrule) {
        if (!rrule.matches("FREQ=WEEKLY;BYDAY=[A-Z,]+")) {
            return Optional.empty();
        }
        Map<String, String> names = Map.of("MO", "Mo", "TU", "Di", "WE", "Mi", "TH", "Do", "FR", "Fr", "SA", "Sa",
                "SU", "So");
        String[] days = rrule.substring(rrule.indexOf("BYDAY=") + 6).split(",");
        StringBuilder sb = new StringBuilder("Jeden ");
        for (int i = 0; i < days.length; i++) {
            String n = names.get(days[i]);
            if (n == null) {
                return Optional.empty();
            }
            sb.append(i == 0 ? "" : (i == days.length - 1 ? " und " : ", ")).append(n);
        }
        return Optional.of(sb.toString());
    }
}
