package club.badernet.nestwerk.school;

import club.badernet.nestwerk.security.PermissionSource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class SchoolPermissionSource implements PermissionSource {

    @Override
    public List<SchoolPermission> permissions() {
        return List.of(SchoolPermission.values());
    }
}
