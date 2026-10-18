package club.badernet.nestwerk.security;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
class AdminPermissionSource implements PermissionSource {

    @Override
    public List<AdminPermission> permissions() {
        return List.of(AdminPermission.values());
    }
}
