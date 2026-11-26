/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.veogeo.geoservercloud.oidc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.geoserver.platform.resource.Resource.Lock;
import org.geoserver.security.GeoServerSecurityFilterChain;
import org.geoserver.security.GeoServerSecurityManager;
import org.geoserver.security.RequestFilterChain;
import org.geoserver.security.config.SecurityFilterConfig;
import org.geoserver.security.config.SecurityManagerConfig;
import org.geoserver.security.oauth2.config.GeoServerOAuth2LoginFilterConfig;
import org.geoserver.security.oauth2.config.OpenIdRoleSource;
import org.geoserver.security.oauth2.login.GeoServerOAuth2LoginAuthenticationFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/** Installs the optional browser login filter using an OpenID Connect provider's discovery metadata. */
@Slf4j
@AutoConfiguration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = OidcLoginInitializer.PREFIX, name = "enabled", havingValue = "true")
public class OidcLoginInitializer {

    static final String PREFIX = "geoserver.extension.security.oidc-login";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long DEFAULT_DISCOVERY_CONNECT_TIMEOUT_SECONDS = 5;
    private static final long DEFAULT_DISCOVERY_READ_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_DISCOVERY_MAX_ATTEMPTS = 6;
    private static final long DEFAULT_DISCOVERY_RETRY_DELAY_SECONDS = 5;
    private static final long DEFAULT_DISCOVERY_MAX_RETRY_DELAY_SECONDS = 60;

    private final GeoServerSecurityManager securityManager;
    private final Environment environment;
    private final AtomicBoolean initializationStarted = new AtomicBoolean();

    @EventListener(ApplicationReadyEvent.class)
    public void configureOidcLogin() {
        if (!initializationStarted.compareAndSet(false, true)) return;

        // The discovery request both wakes a cold IdP and obtains its metadata. Run it away from
        // the application-ready thread so an unavailable or slow provider cannot fail GeoServer startup.
        Thread initializer = new Thread(this::configureOidcLoginBestEffort, "geoserver-oidc-login-initializer");
        initializer.setDaemon(true);
        initializer.start();
        log.info("Started best-effort asynchronous OIDC discovery for browser login.");
    }

    private void configureOidcLoginBestEffort() {
        final String filterName;
        final String clientId;
        final String clientSecret;
        final String issuerUri;
        final String discoveryUri;
        try {
            filterName = property("filter-name", "oidc");
            clientId = required("client-id");
            clientSecret = required("client-secret");
            issuerUri = required("issuer-uri");
            discoveryUri =
                    property("discovery-uri", trimTrailingSlash(issuerUri) + "/.well-known/openid-configuration");
        } catch (Exception e) {
            log.error(
                    "OIDC browser login configuration is invalid; GeoServer will continue without the login filter.",
                    e);
            return;
        }

        int maxAttempts = Math.max(
                1,
                environment.getProperty(
                        PREFIX + ".discovery-max-attempts", Integer.class, DEFAULT_DISCOVERY_MAX_ATTEMPTS));
        Duration connectTimeout =
                timeoutProperty("discovery-connect-timeout-seconds", DEFAULT_DISCOVERY_CONNECT_TIMEOUT_SECONDS);
        Duration readTimeout =
                timeoutProperty("discovery-read-timeout-seconds", DEFAULT_DISCOVERY_READ_TIMEOUT_SECONDS);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> metadata = readProviderMetadata(discoveryUri, connectTimeout, readTimeout);
                Lock lock = securityManager.security().lock();
                try {
                    SecurityFilterConfig existingConfig = securityManager.loadFilterConfig(filterName, false);
                    if (existingConfig != null && !(existingConfig instanceof GeoServerOAuth2LoginFilterConfig)) {
                        throw new IllegalStateException(
                                "A non-OIDC security filter named '" + filterName + "' already exists.");
                    }
                    GeoServerOAuth2LoginFilterConfig filterConfig = createFilterConfig(
                            filterName,
                            clientId,
                            clientSecret,
                            issuerUri,
                            discoveryUri,
                            metadata,
                            (GeoServerOAuth2LoginFilterConfig) existingConfig);
                    securityManager.saveFilter(filterConfig);
                    addFilterToWebChain(filterName);
                } finally {
                    lock.release();
                }
                log.info("Configured optional OpenID Connect login filter '{}' from provider metadata.", filterName);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    log.error(
                            "Could not configure the GeoServer OpenID Connect login filter after {} attempts. "
                                    + "GeoServer remains available; browser OIDC login stays unavailable until a later startup attempt succeeds.",
                            maxAttempts,
                            e);
                    return;
                }

                Duration delay = retryDelay(attempt);
                log.warn(
                        "OIDC provider discovery/configuration attempt {}/{} failed. GeoServer remains available; "
                                + "retrying in {} seconds.",
                        attempt,
                        maxAttempts,
                        delay.toSeconds(),
                        e);
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    log.warn("OIDC login initialization was interrupted; GeoServer remains available.");
                    return;
                }
            }
        }
    }

    private Duration timeoutProperty(String key, long defaultSeconds) {
        long seconds = environment.getProperty(PREFIX + "." + key, Long.class, defaultSeconds);
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    private Duration retryDelay(int failedAttempt) {
        long baseSeconds = Math.max(
                1,
                environment.getProperty(
                        PREFIX + ".discovery-retry-delay-seconds", Long.class, DEFAULT_DISCOVERY_RETRY_DELAY_SECONDS));
        long maxSeconds = Math.max(
                1,
                environment.getProperty(
                        PREFIX + ".discovery-max-retry-delay-seconds",
                        Long.class,
                        DEFAULT_DISCOVERY_MAX_RETRY_DELAY_SECONDS));
        long multiplier = 1L << Math.min(failedAttempt - 1, 20);
        long delaySeconds = baseSeconds >= maxSeconds || baseSeconds > maxSeconds / multiplier
                ? maxSeconds
                : baseSeconds * multiplier;
        return Duration.ofSeconds(delaySeconds);
    }

    private GeoServerOAuth2LoginFilterConfig createFilterConfig(
            String filterName,
            String clientId,
            String clientSecret,
            String issuerUri,
            String discoveryUri,
            Map<String, Object> metadata,
            GeoServerOAuth2LoginFilterConfig existingConfig) {
        GeoServerOAuth2LoginFilterConfig config =
                existingConfig == null ? new GeoServerOAuth2LoginFilterConfig() : existingConfig;

        config.setName(filterName);
        config.setClassName(GeoServerOAuth2LoginAuthenticationFilter.class.getName());
        config.setOidcEnabled(true);
        config.setOidcClientId(clientId);
        config.setOidcClientSecret(clientSecret);
        config.setOidcAuthorizationUri(endpoint("authorization-uri", metadata, "authorization_endpoint"));
        config.setOidcTokenUri(endpoint("token-uri", metadata, "token_endpoint"));
        config.setOidcUserInfoUri(endpoint("user-info-uri", metadata, "userinfo_endpoint"));
        config.setOidcJwkSetUri(endpoint("jwks-uri", metadata, "jwks_uri"));
        config.setOidcLogoutUri(optionalEndpoint("logout-uri", metadata, "end_session_endpoint"));
        config.setOidcDiscoveryUri(discoveryUri);
        config.setOidcScopes(property("scopes", "openid profile email"));
        config.setOidcUserNameAttribute(property("username-claim", "preferred_username"));
        config.setTokenRolesClaim(property("roles-claim", "groups"));
        config.setRoleSource(OpenIdRoleSource.valueOf(property("role-source", "UserInfo")));
        config.setRoleConverterString(property("role-converter", ""));
        config.setOnlyExternalListedRoles(false);
        config.setAllowAdminLogin(Boolean.parseBoolean(property("allow-admin-login", "false")));
        // Service-side JWT validation is a separate extension; this is browser login only.
        config.setEnableResourceServerMode(false);
        config.setOidcUsePKCE(Boolean.parseBoolean(property("use-pkce", "true")));
        config.setOidcForceAuthorizationUriHttps(isHttps(config.getOidcAuthorizationUri()));
        config.setOidcForceTokenUriHttps(isHttps(config.getOidcTokenUri()));
        String publicUrl = property("public-url", "http://localhost");
        config.setPostLogoutRedirectUri(
                property("post-logout-redirect-uri", trimTrailingSlash(publicUrl) + "/geoserver/cloud/web/"));
        config.calculateRedirectUris();
        return config;
    }

    private String endpoint(String property, Map<String, Object> metadata, String metadataKey) {
        String configured = environment.getProperty(PREFIX + "." + property);
        if (StringUtils.hasText(configured)) return configured.trim();
        Object discovered = metadata.get(metadataKey);
        if (discovered instanceof String value && StringUtils.hasText(value)) return value.trim();
        throw new IllegalStateException(
                "Set " + PREFIX + "." + property + " or provide " + metadataKey + " in the OIDC discovery document.");
    }

    private String optionalEndpoint(String property, Map<String, Object> metadata, String metadataKey) {
        String configured = environment.getProperty(PREFIX + "." + property);
        if (StringUtils.hasText(configured)) return configured.trim();
        Object discovered = metadata.get(metadataKey);
        return discovered instanceof String value ? value.trim() : "";
    }

    private Map<String, Object> readProviderMetadata(
            String discoveryUri, Duration connectTimeout, Duration readTimeout) {
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(connectTimeout);
            requestFactory.setReadTimeout(readTimeout);
            String body = new RestTemplate(requestFactory).getForObject(discoveryUri, String.class);
            if (!StringUtils.hasText(body)) {
                throw new IllegalStateException("OIDC discovery document is empty at " + discoveryUri + ".");
            }
            return OBJECT_MAPPER.readValue(body, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Could not read OIDC discovery metadata from " + discoveryUri + ".", e);
        }
    }

    private void addFilterToWebChain(String filterName) throws Exception {
        SecurityManagerConfig securityConfig = securityManager.getSecurityConfig();
        GeoServerSecurityFilterChain filterChain = securityConfig.getFilterChain();
        RequestFilterChain webChain = filterChain.getRequestChains().stream()
                .filter(chain -> "web".equals(chain.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("GeoServer security has no 'web' filter chain."));

        List<String> filterNames = new ArrayList<>(webChain.getFilterNames());
        if (!filterNames.contains(filterName)) {
            int formIndex = filterNames.indexOf("form");
            int anonymousIndex = filterNames.indexOf("anonymous");
            int insertIndex = formIndex >= 0 ? formIndex + 1 : anonymousIndex;
            if (insertIndex < 0) insertIndex = filterNames.size();
            filterNames.add(insertIndex, filterName);
            webChain.setFilterNames(filterNames);
            securityManager.saveSecurityConfig(securityConfig);
        }
    }

    private String required(String key) {
        String value = environment.getProperty(PREFIX + "." + key);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Set " + PREFIX + "." + key + " when OIDC Web login is enabled.");
        }
        return value.trim();
    }

    private String property(String key, String defaultValue) {
        return environment.getProperty(PREFIX + "." + key, defaultValue).trim();
    }

    private boolean isHttps(String endpoint) {
        return endpoint != null && "https".equalsIgnoreCase(URI.create(endpoint).getScheme());
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
