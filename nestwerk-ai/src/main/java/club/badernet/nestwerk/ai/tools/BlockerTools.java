package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.blocker.Blocker;
import club.badernet.nestwerk.blocker.BlockerInput;
import club.badernet.nestwerk.blocker.BlockerKind;
import club.badernet.nestwerk.blocker.BlockerService;
import club.badernet.nestwerk.blocker.BlockerSettings;
import club.badernet.nestwerk.blocker.BlockerStrength;
import club.badernet.nestwerk.blocker.BlockerVisibility;
import club.badernet.nestwerk.blocker.RestGoal;
import club.badernet.nestwerk.blocker.RestGoals;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberRepository;
import club.badernet.nestwerk.user.User;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Zeiten (Blocker): Arbeits-, Schlaf- und Ruhezeiten pflegen, Ruhe-Ziele und das Bundesland für Feiertage. Nur
 * „belegt“ sichtbare Blocker anderer erscheinen ohne Titel, unsichtbare gar nicht – wie in der Weboberfläche.
 */
@Component
public class BlockerTools {

    public record BlockerItem(Long blockerId, String title, String kind, String start, String end,
                              List<String> weekdays, LocalDate validFrom, LocalDate validUntil, String strength,
                              String visibility, boolean quiet, boolean holidaysOff, List<String> people,
                              List<String> planners, List<LocalDate> skips, boolean editable) {
    }

    public record GoalItem(Long goalId, Long memberId, String label, String kind, String windowStart,
                           String windowEnd, int target, String status, boolean met) {
    }

    private final Tools tools;
    private final BlockerService blockers;
    private final RestGoals restGoals;
    private final BlockerSettings settings;
    private final FamilyMemberRepository members;
    private final Clock clock;

    public BlockerTools(Tools tools, BlockerService blockers, RestGoals restGoals, BlockerSettings settings,
                        FamilyMemberRepository members, Clock clock) {
        this.tools = tools;
        this.blockers = blockers;
        this.restGoals = restGoals;
        this.settings = settings;
        this.members = members;
        this.clock = clock;
    }

    @McpTool(name = "list_blockers", description = "Listet Zeiten der Familie (Arbeit, Schlaf, Ruhe, Freizeit, "
            + "Eigene) mit Wochentagen und Wirkung auf Termine. Ohne memberId alle Mitglieder. Fremde Zeiten mit "
            + "Sichtbarkeit „nur belegt“ erscheinen ohne Titel.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<BlockerItem> listBlockers(McpTransportContext context,
                                          @McpToolParam(description = "Nur Zeiten dieses Mitglieds", required = false)
                                          Long memberId) {
        return tools.read(context, user -> {
            Long own = blockers.ownMember(user).orElse(null);
            Map<Long, String> names = names();
            List<Blocker> list = memberId == null ? blockers.all() : blockers.forMember(memberId);
            return list.stream().map(b -> item(b, user, own, names)).filter(java.util.Objects::nonNull).toList();
        });
    }

    @McpTool(name = "create_blocker", description = "Legt eine regelmäßige Zeit an, z. B. Arbeit Mo–Fr 08:00–16:30. "
            + "kind: WORK, SLEEP, REST, LEISURE oder CUSTOM. strength: SHOW (nur anzeigen), WARN (warnen), BLOCK "
            + "(Termine dort sperren). visibility: TITLE, BUSY oder HIDDEN. weekdays z. B. [MONDAY, TUESDAY]. "
            + "Endet die Zeit vor dem Beginn, läuft sie über Nacht. skips: einzelne Ausnahmetage.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public BlockerItem createBlocker(McpTransportContext context,
                                     @McpToolParam(description = "Titel (Standard: Art)", required = false)
                                     String title,
                                     @McpToolParam(description = "WORK, SLEEP, REST, LEISURE oder CUSTOM") String kind,
                                     @McpToolParam(description = "Beginn HH:MM") String startTime,
                                     @McpToolParam(description = "Ende HH:MM") String endTime,
                                     @McpToolParam(description = "Wochentage, z. B. MONDAY") List<String> weekdays,
                                     @McpToolParam(description = "IDs der Mitglieder (list_members)")
                                     List<Long> memberIds,
                                     @McpToolParam(description = "Gilt ab, JJJJ-MM-TT (Standard heute)",
                                             required = false) String validFrom,
                                     @McpToolParam(description = "Gilt bis, JJJJ-MM-TT", required = false)
                                     String validUntil,
                                     @McpToolParam(description = "SHOW, WARN oder BLOCK (Standard WARN)",
                                             required = false) String strength,
                                     @McpToolParam(description = "TITLE, BUSY oder HIDDEN (Standard TITLE)",
                                             required = false) String visibility,
                                     @McpToolParam(description = "Ruhe-Modus: keine Benachrichtigungen",
                                             required = false) Boolean quiet,
                                     @McpToolParam(description = "An Feiertagen frei", required = false)
                                     Boolean holidaysOff,
                                     @McpToolParam(description = "IDs der Mitglieder, die dafür planen",
                                             required = false) List<Long> plannerIds,
                                     @McpToolParam(description = "Ausnahmetage, JJJJ-MM-TT", required = false)
                                     List<String> skips) {
        return tools.write(context, user -> {
            BlockerInput input = new BlockerInput(title,
                    Tools.required(Tools.enumValue(BlockerKind.class, kind, "kind"), "kind"),
                    Tools.required(Tools.time(startTime, "startTime"), "startTime"),
                    Tools.required(Tools.time(endTime, "endTime"), "endTime"), days(weekdays),
                    validFrom == null || validFrom.isBlank() ? LocalDate.now(clock) : Tools.date(validFrom,
                            "validFrom"), Tools.date(validUntil, "validUntil"),
                    orDefault(Tools.enumValue(BlockerStrength.class, strength, "strength"), BlockerStrength.WARN),
                    orDefault(Tools.enumValue(BlockerVisibility.class, visibility, "visibility"),
                            BlockerVisibility.TITLE), Boolean.TRUE.equals(quiet), Boolean.TRUE.equals(holidaysOff),
                    set(memberIds), set(plannerIds), dates(skips));
            Blocker saved = blockers.create(input, user);
            return item(blockers.get(saved.getId()), user, blockers.ownMember(user).orElse(null), names());
        });
    }

    @McpTool(name = "update_blocker", description = "Ändert eine Zeit. Nur angegebene Felder werden geändert; "
            + "weekdays, memberIds, plannerIds und skips ersetzen die Listen, leerer validUntil löscht das Ende.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public BlockerItem updateBlocker(McpTransportContext context,
                                     @McpToolParam(description = "blockerId aus list_blockers") Long blockerId,
                                     @McpToolParam(description = "Titel", required = false) String title,
                                     @McpToolParam(description = "WORK, SLEEP, REST, LEISURE oder CUSTOM",
                                             required = false) String kind,
                                     @McpToolParam(description = "Beginn HH:MM", required = false) String startTime,
                                     @McpToolParam(description = "Ende HH:MM", required = false) String endTime,
                                     @McpToolParam(description = "Wochentage (ersetzt)", required = false)
                                     List<String> weekdays,
                                     @McpToolParam(description = "Mitglieder-IDs (ersetzt)", required = false)
                                     List<Long> memberIds,
                                     @McpToolParam(description = "Gilt ab, JJJJ-MM-TT", required = false)
                                     String validFrom,
                                     @McpToolParam(description = "Gilt bis, JJJJ-MM-TT; leer = unbegrenzt",
                                             required = false) String validUntil,
                                     @McpToolParam(description = "SHOW, WARN oder BLOCK", required = false)
                                     String strength,
                                     @McpToolParam(description = "TITLE, BUSY oder HIDDEN", required = false)
                                     String visibility,
                                     @McpToolParam(description = "Ruhe-Modus", required = false) Boolean quiet,
                                     @McpToolParam(description = "An Feiertagen frei", required = false)
                                     Boolean holidaysOff,
                                     @McpToolParam(description = "Planer-IDs (ersetzt)", required = false)
                                     List<Long> plannerIds,
                                     @McpToolParam(description = "Ausnahmetage (ersetzt), JJJJ-MM-TT",
                                             required = false) List<String> skips) {
        return tools.write(context, user -> {
            Long id = Tools.required(blockerId, "blockerId");
            Blocker b = blockers.get(id);
            BlockerInput input = new BlockerInput(title != null ? title : b.getTitle(),
                    orDefault(Tools.enumValue(BlockerKind.class, kind, "kind"), b.getKind()),
                    orDefault(Tools.time(startTime, "startTime"), b.getStartTime()),
                    orDefault(Tools.time(endTime, "endTime"), b.getEndTime()),
                    weekdays != null ? days(weekdays) : EnumSet.copyOf(b.getWeekdays()),
                    orDefault(Tools.date(validFrom, "validFrom"), b.getValidFrom()),
                    validUntil != null ? Tools.date(validUntil, "validUntil") : b.getValidUntil(),
                    orDefault(Tools.enumValue(BlockerStrength.class, strength, "strength"), b.getStrength()),
                    orDefault(Tools.enumValue(BlockerVisibility.class, visibility, "visibility"), b.getVisibility()),
                    quiet != null ? quiet : b.isQuiet(), holidaysOff != null ? holidaysOff : b.isHolidaysOff(),
                    memberIds != null ? set(memberIds) : new HashSet<>(b.getMemberIds()),
                    plannerIds != null ? set(plannerIds) : new HashSet<>(b.getPlannerIds()),
                    skips != null ? dates(skips) : new TreeSet<>(b.getSkips()));
            blockers.update(id, input, user);
            return item(blockers.get(id), user, blockers.ownMember(user).orElse(null), names());
        });
    }

    @McpTool(name = "list_rest_goals", description = "Zeigt die Ruhe-Ziele eines Mitglieds und wie weit sie diese "
            + "Woche erreicht sind (z. B. „3 freie Abende“ oder „40 Stunden Ruhe“).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<GoalItem> listRestGoals(McpTransportContext context,
                                        @McpToolParam(description = "Mitglieds-ID aus list_members") Long memberId) {
        return tools.read(context, user -> restGoals.status(Tools.required(memberId, "memberId"),
                LocalDate.now(clock)).stream().map(s -> new GoalItem(s.goal().getId(), s.goal().getMemberId(),
                s.goal().getLabel(), s.goal().getKind().name(), time(s.goal().getWindowStart()),
                time(s.goal().getWindowEnd()), s.goal().getKind() == RestGoal.Kind.HOURS
                ? s.goal().getTarget() / 60 : s.goal().getTarget(), s.text(), s.met())).toList());
    }

    @McpTool(name = "save_rest_goal", description = "Legt ein Ruhe-Ziel an oder ändert es (goalId). kind WINDOWS: "
            + "target freie Zeitfenster pro Woche zwischen windowStart und windowEnd (z. B. 3 Abende 18:00–22:00); "
            + "kind HOURS: target Stunden Ruhe pro Woche.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public String saveRestGoal(McpTransportContext context,
                               @McpToolParam(description = "Mitglieds-ID") Long memberId,
                               @McpToolParam(description = "WINDOWS oder HOURS") String kind,
                               @McpToolParam(description = "Ziel: Anzahl Fenster bzw. Stunden pro Woche")
                               Integer target,
                               @McpToolParam(description = "goalId zum Ändern, sonst neu", required = false)
                               Long goalId,
                               @McpToolParam(description = "Fensterbeginn HH:MM (nur WINDOWS)", required = false)
                               String windowStart,
                               @McpToolParam(description = "Fensterende HH:MM (nur WINDOWS)", required = false)
                               String windowEnd,
                               @McpToolParam(description = "Bezeichnung", required = false) String label) {
        return tools.write(context, user -> {
            RestGoal.Kind k = Tools.required(Tools.enumValue(RestGoal.Kind.class, kind, "kind"), "kind");
            int value = Tools.required(target, "target");
            blockers.saveGoal(goalId, Tools.required(memberId, "memberId"), k, Tools.time(windowStart, "windowStart"),
                    Tools.time(windowEnd, "windowEnd"), k == RestGoal.Kind.HOURS ? value * 60 : value, label, user);
            return "Ruhe-Ziel gespeichert.";
        });
    }

    @McpTool(name = "set_holiday_state", description = "Setzt das Bundesland für Feiertage (Kürzel wie BW, BY, NW; "
            + "leer = keins). Zeiten mit „an Feiertagen frei“ richten sich danach. Nur für Kalender-Verwalter.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public String setHolidayState(McpTransportContext context,
                                  @McpToolParam(description = "Bundesland-Kürzel", required = false) String state) {
        return tools.write(context, user -> {
            if (!blockers.canManageSettings(user)) {
                throw new org.springframework.security.access.AccessDeniedException("Nur für Verwalter des Kalenders");
            }
            settings.setState(state);
            return "Bundesland: " + (settings.state() == null ? "keins" : settings.state());
        });
    }

    private BlockerItem item(Blocker b, User viewer, Long own, Map<Long, String> names) {
        boolean canEdit = blockers.canEdit(b, viewer);
        boolean insider = canEdit || viewer.isAdministrator() || b.getMemberIds().contains(own);
        if (!insider && b.getVisibility() == BlockerVisibility.HIDDEN) {
            return null;
        }
        String title = insider || b.getVisibility() == BlockerVisibility.TITLE ? b.getTitle() : "belegt";
        return new BlockerItem(b.getId(), title, b.getKind().name(), b.getStartTime().toString(),
                b.getEndTime().toString(), new TreeSet<>(b.getWeekdays()).stream().map(DayOfWeek::name).toList(),
                b.getValidFrom(), b.getValidUntil(), b.getStrength().name(), b.getVisibility().name(), b.isQuiet(),
                b.isHolidaysOff(), people(b.getMemberIds(), names), people(b.getPlannerIds(), names),
                b.getSkips().stream().sorted().toList(), canEdit);
    }

    private static List<String> people(Set<Long> ids, Map<Long, String> names) {
        return ids.stream().map(id -> names.getOrDefault(id, "?") + " (" + id + ")").sorted().toList();
    }

    private Map<Long, String> names() {
        return members.findAll().stream().collect(Collectors.toMap(FamilyMember::getId, FamilyMember::getName));
    }

    private static Set<DayOfWeek> days(List<String> names) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String name : Tools.required(names, "weekdays")) {
            String key = name.trim().replace(".", "").toUpperCase(Locale.ROOT);
            DayOfWeek day = null;
            for (DayOfWeek d : DayOfWeek.values()) {
                if (d.name().equals(key) || d.getDisplayName(TextStyle.SHORT, Locale.GERMAN).replace(".", "").equalsIgnoreCase(key)
                        || d.getDisplayName(TextStyle.FULL, Locale.GERMAN).equalsIgnoreCase(key)) {
                    day = d;
                }
            }
            if (day == null) {
                throw new Tools.ToolException("weekdays: unbekannter Wochentag „" + name + "“ (z. B. MONDAY).");
            }
            days.add(day);
        }
        return days;
    }

    private static Set<Long> set(List<Long> ids) {
        return ids == null ? Set.of() : new HashSet<>(ids);
    }

    private static Set<LocalDate> dates(List<String> values) {
        Set<LocalDate> result = new TreeSet<>();
        if (values != null) {
            values.forEach(v -> result.add(Tools.required(Tools.date(v, "skips"), "skips")));
        }
        return result;
    }

    private static String time(LocalTime value) {
        return value == null ? null : value.toString();
    }

    private static <T> T orDefault(T value, T fallback) {
        return value != null ? value : fallback;
    }

    @McpTool(name = "delete_blocker", description = "Löscht eine Zeit (Arbeit, Schlaf, Ruhe …) endgültig. Nur nach Rückfrage benutzen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public String deleteBlocker(McpTransportContext context,
                           @McpToolParam(description = "ID der Zeit aus list_blockers") Long blockerId) {
        return tools.write(context, user -> {
            Long id = Tools.required(blockerId, "blockerId");
            String title = blockers.get(id).getTitle();
            blockers.delete(id, user);
            return "Zeit „" + title + "“ wurde gelöscht.";
        });
    }

    @McpTool(name = "delete_rest_goal", description = "Löscht ein Ruhe-Ziel endgültig. Nur nach Rückfrage benutzen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public String deleteRestGoal(McpTransportContext context,
                           @McpToolParam(description = "ID des Ziels aus list_rest_goals") Long goalId) {
        return tools.write(context, user -> {
            Long id = Tools.required(goalId, "goalId");
            blockers.deleteGoal(id, user);
            return "Ruhe-Ziel wurde gelöscht.";
        });
    }

    @McpTool(name = "get_blocker", description = "Zeigt eine Zeit (Arbeit, Schlaf, Ruhe …). Fremde Zeiten mit "
            + "Sichtbarkeit „nur belegt“ erscheinen ohne Titel, unsichtbare gibt es für andere nicht.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public BlockerItem getBlocker(McpTransportContext context,
                                  @McpToolParam(description = "blockerId aus list_blockers") Long blockerId) {
        return tools.read(context, user -> {
            BlockerItem item = item(blockers.get(Tools.required(blockerId, "blockerId")), user,
                    blockers.ownMember(user).orElse(null), names());
            if (item == null) {
                throw new Tools.ToolException("Zeit nicht gefunden");
            }
            return item;
        });
    }
}
