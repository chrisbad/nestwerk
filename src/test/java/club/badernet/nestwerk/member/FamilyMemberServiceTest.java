package club.badernet.nestwerk.member;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class FamilyMemberServiceTest {

    @Autowired FamilyMemberService members;
    @Autowired UserService userService;

    @Test
    void createsMemberWithoutAccount() {
        FamilyMember m = members.create("Lena", "#ff8800", null);

        assertThat(m.getUserId()).isNull();
        assertThat(members.findAll()).extracting(FamilyMember::getName).contains("Lena");
    }

    @Test
    void linksMemberToUser() {
        User user = userService.create("lena", "passwort1", Set.of());

        FamilyMember m = members.create("Lena", "#ff8800", user.getId());

        assertThat(members.findByUserId(user.getId())).get().extracting(FamilyMember::getId).isEqualTo(m.getId());
    }

    @Test
    void rejectsUserAlreadyLinkedToAnotherMember() {
        User user = userService.create("geteilt", "passwort1", Set.of());
        members.create("Erstes", "#112233", user.getId());

        assertThatThrownBy(() -> members.create("Zweites", "#445566", user.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bereits");
    }

    @Test
    void allowsKeepingOwnLinkOnUpdate() {
        User user = userService.create("eigen", "passwort1", Set.of());
        FamilyMember m = members.create("Eigen", "#112233", user.getId());

        members.update(m.getId(), "Eigen neu", "#445566", user.getId());

        assertThat(members.findAll()).extracting(FamilyMember::getName).contains("Eigen neu");
    }

    @Test
    void rejectsInvalidColorAndBlankName() {
        assertThatThrownBy(() -> members.create("Farbe", "rot", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> members.create(" ", "#112233", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownUser() {
        assertThatThrownBy(() -> members.create("Geist", "#112233", 999_999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesMember() {
        FamilyMember m = members.create("Weg", "#112233", null);

        members.delete(m.getId());

        assertThat(members.findAll()).extracting(FamilyMember::getId).doesNotContain(m.getId());
    }
}
