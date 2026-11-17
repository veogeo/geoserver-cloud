/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.geoserver.cloud.autoconfigure.web.security;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/** Installs the optional browser OIDC login filter in WebUI's shared security configuration. */
@Slf4j
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "geoserver.extension.security.oidc-login", name = "enabled", havingValue = "true")
public class CloudOidcLoginInitializer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String FILTER_NAME = "geonode";
    private static final String GEONODE_USERINFO_URI = "/api/v2/userinfo/";
    private static final String GEONODE_TOKEN_URI = "/o/token/";
    private static final String GEONODE_JWKS_URI = "/o/.well-known/jwks.json";
    private static final String GEONODE_AUTHORIZATION_URI = "/o/authorize/";

    private final GeoServerSecurityManager securityManager;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void configureOidcLogin() {
        String clientId = required("NOXT_GEOSERVER_CLOUD_OIDC_CLIENT_ID");
        String clientSecret = required("NOXT_GEOSERVER_CLOUD_OIDC_CLIENT_SECRET");
        String siteUrl = required("SITEURL");
        String issuer = required("NOXT_GEOSERVER_CLOUD_ISSUER_URI", siteUrl + "/o/");
        String defaultDiscoveryUri = isGeoNodeIssuer(issuer, siteUrl)
                ? "http://django:8000/o/.well-known/openid-configuration/"
                : trimTrailingSlash(issuer) + "/.well-known/openid-configuration/";
        String discoveryUri = required("NOXT_GEOSERVER_CLOUD_DISCOVERY_URI", defaultDiscoveryUri);

        Lock lock = securityManager.security().lock();
        try {
            SecurityFilterConfig existingConfig = securityManager.loadFilterConfig(FILTER_NAME, false);
            if (existingConfig != null && !(existingConfig instanceof GeoServerOAuth2LoginFilterConfig)) {
                throw new IllegalStateException(
                        "A non-OIDC security filter named '" + FILTER_NAME + "' already exists.");
            }
            GeoServerOAuth2LoginFilterConfig filterConfig = createFilterConfig(
                    clientId, clientSecret, siteUrl, issuer, discoveryUri, (GeoServerOAuth2LoginFilterConfig)
                            existingConfig);
            securityManager.saveFilter(filterConfig);
            addFilterToWebChain();
            log.info("Configured optional GeoNode Cloud OIDC login filter '{}'.", FILTER_NAME);
        } catch (Exception e) {
            throw new IllegalStateException("Could not configure the GeoServer Cloud OIDC login filter.", e);
        } finally {
            lock.release();
        }
    }

    private GeoServerOAuth2LoginFilterConfig createFilterConfig(
            String clientId,
            String clientSecret,
            String siteUrl,
            String issuer,
            String discoveryUri,
            GeoServerOAuth2LoginFilterConfig existingConfig) {
        Map<String, String> metadata =
                isGeoNodeIssuer(issuer, siteUrl) ? geoNodeMetadata(siteUrl) : readProviderMetadata(discoveryUri);

        GeoServerOAuth2LoginFilterConfig config =
                existingConfig == null ? new GeoServerOAuth2LoginFilterConfig() : existingConfig;
        config.setName(FILTER_NAME);
        config.setClassName(GeoServerOAuth2LoginAuthenticationFilter.class.getName());
        config.setOidcEnabled(true);
        config.setOidcClientId(clientId);
        config.setOidcClientSecret(clientSecret);
        config.setOidcAuthorizationUri(metadata.get("authorization_endpoint"));
        config.setOidcTokenUri(metadata.get("token_endpoint"));
        config.setOidcUserInfoUri(metadata.get("userinfo_endpoint"));
        config.setOidcJwkSetUri(metadata.get("jwks_uri"));
        config.setOidcLogoutUri(metadata.get("end_session_endpoint"));
        config.setOidcDiscoveryUri(discoveryUri);
        config.setOidcScopes(
                environment.getProperty("NOXT_GEOSERVER_CLOUD_OIDC_SCOPES", "openid profile email groups"));
        config.setOidcUserNameAttribute(
                environment.getProperty("NOXT_GEOSERVER_CLOUD_USERNAME_CLAIM", "preferred_username"));
        config.setTokenRolesClaim(environment.getProperty("NOXT_GEOSERVER_CLOUD_ROLES_CLAIM", "groups"));
        config.setRoleSource(OpenIdRoleSource.UserInfo);
        config.setRoleConverterString(environment.getProperty("NOXT_GEOSERVER_CLOUD_OIDC_ROLE_CONVERTER", ""));
        config.setOnlyExternalListedRoles(false);
        config.setAllowAdminLogin(
                Boolean.parseBoolean(environment.getProperty("NOXT_GEOSERVER_CLOUD_OIDC_ALLOW_ADMIN_LOGIN", "false")));
        // Resource-server JWT validation is handled by the per-service bearer-auth extension.
        config.setEnableResourceServerMode(false);
        config.setOidcUsePKCE(true);
        config.setOidcForceAuthorizationUriHttps(isHttps(metadata.get("authorization_endpoint")));
        config.setOidcForceTokenUriHttps(isHttps(metadata.get("token_endpoint")));
        config.setPostLogoutRedirectUri(siteUrl + "/geoserver/cloud/web/");
        config.calculateRedirectUris();
        return config;
    }

    private boolean isGeoNodeIssuer(String issuer, String siteUrl) {
        String normalizedIssuer = trimTrailingSlash(issuer);
        return normalizedIssuer.equals(trimTrailingSlash(siteUrl) + "/o");
    }

    private Map<String, String> geoNodeMetadata(String siteUrl) {
        String publicOrigin = trimTrailingSlash(siteUrl);
        return Map.of(
                "authorization_endpoint", publicOrigin + GEONODE_AUTHORIZATION_URI,
                "token_endpoint", "http://django:8000" + GEONODE_TOKEN_URI,
                "userinfo_endpoint", "http://django:8000" + GEONODE_USERINFO_URI,
                "jwks_uri", "http://django:8000" + GEONODE_JWKS_URI);
    }

    private Map<String, String> readProviderMetadata(String discoveryUri) {
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(5));
            requestFactory.setReadTimeout(Duration.ofSeconds(5));
            String body = new RestTemplate(requestFactory).getForObject(discoveryUri, String.class);
            Map<String, String> metadata = OBJECT_MAPPER.readValue(body, new TypeReference<>() {});
            for (String key : List.of("authorization_endpoint", "token_endpoint", "jwks_uri")) {
                if (!StringUtils.hasText(metadata.get(key))) {
                    throw new IllegalStateException("OIDC discovery document is missing " + key + ".");
                }
            }
            return metadata;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read OIDC discovery metadata from " + discoveryUri + ".", e);
        }
    }

    private void addFilterToWebChain() throws Exception {
        SecurityManagerConfig securityConfig = securityManager.getSecurityConfig();
        GeoServerSecurityFilterChain filterChain = securityConfig.getFilterChain();
        RequestFilterChain webChain = filterChain.getRequestChains().stream()
                .filter(chain -> "web".equals(chain.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("GeoServer security has no 'web' filter chain."));

        List<String> filterNames = new ArrayList<>(webChain.getFilterNames());
        if (!filterNames.contains(FILTER_NAME)) {
            int formIndex = filterNames.indexOf("form");
            int anonymousIndex = filterNames.indexOf("anonymous");
            int insertIndex = formIndex >= 0 ? formIndex + 1 : anonymousIndex;
            if (insertIndex < 0) {
                insertIndex = filterNames.size();
            }
            filterNames.add(insertIndex, FILTER_NAME);
            webChain.setFilterNames(filterNames);
            securityManager.saveSecurityConfig(securityConfig);
        }
    }

    private String required(String key) {
        return required(key, null);
    }

    private String required(String key, String defaultValue) {
        String value = environment.getProperty(key);
        if (!StringUtils.hasText(value)) {
            value = defaultValue;
        }
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Set " + key + " when GeoServer Cloud OIDC Web login is enabled.");
        }
        return value.trim();
    }

    private boolean isHttps(String endpoint) {
        return endpoint != null && "https".equalsIgnoreCase(URI.create(endpoint).getScheme());
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
