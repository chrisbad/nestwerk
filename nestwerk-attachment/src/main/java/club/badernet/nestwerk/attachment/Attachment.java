package club.badernet.nestwerk.attachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Metadaten einer hochgeladenen Datei; der Inhalt liegt im Dateisystem unter {@code storageKey}. */
@Entity
@Table(name = "attachments")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "todo_id", updatable = false)
    private Long todoId;

    @Column(name = "comment_id", updatable = false)
    private Long commentId;

    @Column(name = "contact_id", updatable = false)
    private Long contactId;

    @Column(name = "recipe_id", updatable = false)
    private Long recipeId;

    @Column(name = "letter_id", updatable = false)
    private Long letterId;

    @Column(nullable = false, length = 255, updatable = false)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(nullable = false, updatable = false)
    private long size;

    @Column(name = "storage_key", nullable = false, length = 64, unique = true, updatable = false)
    private String storageKey;

    @Column(name = "uploaded_by", updatable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt = Instant.now();

    protected Attachment() {
    }

    Attachment(AttachmentOwner owner, String filename, String contentType, long size, String storageKey,
               Long uploadedBy) {
        switch (owner.type()) {
            case TODO -> todoId = owner.id();
            case COMMENT -> commentId = owner.id();
            case CONTACT -> contactId = owner.id();
            case RECIPE -> recipeId = owner.id();
            case LETTER -> letterId = owner.id();
        }
        this.filename = filename;
        this.contentType = contentType;
        this.size = size;
        this.storageKey = storageKey;
        this.uploadedBy = uploadedBy;
    }

    public AttachmentOwner owner() {
        if (todoId != null) {
            return AttachmentOwner.todo(todoId);
        }
        if (commentId != null) {
            return AttachmentOwner.comment(commentId);
        }
        if (recipeId != null) {
            return AttachmentOwner.recipe(recipeId);
        }
        if (letterId != null) {
            return AttachmentOwner.letter(letterId);
        }
        return AttachmentOwner.contact(contactId);
    }

    public Long getId() { return id; }
    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
    public String getStorageKey() { return storageKey; }
    public Long getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }

    public boolean isImage() {
        return contentType.startsWith("image/");
    }

    /** Lesbare Größe, z. B. „1,2 MB“. */
    public String sizeLabel() {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return Math.round(size / 1024.0) + " KB";
        }
        return String.format(java.util.Locale.GERMANY, "%.1f MB", size / (1024.0 * 1024.0));
    }
}
