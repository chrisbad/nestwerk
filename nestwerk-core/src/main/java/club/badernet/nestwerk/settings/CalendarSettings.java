package club.badernet.nestwerk.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
public class CalendarSettings {

    private final ZoneId zone;

    public CalendarSettings(@Value("${nestwerk.timezone:Europe/Berlin}") String timezone) {
        this.zone = ZoneId.of(timezone);
    }

    public ZoneId zone() {
        return zone;
    }
}
