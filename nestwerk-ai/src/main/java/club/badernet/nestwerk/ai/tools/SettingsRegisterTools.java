package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.web.SettingArea;
import club.badernet.nestwerk.web.SettingEntry;
import club.badernet.nestwerk.web.SettingFilter;
import club.badernet.nestwerk.web.SettingScope;
import club.badernet.nestwerk.web.SettingStatus;
import club.badernet.nestwerk.web.Settings;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The register of all settings: find where something is set and what it currently says. */
@Component
public class SettingsRegisterTools {

    /** One setting: where it lives, whom it applies to, its current value and the page that edits it. */
    public record SettingItem(String settingId, String area, String scope, String title, String description,
                              String currentValue, String status, String path) {
    }

    private final Tools tools;
    private final Settings settings;

    public SettingsRegisterTools(Tools tools, Settings settings) {
        this.tools = tools;
        this.settings = settings;
    }

    @McpTool(name = "list_settings", description = "Listet die Einstellungen der App, die der Benutzer sehen darf, "
            + "mit aktuellem Wert und Status (OK, ATTENTION, NOT_SET). Filter sind optional: text sucht in Titel, "
            + "Beschreibung und Schlagwörtern; area ist CALENDAR, HOUSEHOLD, KITCHEN, SCHOOL, NOTIFICATIONS, ACCOUNT "
            + "oder SYSTEM; scope ist PERSONAL, FAMILY oder SYSTEM. Geändert wird über die jeweiligen Fachtools, "
            + "path ist die Seite der Weboberfläche.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<SettingItem> listSettings(McpTransportContext context,
                                          @McpToolParam(description = "Suchtext", required = false) String text,
                                          @McpToolParam(description = "Bereich, z. B. CALENDAR", required = false)
                                          String area,
                                          @McpToolParam(description = "Geltung: PERSONAL, FAMILY oder SYSTEM",
                                                  required = false) String scope,
                                          @McpToolParam(description = "Status: OK, ATTENTION oder NOT_SET",
                                                  required = false) String status) {
        return tools.read(context, user -> {
            SettingFilter filter = new SettingFilter(text, areas(area), parse(SettingScope.class, scope),
                    parse(SettingStatus.class, status));
            return settings.list(user, filter).stream().map(SettingsRegisterTools::view).toList();
        });
    }

    private static Set<SettingArea> areas(String area) {
        SettingArea parsed = parse(SettingArea.class, area);
        return parsed == null ? EnumSet.noneOf(SettingArea.class) : EnumSet.of(parsed);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Tools.ToolException("Unbekannter Wert „" + value + "“. Erlaubt: "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    private static SettingItem view(SettingEntry entry) {
        return new SettingItem(entry.id(), entry.area().name(), entry.scope().name(), entry.title(),
                entry.description(), entry.summary(), entry.status().name(), entry.path());
    }
}
