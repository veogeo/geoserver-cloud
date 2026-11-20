/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.veogeo.geoservercloud.service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Validates Bearer JWTs before GeoServer's native servlet security filter chain runs. */
@Slf4j
final class ServiceBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String USERNAME_HEADER = "x-gsc-username";
    private static final String ROLES_HEADER = "x-gsc-roles";

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter authenticationConverter;
    private final BearerTokenResolver bearerTokenResolver = new DefaultBearerTokenResolver();
    private final BearerTokenAuthenticationEntryPoint authenticationEntryPoint =
            new BearerTokenAuthenticationEntryPoint();

    ServiceBearerAuthenticationFilter(JwtDecoder jwtDecoder, JwtAuthenticationConverter authenticationConverter) {
        this.jwtDecoder = jwtDecoder;
        this.authenticationConverter = authenticationConverter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        log.debug("Validating Bearer JWT for GeoServer request {}", request.getRequestURI());
        final String token;
        try {
            token = bearerTokenResolver.resolve(request);
        } catch (OAuth2AuthenticationException e) {
            reject(request, response);
            return;
        }

        if (!StringUtils.hasText(token)) {
            chain.doFilter(request, response);
            return;
        }

        final Authentication authentication;
        try {
            authentication = authenticationConverter.convert(jwtDecoder.decode(token));
        } catch (JwtException | OAuth2AuthenticationException e) {
            log.debug("Rejecting invalid Bearer JWT: {}", e.getClass().getSimpleName());
            reject(request, response);
            return;
        }

        if (authentication == null
                || !authentication.isAuthenticated()
                || !StringUtils.hasText(authentication.getName())) {
            reject(request, response);
            return;
        }

        String username = authentication.getName();
        List<String> roles = authentication.getAuthorities().stream()
                .filter(authority -> !(authority instanceof FactorGrantedAuthority))
                .map(authority -> authority.getAuthority())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        log.debug("Accepted Bearer JWT with {} mapped GeoServer roles", roles.size());
        chain.doFilter(new TrustedIdentityRequest(request, username, roles), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        authenticationEntryPoint.commence(
                request, response, new InvalidBearerTokenException("The access token is invalid."));
    }

    /** Supplies only the identity produced by JWT verification to GeoServer's existing shared-auth filter. */
    private static final class TrustedIdentityRequest extends HttpServletRequestWrapper {

        private final String username;
        private final List<String> roles;

        TrustedIdentityRequest(HttpServletRequest request, String username, List<String> roles) {
            super(request);
            this.username = username;
            this.roles = roles;
        }

        @Override
        public String getHeader(String name) {
            if (USERNAME_HEADER.equalsIgnoreCase(name)) return username;
            if (ROLES_HEADER.equalsIgnoreCase(name))
                return roles.stream().findFirst().orElse(null);
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
    }
}
