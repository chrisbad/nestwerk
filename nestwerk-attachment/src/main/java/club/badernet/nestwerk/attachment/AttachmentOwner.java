package club.badernet.nestwerk.attachment;

/** Wem ein Anhang gehört: einem ToDo, einem Kommentar, einem Kontakt oder einem Rezept. */
public record AttachmentOwner(Type type, Long id) {

    public enum Type { TODO, COMMENT, CONTACT, RECIPE }

    public static AttachmentOwner todo(Long id) {
        return new AttachmentOwner(Type.TODO, id);
    }

    public static AttachmentOwner comment(Long id) {
        return new AttachmentOwner(Type.COMMENT, id);
    }

    public static AttachmentOwner contact(Long id) {
        return new AttachmentOwner(Type.CONTACT, id);
    }

    public static AttachmentOwner recipe(Long id) {
        return new AttachmentOwner(Type.RECIPE, id);
    }
}
