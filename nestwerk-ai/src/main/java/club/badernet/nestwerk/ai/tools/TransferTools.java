package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.todo.TodoFilter;
import club.badernet.nestwerk.transfer.CalendarTransfer;
import club.badernet.nestwerk.transfer.ContactTransfer;
import club.badernet.nestwerk.transfer.ImportReport;
import club.badernet.nestwerk.transfer.TodoTransfer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Export und Import von Terminen (iCal), Kontakten (vCard, CSV) und ToDos (CSV) als Text. */
@Component
public class TransferTools {

    private static final int MAX_EXPORT_CHARS = 200_000;
    private static final int MAX_IMPORT_BYTES = 1_000_000;

    public record Export(String format, int characters, boolean truncated, String content) {
    }

    public record ImportRow(String label, String detail, String action, String problem) {
    }

    public record ImportResult(boolean applied, long created, long updated, long skipped, List<ImportRow> rows,
                               List<String> notes) {
    }

    private final Tools tools;
    private final CalendarTransfer calendar;
    private final ContactTransfer contacts;
    private final TodoTransfer todos;

    public TransferTools(Tools tools, CalendarTransfer calendar, ContactTransfer contacts, TodoTransfer todos) {
        this.tools = tools;
        this.calendar = calendar;
        this.contacts = contacts;
        this.todos = todos;
    }

    @McpTool(name = "export_data", description = "Exportiert sichtbare Daten als Text: format CALENDAR_ICS (alle "
            + "Termine als iCal), CONTACTS_VCF, CONTACTS_CSV oder TODOS_CSV (alle offenen und erledigten ToDos). "
            + "Sehr lange Exporte werden nach 200.000 Zeichen abgeschnitten (truncated=true).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Export exportData(McpTransportContext context,
                             @McpToolParam(description = "CALENDAR_ICS, CONTACTS_VCF, CONTACTS_CSV oder TODOS_CSV")
                             String format) {
        return tools.read(context, user -> {
            String key = Tools.required(format, "format").trim().toUpperCase(Locale.ROOT);
            String text = switch (key) {
                case "CALENDAR_ICS" -> calendar.exportIcs(user);
                case "CONTACTS_VCF" -> contacts.exportVcf(user);
                case "CONTACTS_CSV" -> new String(contacts.exportCsv(user), StandardCharsets.UTF_8);
                case "TODOS_CSV" -> new String(todos.exportCsv(user, new TodoFilter(TodoFilter.Involvement.ALL, null,
                        null, null, null, null, null, true)), StandardCharsets.UTF_8);
                default -> throw new Tools.ToolException(
                        "format: erlaubt sind CALENDAR_ICS, CONTACTS_VCF, CONTACTS_CSV, TODOS_CSV.");
            };
            boolean cut = text.length() > MAX_EXPORT_CHARS;
            return new Export(key, text.length(), cut, cut ? text.substring(0, MAX_EXPORT_CHARS) : text);
        });
    }

    @McpTool(name = "import_data", description = "Importiert Daten aus Text: format CALENDAR_ICS, CONTACTS_VCF, "
            + "CONTACTS_CSV oder TODOS_CSV, content ist der Dateiinhalt (höchstens 1 MB). Ohne apply=true gibt es "
            + "nur eine Vorschau (was neu, geändert oder übersprungen würde); mit apply=true wird übernommen. "
            + "Vorhandene Einträge mit gleicher UID, ID oder Name werden aktualisiert statt doppelt angelegt. "
            + "Zeigt zuerst die Vorschau und lässt den Benutzer bestätigen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public ImportResult importData(McpTransportContext context,
                                   @McpToolParam(description = "CALENDAR_ICS, CONTACTS_VCF, CONTACTS_CSV oder "
                                           + "TODOS_CSV") String format,
                                   @McpToolParam(description = "Dateiinhalt als Text") String content,
                                   @McpToolParam(description = "true = übernehmen, sonst nur Vorschau",
                                           required = false) Boolean apply) {
        return tools.write(context, user -> {
            String key = Tools.required(format, "format").trim().toUpperCase(Locale.ROOT);
            byte[] data = Tools.required(content, "content").getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_IMPORT_BYTES) {
                throw new Tools.ToolException("Der Inhalt ist größer als 1 MB.");
            }
            boolean commit = Boolean.TRUE.equals(apply);
            ImportReport report = switch (key) {
                case "CALENDAR_ICS" -> calendar.importIcs(data, user, commit);
                case "CONTACTS_VCF" -> contacts.importVcf(data, user, commit);
                case "CONTACTS_CSV" -> contacts.importCsv(data, user, commit);
                case "TODOS_CSV" -> todos.importCsv(data, user, commit);
                default -> throw new Tools.ToolException(
                        "format: erlaubt sind CALENDAR_ICS, CONTACTS_VCF, CONTACTS_CSV, TODOS_CSV.");
            };
            return new ImportResult(report.applied(), report.getNewCount(), report.getUpdateCount(),
                    report.getSkipCount(), report.rows().stream().limit(200).map(r -> new ImportRow(r.label(),
                    r.detail(), r.action().name(), r.problem())).toList(), report.notes());
        });
    }
}
