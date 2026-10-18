package club.badernet.nestwerk.ai;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.converter.OAuth2ClientRegistrationRegisteredClientConverter;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Regeln für KI-Assistenten, die sich selbst als OAuth-Client registrieren (RFC 7591), etwa Claude. Jeder Client
 * bekommt denselben Zuschnitt: Autorisierungscode mit PKCE, Zustimmung durch den Benutzer, ein einziger Scope und
 * undurchsichtige Tokens, die Nestwerk in der Datenbank nachschlägt.
 */
public final class McpClientRegistrations {

    /** Der einzige Scope: der Assistent handelt mit genau den Rechten des Benutzers. */
    public static final String SCOPE = "mcp";

    static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(1);
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(90);

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private McpClientRegistrations() {
    }

    /** Übernimmt Name, Redirect-URIs und Authentifizierungsart aus der Registrierung, alles andere legt Nestwerk fest. */
    public static Converter<OAuth2ClientRegistration, RegisteredClient> registeredClientConverter() {
        OAuth2ClientRegistrationRegisteredClientConverter defaults = new OAuth2ClientRegistrationRegisteredClientConverter();
        return registration -> {
            RegisteredClient client = defaults.convert(registration);
            return RegisteredClient.from(client)
                    .authorizationGrantTypes(grants -> {
                        grants.clear();
                        grants.add(AuthorizationGrantType.AUTHORIZATION_CODE);
                        grants.add(AuthorizationGrantType.REFRESH_TOKEN);
                    })
                    .scopes(scopes -> {
                        scopes.clear();
                        scopes.add(SCOPE);
                    })
                    .clientSettings(ClientSettings.builder()
                            .requireProofKey(true)
                            .requireAuthorizationConsent(true)
                            .build())
                    .tokenSettings(TokenSettings.builder()
                            .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                            .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                            .refreshTokenTimeToLive(REFRESH_TOKEN_TTL)
                            .reuseRefreshTokens(false)
                            .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                            .build())
                    .build();
        };
    }

    /**
     * Prüft die Registrierung: Redirect-URIs sind Pflicht, unverschlüsseltes HTTP nur zurück auf dasselbe Gerät.
     * Angefragte Scopes werden nicht abgelehnt, sondern durch {@link #SCOPE} ersetzt.
     */
    public static Consumer<OAuth2ClientRegistrationAuthenticationContext> validator() {
        return OAuth2ClientRegistrationAuthenticationValidator.DEFAULT_REDIRECT_URI_VALIDATOR
                .andThen(OAuth2ClientRegistrationAuthenticationValidator.DEFAULT_JWK_SET_URI_VALIDATOR)
                .andThen(McpClientRegistrations::validateRedirectTargets);
    }

    private static void validateRedirectTargets(OAuth2ClientRegistrationAuthenticationContext context) {
        OAuth2ClientRegistrationAuthenticationToken authentication = context.getAuthentication();
        List<String> redirectUris = authentication.getClientRegistration().getRedirectUris();
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw invalidRedirect();
        }
        for (String redirectUri : redirectUris) {
            URI uri = URI.create(redirectUri);
            if ("http".equalsIgnoreCase(uri.getScheme()) && !LOOPBACK_HOSTS.contains(String.valueOf(uri.getHost()))) {
                throw invalidRedirect();
            }
        }
    }

    private static OAuth2AuthenticationException invalidRedirect() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REDIRECT_URI,
                "redirect_uris: https or http on localhost required", null));
    }
}
