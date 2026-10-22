package club.badernet.nestwerk.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

import javax.sql.DataSource;
import java.util.UUID;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(RememberMeProperties.class)
public class SecurityConfig {

    private static final String REMEMBER_ME_KEY = UUID.randomUUID().toString();

    @Bean
    PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }

    /**
     * Tokens live in the database, so logging out or a stolen-cookie detection invalidates them. The key only ties
     * the services to the authentication provider and may change with every start.
     */
    @Bean
    PersistentTokenBasedRememberMeServices rememberMeServices(UserDetailsService userDetailsService,
                                                              PersistentTokenRepository tokens,
                                                              RememberMeProperties properties) {
        PersistentTokenBasedRememberMeServices services = new RevokingRememberMeServices(
                REMEMBER_ME_KEY, userDetailsService, tokens);
        services.setTokenValiditySeconds((int) properties.validity().toSeconds());
        services.setCookieCustomizer(cookie -> cookie.setAttribute("SameSite", "Lax"));
        return services;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PersistentTokenBasedRememberMeServices rememberMe)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/error", "/actuator/health/**",
                                "/css/**", "/js/**", "/fonts/**", "/favicon.ico", "/icons/**",
                                "/sw.js", "/manifest.webmanifest").permitAll()
                        // OAuth-Metadaten des MCP-Servers, damit KI-Assistenten den Login finden (OAuthConfig)
                        .requestMatchers("/.well-known/oauth-protected-resource",
                                "/.well-known/oauth-protected-resource/**").permitAll()
                        // Wand-Tablet: eigene Prüfung über das Geräte-Cookie im TabletController
                        .requestMatchers("/tablet", "/tablet/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").successHandler(new LandingPageSuccessHandler()).permitAll())
                .rememberMe(remember -> remember.key(REMEMBER_ME_KEY).rememberMeServices(rememberMe))
                .logout(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
