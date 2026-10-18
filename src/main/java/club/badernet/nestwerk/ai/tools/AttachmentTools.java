package club.badernet.nestwerk.ai.tools;

import club.badernet.nestwerk.attachment.Attachment;
import club.badernet.nestwerk.attachment.AttachmentOwner;
import club.badernet.nestwerk.attachment.AttachmentService;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.kitchen.RecipeService;
import club.badernet.nestwerk.todo.TodoCommentService;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.user.User;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Anhänge an ToDos, Kommentaren, Kontakten und Rezepten: auflisten, Text- und CSV-Dateien lesen, kleine Dateien
 * hinzufügen. Bilder, PDFs und Office-Dateien lassen sich hier nicht lesen, nur auflisten.
 */
@Component
public class AttachmentTools {

    private static final int MAX_READ_CHARS = 200_000;
    private static final int MAX_UPLOAD_BYTES = 3_000_000;

    public record AttachmentItem(Long attachmentId, String fileName, String contentType, long sizeBytes,
                                 String uploaded, boolean readableAsText) {
    }

    public record AttachmentContent(AttachmentItem attachment, boolean truncated, String text) {
    }

    private final Tools tools;
    private final AttachmentService attachments;
    private final TodoService todos;
    private final TodoCommentService comments;
    private final ContactService contacts;
    private final RecipeService recipes;

    public AttachmentTools(Tools tools, AttachmentService attachments, TodoService todos,
                           TodoCommentService comments, ContactService contacts, RecipeService recipes) {
        this.tools = tools;
        this.attachments = attachments;
        this.todos = todos;
        this.comments = comments;
        this.contacts = contacts;
        this.recipes = recipes;
    }

    @McpTool(name = "list_attachments", description = "Listet die Anhänge eines ToDos, Kommentars, Kontakts oder "
            + "Rezepts (ownerType TODO, COMMENT, CONTACT oder RECIPE).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<AttachmentItem> listAttachments(McpTransportContext context,
                                                @McpToolParam(description = "TODO, COMMENT, CONTACT oder RECIPE")
                                                String ownerType,
                                                @McpToolParam(description = "ID des ToDos, Kommentars, Kontakts oder "
                                                        + "Rezepts") Long ownerId) {
        return tools.read(context, user -> {
            AttachmentOwner owner = owner(ownerType, ownerId);
            requireVisible(owner, user);
            List<Attachment> found = switch (owner.type()) {
                case TODO -> attachments.forTodo(owner.id());
                case COMMENT -> attachments.forComments(List.of(owner.id())).getOrDefault(owner.id(), List.of());
                case CONTACT -> attachments.forContact(owner.id());
                case RECIPE -> attachments.forRecipe(owner.id());
            };
            return found.stream().map(AttachmentTools::item).toList();
        });
    }

    @McpTool(name = "read_attachment", description = "Liest einen Anhang als Text, wenn es eine .txt- oder "
            + ".csv-Datei ist (höchstens 200.000 Zeichen). Andere Dateitypen lassen sich nur auflisten.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public AttachmentContent readAttachment(McpTransportContext context,
                                            @McpToolParam(description = "attachmentId aus list_attachments")
                                            Long attachmentId) {
        return tools.read(context, user -> {
            AttachmentService.Download download = attachments.load(Tools.required(attachmentId, "attachmentId"), user);
            AttachmentItem item = item(download.attachment());
            if (!item.readableAsText()) {
                throw new Tools.ToolException("„" + item.fileName() + "“ ist keine Text- oder CSV-Datei und lässt "
                        + "sich hier nicht lesen.");
            }
            try (InputStream in = download.resource().getInputStream()) {
                String text = new String(in.readNBytes(MAX_READ_CHARS * 4), StandardCharsets.UTF_8);
                boolean cut = text.length() > MAX_READ_CHARS;
                return new AttachmentContent(item, cut, cut ? text.substring(0, MAX_READ_CHARS) : text);
            } catch (IOException e) {
                throw new Tools.ToolException("Die Datei konnte nicht gelesen werden.");
            }
        });
    }

    @McpTool(name = "add_attachment", description = "Hängt eine Datei an ein ToDo, einen Kommentar, einen Kontakt "
            + "oder ein Rezept an. Der Inhalt kommt entweder als text (für .txt/.csv) oder als base64 (bis ca. 3 MB). "
            + "Erlaubt sind Bilder, PDF, Office-Dokumente, Text und CSV; der Inhalt muss zur Endung passen.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false))
    public AttachmentItem addAttachment(McpTransportContext context,
                                        @McpToolParam(description = "TODO, COMMENT, CONTACT oder RECIPE")
                                        String ownerType,
                                        @McpToolParam(description = "ID des Besitzers") Long ownerId,
                                        @McpToolParam(description = "Dateiname mit Endung, z. B. liste.csv")
                                        String fileName,
                                        @McpToolParam(description = "Inhalt als Text", required = false) String text,
                                        @McpToolParam(description = "Inhalt base64-kodiert", required = false)
                                        String base64) {
        return tools.write(context, user -> {
            AttachmentOwner owner = owner(ownerType, ownerId);
            byte[] data;
            if (base64 != null && !base64.isBlank()) {
                try {
                    data = Base64.getDecoder().decode(base64.replaceAll("\\s+", ""));
                } catch (IllegalArgumentException e) {
                    throw new Tools.ToolException("base64: kein gültiger Base64-Inhalt.");
                }
            } else {
                data = Tools.required(text, "text oder base64").getBytes(StandardCharsets.UTF_8);
            }
            if (data.length > MAX_UPLOAD_BYTES) {
                throw new Tools.ToolException("Die Datei ist größer als 3 MB.");
            }
            return item(attachments.store(owner, new InMemoryFile(Tools.required(fileName, "fileName"), data), user));
        });
    }

    private void requireVisible(AttachmentOwner owner, User user) {
        switch (owner.type()) {
            case TODO -> todos.get(owner.id(), user);
            case COMMENT -> comments.load(owner.id(), user);
            case CONTACT -> contacts.get(owner.id(), user);
            case RECIPE -> recipes.get(owner.id(), user);
        }
    }

    private static AttachmentOwner owner(String type, Long id) {
        AttachmentOwner.Type t = Tools.required(Tools.enumValue(AttachmentOwner.Type.class, type, "ownerType"),
                "ownerType");
        Long ownerId = Tools.required(id, "ownerId");
        return switch (t) {
            case TODO -> AttachmentOwner.todo(ownerId);
            case COMMENT -> AttachmentOwner.comment(ownerId);
            case CONTACT -> AttachmentOwner.contact(ownerId);
            case RECIPE -> AttachmentOwner.recipe(ownerId);
        };
    }

    private static AttachmentItem item(Attachment a) {
        String type = a.getContentType() == null ? "" : a.getContentType().toLowerCase(Locale.ROOT);
        return new AttachmentItem(a.getId(), a.getFilename(), a.getContentType(), a.getSize(),
                String.valueOf(a.getUploadedAt()), type.startsWith("text/"));
    }

    /** Eine im Speicher liegende Datei, damit der Anhangs-Dienst sie wie einen Upload der Oberfläche prüft. */
    private record InMemoryFile(String name, byte[] data) implements MultipartFile {

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return name;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return data.length == 0;
        }

        @Override
        public long getSize() {
            return data.length;
        }

        @Override
        public byte[] getBytes() {
            return data;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(data);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), data);
        }
    }
}
