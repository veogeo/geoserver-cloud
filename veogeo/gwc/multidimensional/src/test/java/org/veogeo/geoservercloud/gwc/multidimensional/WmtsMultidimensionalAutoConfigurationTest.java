package org.veogeo.geoservercloud.gwc.multidimensional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.geoserver.catalog.Catalog;
import org.geoserver.config.GeoServer;
import org.geoserver.gwc.wmts.MultiDimensionalExtension;
import org.geoserver.platform.ModuleStatus;
import org.geoserver.wms.WMS;
import org.geowebcache.layer.TileLayerDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WmtsMultidimensionalAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WmtsMultidimensionalAutoConfiguration.class))
            .withBean(GeoServer.class, () -> mock(GeoServer.class))
            .withBean(WMS.class, () -> mock(WMS.class))
            .withBean(Catalog.class, () -> mock(Catalog.class))
            .withBean(TileLayerDispatcher.class, () -> mock(TileLayerDispatcher.class));

    @Test
    void registersMultidimensionalOperationsWhenGwcIsEnabled() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(MultiDimensionalExtension.class)
                .hasSingleBean(ModuleStatus.class));
    }

    @Test
    void doesNotRegisterWhenGwcIsDisabled() {
        runner.withPropertyValues("gwc.enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(MultiDimensionalExtension.class)
                .doesNotHaveBean(ModuleStatus.class));
    }
}
