package club.badernet.nestwerk.attachment;

import club.badernet.nestwerk.contact.Contact;
import club.badernet.nestwerk.contact.ContactService;
import club.badernet.nestwerk.kitchen.KitchenAccess;
import club.badernet.nestwerk.kitchen.KitchenPermission;
import club.badernet.nestwerk.todo.Todo;
import club.badernet.nestwerk.todo.TodoAccess;
import club.badernet.nestwerk.todo.TodoComment;
import club.badernet.nestwerk.todo.TodoCommentService;
import club.badernet.nestwerk.todo.TodoNotFoundException;
import club.badernet.nestwerk.todo.TodoService;
import club.badernet.nestwerk.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Anhänge an ToDos, Kommentaren, Kontakten und Rezepten mit Typ-, Größen- und Rechteprüfung. */
@Service
public class AttachmentService {

    public static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final int HEAD_BYTES = 8192;
    private static final Duration ORPHAN_GRACE = Duration.ofHours(1);
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    /** Ein Anhang samt Dateiinhalt für den Download. */
    public record Download(Attachment attachment, Resource resource) {
    }

    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final TodoService todos;
    private final TodoAccess todoAccess;
    private final TodoCommentService comments;
    private final ContactService contacts;
    private final KitchenAccess kitchen;

    public AttachmentService(AttachmentRepository attachments, AttachmentStorage storage, TodoService todos,
                             TodoAccess todoAccess, TodoCommentService comments, ContactService contacts,
                             KitchenAccess kitchen) {
        this.kitchen = kitchen;
        this.attachments = attachments;
        this.storage = storage;
        this.todos = todos;
        this.todoAccess = todoAccess;
        this.comments = comments;
        this.contacts = contacts;
    }

    @Transactional
    public Attachment store(AttachmentOwner owner, MultipartFile file, User actor) {
        requireMayAttach(owner, actor);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Die Datei ist leer.");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("Eine Datei darf höchstens 20 MB groß sein.");
        }
        String filename = sanitize(file.getOriginalFilename());
        AttachmentType type = AttachmentType.byFilename(filename)
                .orElseThrow(() -> new IllegalArgumentException("Dieser Dateityp ist nicht erlaubt. "
                        + "Möglich sind Bilder, PDF, Office-Dokumente, Text und CSV."));
        if (!type.matches(head(file))) {
            throw new IllegalArgumentException("Der Inhalt von „" + filename + "“ passt nicht zur Dateiendung.");
        }
        String key = UUID.randomUUID().toString().replace("-", "");
        try (InputStream in = file.getInputStream()) {
            storage.write(key, in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return attachments.save(new Attachment(owner, filename, type.contentType(), file.getSize(), key,
                actor.getId()));
    }

    /** Liefert den Anhang, wenn der Betrachter dessen Besitzer sehen darf; sonst wie „nicht vorhanden“. */
    @Transactional(readOnly = true)
    public Download load(Long id, User viewer) {
        Attachment attachment = attachments.findById(id).orElseThrow(TodoNotFoundException::new);
        requireVisible(attachment.owner(), viewer);
        return new Download(attachment, storage.read(attachment.getStorageKey()));
    }

    /** Löschen dürfen Hochlader, Administratoren und wer den Besitzer bearbeiten darf. */
    @Transactional
    public void delete(Long id, User actor) {
        Attachment attachment = attachments.findById(id).orElseThrow(TodoNotFoundException::new);
        requireVisible(attachment.owner(), actor);
        boolean allowed = actor.isAdministrator() || actor.getId().equals(attachment.getUploadedBy())
                || mayEditOwner(attachment.owner(), actor);
        if (!allowed) {
            throw new AccessDeniedException("Keine Berechtigung, diesen Anhang zu löschen");
        }
        attachments.delete(attachment);
    }

    @Transactional(readOnly = true)
    public List<Attachment> forTodo(Long todoId) {
        return attachments.findAllByTodoIdOrderByUploadedAtAsc(todoId);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<Attachment>> forComments(Collection<Long> commentIds) {
        Map<Long, List<Attachment>> result = new HashMap<>();
        if (commentIds.isEmpty()) {
            return result;
        }
        for (Attachment a : attachments.findAllByCommentIdInOrderByUploadedAtAsc(commentIds)) {
            result.computeIfAbsent(a.owner().id(), k -> new java.util.ArrayList<>()).add(a);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Attachment> forContact(Long contactId) {
        return attachments.findAllByContactIdOrderByUploadedAtAsc(contactId);
    }

    @Transactional(readOnly = true)
    public List<Attachment> forRecipe(Long recipeId) {
        return attachments.findAllByRecipeIdOrderByUploadedAtAsc(recipeId);
    }

    /** Entfernt Dateien ohne Datensatz (z. B. nach Löschen des Besitzers); frische Dateien bleiben liegen. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional(readOnly = true)
    public int sweepOrphans() {
        Set<String> known = attachments.findAllStorageKeys();
        Instant threshold = Instant.now().minus(ORPHAN_GRACE);
        int removed = 0;
        for (AttachmentStorage.StoredFile file : storage.list()) {
            if (!known.contains(file.key()) && file.modified().toInstant().isBefore(threshold)) {
                storage.delete(file.key());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("{} verwaiste Anhänge entfernt", removed);
        }
        return removed;
    }

    private void requireMayAttach(AttachmentOwner owner, User actor) {
        switch (owner.type()) {
            case TODO -> {
                Todo todo = todos.get(owner.id(), actor);
                if (!todoAccess.can(actor, "ATTACH", todo)) {
                    throw new AccessDeniedException("Keine Berechtigung, Anhänge hochzuladen");
                }
            }
            case COMMENT -> {
                TodoComment comment = comments.load(owner.id(), actor);
                if (!comment.getAuthorId().equals(actor.getId())) {
                    throw new AccessDeniedException("Anhänge gehören zum eigenen Kommentar");
                }
            }
            case CONTACT -> {
                Contact contact = contacts.get(owner.id(), actor);
                if (!contacts.can(actor, "ATTACH", contact)) {
                    throw new AccessDeniedException("Keine Berechtigung, Anhänge hochzuladen");
                }
            }
            case RECIPE -> kitchen.require(actor, KitchenPermission.KITCHEN_RECIPES_EDIT);
        }
    }

    private void requireVisible(AttachmentOwner owner, User viewer) {
        switch (owner.type()) {
            case TODO -> todos.get(owner.id(), viewer);
            case COMMENT -> comments.load(owner.id(), viewer);
            case CONTACT -> contacts.get(owner.id(), viewer);
            case RECIPE -> kitchen.require(viewer, KitchenPermission.KITCHEN_VIEW);
        }
    }

    private boolean mayEditOwner(AttachmentOwner owner, User actor) {
        return switch (owner.type()) {
            case TODO -> todoAccess.can(actor, "EDIT", todos.get(owner.id(), actor));
            case COMMENT -> comments.load(owner.id(), actor).getAuthorId().equals(actor.getId());
            case CONTACT -> contacts.can(actor, "EDIT", contacts.get(owner.id(), actor));
            case RECIPE -> kitchen.has(actor, KitchenPermission.KITCHEN_RECIPES_EDIT);
        };
    }

    /** Nur der letzte Pfadteil, ohne Steuerzeichen, höchstens 200 Zeichen. */
    static String sanitize(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\"<>|:*?]", "").strip();
        if (name.length() > 200) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, 200 - ext.length()) + ext;
        }
        return name.isEmpty() || name.equals(".") || name.equals("..") ? "datei" : name;
    }

    private static byte[] head(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEAD_BYTES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
