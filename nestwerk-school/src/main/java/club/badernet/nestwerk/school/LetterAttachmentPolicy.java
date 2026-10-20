package club.badernet.nestwerk.school;

import club.badernet.nestwerk.attachment.AttachmentOwner;
import club.badernet.nestwerk.attachment.AttachmentOwnerPolicy;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Component;

/** Anhänge an Elternbriefe: sehen mit {@code SCHOOL_VIEW}, anhängen und löschen mit {@code SCHOOL_EDIT}. */
@Component
class LetterAttachmentPolicy implements AttachmentOwnerPolicy {

    private final SchoolLetterRepository letters;
    private final SchoolAccess access;

    LetterAttachmentPolicy(SchoolLetterRepository letters, SchoolAccess access) {
        this.letters = letters;
        this.access = access;
    }

    @Override
    public AttachmentOwner.Type type() {
        return AttachmentOwner.Type.LETTER;
    }

    @Override
    public void requireMayAttach(Long ownerId, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        requireExists(ownerId);
    }

    @Override
    public void requireVisible(Long ownerId, User viewer) {
        access.require(viewer, SchoolPermission.SCHOOL_VIEW);
        requireExists(ownerId);
    }

    @Override
    public boolean mayEdit(Long ownerId, User actor) {
        return access.has(actor, SchoolPermission.SCHOOL_EDIT);
    }

    private void requireExists(Long id) {
        if (!letters.existsById(id)) {
            throw new IllegalArgumentException("Brief nicht gefunden");
        }
    }
}
