package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.security.PermissionSource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class KitchenPermissionSource implements PermissionSource {

    @Override
    public List<KitchenPermission> permissions() {
        return List.of(KitchenPermission.values());
    }
}
