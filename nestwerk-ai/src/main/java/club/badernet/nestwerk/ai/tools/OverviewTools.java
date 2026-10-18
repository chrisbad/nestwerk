package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.user.User;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tagesüberblick als Einstieg für die KI. Bereiche, die der Benutzer nicht sehen darf, bleiben leer, statt den
 * ganzen Aufruf scheitern zu lassen.
 */
@Component
public class OverviewTools {

    public record Overview(String user, LocalDate today, List<CalendarTools.Event> eventsToday,
                           List<TodoTools.TodoItem> myUrgentTodos, List<HouseholdTools.ChoreTaskItem> myChoreTasks,
                           long unreadNotifications, List<String> notes) {
    }

    /** Zeitlagen, die auch die Start-Kachel zeigt. */
    private static final List<String> URGENT = List.of("OVERDUE", "DUE_SOON", "CURRENT");

    private final Tools tools;
    private final CalendarTools calendar;
    private final TodoTools todos;
    private final HouseholdTools household;
    private final NotificationTools notifications;

    public OverviewTools(Tools tools, CalendarTools calendar, TodoTools todos, HouseholdTools household,
                         NotificationTools notifications) {
        this.tools = tools;
        this.calendar = calendar;
        this.todos = todos;
        this.household = household;
        this.notifications = notifications;
    }

    @McpTool(name = "today_overview", description = "Überblick für heute: Termine, eigene dringende ToDos "
            + "(überfällig, bald fällig, aktuell), eigene Hausarbeiten und Zahl ungelesener Benachrichtigungen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Overview todayOverview(McpTransportContext context) {
        // jeder Bereich läuft in eigener Transaktion, damit ein verweigerter Bereich die anderen nicht mitreißt
        String username = tools.read(context, User::getUsername);
        List<String> notes = new ArrayList<>();
        LocalDate today = LocalDate.now(calendar.zone());
        String day = today.toString();
        List<CalendarTools.Event> events = section(notes, "Kalender", () -> calendar.listEvents(context, day, day));
        List<TodoTools.TodoItem> urgent = section(notes, "ToDos", () -> todos
                .listTodos(context, "MINE", null, null, null, null, null, false).stream()
                .filter(t -> URGENT.contains(t.timing())).limit(20).toList());
        List<HouseholdTools.ChoreTaskItem> chores = section(notes, "Haushalt", () -> household.myChoreTasks(context));
        long unread = notifications.listNotifications(context, true).size();
        return new Overview(username, today, events, urgent, chores, unread, notes);
    }

    private static <T> List<T> section(List<String> notes, String name, Supplier<List<T>> load) {
        try {
            return load.get();
        } catch (Tools.ToolException e) {
            notes.add(name + ": " + e.getMessage());
            return List.of();
        }
    }
}
