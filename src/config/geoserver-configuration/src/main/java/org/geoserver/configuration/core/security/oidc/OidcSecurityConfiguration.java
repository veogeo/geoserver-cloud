/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.geoserver.configuration.core.security.oidc;

import org.geoserver.spring.config.annotations.TranspileXmlConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;

/** Loads optional OIDC WebUI login support when explicitly enabled for this deployment. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "geoserver.extension.security.oidc-login",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
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
@ImportResource("classpath:org/geoserver/configuration/core/security/oidc/oidcHttpSecurityConfiguration.xml")
public class OidcSecurityConfiguration {}
