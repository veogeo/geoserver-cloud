/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.veogeo.geoservercloud.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.geoserver.configuration.core.main.GeoServerMainSecurityConfiguration;
import org.geoserver.configuration.core.web.sec.WebSecCoreConfiguration;
import org.geoserver.security.GeoServerSecurityManager;
import org.geoserver.security.oauth2.login.GeoServerOAuth2LoginAuthenticationProvider;
import org.geoserver.security.oauth2.spring.GeoServerOidcIdTokenDecoderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Import;

class OidcSecurityConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(
                    "authenticationManager", GeoServerSecurityManager.class, () -> mock(GeoServerSecurityManager.class))
            .withPropertyValues("geoserver.extension.security.oidc-login.enabled=true")
            .withConfiguration(UserConfigurations.of(OidcSecurityConfiguration.class));

    @Test
    void loadsOidcCoreAndWebBeansIntoCloudContext() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(GeoServerOAuth2LoginAuthenticationProvider.class)
                .hasSingleBean(GeoServerOidcIdTokenDecoderFactory.class)
                .hasBean("oidcHttpSecurityConfiguration")
                .hasBean("openIdConnectAuthPanelInfo"));
    }

    @Test
    void commonSecurityConfigurationLeavesOidcLoginOutOfEveryService() {
        assertThat(GeoServerMainSecurityConfiguration.class
                        .getAnnotation(Import.class)
                        .value())
                .doesNotContain(OidcSecurityConfiguration.class);
        assertThat(WebSecCoreConfiguration.class.getAnnotation(Import.class).value())
                .doesNotContain(OidcSecurityConfiguration.class);
    }
}
