package club.badernet.nestwerk.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** Bricht den Start ab, wenn der Code Rechte prüft, die per Migration nie angelegt wurden. */
@Component
public class PermissionCatalogVerifier implements ApplicationRunner {

    private final PermissionCatalog catalog;

    public PermissionCatalogVerifier(PermissionCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify(catalog.codeKeys());
    }

    void verify(Collection<String> codeKeys) {
        List<String> missing = codeKeys.stream().filter(k -> !catalog.exists(k)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Diese Rechte fehlen in der Tabelle permissions "
                    + "(Flyway-Migration vergessen?): " + String.join(", ", missing));
        }
    }
}
