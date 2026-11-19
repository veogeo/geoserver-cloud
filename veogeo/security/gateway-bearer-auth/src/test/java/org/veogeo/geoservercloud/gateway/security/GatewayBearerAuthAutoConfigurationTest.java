/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.veogeo.geoservercloud.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

class GatewayBearerAuthAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GatewayBearerAuthAutoConfiguration.class));

    @Test
    void gatewayFallbackSkipsWhenSpringSecurityHasNoHttpSecurityBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GatewaySecurityFallbackConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
                });
    }

    @Test
    void disabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(GatewayBearerAuthAutoConfiguration.class);
        });
    }

    @Test
    void remainsDisabledWhenExplicitlyFalse() {
        contextRunner
                .withPropertyValues(GatewayBearerAuthConfigProperties.PREFIX + ".enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GatewayBearerAuthAutoConfiguration.class);
                });
    }

    @Test
    void enabledRegistersBearerFilterAheadOfGeoServerSecurity() {
        String prefix = GatewayBearerAuthConfigProperties.PREFIX;
        contextRunner
                .withPropertyValues(
                        prefix + ".enabled=true",
                        prefix + ".issuer-uri=https://idp.example/",
                        prefix + ".jwk-set-uri=https://idp.example/jwks",
                        prefix + ".audience=geoserver-cloud",
                        prefix + ".roles-claim=groups",
                        prefix + ".role-mappings.admin=ROLE_ADMINISTRATOR")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GatewayBearerAuthenticationFilter.class);
                    assertThat(context).doesNotHaveBean(SecurityFilterChain.class);

                    FilterRegistrationBean<?> registration = context.getBean(
                            "gatewayBearerAuthenticationFilterRegistration", FilterRegistrationBean.class);
                    assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
                });
    }

    @Test
    void emptyRolePrefixPreservesExactGeoServerRoles() {
        String prefix = GatewayBearerAuthConfigProperties.PREFIX;
        contextRunner
                .withPropertyValues(
                        prefix + ".enabled=true",
                        prefix + ".issuer-uri=https://idp.example/",
                        prefix + ".jwk-set-uri=https://idp.example/jwks",
                        prefix + ".audience=geoserver-cloud",
                        prefix + ".roles-claim=groups",
                        prefix + ".role-prefix=",
                        prefix + ".role-mappings.admin=ADMIN")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Jwt jwt = Jwt.withTokenValue("test")
                            .header("alg", "RS256")
                            .claim("preferred_username", "admin")
                            .claim("groups", List.of("admin"))
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
                    List<String> authorities =
                            context.getBean(JwtAuthenticationConverter.class).convert(jwt).getAuthorities().stream()
                                    .map(GrantedAuthority::getAuthority)
                                    .toList();
                    assertThat(authorities).contains("ADMIN").doesNotContain("ROLE_ADMIN", "ROLE_ADMINISTRATOR");
                });
    }
}
