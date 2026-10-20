package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.calendar.CategoryColor;
import club.badernet.nestwerk.calendar.CategoryInput;
import club.badernet.nestwerk.calendar.EventCategory;
import club.badernet.nestwerk.calendar.EventCategoryService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** Event categories (kinds of events with their colour): read, create, change, delete. */
@Component
public class EventCategoryTools {

    /** A category with its palette colour (name, label and hex value). */
    public record Category(Long categoryId, String name, String color, String colorLabel, String hex) {
    }

    private final Tools tools;
    private final EventCategoryService categories;

    public EventCategoryTools(Tools tools, EventCategoryService categories) {
        this.tools = tools;
        this.categories = categories;
    }

    @McpTool(name = "list_event_categories", description = "Listet die Terminkategorien (z. B. Arzt, Sport) mit "
            + "ihrer Farbe. categoryId wird bei create_event und update_event angegeben.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<Category> listEventCategories(McpTransportContext context) {
        return tools.read(context, user -> categories.list(user).stream().map(EventCategoryTools::view).toList());
    }

    @McpTool(name = "get_event_category", description = "Zeigt eine Terminkategorie.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Category getEventCategory(McpTransportContext context,
                                     @McpToolParam(description = "ID aus list_event_categories") Long categoryId) {
        return tools.read(context, user -> view(categories.get(Tools.required(categoryId, "categoryId"), user)));
    }

    @McpTool(name = "create_event_category", description = "Legt eine Terminkategorie an (braucht das Recht "
            + "„Terminkategorien verwalten“). Farbe aus der festen Palette: RED, ORANGE, AMBER, GREEN, TEAL, "
            + "BLUE, INDIGO, VIOLET, PINK, BROWN, SLATE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public Category createEventCategory(McpTransportContext context,
                                        @McpToolParam(description = "Name, 1–60 Zeichen, eindeutig") String name,
                                        @McpToolParam(description = "Farbe aus der Palette, z. B. BLUE") String color) {
        return tools.write(context, user -> view(categories.create(new CategoryInput(name, CategoryColor.parse(color)),
                user)));
    }

    @McpTool(name = "update_event_category", description = "Ändert Name und/oder Farbe einer Terminkategorie; nicht "
            + "angegebene Felder bleiben.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false,
                    idempotentHint = true))
    public Category updateEventCategory(McpTransportContext context,
                                        @McpToolParam(description = "ID aus list_event_categories") Long categoryId,
                                        @McpToolParam(description = "Neuer Name", required = false) String name,
                                        @McpToolParam(description = "Neue Farbe aus der Palette", required = false)
                                        String color) {
        return tools.write(context, user -> {
            Long id = Tools.required(categoryId, "categoryId");
            EventCategory current = categories.get(id, user);
            CategoryInput input = new CategoryInput(name != null ? name : current.getName(),
                    color != null ? CategoryColor.parse(color) : current.getColor());
            return view(categories.update(id, input, user));
        });
    }

    @McpTool(name = "delete_event_category", description = "Löscht eine Terminkategorie endgültig; ihre Termine "
            + "bleiben ohne Kategorie erhalten. Nur nach Rückfrage benutzen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false,
                    idempotentHint = false))
    public String deleteEventCategory(McpTransportContext context,
                                      @McpToolParam(description = "ID aus list_event_categories") Long categoryId) {
        return tools.write(context, user -> {
            Long id = Tools.required(categoryId, "categoryId");
            String name = categories.get(id, user).getName();
            categories.delete(id, user);
            return "Kategorie „" + name + "“ gelöscht; ihre Termine haben jetzt keine Kategorie.";
        });
    }

    private static Category view(EventCategory category) {
        return new Category(category.getId(), category.getName(), category.getColor().name(),
                category.getColor().label(), category.getColor().hex());
    }
}
