package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.item.CalendarItem;
import club.badernet.nestwerk.item.CalendarItems;
import club.badernet.nestwerk.item.ItemAction;
import club.badernet.nestwerk.item.ItemType;
import club.badernet.nestwerk.settings.CalendarSettings;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the calendar shows (events, chores, to-dos, school dates) through one pair of tools. Creating, changing
 * and deleting stays with the type-specific tools, which go through the same domain services as the web UI.
 */
@Component
public class CalendarItemTools {

    private static final int MAX_DAYS = 92;

    /** One calendar entry; {@code details} holds the properties of its type, {@code actions} what the caller may do. */
    public record Item(String type, Long id, String title, String start, String end, boolean allDay,
                       List<Long> memberIds, String color, String status, Long seriesId, List<String> actions,
                       Map<String, String> details) {
    }

    private final Tools tools;
    private final CalendarItems items;
    private final ZoneId zone;

    public CalendarItemTools(Tools tools, CalendarItems items, CalendarSettings settings) {
        this.tools = tools;
        this.items = items;
        this.zone = settings.zone();
    }

    @McpTool(name = "list_calendar_items", description = "Listet alles, was der Kalender zeigt, in einer Liste: "
            + "Termine (EVENT), geplante Hausarbeiten (CHORE), fällige ToDos (TODO) und Schultermine (SCHOOL) "
            + "im Zeitraum von–bis, jeweils einschließlich, nach Beginn sortiert. Ohne Angabe: heute bis in 7 "
            + "Tagen, höchstens 92 Tage. Mit type und memberId lässt sich einschränken. actions nennt, was der "
            + "Aufrufer mit dem Eintrag tun darf. Ändern und Löschen geschieht mit den Tools des jeweiligen Typs.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<Item> listCalendarItems(McpTransportContext context,
                                        @McpToolParam(description = "Erster Tag, JJJJ-MM-TT", required = false)
                                        String from,
                                        @McpToolParam(description = "Letzter Tag, JJJJ-MM-TT", required = false)
                                        String to,
                                        @McpToolParam(description = "Nur diesen Typ: EVENT, CHORE, TODO oder SCHOOL",
                                                required = false) String type,
                                        @McpToolParam(description = "Nur Einträge dieser Person (ID aus list_members)",
                                                required = false) Long memberId) {
        return tools.read(context, user -> {
            LocalDate first = Tools.date(from, "from");
            if (first == null) {
                first = LocalDate.now(zone);
            }
            LocalDate last = Tools.date(to, "to");
            if (last == null) {
                last = first.plusDays(7);
            }
            if (last.isBefore(first) || last.isAfter(first.plusDays(MAX_DAYS))) {
                throw new Tools.ToolException("Der Zeitraum muss 0–" + MAX_DAYS + " Tage umfassen.");
            }
            ItemType only = Tools.enumValue(ItemType.class, type, "type");
            Set<ItemType> types = only == null ? Set.of() : Set.of(only);
            return items.between(first, last, user, types, memberId).stream().map(CalendarItemTools::view).toList();
        });
    }

    @McpTool(name = "get_calendar_item", description = "Zeigt einen Kalendereintrag mit Typ und Eigenschaften. "
            + "type ist EVENT, CHORE, TODO oder SCHOOL, die ID kommt aus list_calendar_items. Bei Serien ist es das "
            + "erste Vorkommen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Item getCalendarItem(McpTransportContext context,
                                @McpToolParam(description = "EVENT, CHORE, TODO oder SCHOOL") String type,
                                @McpToolParam(description = "ID des Eintrags aus list_calendar_items") Long id) {
        return tools.read(context, user -> {
            ItemType itemType = Tools.required(Tools.enumValue(ItemType.class, type, "type"), "type");
            return items.find(itemType, Tools.required(id, "id"), user).map(CalendarItemTools::view)
                    .orElseThrow(() -> new Tools.ToolException("Eintrag nicht gefunden"));
        });
    }

    private static Item view(CalendarItem item) {
        List<String> actions = item.actions().stream().sorted(Comparator.naturalOrder()).map(ItemAction::name)
                .toList();
        return new Item(item.type().name(), item.id(), item.title(), item.start().toString(), item.end().toString(),
                item.allDay(), item.memberIds().stream().sorted().toList(), item.color(), item.status(),
                item.seriesId(), actions, details(item));
    }

    private static Map<String, String> details(CalendarItem item) {
        Map<String, String> details = new LinkedHashMap<>();
        switch (item) {
            case CalendarItem.Event e -> {
                put(details, "location", e.location());
                put(details, "recurrenceId", e.occurrence() == null ? null : e.occurrence().toString());
                put(details, "private", e.privateEvent() ? "true" : null);
            }
            case CalendarItem.Chore c -> {
                details.put("minutes", String.valueOf(c.minutes()));
                details.put("planned", String.valueOf(c.planned()));
            }
            case CalendarItem.Todo t -> details.put("priority", t.priority());
            case CalendarItem.School s -> details.put("kind", s.kind());
        }
        return details;
    }

    private static void put(Map<String, String> details, String key, String value) {
        if (value != null && !value.isBlank()) {
            details.put(key, value);
        }
    }
}
