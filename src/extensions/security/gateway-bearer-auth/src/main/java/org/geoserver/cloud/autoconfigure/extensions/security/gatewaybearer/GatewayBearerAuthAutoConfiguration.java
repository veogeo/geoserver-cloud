/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.geoserver.cloud.autoconfigure.extensions.security.gatewaybearer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Validates OIDC access-token JWTs in the GeoServer service receiving the request. */
@AutoConfiguration
@ConditionalOnGatewayBearerAuth
@EnableConfigurationProperties(GatewayBearerAuthConfigProperties.class)
@EnableWebSecurity
public class GatewayBearerAuthAutoConfiguration {

    /**
     * GeoServer REST also exposes a style-PUT-specific content negotiation strategy. Spring Security expects a single
     * strategy when creating HttpSecurity, so explicitly use the regular MVC manager for security negotiation.
     */
    @Bean
    @Primary
    @ConditionalOnBean(name = "mvcContentNegotiationManager")
    ContentNegotiationStrategy bearerAuthContentNegotiationStrategy(
            @Qualifier("mvcContentNegotiationManager") ContentNegotiationStrategy mvcContentNegotiationManager) {
        return mvcContentNegotiationManager;
    }

    @Bean
    @Order(1)
    SecurityFilterChain bearerTokenSecurityFilterChain(
            HttpSecurity http, JwtAuthenticationConverter bearerJwtAuthenticationConverter) throws Exception {
        http.securityMatcher(bearerTokenRequestMatcher())
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        http.oauth2ResourceServer(resourceServer ->
                resourceServer.jwt(jwt -> jwt.jwtAuthenticationConverter(bearerJwtAuthenticationConverter)));
        http.addFilterAfter(new GeoServerSharedAuthBridgeFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    RequestMatcher bearerTokenRequestMatcher() {
        return request -> {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        };
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder bearerJwtDecoder(GatewayBearerAuthConfigProperties properties) {
        Assert.hasText(properties.getIssuerUri(), GatewayBearerAuthConfigProperties.PREFIX + ".issuer-uri is required");
        Assert.hasText(properties.getAudience(), GatewayBearerAuthConfigProperties.PREFIX + ".audience is required");
        Assert.hasText(
                properties.getUsernameClaim(),
                GatewayBearerAuthConfigProperties.PREFIX + ".username-claim is required");
        Assert.hasText(
                properties.getRolesClaim(), GatewayBearerAuthConfigProperties.PREFIX + ".roles-claim is required");
        Assert.notEmpty(
                properties.getRoleMappings(),
                GatewayBearerAuthConfigProperties.PREFIX + ".role-mappings must not be empty");
        Assert.hasText(
                properties.getRolePrefix(), GatewayBearerAuthConfigProperties.PREFIX + ".role-prefix is required");

        NimbusJwtDecoder decoder = (StringUtils.hasText(properties.getJwkSetUri())
                        ? NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri())
                        : NimbusJwtDecoder.withIssuerLocation(properties.getIssuerUri()))
                .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("at+jwt"))))
                .build();
        OAuth2TokenValidator<Jwt> issuerAndTimeValidator = new DelegatingOAuth2TokenValidator<>(
                new JwtTypeValidator("JWT", "at+jwt"),
                new JwtTimestampValidator(),
                new JwtIssuerValidator(properties.getIssuerUri()));
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(properties.getAudience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Required audience is missing", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndTimeValidator, audienceValidator));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter bearerJwtAuthenticationConverter(GatewayBearerAuthConfigProperties properties) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(properties.getUsernameClaim());
        converter.setJwtGrantedAuthoritiesConverter(jwt -> mappedAuthorities(jwt, properties));
        return converter;
    }

    /**
     * Bridges the JWT identity verified by Spring Security into GeoServer's native shared-auth filter chain.
     * The gateway strips client-supplied x-gsc-* headers; these values are synthesized only after JWT validation.
     */
    private static final class GeoServerSharedAuthBridgeFilter extends OncePerRequestFilter {
        private static final String USERNAME_HEADER = "x-gsc-username";
        private static final String ROLES_HEADER = "x-gsc-roles";

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!(authentication instanceof JwtAuthenticationToken) || !authentication.isAuthenticated()
                    || !StringUtils.hasText(authentication.getName())) {
                chain.doFilter(request, response);
                return;
            }

            String username = authentication.getName();
            List<String> roles = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            response.setHeader(USERNAME_HEADER, username);
            roles.forEach(role -> response.addHeader(ROLES_HEADER, role));

            HttpServletRequest trustedRequest = new HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    if (USERNAME_HEADER.equalsIgnoreCase(name)) return username;
                    if (ROLES_HEADER.equalsIgnoreCase(name)) return roles.stream().findFirst().orElse(null);
                    return super.getHeader(name);
                }

                @Override
                public Enumeration<String> getHeaders(String name) {
                    if (USERNAME_HEADER.equalsIgnoreCase(name)) return Collections.enumeration(List.of(username));
                    if (ROLES_HEADER.equalsIgnoreCase(name)) return Collections.enumeration(roles);
                    return super.getHeaders(name);
                }

                @Override
                public Enumeration<String> getHeaderNames() {
                    LinkedHashSet<String> names = new LinkedHashSet<>();
                    Enumeration<String> existingNames = super.getHeaderNames();
                    while (existingNames != null && existingNames.hasMoreElements()) {
                        names.add(existingNames.nextElement());
                    }
                    names.add(USERNAME_HEADER);
                    if (!roles.isEmpty()) names.add(ROLES_HEADER);
                    return Collections.enumeration(names);
                }
            };
            chain.doFilter(trustedRequest, response);
        }
    }

    private static Collection<GrantedAuthority> mappedAuthorities(
            Jwt jwt, GatewayBearerAuthConfigProperties properties) {
        Object roleClaim = nestedClaim(jwt.getClaims(), properties.getRolesClaim());
        List<?> sourceRoles;
        if (roleClaim instanceof Collection<?> values) {
            sourceRoles = List.copyOf(values);
        } else if (roleClaim instanceof String value) {
            sourceRoles = List.of(value);
        } else {
            sourceRoles = List.of();
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        for (Object sourceRole : sourceRoles) {
            if (!(sourceRole instanceof String role)) continue;
            String mappedRole = properties.getRoleMappings().get(role);
            if (mappedRole != null && !mappedRole.isBlank()) {
                String prefix = properties.getRolePrefix();
                String authority =
                        !prefix.isEmpty() && mappedRole.startsWith(prefix) ? mappedRole : prefix + mappedRole;
                authorities.add(new SimpleGrantedAuthority(authority));
            }
        }
        return authorities;
    }

    private static Object nestedClaim(Map<String, Object> claims, String path) {
        Object current = claims;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(segment);
        }
        return current;
    }
}
