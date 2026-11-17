/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.geoserver.cloud.autoconfigure.extensions.security.gatewaybearer;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** Validates OIDC access-token JWTs in the GeoServer service receiving the request. */
@AutoConfiguration
@ConditionalOnGatewayBearerAuth
@EnableConfigurationProperties(GatewayBearerAuthConfigProperties.class)
public class GatewayBearerAuthAutoConfiguration {

    @Bean
    GatewayBearerAuthenticationFilter gatewayBearerAuthenticationFilter(
            JwtDecoder bearerJwtDecoder, JwtAuthenticationConverter bearerJwtAuthenticationConverter) {
        return new GatewayBearerAuthenticationFilter(bearerJwtDecoder, bearerJwtAuthenticationConverter);
    }

    /**
     * GeoServer's own servlet security proxy runs before Spring Security's servlet proxy. Register the Bearer filter
     * before the web.xml filters so a verified identity is available to GeoServer's native shared-auth filter.
     */
    @Bean
    FilterRegistrationBean<GatewayBearerAuthenticationFilter> gatewayBearerAuthenticationFilterRegistration(
            GatewayBearerAuthenticationFilter filter) {
        FilterRegistrationBean<GatewayBearerAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("gatewayBearerAuthenticationFilter");
        registration.addUrlPatterns("/*");
        registration.setMatchAfter(false);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
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
        Assert.notNull(
                properties.getRolePrefix(), GatewayBearerAuthConfigProperties.PREFIX + ".role-prefix must not be null");

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
