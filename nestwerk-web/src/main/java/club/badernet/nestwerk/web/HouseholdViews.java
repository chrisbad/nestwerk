package club.badernet.nestwerk.web;

import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreCredit;
import club.badernet.nestwerk.household.ChoreCreditRepository;
import club.badernet.nestwerk.household.ChoreEffort;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.household.HouseholdAccess;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN);

    /**
     * Eine Aufgabe als Chip mit allem, was die Ansicht braucht. {@code minutes} ist bei offenen Aufgaben der Planwert,
     * bei erledigten die gebuchte Dauer; {@code effort} gibt es nur bei erledigten.
     */
    public record TaskChip(Long id, String title, String area, String time, int minutes, String effort,
                           String effortLabel, boolean done, boolean overdue, String dayLabel,
                           FamilyMember assignee, boolean canComplete, boolean canUndo, int minutesEasy,
                           int minutesMedium, int minutesHard, boolean noOneFree, boolean skipped,
                           boolean canSkip) {
    }

    /** Was der Betrachter darf und wer er ist; einmal je Anfrage ermittelt. */
    public record Context(User viewer, Long ownMember, boolean completeAny, boolean correctAny, LocalDate today,
                          Map<Long, FamilyMember> members) {
    }

    private final HouseholdAccess access;
    private final FamilyMemberRepository members;
    private final ChoreCreditRepository credits;
    private final ChoreTaskService tasks;
    private final Clock clock;

    public HouseholdViews(HouseholdAccess access, FamilyMemberRepository members, ChoreCreditRepository credits,
                          ChoreTaskService tasks, Clock clock) {
        this.access = access;
        this.members = members;
        this.credits = credits;
        this.tasks = tasks;
        this.clock = clock;
    }

    /** Aufwand einer erledigten Aufgabe als Kürzel für die CSS-Klasse („easy“ …), sonst {@code null}. */
    public static String effortKey(ChoreEffort effort) {
        return effort == null ? null : effort.name().toLowerCase(Locale.ROOT);
    }

    /** Aufwandsgrade für die Buchen-Dialoge. */
    public ChoreEffort[] efforts() {
        return ChoreEffort.values();
    }

    /** Alle Mitglieder für die Mithelfer-Auswahl beim Buchen. */
    public List<FamilyMember> bookMembers() {
        return members.findAllByOrderByNameAsc();
    }

    /** Wert für {@code datetime-local}: jetzt, auf Minuten gekürzt. */
    public String nowInput() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES).toString();
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
        boolean canUndo = !t.isOpen() && (tasks.bookedTodayBy(t, ctx.viewer()) || ctx.correctAny());
        FamilyMember assignee = t.getAssigneeMemberId() == null ? null : ctx.members().get(t.getAssigneeMemberId());
        String time = t.isOpen() && t.getPlannedAt() != null ? t.getPlannedAt().format(TIME) : t.timeLabel();
        boolean noOneFree = t.isOpen() && t.getAssigneeMemberId() == null && !t.isAdHoc()
                && c.getDistribution() != club.badernet.nestwerk.household.Distribution.OPEN;
        return new TaskChip(t.getId(), c.getTitle(), c.getArea(), time, minutes(t),
                effortKey(t.getEffort()), t.getEffort() == null ? null : t.getEffort().label(), t.isDone(),
                t.isOverdue(ctx.today()), dayLabel(t.getDueOn(), ctx.today()), assignee, canComplete, canUndo,
                c.getMinutesEasy(), c.getMinutesMedium(), c.getMinutesHard(), noOneFree, t.isSkipped(),
                canComplete && !t.isAdHoc());
    }

    /** Planwert bei offenen Aufgaben, gebuchte Dauer (des Erledigers) bei erledigten. */
    public int minutes(ChoreTask t) {
        if (t.isOpen()) {
            return t.getChore().getPlanMinutes();
        }
        return credits.findAllByTaskId(t.getId()).stream().mapToInt(ChoreCredit::effectiveMinutes).max()
                .orElse(t.getChore().getPlanMinutes());
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

    /** Wochentag und Datum, z. B. „Mi. 14.10.“ (Spaltenköpfe im Wochenplan). */
    public static String shortDay(LocalDate day) {
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
            case ON_DEMAND -> "Bei Bedarf";
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
