/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.configuration.gwc;

import org.geoserver.spring.config.annotations.TranspileXmlConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Loads the WMTS multidimensional extension operations in GeoServer Cloud's GWC service. */
@Configuration(proxyBeanMethods = false)
@TranspileXmlConfig(
        locations = "jar:gs-wmts-multi-dimensional-.*!/applicationContext.xml",
        excludes = {"multiDimLayerConfig", "multiDimAdminConfig"})
@Import(WmtsMultiDimensionalConfiguration_Generated.class)
public class WmtsMultiDimensionalConfiguration {}
