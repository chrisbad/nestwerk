package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.household.ChoreEffort;
import club.badernet.nestwerk.household.ChoreTask;
import club.badernet.nestwerk.household.ChoreTaskService;
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

/** Aufgaben aus dem Haushaltsplan ansehen und abhaken. */
@Component
public class HouseholdTools {

    public record ChoreTaskItem(Long taskId, String chore, String area, LocalDate dueOn, String dueTime,
                                String assignee, String status, int plannedMinutes) {
    }

    private static final int MAX_DAYS = 31;

    private final Tools tools;
    private final ChoreTaskService tasks;
    private final FamilyMemberRepository members;

    public HouseholdTools(Tools tools, ChoreTaskService tasks, FamilyMemberRepository members) {
        this.tools = tools;
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

    @McpTool(name = "undo_chore_task", description = "Nimmt das heutige eigene Abhaken einer Aufgabe zurück.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ChoreTaskItem undoChoreTask(McpTransportContext context,
                                       @McpToolParam(description = "ID der Aufgabe") Long taskId) {
        return tools.write(context, user -> {
            Long id = Tools.required(taskId, "taskId");
            tasks.undo(id, user);
            return item(tasks.get(id, user), names());
        });
    }

    private Map<Long, String> names() {
        return members.findAll().stream().collect(Collectors.toMap(FamilyMember::getId, FamilyMember::getName));
    }

    private static ChoreTaskItem item(ChoreTask t, Map<Long, String> names) {
        return new ChoreTaskItem(t.getId(), t.getChore().getTitle(), t.getChore().getArea(), t.getDueOn(),
                t.getDueTime() == null ? null : t.getDueTime().toString(),
                t.getAssigneeMemberId() == null ? null : names.getOrDefault(t.getAssigneeMemberId(), "?"),
                t.getStatus().name(), t.getChore().getPlanMinutes());
    }
}
