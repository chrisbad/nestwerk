package club.badernet.nestwerk.school;

import club.badernet.nestwerk.attachment.Attachment;
import club.badernet.nestwerk.attachment.AttachmentService;
import club.badernet.nestwerk.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Elternbriefe eines Schuljahrs: Frist, Anhang und Unterschrift. */
@Service
public class LetterService {

    /** Ein Brief mit seinen Anhängen für die Anzeige. */
    public record Item(SchoolLetter letter, List<Attachment> attachments) {
    }

    private final SchoolLetterRepository letters;
    private final SchoolYearRepository years;
    private final AttachmentService attachments;
    private final SchoolAccess access;

    public LetterService(SchoolLetterRepository letters, SchoolYearRepository years, AttachmentService attachments,
                         SchoolAccess access) {
        this.letters = letters;
        this.years = years;
        this.attachments = attachments;
        this.access = access;
    }

    /** Offene Briefe zuerst nach Frist, danach die unterschriebenen, neueste zuerst. */
    @Transactional(readOnly = true)
    public List<Item> list(Long yearId) {
        List<SchoolLetter> all = letters.findAllByYearId(yearId);
        Comparator<SchoolLetter> open = Comparator.comparing(SchoolLetter::getDueOn,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(SchoolLetter::getId);
        Comparator<SchoolLetter> done = Comparator.comparing(SchoolLetter::getSignedAt).reversed();
        List<SchoolLetter> sorted = new java.util.ArrayList<>(all.stream().filter(l -> !l.isSigned()).sorted(open)
                .toList());
        sorted.addAll(all.stream().filter(SchoolLetter::isSigned).sorted(done).toList());
        Map<Long, List<Attachment>> files = sorted.stream()
                .collect(Collectors.toMap(SchoolLetter::getId, l -> attachments.forLetter(l.getId())));
        return sorted.stream().map(l -> new Item(l, files.get(l.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public SchoolLetter get(Long id) {
        return letters.findById(id).orElseThrow(() -> new IllegalArgumentException("Brief nicht gefunden"));
    }

    @Transactional
    public SchoolLetter create(Long yearId, String title, LocalDate dueOn, String note, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        if (!years.existsById(yearId)) {
            throw new IllegalArgumentException("Schuljahr nicht gefunden");
        }
        String cleanTitle = title == null ? "" : title.trim();
        if (cleanTitle.isEmpty() || cleanTitle.length() > 150) {
            throw new IllegalArgumentException("Der Titel muss 1–150 Zeichen lang sein.");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > 500) {
            throw new IllegalArgumentException("Die Notiz darf höchstens 500 Zeichen lang sein.");
        }
        return letters.save(new SchoolLetter(yearId, cleanTitle, dueOn, cleanNote));
    }

    @Transactional
    public void setSigned(Long id, boolean signed, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        SchoolLetter letter = get(id);
        if (signed) {
            letter.sign(actor.getId());
        } else {
            letter.unsign();
        }
    }

    @Transactional
    public void delete(Long id, User actor) {
        access.require(actor, SchoolPermission.SCHOOL_EDIT);
        letters.delete(get(id));
    }
}
