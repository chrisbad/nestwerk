package club.badernet.nestwerk.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Geplante Aufgaben: Aufräumen verwaister Anhänge, Erinnerungen, Versand von Benachrichtigungen. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
