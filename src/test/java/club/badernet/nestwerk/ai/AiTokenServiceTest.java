package club.badernet.nestwerk.ai;

import club.badernet.nestwerk.TestcontainersConfiguration;
import club.badernet.nestwerk.role.Role;
import club.badernet.nestwerk.role.RoleService;
import club.badernet.nestwerk.user.User;
import club.badernet.nestwerk.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
@Transactional
class AiTokenServiceTest {

    @Autowired AiTokenService service;
    @Autowired AiTokenRepository tokens;
    @Autowired UserService userService;
    @Autowired RoleService roleService;

    User mama;
    User papa;
    User kid;

    @BeforeEach
    void setUp() {
        Role ki = roleService.create("KI-Nutzer", null, Set.of("AI_ACCESS"));
        mama = userService.create("mama", "passwort1", Set.of(ki.getId()));
        papa = userService.create("papa", "passwort1", Set.of(ki.getId()));
        kid = userService.create("kind", "passwort1", Set.of());
    }

    @Test
    void issuesTokenOnceAndStoresOnlyHash() {
        AiTokenService.Issued issued = service.create("Claude", null, mama);

        assertThat(issued.plaintext()).startsWith("nw_").hasSizeGreaterThan(40);
        AiToken stored = tokens.findById(issued.token().getId()).orElseThrow();
        assertThat(stored.getTokenHash()).hasSize(64).doesNotContain(issued.plaintext());
        assertThat(service.authenticate(issued.plaintext())).map(User::getId).contains(mama.getId());
        assertThat(service.mine(mama)).extracting(AiToken::getName).containsExactly("Claude");
    }

    @Test
    void rejectsUnknownRevokedAndExpiredTokens() {
        AiTokenService.Issued revoked = service.create("alt", null, mama);
        service.revoke(revoked.token().getId(), mama);
        AiTokenService.Issued expired = service.create("abgelaufen", LocalDate.now().minusDays(1), mama);

        assertThat(service.authenticate("nw_gibtesnicht")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate(revoked.plaintext())).isEmpty();
        assertThat(service.authenticate(expired.plaintext())).isEmpty();
    }

    @Test
    void requiresAiAccessPermission() {
        assertThatThrownBy(() -> service.create("Claude", null, kid)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.mayUse(kid)).isFalse();
        assertThat(service.mayUse(mama)).isTrue();
    }

    @Test
    void tokenStopsWorkingWhenPermissionIsWithdrawn() {
        AiTokenService.Issued issued = service.create("Claude", null, mama);
        userService.changeRoles(mama.getId(), Set.of());

        assertThat(service.authenticate(issued.plaintext())).isEmpty();
    }

    @Test
    void tokenOfDisabledUserIsRejected() {
        AiTokenService.Issued issued = service.create("Claude", null, mama);
        userService.setEnabled(mama.getId(), false);

        assertThat(service.authenticate(issued.plaintext())).isEmpty();
    }

    @Test
    void cannotRevokeForeignToken() {
        AiTokenService.Issued issued = service.create("Claude", null, mama);

        assertThatThrownBy(() -> service.revoke(issued.token().getId(), papa))
                .isInstanceOf(AiTokenNotFoundException.class);
        assertThat(service.authenticate(issued.plaintext())).isPresent();
    }

    @Test
    void validatesName() {
        assertThatThrownBy(() -> service.create("  ", null, mama)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("x".repeat(101), null, mama))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
