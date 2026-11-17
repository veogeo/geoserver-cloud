/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.geoserver.cloud.autoconfigure.extensions.security.gatewaybearer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class GatewayBearerAuthenticationFilterTest {

    private final JwtDecoder jwtDecoder = mock(JwtDecoder.class);
    private final JwtAuthenticationConverter converter = mock(JwtAuthenticationConverter.class);
    private final GatewayBearerAuthenticationFilter filter =
            new GatewayBearerAuthenticationFilter(jwtDecoder, converter);

    @Test
    void forwardsVerifiedIdentityToGeoServerSharedAuthentication() throws Exception {
        Jwt jwt = Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .claim("preferred_username", "admin")
                .claim("groups", List.of("admin"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                jwt,
                List.of(
                        new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"),
                        FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.BEARER_AUTHORITY)),
                "admin");
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);
        when(converter.convert(jwt)).thenReturn(authentication);

        MockHttpServletRequest request = requestWithBearer("valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> forwardedRequest = new AtomicReference<>();
        FilterChain chain = (forwarded, ignored) -> forwardedRequest.set((HttpServletRequest) forwarded);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(forwardedRequest.get().getHeader("x-gsc-username")).isEqualTo("admin");
        assertThat(Collections.list(forwardedRequest.get().getHeaders("x-gsc-roles")))
                .containsExactly("ROLE_ADMINISTRATOR");
    }

    @Test
    void rejectsAnInvalidBearerTokenBeforeGeoServerSecurity() throws Exception {
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid"));
        MockHttpServletRequest request = requestWithBearer("invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> forwardedRequest = new AtomicReference<>();
        FilterChain chain = (forwarded, ignored) -> forwardedRequest.set((HttpServletRequest) forwarded);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(forwardedRequest.get()).isNull();
    }

    @Test
    void keepsExistingSessionAndGatewayAuthenticationWhenNoBearerIsProvided() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/layers");
        request.addHeader("x-gsc-username", "browser-user");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> forwardedRequest = new AtomicReference<>();
        FilterChain chain = (forwarded, ignored) -> forwardedRequest.set((HttpServletRequest) forwarded);

        filter.doFilter(request, response, chain);

        assertThat(forwardedRequest.get()).isSameAs(request);
        assertThat(forwardedRequest.get().getHeader("x-gsc-username")).isEqualTo("browser-user");
    }

    private MockHttpServletRequest requestWithBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/layers");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
