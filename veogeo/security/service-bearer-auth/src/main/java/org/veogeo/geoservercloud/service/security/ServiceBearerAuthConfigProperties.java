/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.veogeo.geoservercloud.service.security;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for optional OIDC Bearer JWT authentication in GeoServer services. */
@Data
@ConfigurationProperties(prefix = ServiceBearerAuthConfigProperties.PREFIX)
public class ServiceBearerAuthConfigProperties {

    public static final String PREFIX = "geoserver.extension.security.bearer-auth";
    public static final boolean DEFAULT = false;

    /** Whether service-side Bearer JWT authentication is enabled (default: false). */
    private boolean enabled = DEFAULT;

    /** OIDC issuer URI; must match the token's {@code iss} claim. */
    private String issuerUri;

    /**
     * Optional internal JWKS endpoint override when the advertised endpoint is not reachable from the service network.
     */
    private String jwkSetUri;

    /** Required audience identifying this GeoServer Cloud API. */
    private String audience;

    /** Claim used as the GeoServer username. */
    private String usernameClaim = "preferred_username";

    /** Nested claim path containing IdP roles. */
    private String rolesClaim = "groups";

    /** Explicit mapping from IdP role names to GeoServer role names. Unmapped roles are discarded. */
    private Map<String, String> roleMappings = new LinkedHashMap<>();

    /** Prefix added to mapped role names that don't already include it; set empty for exact role names. */
    private String rolePrefix = "ROLE_";
}
