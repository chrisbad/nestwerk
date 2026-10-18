package club.badernet.nestwerk.config;

import club.badernet.nestwerk.settings.CalendarSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    /** Uhr in der Zeitzone der App; „heute“ für Fälligkeiten und Erinnerungen kommt von hier. */
    @Bean
    Clock clock(CalendarSettings settings) {
        return Clock.system(settings.zone());
    }
}
