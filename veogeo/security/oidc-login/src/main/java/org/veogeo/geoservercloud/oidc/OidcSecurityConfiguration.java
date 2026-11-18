/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.veogeo.geoservercloud.oidc;

import org.geoserver.spring.config.annotations.TranspileXmlConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;

/** Loads the GeoServer OpenID Connect support beans only for the WebUI service. */
@AutoConfiguration
@ConditionalOnProperty(prefix = OidcLoginInitializer.PREFIX, name = "enabled", havingValue = "true")
@TranspileXmlConfig(
        locations = {"jar:gs-sec-oidc-core-.*!/applicationContext.xml", "jar:gs-sec-oidc-web-.*!/applicationContext.xml"
        },
        excludes = {
            "oidcHttpSecurityConfiguration",
            "oidcObjectPostProcessorConfiguration",
            "oidcAuthenticationConfiguration",
            "oauth2LoginAuthenticationProvider"
        })
@Import(OidcSecurityConfiguration_Generated.class)
@ImportResource("classpath:org/veogeo/geoservercloud/oidc/oidcHttpSecurityConfiguration.xml")
public class OidcSecurityConfiguration {}
