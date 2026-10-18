package club.badernet.nestwerk.ai;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Refresh-Tokens für öffentliche Clients (ohne Client-Secret, etwa Claude Code). Spring stellt ihnen keine aus;
 * OAuth 2.1 erlaubt es, wenn jedes Refresh-Token nur einmal gilt – die Clients sind darauf mit
 * {@code reuseRefreshTokens(false)} eingestellt.
 */
public final class PublicClientRefresh {

    private PublicClientRefresh() {
    }

    /** Erzeugt Refresh-Tokens auch für öffentliche Clients. */
    public static final class TokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

        private final StringKeyGenerator keys = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

        @Override
        public OAuth2RefreshToken generate(OAuth2TokenContext context) {
            if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
                return null;
            }
            Instant issuedAt = Instant.now();
            Instant expiresAt = issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
            return new OAuth2RefreshToken(keys.generateKey(), issuedAt, expiresAt);
        }
    }

    /** Erkennt den Refresh eines öffentlichen Clients: nur {@code client_id}, kein Secret, keine Assertion. */
    public static final class ClientConverter implements AuthenticationConverter {

        @Override
        public Authentication convert(HttpServletRequest request) {
            if (!AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))
                    || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                    || request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null
                    || request.getParameter(OAuth2ParameterNames.CLIENT_ASSERTION) != null) {
                return null;
            }
            String[] clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
            if (clientIds == null || clientIds.length != 1 || clientIds[0].isBlank()) {
                return null;
            }
            return new OAuth2ClientAuthenticationToken(clientIds[0], ClientAuthenticationMethod.NONE, null,
                    Map.of(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.REFRESH_TOKEN.getValue()));
        }
    }

    /** Authentifiziert den öffentlichen Client allein über seine ID; das Refresh-Token prüft danach Spring. */
    public static final class ClientProvider implements AuthenticationProvider {

        private final RegisteredClientRepository clients;

        public ClientProvider(RegisteredClientRepository clients) {
            this.clients = clients;
        }

        @Override
        public Authentication authenticate(Authentication authentication) {
            OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
            if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
                    || !AuthorizationGrantType.REFRESH_TOKEN.getValue()
                    .equals(token.getAdditionalParameters().get(OAuth2ParameterNames.GRANT_TYPE))) {
                return null;
            }
            RegisteredClient client = clients.findByClientId(token.getPrincipal().toString());
            if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
            }
            return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }
}
