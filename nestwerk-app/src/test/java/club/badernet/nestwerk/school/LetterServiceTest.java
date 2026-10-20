package club.badernet.nestwerk.school;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.attachment.Attachment;
import club.badernet.nestwerk.attachment.AttachmentOwner;
import club.badernet.nestwerk.attachment.AttachmentService;
import club.badernet.nestwerk.member.FamilyMember;
import club.badernet.nestwerk.member.FamilyMemberService;
import club.badernet.nestwerk.notification.Notification;
import club.badernet.nestwerk.notification.NotificationService;
import club.badernet.nestwerk.notification.NotificationType;
import club.badernet.nestwerk.role.RoleRepository;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class LetterServiceTest {

    static final LocalDate DUE = LocalDate.of(2026, 10, 20);

    @Autowired SchoolService school;
    @Autowired LetterService letters;
    @Autowired LetterReminder reminder;
    @Autowired AttachmentService attachments;
    @Autowired NotificationService notifications;
    @Autowired FamilyMemberService members;
    @Autowired UserService users;
    @Autowired RoleRepository roles;

    User admin;
    User parent;
    FamilyMember lena;
    SchoolYear year;

    @BeforeEach
    void setUp() {
        admin = users.createAdministrator("chef", "passwort1");
        parent = users.create("mama", "passwort1", Set.of(roles.findByNameIgnoreCase("Benutzer").orElseThrow().getId()));
        lena = members.create("Lena", "#aabbcc", null);
        SchoolProfile profile = school.createProfile(lena.getId(), null);
        year = school.startYear(profile.getId(), "2026/27", null, "3a", LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 7, 31));
    }

    @Test
    void createsLettersAndSortsOpenOnesByDueDateBeforeSignedOnes() {
        SchoolLetter late = letters.create(year.getId(), "Spät", DUE.plusDays(10), null, admin);
        SchoolLetter early = letters.create(year.getId(), "Früh", DUE, "Bitte bis Montag", admin);
        SchoolLetter none = letters.create(year.getId(), "Ohne Frist", null, null, admin);
        SchoolLetter signed = letters.create(year.getId(), "Fertig", DUE.minusDays(5), null, admin);
        letters.setSigned(signed.getId(), true, admin);

        assertThat(letters.list(year.getId())).extracting(i -> i.letter().getTitle())
                .containsExactly("Früh", "Spät", "Ohne Frist", "Fertig");
        assertThat(early.getNote()).isEqualTo("Bitte bis Montag");
        assertThat(late.isSigned()).isFalse();
        assertThat(none.getDueOn()).isNull();
    }

    @Test
    void signAndUnsignRecordWhoAndWhen() {
        SchoolLetter letter = letters.create(year.getId(), "Zoo", DUE, null, admin);

        letters.setSigned(letter.getId(), true, admin);
        assertThat(letters.get(letter.getId()).isSigned()).isTrue();
        assertThat(letters.get(letter.getId()).getSignedBy()).isEqualTo(admin.getId());

        letters.setSigned(letter.getId(), false, admin);
        assertThat(letters.get(letter.getId()).isSigned()).isFalse();
        assertThat(letters.get(letter.getId()).getSignedBy()).isNull();
    }

    @Test
    void validatesInputAndRights() {
        assertThatThrownBy(() -> letters.create(year.getId(), " ", DUE, null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Titel");
        assertThatThrownBy(() -> letters.create(year.getId(), "x", DUE, "a".repeat(501), admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Notiz");
        assertThatThrownBy(() -> letters.create(999_999L, "x", DUE, null, admin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> letters.create(year.getId(), "x", DUE, null, parent))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void attachmentsBelongToTheLetterAndFollowSchoolRights() {
        SchoolLetter letter = letters.create(year.getId(), "Zoo", DUE, null, admin);
        MockMultipartFile file = new MockMultipartFile("files", "zoo.txt", "text/plain", "Treffpunkt 8 Uhr".getBytes());

        Attachment stored = attachments.store(AttachmentOwner.letter(letter.getId()), file, admin);

        assertThat(attachments.forLetter(letter.getId())).extracting(Attachment::getFilename).containsExactly("zoo.txt");
        assertThat(stored.owner()).isEqualTo(AttachmentOwner.letter(letter.getId()));
        assertThat(attachments.load(stored.getId(), parent).attachment().getFilename()).isEqualTo("zoo.txt");
        assertThatThrownBy(() -> attachments.store(AttachmentOwner.letter(letter.getId()), file, parent))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> attachments.delete(stored.getId(), parent)).isInstanceOf(AccessDeniedException.class);

        User outsider = users.create("gast", "passwort1", Set.of());
        assertThatThrownBy(() -> attachments.load(stored.getId(), outsider)).isInstanceOf(AccessDeniedException.class);

        attachments.delete(stored.getId(), admin);
        assertThat(attachments.forLetter(letter.getId())).isEmpty();
    }

    @Test
    void deletingALetterRemovesItsAttachments() {
        SchoolLetter letter = letters.create(year.getId(), "Zoo", DUE, null, admin);
        attachments.store(AttachmentOwner.letter(letter.getId()),
                new MockMultipartFile("files", "a.txt", "text/plain", "x".getBytes()), admin);

        letters.delete(letter.getId(), admin);

        assertThat(letters.list(year.getId())).isEmpty();
        assertThat(attachments.forLetter(letter.getId())).isEmpty();
    }

    @Test
    void remindsThreeDaysOneDayAndOnTheDayOnlyWhileUnsigned() {
        SchoolLetter letter = letters.create(year.getId(), "Zoo", DUE, null, admin);

        assertThat(reminder.run(DUE.minusDays(5))).isZero();
        assertThat(reminder.run(DUE.minusDays(3))).isPositive();
        assertThat(reminder.run(DUE.minusDays(3))).as("nicht doppelt").isZero();
        assertThat(reminder.run(DUE.minusDays(2))).isZero();
        assertThat(reminder.run(DUE.minusDays(1))).isPositive();
        assertThat(reminder.run(DUE)).isPositive();

        List<Notification> mine = notifications.list(parent.getId(), false, 20);
        assertThat(mine).extracting(Notification::getType).containsOnly(NotificationType.SCHOOL_LETTER);
        assertThat(mine).extracting(Notification::getTitle).contains(
                "Elternbrief „Zoo“ ist in 3 Tagen fällig", "Elternbrief „Zoo“ ist morgen fällig",
                "Elternbrief „Zoo“ ist heute fällig");

        letters.setSigned(letter.getId(), true, admin);
        assertThat(reminder.run(DUE.minusDays(1))).as("unterschrieben").isZero();
    }

    @Test
    void noReminderWhenTheYearIsNotActive() {
        letters.create(year.getId(), "Zoo", DUE, null, admin);
        SchoolProfile profile = school.profileOf(lena.getId()).orElseThrow();
        school.startYear(profile.getId(), "2027/28", null, "4a", LocalDate.of(2027, 9, 1),
                LocalDate.of(2028, 7, 31));

        assertThat(reminder.run(DUE.minusDays(1))).isZero();
    }
}
