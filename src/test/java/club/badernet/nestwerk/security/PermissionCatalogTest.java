package club.badernet.nestwerk.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionCatalogTest {

    @Test
    void namesAreUnique() {
        assertThat(PermissionCatalog.all()).extracting(Permission::name).doesNotHaveDuplicates();
    }

    @Test
    void findsPermissionByName() {
        assertThat(PermissionCatalog.find("USER_MANAGE")).contains(AdminPermission.USER_MANAGE);
    }

    @Test
    void unknownNameIsEmpty() {
        assertThat(PermissionCatalog.find("GIBT_ES_NICHT")).isEmpty();
    }

    @Test
    void groupsByModule() {
        assertThat(PermissionCatalog.byModule()).containsKey("Verwaltung");
        assertThat(PermissionCatalog.byModule().get("Verwaltung"))
                .contains(AdminPermission.USER_MANAGE, AdminPermission.ACCESS_ROLE_MANAGE,
                        AdminPermission.MEMBER_MANAGE);
    }
}
