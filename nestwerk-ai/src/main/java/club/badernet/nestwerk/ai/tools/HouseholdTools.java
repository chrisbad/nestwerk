package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.household.Chore;
import club.badernet.nestwerk.household.ChoreEffort;
import club.badernet.nestwerk.household.ChoreInput;
import club.badernet.nestwerk.household.ChoreKind;
import club.badernet.nestwerk.household.ChoreService;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskService;
import club.badernet.nestwerk.household.Distribution;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Haushaltsplan: Hausarbeiten anlegen und ändern, Aufgaben ansehen, zuweisen und abhaken. */
@Component
public class HouseholdTools {

    /** {@code plannedAt}: time of day the task is planned at in the calendar ({@code null} = not planned). */
    public record ChoreTaskItem(Long taskId, String chore, String area, LocalDate dueOn, String dueTime,
                                String assignee, String status, int plannedMinutes, String plannedAt) {
    }

    public record ChoreItem(Long choreId, String title, String area, String description, String kind, String rrule,
                            LocalDate startsOn, String dueTime, Integer intervalDays, String distribution,
                            List<String> members, int minutesEasy, int minutesMedium, int minutesHard,
                            boolean active, List<String> dates, boolean oncePerDay) {
    }

    private static final int MAX_DAYS = 31;

    private final Tools tools;
    private final ChoreService chores;
    private final ChoreTaskService tasks;
    private final FamilyMemberRepository members;

    public HouseholdTools(Tools tools, ChoreService chores, ChoreTaskService tasks, FamilyMemberRepository members) {
        this.tools = tools;
        this.chores = chores;
        this.tasks = tasks;
        this.members = members;
    }

    @McpTool(name = "list_chore_tasks", description = "Listet Aufgaben aus dem Haushaltsplan im Zeitraum (jeweils "
            + "einschließlich), ohne Angabe heute bis in 6 Tagen; höchstens 31 Tage.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<ChoreTaskItem> listChoreTasks(McpTransportContext context,
                                              @McpToolParam(description = "Erster Tag, JJJJ-MM-TT", required = false)
                                              String from,
                                              @McpToolParam(description = "Letzter Tag, JJJJ-MM-TT", required = false)
                                              String to) {
        return tools.read(context, user -> {
            LocalDate first = Tools.date(from, "from");
            if (first == null) {
                first = tasks.today();
            }
            LocalDate last = Tools.date(to, "to");
            if (last == null) {
                last = first.plusDays(6);
            }
            if (last.isBefore(first) || last.isAfter(first.plusDays(MAX_DAYS))) {
                throw new Tools.ToolException("Der Zeitraum muss 0–" + MAX_DAYS + " Tage umfassen.");
            }
            Map<Long, String> names = names();
            return tasks.between(user, first, last).stream().map(t -> item(t, names)).toList();
        });
    }

    @McpTool(name = "my_chore_tasks", description = "Eigene offene Hausarbeiten bis heute, auch überfällige.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<ChoreTaskItem> myChoreTasks(McpTransportContext context) {
        return tools.read(context, user -> {
            Map<Long, String> names = names();
            return tasks.mine(user, tasks.today()).stream().map(t -> item(t, names)).toList();
        });
    }

    @McpTool(name = "complete_chore_task", description = "Hakt eine Aufgabe aus dem Haushaltsplan jetzt ab und bucht "
            + "die Minuten. Ohne doerMemberId hat sie der Benutzer selbst erledigt; Helfer bekommen die Minuten auch.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ChoreTaskItem completeChoreTask(McpTransportContext context,
                                           @McpToolParam(description = "ID der Aufgabe (taskId)") Long taskId,
                                           @McpToolParam(description = "Wer es erledigt hat (Mitglieds-ID)",
                                                   required = false) Long doerMemberId,
                                           @McpToolParam(description = "IDs der Mithelfer", required = false)
                                           List<Long> helperMemberIds,
                                           @McpToolParam(description = "Aufwand: EASY, MEDIUM, HARD", required = false)
                                           String effort,
                                           @McpToolParam(description = "Tatsächliche Minuten", required = false)
                                           Integer minutes) {
        return tools.write(context, user -> {
            Long id = Tools.required(taskId, "taskId");
            Set<Long> helpers = helperMemberIds == null ? Set.of() : new HashSet<>(helperMemberIds);
            tasks.complete(id, user, doerMemberId, helpers, new ChoreTaskService.Booking(
                    Tools.enumValue(ChoreEffort.class, effort, "effort"), minutes, null));
            return item(tasks.get(id, user), names());
        });
    }

    @McpTool(name = "skip_chore_task", description = "Markiert eine offene Aufgabe für ihren Tag als nicht nötig, "
            + "z. B. weil die Spülmaschine nicht benutzt wurde. Es gibt keine Gutschrift, die Aufgabe bleibt als "
            + "durchgestrichener Platzhalter stehen und wird nicht neu geplant; bei Hausarbeiten mit Abstand nach "
            + "Erledigung läuft der Rhythmus von diesem Tag an weiter. Rückgängig mit undo_chore_task.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ChoreTaskItem skipChoreTask(McpTransportContext context,
                                       @McpToolParam(description = "ID der Aufgabe (taskId)") Long taskId) {
        return tools.write(context, user -> {
            Long id = Tools.required(taskId, "taskId");
            tasks.skip(id, user);
            return item(tasks.get(id, user), names());
        });
    }

    @McpTool(name = "undo_chore_task", description = "Nimmt das heutige eigene Abhaken oder „nicht nötig“ einer "
            + "Aufgabe zurück.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ChoreTaskItem undoChoreTask(McpTransportContext context,
                                       @McpToolParam(description = "ID der Aufgabe") Long taskId) {
        return tools.write(context, user -> {
            Long id = Tools.required(taskId, "taskId");
            tasks.undo(id, user);
            return item(tasks.get(id, user), names());
        });
    }

    @McpTool(name = "list_chores", description = "Listet die Hausarbeiten des Haushaltsplans (Vorlagen mit Rhythmus, "
            + "Zuständigen und Zeitaufwand), bei festen Terminen auch deren Daten. Nicht zu verwechseln mit "
            + "list_chore_tasks (die einzelnen fälligen Aufgaben).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<ChoreItem> listChores(McpTransportContext context) {
        return tools.read(context, user -> {
            Map<Long, String> names = names();
            return chores.visible(user).stream().map(c -> chore(c, names)).toList();
        });
    }

    @McpTool(name = "create_chore", description = "Legt eine Hausarbeit im Haushaltsplan an; der Plan erzeugt die "
            + "Aufgaben automatisch. kind: RECURRING (Regelmäßig, mit rrule), DATES (feste Termine, danach "
            + "add_chore_date), INTERVAL (alle intervalDays Tage nach Erledigung) oder ON_DEMAND (bei Bedarf). "
            + "rrule z. B. FREQ=WEEKLY;BYDAY=MO,TH oder FREQ=DAILY. distribution: FIXED (feste Person), ROTATION "
            + "(reihum), BALANCED (ausgleichend), OPEN (offen zum Nehmen); memberIds sind die Kandidaten in "
            + "Reihenfolge (IDs aus list_members). Zeitaufwand in Minuten für leicht/mittel/schwer.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ChoreItem createChore(McpTransportContext context,
                                 @McpToolParam(description = "Titel, z. B. Bad putzen") String title,
                                 @McpToolParam(description = "Bereich, z. B. Bad", required = false) String area,
                                 @McpToolParam(description = "Beschreibung", required = false) String description,
                                 @McpToolParam(description = "RECURRING, DATES, INTERVAL oder ON_DEMAND (Standard "
                                         + "RECURRING)", required = false) String kind,
                                 @McpToolParam(description = "Wiederholung als RRULE (Standard FREQ=WEEKLY)",
                                         required = false) String rrule,
                                 @McpToolParam(description = "Erster Tag, JJJJ-MM-TT", required = false)
                                 String startsOn,
                                 @McpToolParam(description = "Fällig um HH:MM", required = false) String dueTime,
                                 @McpToolParam(description = "Abstand in Tagen bei INTERVAL (Standard 7)",
                                         required = false) Integer intervalDays,
                                 @McpToolParam(description = "FIXED, ROTATION, BALANCED oder OPEN (Standard ROTATION)",
                                         required = false) String distribution,
                                 @McpToolParam(description = "IDs der zuständigen Mitglieder", required = false)
                                 List<Long> memberIds,
                                 @McpToolParam(description = "Minuten bei leichtem Aufwand (Standard 10)",
                                         required = false) Integer minutesEasy,
                                 @McpToolParam(description = "Minuten bei mittlerem Aufwand (Standard 15)",
                                         required = false) Integer minutesMedium,
                                 @McpToolParam(description = "Minuten bei schwerem Aufwand (Standard 30)",
                                         required = false) Integer minutesHard,
                                 @McpToolParam(description = "Frühester Beginn HH:MM", required = false)
                                 String windowStart,
                                 @McpToolParam(description = "Spätestes Ende HH:MM", required = false)
                                 String windowEnd,
                                 @McpToolParam(description = "true = höchstens eine Buchung pro Tag (Standard false)",
                                         required = false) Boolean oncePerDay) {
        return tools.write(context, user -> {
            ChoreKind k = Tools.enumValue(ChoreKind.class, kind, "kind");
            Distribution d = Tools.enumValue(Distribution.class, distribution, "distribution");
            Chore created = chores.create(new ChoreInput(Tools.required(title, "title"), area, description,
                    minutesEasy == null ? 10 : minutesEasy, minutesMedium == null ? 15 : minutesMedium,
                    minutesHard == null ? 30 : minutesHard, k == null ? ChoreKind.RECURRING : k,
                    rrule == null || rrule.isBlank() ? "FREQ=WEEKLY" : rrule, Tools.date(startsOn, "startsOn"),
                    Tools.time(dueTime, "dueTime"), intervalDays == null ? 7 : intervalDays,
                    d == null ? Distribution.ROTATION : d, memberIds == null ? List.of() : memberIds,
                    Tools.time(windowStart, "windowStart"), Tools.time(windowEnd, "windowEnd"),
                    Boolean.TRUE.equals(oncePerDay)), user);
            return chore(chores.get(created.getId(), user), names());
        });
    }

    @McpTool(name = "update_chore", description = "Ändert eine Hausarbeit. Nur angegebene Felder werden geändert "
            + "(Werte wie bei create_chore); die offenen künftigen Aufgaben werden neu geplant. memberIds ersetzt die "
            + "Kandidatenliste.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public ChoreItem updateChore(McpTransportContext context,
                                 @McpToolParam(description = "ID der Hausarbeit (choreId)") Long choreId,
                                 @McpToolParam(description = "Titel", required = false) String title,
                                 @McpToolParam(description = "Bereich", required = false) String area,
                                 @McpToolParam(description = "Beschreibung", required = false) String description,
                                 @McpToolParam(description = "RECURRING, DATES, INTERVAL oder ON_DEMAND",
                                         required = false) String kind,
                                 @McpToolParam(description = "Wiederholung als RRULE", required = false) String rrule,
                                 @McpToolParam(description = "Erster Tag, JJJJ-MM-TT", required = false)
                                 String startsOn,
                                 @McpToolParam(description = "Fällig um HH:MM", required = false) String dueTime,
                                 @McpToolParam(description = "Abstand in Tagen bei INTERVAL", required = false)
                                 Integer intervalDays,
                                 @McpToolParam(description = "FIXED, ROTATION, BALANCED oder OPEN", required = false)
                                 String distribution,
                                 @McpToolParam(description = "IDs der zuständigen Mitglieder (ersetzt die Liste)",
                                         required = false) List<Long> memberIds,
                                 @McpToolParam(description = "Minuten bei leichtem Aufwand", required = false)
                                 Integer minutesEasy,
                                 @McpToolParam(description = "Minuten bei mittlerem Aufwand", required = false)
                                 Integer minutesMedium,
                                 @McpToolParam(description = "Minuten bei schwerem Aufwand", required = false)
                                 Integer minutesHard,
                                 @McpToolParam(description = "Frühester Beginn HH:MM", required = false)
                                 String windowStart,
                                 @McpToolParam(description = "Spätestes Ende HH:MM", required = false)
                                 String windowEnd,
                                 @McpToolParam(description = "true = höchstens eine Buchung pro Tag", required = false)
                                 Boolean oncePerDay) {
        return tools.write(context, user -> {
            Long id = Tools.required(choreId, "choreId");
            Chore c = chores.get(id, user);
            ChoreKind k = Tools.enumValue(ChoreKind.class, kind, "kind");
            Distribution d = Tools.enumValue(Distribution.class, distribution, "distribution");
            chores.update(id, new ChoreInput(title == null ? c.getTitle() : title, area == null ? c.getArea() : area,
                    description == null ? c.getDescription() : description,
                    minutesEasy == null ? c.getMinutesEasy() : minutesEasy,
                    minutesMedium == null ? c.getMinutesMedium() : minutesMedium,
                    minutesHard == null ? c.getMinutesHard() : minutesHard, k == null ? c.getKind() : k,
                    rrule == null ? c.getRrule() : rrule,
                    startsOn == null ? c.getStartsOn() : Tools.date(startsOn, "startsOn"),
                    dueTime == null ? c.getDueTime() : Tools.time(dueTime, "dueTime"),
                    intervalDays == null ? c.getIntervalDays() : intervalDays,
                    d == null ? c.getDistribution() : d, memberIds == null ? c.getMemberIds() : memberIds,
                    windowStart == null ? c.getWindowStart() : Tools.time(windowStart, "windowStart"),
                    windowEnd == null ? c.getWindowEnd() : Tools.time(windowEnd, "windowEnd"),
                    oncePerDay == null ? c.isOncePerDay() : oncePerDay), user);
            return chore(chores.get(id, user), names());
        });
    }

    @McpTool(name = "set_chore_active", description = "Schaltet eine Hausarbeit aus (active=false: künftige offene "
            + "Aufgaben verschwinden, erledigte bleiben) oder wieder ein (active=true: der Plan erzeugt Aufgaben).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public ChoreItem setChoreActive(McpTransportContext context,
                                    @McpToolParam(description = "ID der Hausarbeit (choreId)") Long choreId,
                                    @McpToolParam(description = "true = aktiv, false = ausgeschaltet") Boolean active) {
        return tools.write(context, user -> {
            Long id = Tools.required(choreId, "choreId");
            if (Tools.required(active, "active")) {
                chores.activate(id, user);
            } else {
                chores.deactivate(id, user);
            }
            return chore(chores.get(id, user), names());
        });
    }

    @McpTool(name = "add_chore_date", description = "Fügt einer Hausarbeit mit festen Terminen (kind DATES) einen "
            + "Termin hinzu; der Plan erzeugt die Aufgabe.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ChoreItem addChoreDate(McpTransportContext context,
                                  @McpToolParam(description = "ID der Hausarbeit (choreId)") Long choreId,
                                  @McpToolParam(description = "Tag, JJJJ-MM-TT") String day,
                                  @McpToolParam(description = "Uhrzeit HH:MM", required = false) String time) {
        return tools.write(context, user -> {
            Long id = Tools.required(choreId, "choreId");
            chores.addDate(id, Tools.required(Tools.date(day, "day"), "day"), Tools.time(time, "time"), user);
            return chore(chores.get(id, user), names());
        });
    }

    @McpTool(name = "plan_chore_task", description = "Plant eine Hausarbeit einmalig für einen Tag ein, unabhängig "
            + "von ihrem Rhythmus (so bekommt auch eine Hausarbeit „bei Bedarf“ einen Termin), wie „Hausarbeit“ im "
            + "Kalender. Uhrzeit und Mitglied sind optional; ohne memberId ist die Aufgabe offen zum Nehmen. Für "
            + "andere zuzuweisen braucht das Recht zum Zuweisen. Abhaken mit complete_chore_task.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ChoreTaskItem planChoreTask(McpTransportContext context,
                                       @McpToolParam(description = "ID der Hausarbeit (choreId) aus list_chores")
                                       Long choreId,
                                       @McpToolParam(description = "Tag JJJJ-MM-TT, nicht in der Vergangenheit")
                                       String day,
                                       @McpToolParam(description = "Uhrzeit HH:MM", required = false) String time,
                                       @McpToolParam(description = "Zuständiges Mitglied (ID aus list_members)",
                                               required = false) Long memberId) {
        return tools.write(context, user -> {
            ChoreTask task = tasks.planOnce(Tools.required(choreId, "choreId"),
                    Tools.required(Tools.date(day, "day"), "day"), Tools.time(time, "time"), memberId, user);
            return item(tasks.get(task.getId(), user), names());
        });
    }

    @McpTool(name = "assign_chore_task", description = "Weist eine offene Aufgabe einem Mitglied zu bzw. gibt sie "
            + "ab (memberId weglassen = niemand, offen zum Nehmen).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public ChoreTaskItem assignChoreTask(McpTransportContext context,
                                         @McpToolParam(description = "ID der Aufgabe (taskId)") Long taskId,
                                         @McpToolParam(description = "Mitglieds-ID aus list_members", required = false)
                                         Long memberId) {
        return tools.write(context, user -> {
            Long id = Tools.required(taskId, "taskId");
            tasks.assign(id, memberId, user);
            return item(tasks.get(id, user), names());
        });
    }

    @McpTool(name = "reschedule_chore_task", description = "Plant eine offene Aufgabe zu einer anderen Uhrzeit und "
            + "wahlweise an einem anderen Tag ein (wie Verschieben im Kalender). Ohne date bleibt der Tag. Die "
            + "automatische Planung behält Tag und Uhrzeit, solange die zuständige Person da ist.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public ChoreTaskItem rescheduleChoreTask(McpTransportContext context,
                                             @McpToolParam(description = "ID der Aufgabe (taskId)") Long taskId,
                                             @McpToolParam(description = "Neue Uhrzeit HH:MM") String time,
                                             @McpToolParam(description = "Neuer Tag JJJJ-MM-TT, nicht in der "
                                                     + "Vergangenheit", required = false) String date) {
        return tools.write(context, user -> {
            Long id = Tools.required(taskId, "taskId");
            LocalDate day = Tools.date(date, "date");
            tasks.reschedule(id, day == null ? tasks.get(id, user).getDueOn() : day,
                    Tools.required(Tools.time(time, "time"), "time"), user);
            return item(tasks.get(id, user), names());
        });
    }

    @McpTool(name = "cancel_chore_task", description = "Löscht eine offene Aufgabe nur für ihren Termin: sie entfällt "
            + "und wird nicht neu geplant; die Hausarbeit und ihre anderen Termine bleiben. Bei INTERVAL ist die "
            + "nächste Aufgabe intervalDays nach diesem Termin fällig.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public ChoreTaskItem cancelChoreTask(McpTransportContext context,
                                         @McpToolParam(description = "ID der Aufgabe (taskId) aus list_chore_tasks")
                                         Long taskId) {
        return tools.write(context, user -> {
            Long id = Tools.required(taskId, "taskId");
            tasks.cancel(id, user);
            return item(tasks.get(id, user), names());
        });
    }

    @McpTool(name = "delete_chore", description = "Löscht eine Hausarbeit samt aller Aufgaben endgültig. Gibt es "
            + "schon Gutschriften, ist das nicht möglich; dann set_chore_active mit active=false verwenden.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public String deleteChore(McpTransportContext context,
                              @McpToolParam(description = "ID der Hausarbeit (choreId) aus list_chores") Long choreId) {
        return tools.write(context, user -> {
            Long id = Tools.required(choreId, "choreId");
            String title = chores.get(id, user).getTitle();
            chores.delete(id, user);
            return "Hausarbeit „" + title + "“ wurde gelöscht.";
        });
    }

    @McpTool(name = "book_chore", description = "Bucht eine Hausarbeit ohne geplante Aufgabe, z. B. „bei Bedarf“ "
            + "(Staubsaugen, das gerade erledigt wurde). Ohne doerMemberId hat der Benutzer sie selbst erledigt.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ChoreTaskItem bookChore(McpTransportContext context,
                                   @McpToolParam(description = "ID der Hausarbeit (choreId)") Long choreId,
                                   @McpToolParam(description = "Wer es erledigt hat (Mitglieds-ID)", required = false)
                                   Long doerMemberId,
                                   @McpToolParam(description = "IDs der Mithelfer", required = false)
                                   List<Long> helperMemberIds,
                                   @McpToolParam(description = "Aufwand: EASY, MEDIUM, HARD", required = false)
                                   String effort,
                                   @McpToolParam(description = "Tatsächliche Minuten", required = false)
                                   Integer minutes) {
        return tools.write(context, user -> {
            Set<Long> helpers = helperMemberIds == null ? Set.of() : new HashSet<>(helperMemberIds);
            ChoreTask task = tasks.bookAdHoc(Tools.required(choreId, "choreId"), user, doerMemberId, helpers,
                    new ChoreTaskService.Booking(Tools.enumValue(ChoreEffort.class, effort, "effort"), minutes, null));
            return item(tasks.get(task.getId(), user), names());
        });
    }

    private ChoreItem chore(Chore c, Map<Long, String> names) {
        List<String> dates = c.getKind() != ChoreKind.DATES ? List.of() : chores.dates(c.getId()).stream()
                .map(d -> d.getDueOn() + (d.getDueTime() == null ? "" : " " + d.getDueTime())).toList();
        return new ChoreItem(c.getId(), c.getTitle(), c.getArea(), c.getDescription(), c.getKind().name(),
                c.getRrule(), c.getStartsOn(), c.getDueTime() == null ? null : c.getDueTime().toString(),
                c.getIntervalDays(), c.getDistribution().name(),
                c.getMemberIds().stream().map(m -> names.getOrDefault(m, "?") + " (" + m + ")").toList(),
                c.getMinutesEasy(), c.getMinutesMedium(), c.getMinutesHard(), c.isActive(), dates, c.isOncePerDay());
    }

    private Map<Long, String> names() {
        return members.findAll().stream().collect(Collectors.toMap(FamilyMember::getId, FamilyMember::getName));
    }

    private static ChoreTaskItem item(ChoreTask t, Map<Long, String> names) {
        return new ChoreTaskItem(t.getId(), t.getChore().getTitle(), t.getChore().getArea(), t.getDueOn(),
                t.getDueTime() == null ? null : t.getDueTime().toString(),
                t.getAssigneeMemberId() == null ? null : names.getOrDefault(t.getAssigneeMemberId(), "?"),
                t.getStatus().name(), t.getChore().getPlanMinutes(),
                t.getPlannedAt() == null ? null : t.getPlannedAt().toString());
    }
}
