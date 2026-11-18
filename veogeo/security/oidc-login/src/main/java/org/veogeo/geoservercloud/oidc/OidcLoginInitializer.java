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

    private final GeoServerSecurityManager securityManager;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void configureOidcLogin() {
        String filterName = property("filter-name", "oidc");
        String clientId = required("client-id");
        String clientSecret = required("client-secret");
        String issuerUri = required("issuer-uri");
        String discoveryUri =
                property("discovery-uri", trimTrailingSlash(issuerUri) + "/.well-known/openid-configuration");

        Lock lock = securityManager.security().lock();
        try {
            SecurityFilterConfig existingConfig = securityManager.loadFilterConfig(filterName, false);
            if (existingConfig != null && !(existingConfig instanceof GeoServerOAuth2LoginFilterConfig)) {
                throw new IllegalStateException(
                        "A non-OIDC security filter named '" + filterName + "' already exists.");
            }
            GeoServerOAuth2LoginFilterConfig filterConfig = createFilterConfig(
                    filterName, clientId, clientSecret, issuerUri, discoveryUri, (GeoServerOAuth2LoginFilterConfig)
                            existingConfig);
            securityManager.saveFilter(filterConfig);
            addFilterToWebChain(filterName);
            log.info("Configured optional OpenID Connect login filter '{}'.", filterName);
        } catch (Exception e) {
            throw new IllegalStateException("Could not configure the GeoServer OpenID Connect login filter.", e);
        } finally {
            lock.release();
        }
    }

    private GeoServerOAuth2LoginFilterConfig createFilterConfig(
            String filterName,
            String clientId,
            String clientSecret,
            String issuerUri,
            String discoveryUri,
            GeoServerOAuth2LoginFilterConfig existingConfig) {
        Map<String, Object> metadata = readProviderMetadata(discoveryUri);
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

    private Map<String, Object> readProviderMetadata(String discoveryUri) {
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(5));
            requestFactory.setReadTimeout(Duration.ofSeconds(5));
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
