package club.badernet.nestwerk.security;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
class PermissionCatalogVerifierTest {

    @Autowired PermissionCatalogVerifier verifier;
    @Autowired PermissionCatalog catalog;

    @Test
    void acceptsKeysThatExist() {
        assertThatCode(() -> verifier.verify(catalog.codeKeys())).doesNotThrowAnyException();
    }

    @Test
    void namesMissingKeys() {
        assertThatThrownBy(() -> verifier.verify(List.of("USER_MANAGE", "FEHLT_NOCH", "AUCH_WEG")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FEHLT_NOCH")
                .hasMessageContaining("AUCH_WEG")
                .hasMessageNotContaining("USER_MANAGE");
    }
}
