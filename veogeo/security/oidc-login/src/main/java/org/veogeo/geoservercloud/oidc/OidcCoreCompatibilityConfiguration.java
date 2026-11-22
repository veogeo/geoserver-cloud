/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.veogeo.geoservercloud.oidc;

import org.geoserver.security.oauth2.login.GeoServerOAuth2LoginAuthenticationProvider;
import org.geoserver.spring.config.annotations.TranspileXmlConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;

/** Loads GeoServer's OIDC security provider wherever shared security config is read. */
@AutoConfiguration
@ConditionalOnClass(GeoServerOAuth2LoginAuthenticationProvider.class)
@ConditionalOnMissingBean(name = "oauth2LoginAuthenticationProvider")
@ConditionalOnProperty(
        prefix = OidcLoginInitializer.PREFIX,
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
@TranspileXmlConfig(
        locations = {"jar:gs-sec-oidc-core-.*!/applicationContext.xml"},
        excludes = {
            "oidcHttpSecurityConfiguration",
            "oidcObjectPostProcessorConfiguration",
            "oidcAuthenticationConfiguration",
            "oauth2LoginAuthenticationProvider"
        })
@Import(OidcCoreCompatibilityConfiguration_Generated.class)
@ImportResource("classpath:org/veogeo/geoservercloud/oidc/oidcHttpSecurityConfiguration.xml")
public class OidcCoreCompatibilityConfiguration {}
