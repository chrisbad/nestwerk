package club.badernet.nestwerk.config;

import club.badernet.nestwerk.ai.McpClientRegistrations;
import club.badernet.nestwerk.ai.McpOAuthService;
import club.badernet.nestwerk.ai.PublicClientRefresh;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.Set;
import java.util.function.Consumer;

/**
 * OAuth 2.1 Authorization Server, damit sich KI-Assistenten wie Claude mit dem normalen Nestwerk-Login verbinden:
 * Der Client registriert sich selbst ({@code /oauth2/register}), der Benutzer meldet sich im Browser an und stimmt
 * zu ({@code /oauth2/authorize}, {@code /oauth2/consent}), danach holt der Client Tokens ({@code /oauth2/token}).
 * Die Tokens sind undurchsichtig und werden beim Aufruf von {@code /mcp} in der Datenbank nachgeschlagen.
 */
@Configuration
public class OAuthConfig {

    @Bean
    @Order(0)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http, RegisteredClientRepository clients,
                                                               McpOAuthService oauth) throws Exception {
        http
                .oauth2AuthorizationServer(as -> as
                        .tokenGenerator(new DelegatingOAuth2TokenGenerator(new OAuth2AccessTokenGenerator(),
                                new PublicClientRefresh.TokenGenerator()))
                        // öffentliche Clients (PKCE, kein Secret) ankündigen, damit z. B. Claude Code sie wählt
                        .authorizationServerMetadataEndpoint(metadata -> metadata.authorizationServerMetadataCustomizer(
                                builder -> builder.tokenEndpointAuthenticationMethods(methods -> {
                                    if (!methods.contains("none")) {
                                        methods.add("none");
                                    }
                                })))
                        .clientAuthentication(client -> client
                                .authenticationConverter(new PublicClientRefresh.ClientConverter())
                                .authenticationProvider(new PublicClientRefresh.ClientProvider(clients)))
                        .authorizationEndpoint(endpoint -> endpoint
                                .consentPage("/oauth2/consent")
                                .authorizationRequestConverter(mcpScopeRequestConverter())
                                .authenticationProviders(providers -> providers.forEach(provider -> {
                                    if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider code) {
                                        code.setAuthenticationValidator(new OAuth2AuthorizationCodeRequestAuthenticationValidator()
                                                .andThen(requireAiAccess(oauth)));
                                    }
                                })))
                        .clientRegistrationEndpoint(registration -> registration
                                .openRegistrationAllowed(true)
                                .authenticationProviders(providers -> providers.forEach(provider -> {
                                    if (provider instanceof OAuth2ClientRegistrationAuthenticationProvider register) {
                                        register.setRegisteredClientConverter(
                                                McpClientRegistrations.registeredClientConverter());
                                        register.setAuthenticationValidator(McpClientRegistrations.validator());
                                    }
                                }))))
                .securityMatcher(http.getConfigurer(OAuth2AuthorizationServerConfigurer.class).getEndpointsMatcher())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/oauth2/register").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        PathPatternRequestMatcher.withDefaults().matcher("/oauth2/authorize")));
        return http.build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService oauth2AuthorizationService(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService oauth2AuthorizationConsentService(JdbcTemplate jdbc,
                                                                        RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    /**
     * Es gibt nur einen Scope. Fragt ein Client keinen oder einen anderen an, bekommt er {@code mcp} – ohne Scope
     * ließe sich die Zustimmung nicht speichern.
     */
    private static AuthenticationConverter mcpScopeRequestConverter() {
        OAuth2AuthorizationCodeRequestAuthenticationConverter defaults = new OAuth2AuthorizationCodeRequestAuthenticationConverter();
        return request -> {
            Authentication converted = defaults.convert(request);
            if (converted instanceof OAuth2AuthorizationCodeRequestAuthenticationToken token
                    && !token.getScopes().equals(Set.of(McpClientRegistrations.SCOPE))) {
                return new OAuth2AuthorizationCodeRequestAuthenticationToken(token.getAuthorizationUri(),
                        token.getClientId(), (Authentication) token.getPrincipal(), token.getRedirectUri(),
                        token.getState(), Set.of(McpClientRegistrations.SCOPE), token.getAdditionalParameters());
            }
            return converted;
        };
    }

    /** Nach der Anmeldung: ohne das Recht {@code AI_ACCESS} bekommt der Client {@code access_denied}. */
    private static Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> requireAiAccess(McpOAuthService oauth) {
        return context -> {
            Authentication principal = (Authentication) context.getAuthentication().getPrincipal();
            if (principal == null || !principal.isAuthenticated() || principal instanceof AnonymousAuthenticationToken) {
                return;
            }
            if (!oauth.mayConnect(principal.getName())) {
                throw new OAuth2AuthorizationCodeRequestAuthenticationException(new OAuth2Error(
                        OAuth2ErrorCodes.ACCESS_DENIED, "Kein KI-Zugang für diesen Benutzer", null),
                        context.getAuthentication());
            }
        };
    }
}
