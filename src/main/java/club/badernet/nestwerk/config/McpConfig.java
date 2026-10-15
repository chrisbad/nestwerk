package club.badernet.nestwerk.config;

import club.badernet.nestwerk.ai.AiTokenService;
import club.badernet.nestwerk.ai.McpCaller;
import club.badernet.nestwerk.ai.McpTokenFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * MCP-Server unter {@code /mcp}: eigene, zustandslose Filterkette mit Bearer-Token statt Formular-Login und ein
 * Transport, der den angemeldeten Benutzer an die Tools weiterreicht.
 */
@Configuration
public class McpConfig {

    @Bean
    @Order(1)
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http, AiTokenService tokens,
                                               UserDetailsService userDetails) throws Exception {
        http
                .securityMatcher("/mcp", "/mcp/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new McpTokenFilter(tokens, userDetails), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    WebMvcStatelessServerTransport webMvcStatelessServerTransport(JsonMapper jsonMapper,
                                                                  McpServerStreamableHttpProperties properties) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .messageEndpoint(properties.getMcpEndpoint())
                .contextExtractor(request -> request.servletRequest().getAttribute(McpCaller.USER_ID)
                        instanceof Long id ? McpTransportContext.create(Map.of(McpCaller.USER_ID, id))
                        : McpTransportContext.EMPTY)
                .build();
    }
}
