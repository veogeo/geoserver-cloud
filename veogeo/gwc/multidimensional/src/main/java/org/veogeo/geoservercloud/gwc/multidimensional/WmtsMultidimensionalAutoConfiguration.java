/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.veogeo.geoservercloud.gwc.multidimensional;

import org.geoserver.catalog.Catalog;
import org.geoserver.config.GeoServer;
import org.geoserver.gwc.GWC;
import org.geoserver.gwc.wmts.MultiDimensionalExtension;
import org.geoserver.platform.ModuleStatus;
import org.geoserver.platform.ModuleStatusImpl;
import org.geoserver.wms.WMS;
import org.geowebcache.layer.TileLayerDispatcher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Registers WMTS multidimensional operations when GWC is enabled. */
@AutoConfiguration
@ConditionalOnClass(GWC.class)
@ConditionalOnProperty(name = "gwc.enabled", havingValue = "true", matchIfMissing = true)
public class WmtsMultidimensionalAutoConfiguration {

    @Bean
    @ConditionalOnBean(WMS.class)
    MultiDimensionalExtension wmtsMultidimensionalExtension(
            GeoServer geoServer, WMS wms, Catalog catalog, TileLayerDispatcher tileLayerDispatcher) {
        return new MultiDimensionalExtension(geoServer, wms, catalog, tileLayerDispatcher);
    }

    @Bean("WMTSMultiDimExtension")
    ModuleStatus wmtsMultiDimensionalModuleStatus() {
        ModuleStatusImpl status = new ModuleStatusImpl(
                "gs-wmts-multi-dimensional", "WMTS Multi Dimensional extension", "WMTS Multi Dimensional extension");
        status.setCategory(ModuleStatus.Category.EXTENSION);
        return status;
    }
}
