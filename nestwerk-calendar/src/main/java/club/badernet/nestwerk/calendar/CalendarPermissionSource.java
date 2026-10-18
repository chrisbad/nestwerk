package club.badernet.nestwerk.calendar;

import club.badernet.nestwerk.security.PermissionSource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class CalendarPermissionSource implements PermissionSource {

    @Override
    public List<CalendarPermission> permissions() {
        return List.of(CalendarPermission.values());
    }
}
