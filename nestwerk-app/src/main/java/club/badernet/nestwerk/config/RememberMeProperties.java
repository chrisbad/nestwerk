package club.badernet.nestwerk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of the "stay signed in" login option.
 *
 * @param validity how long a remember-me token keeps a user signed in after the last use
 */
@ConfigurationProperties("nestwerk.remember-me")
public record RememberMeProperties(@DefaultValue("30d") Duration validity) {

    public RememberMeProperties {
        if (validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("nestwerk.remember-me.validity must be positive");
        }
    }
}
